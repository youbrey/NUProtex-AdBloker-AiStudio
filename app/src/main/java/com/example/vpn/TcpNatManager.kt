package com.example.vpn

import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Relay TCP non-DNS: setiap koneksi TCP baru dari klien (paket SYN)
 * dipetakan ke satu [Socket] nyata (ter-protect()) ke tujuan yang sama, lalu
 * byte-nya direlay dua arah.
 *
 * === CHANGELOG ===
 * [Fase 1 - 2026-08-07] Baru dibuat.
 * [Audit - 2026-08-08] PERBAIKAN BUG KRITIS ditemukan saat audit performa
 * ("video/reels sangat lambat"). CATATAN PENTING: sejak Fase 6.10 (routing
 * VPN diganti dari whitelist IP DNS sempit menjadi catch-all
 * `0.0.0.0/0`+`::/0`, lihat NetShieldVpnService.kt), kelas ini menangani
 * SELURUH trafik TCP non-DNS aplikasi (bukan cuma DoH/DoT ke resolver
 * tertentu seperti sebelumnya) — bug di bawah ini karena itu jauh LEBIH
 * berdampak sekarang daripada saat pertama ditemukan, karena praktis
 * semua request video/gambar/API dari semua app lewat jalur ini.
 *  - SEBELUMNYA: setiap segmen data masuk (`handleData`) menulis ke
 *    `socket.getOutputStream()` lewat `scope.launch(Dispatchers.IO) {}`
 *    BARU per paket. Karena Dispatchers.IO adalah thread pool bersama,
 *    urutan eksekusi launch TIDAK dijamin sama dengan urutan kedatangan
 *    paket dari klien — byte yang dikirim ke server upstream bisa
 *    terbalik urutannya, merusak stream TLS/HTTP2 (menyebabkan koneksi
 *    di-reset & retry berulang oleh app — persis gejala "reels/video
 *    lambat sekali"). `clientNextSeq += payload.size` juga di-update
 *    tanpa sinkronisasi oleh coroutine-coroutine paralel ini, membuat
 *    nomor ACK yang dikirim balik ke klien bisa salah.
 *  - SEKARANG: setiap sesi punya `outboundChannel` (FIFO, unlimited) +
 *    SATU writer coroutine khusus yang menguras channel secara berurutan.
 *    `handleData` (dipanggil sinkron dari packet loop, jadi sudah pasti
 *    berurutan) hanya `trySend()` ke channel — cepat & non-blocking, TIDAK
 *    lagi membuat coroutine baru per paket. Urutan tulis ke socket upstream
 *    kini dijamin sama dengan urutan kedatangan dari klien.
 *  - Ditambahkan `socket.setTcpNoDelay(true)` pada socket relay (menonaktifkan
 *    Nagle's algorithm) — mengurangi latensi tambahan pada banyak tulisan
 *    kecil (khas frame HTTP/2 pada trafik video/streaming).
 *  - State machine sesi dilengkapi: transisi ke `CLOSE_WAIT`/`LAST_ACK`/
 *    `TIME_WAIT` kini benar-benar di-set (sebelumnya dideklarasikan tapi
 *    tidak pernah dipakai, membuat sesi FIN menumpuk 15 detik penuh di
 *    `CLOSING` dan berisiko meng-evict sesi video yang masih aktif lewat
 *    `evictOldestIfFull()`).
 *  Lihat CHANGELOG.md untuk detail lengkap.
 */
class TcpNatManager(
    private val vpnService: VpnService,
    private val scope: CoroutineScope,
    private val writeToTun: (ByteArray) -> Unit
) {
    private data class SessionKey(val srcPort: Int, val dstIp: String, val dstPort: Int)

    private enum class State {
        LISTEN,
        SYN_SENT,
        SYN_RECEIVED,
        ESTABLISHED,
        FIN_WAIT_1,
        FIN_WAIT_2,
        CLOSE_WAIT,
        CLOSING,
        LAST_ACK,
        TIME_WAIT,
        CLOSED
    }

    private class Session(
        val socket: Socket,
        val clientIp: ByteArray,
        val clientPort: Int,
        val remoteIp: ByteArray,
        val remotePort: Int
    ) {
        @Volatile var state: State = State.SYN_RECEIVED
        // seq/ack dalam ruang 32-bit unsigned, disimpan sebagai Long agar mudah dihitung.
        // HANYA ditulis dari dalam writerJob (arah client->server) atau readLoop (arah
        // server->client) masing-masing satu coroutine per sesi -> aman tanpa lock tambahan.
        @Volatile var serverSeq: Long = INITIAL_SERVER_SEQ
        @Volatile var clientNextSeq: Long = 0L // byte berikutnya yang kita harapkan dari klien
        var readerJob: Job? = null
        var writerJob: Job? = null
        // FIFO — menjamin byte ditulis ke socket upstream PERSIS urutan kedatangan dari klien.
        val outboundChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)
        @Volatile var lastActive: Long = System.currentTimeMillis()
        @Volatile var clientFinReceived: Boolean = false
        @Volatile var serverFinSent: Boolean = false
    }

    private val sessions = ConcurrentHashMap<SessionKey, Session>()
    private val identification = AtomicInteger(1)
    private var sweeperJob: Job? = null

    init {
        startSweeper()
    }

    private fun startSweeper() {
        sweeperJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(SWEEPER_INTERVAL_MS)
                cleanupExpiredSessions()
            }
        }
    }

    private fun cleanupExpiredSessions() {
        val now = System.currentTimeMillis()
        val expired = sessions.filter { (_, session) ->
            val idleTime = now - session.lastActive
            when (session.state) {
                State.ESTABLISHED -> idleTime > IDLE_ESTABLISHED_TIMEOUT_MS
                State.TIME_WAIT, State.CLOSING, State.LAST_ACK,
                State.FIN_WAIT_1, State.FIN_WAIT_2, State.CLOSE_WAIT -> idleTime > IDLE_TRANSIENT_TIMEOUT_MS
                State.SYN_RECEIVED, State.SYN_SENT -> idleTime > IDLE_TRANSIENT_TIMEOUT_MS
                State.CLOSED, State.LISTEN -> true
            }
        }
        expired.keys.forEach { closeSession(it, sendRst = false) }
    }

    /** Hanya meng-evict sesi yang BENAR-BENAR sudah tidak aktif (bukan ESTABLISHED) bila memungkinkan,
     *  supaya sesi video yang sedang streaming tidak ikut tergusur oleh sesi zombie menunggu timeout. */
    private fun evictOldestIfFull() {
        if (sessions.size < MAX_SESSIONS) return
        val closingCandidate = sessions.entries
            .filter { it.value.state != State.ESTABLISHED }
            .minByOrNull { it.value.lastActive }
        val target = closingCandidate ?: sessions.entries.minByOrNull { it.value.lastActive }
        target?.key?.let { closeSession(it, sendRst = true) }
    }

    fun onOutboundPacket(packet: ByteArray, ip: NetPacketUtils.Ipv4Header, tcp: NetPacketUtils.TcpHeader) {
        val dstIpStr = NetPacketUtils.ipToString(ip.dstIp)
        val key = SessionKey(tcp.srcPort, dstIpStr, tcp.dstPort)
        val flags = tcp.flags

        when {
            flags and NetPacketUtils.TCP_FLAG_SYN != 0 -> handleSyn(key, ip, tcp)
            flags and NetPacketUtils.TCP_FLAG_RST != 0 -> closeSession(key, sendRst = false)
            else -> {
                val session = sessions[key] ?: return // segmen untuk sesi yang tidak dikenal -> abaikan
                session.lastActive = System.currentTimeMillis()

                if (tcp.payloadLength > 0) {
                    handleData(session, key, packet, tcp)
                }
                if (flags and NetPacketUtils.TCP_FLAG_FIN != 0) {
                    handleFin(session, key, tcp)
                }
            }
        }
    }

    private fun handleSyn(key: SessionKey, ip: NetPacketUtils.Ipv4Header, tcp: NetPacketUtils.TcpHeader) {
        if (sessions.containsKey(key)) return // SYN duplikat, abaikan
        evictOldestIfFull()

        scope.launch(Dispatchers.IO) {
            try {
                val socket = Socket()
                vpnService.protect(socket) // WAJIB (Fase 1.5)
                val remoteAddr = java.net.InetAddress.getByAddress(ip.dstIp)
                socket.connect(InetSocketAddress(remoteAddr, tcp.dstPort), CONNECT_TIMEOUT_MS)
                // Nonaktifkan Nagle's algorithm: tanpa ini, tulisan kecil berturut-turut
                // (khas frame HTTP/2 pada trafik video/reels) bisa tertahan sampai 200ms
                // menunggu digabung, menambah latensi yang terasa jelas oleh user.
                socket.tcpNoDelay = true

                val session = Session(
                    socket = socket,
                    clientIp = ip.srcIp,
                    clientPort = tcp.srcPort,
                    remoteIp = ip.dstIp,
                    remotePort = tcp.dstPort
                )
                session.clientNextSeq = (tcp.seq + 1) and 0xFFFFFFFFL
                sessions[key] = session

                // Kirim SYN-ACK sintetis ke klien.
                sendControlSegment(
                    session, key,
                    flags = NetPacketUtils.TCP_FLAG_SYN or NetPacketUtils.TCP_FLAG_ACK,
                    seqOverride = session.serverSeq,
                    ackOverride = session.clientNextSeq
                )
                session.serverSeq = (session.serverSeq + 1) and 0xFFFFFFFFL
                session.state = State.ESTABLISHED

                session.readerJob = scope.launch(Dispatchers.IO) { readLoop(key, session) }
                session.writerJob = scope.launch(Dispatchers.IO) { writerLoop(key, session) }
            } catch (e: Exception) {
                Log.w(TAG, "TCP connect gagal ke ${NetPacketUtils.ipToString(ip.dstIp)}:${tcp.dstPort}: ${e.message}")
                // Beri tahu klien koneksi ditolak, supaya app tidak menggantung menunggu timeout lama.
                closeSession(key, sendRst = false)
            }
        }
    }

    /** Hanya menaruh payload ke antrian FIFO sesi — cepat & non-blocking, dipanggil sinkron dari packet loop. */
    private fun handleData(session: Session, key: SessionKey, packet: ByteArray, tcp: NetPacketUtils.TcpHeader) {
        val payload = packet.copyOfRange(tcp.payloadOffset, tcp.payloadOffset + tcp.payloadLength)
        val result = session.outboundChannel.trySend(payload)
        if (result.isFailure) {
            Log.w(TAG, "Gagal antre payload TCP (channel tertutup?) untuk $key")
        }
    }

    /**
     * SATU-satunya coroutine yang menulis ke `socket.getOutputStream()` untuk sesi ini.
     * Menguras `outboundChannel` secara FIFO sehingga byte yang sampai ke server upstream
     * SELALU dalam urutan yang sama persis dengan urutan kedatangan dari klien — ini
     * yang memperbaiki race condition Fase 1 (lihat CHANGELOG di atas kelas).
     */
    private suspend fun writerLoop(key: SessionKey, session: Session) = withContext(Dispatchers.IO) {
        try {
            val output = session.socket.getOutputStream()
            for (payload in session.outboundChannel) {
                try {
                    output.write(payload)
                    output.flush()
                    session.clientNextSeq = (session.clientNextSeq + payload.size) and 0xFFFFFFFFL
                    session.lastActive = System.currentTimeMillis()
                    // ACK dikirim SETELAH tulisan sukses & dalam urutan yang benar.
                    sendControlSegment(session, key, flags = NetPacketUtils.TCP_FLAG_ACK)
                } catch (e: IOException) {
                    Log.w(TAG, "Gagal menulis data TCP relay untuk $key: ${e.message}")
                    closeSession(key, sendRst = true)
                    break
                }
            }
            // Channel ditutup (closeSession dipanggil) & semua data pending sudah ditulis -> FIN ke server.
            if (session.clientFinReceived && !session.socket.isOutputShutdown) {
                try { session.socket.shutdownOutput() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.d(TAG, "writerLoop berhenti untuk $key: ${e.message}")
        }
    }

    private fun handleFin(session: Session, key: SessionKey, tcp: NetPacketUtils.TcpHeader) {
        session.clientNextSeq = (session.clientNextSeq + 1) and 0xFFFFFFFFL
        sendControlSegment(session, key, flags = NetPacketUtils.TCP_FLAG_ACK)
        session.clientFinReceived = true
        session.state = if (session.serverFinSent) State.LAST_ACK else State.CLOSE_WAIT
        // Tutup channel supaya writerLoop menuntaskan sisa data pending lalu shutdownOutput().
        session.outboundChannel.close()
    }

    private suspend fun readLoop(key: SessionKey, session: Session) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        try {
            val input = session.socket.getInputStream()
            while (isActive) {
                val n = try {
                    input.read(buffer)
                } catch (e: IOException) {
                    -1
                }
                if (n < 0) {
                    // Server menutup koneksi -> kirim FIN ke klien.
                    sendControlSegment(session, key, flags = NetPacketUtils.TCP_FLAG_FIN or NetPacketUtils.TCP_FLAG_ACK)
                    session.serverSeq = (session.serverSeq + 1) and 0xFFFFFFFFL
                    session.serverFinSent = true
                    session.state = if (session.clientFinReceived) State.TIME_WAIT else State.FIN_WAIT_1
                    break
                }
                if (n == 0) continue

                val chunk = buffer.copyOf(n)
                var offset = 0
                // Pecah jadi beberapa segmen bila lebih besar dari MSS supaya muat 1 paket tun.
                while (offset < chunk.size) {
                    val end = (offset + MAX_SEGMENT_SIZE).coerceAtMost(chunk.size)
                    val segment = chunk.copyOfRange(offset, end)
                    sendDataSegment(session, key, segment)
                    offset = end
                }
                session.lastActive = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            Log.d(TAG, "TCP relay read loop berhenti untuk $key: ${e.message}")
        } finally {
            if (session.state == State.TIME_WAIT) {
                // Beri sedikit waktu untuk ACK terakhir klien datang, lalu bersihkan.
                delay(TIME_WAIT_LINGER_MS)
            }
            closeSession(key, sendRst = false)
        }
    }

    private fun sendDataSegment(session: Session, key: SessionKey, payload: ByteArray) {
        val packet = NetPacketUtils.buildIpv4TcpPacket(
            srcIp = session.remoteIp,
            srcPort = session.remotePort,
            dstIp = session.clientIp,
            dstPort = session.clientPort,
            seq = session.serverSeq,
            ack = session.clientNextSeq,
            flags = NetPacketUtils.TCP_FLAG_PSH or NetPacketUtils.TCP_FLAG_ACK,
            window = DEFAULT_WINDOW,
            payload = payload,
            identification = identification.getAndIncrement()
        )
        writeToTun(packet)
        session.serverSeq = (session.serverSeq + payload.size) and 0xFFFFFFFFL
    }

    private fun sendControlSegment(
        session: Session,
        key: SessionKey,
        flags: Int,
        seqOverride: Long? = null,
        ackOverride: Long? = null
    ) {
        val packet = NetPacketUtils.buildIpv4TcpPacket(
            srcIp = session.remoteIp,
            srcPort = session.remotePort,
            dstIp = session.clientIp,
            dstPort = session.clientPort,
            seq = seqOverride ?: session.serverSeq,
            ack = ackOverride ?: session.clientNextSeq,
            flags = flags,
            window = DEFAULT_WINDOW,
            payload = ByteArray(0),
            identification = identification.getAndIncrement()
        )
        writeToTun(packet)
    }

    private fun closeSession(key: SessionKey, sendRst: Boolean) {
        val session = sessions.remove(key) ?: return
        if (sendRst) {
            try {
                sendControlSegment(session, key, flags = NetPacketUtils.TCP_FLAG_RST)
            } catch (_: Exception) {
            }
        }
        session.outboundChannel.close()
        session.readerJob?.cancel()
        session.writerJob?.cancel()
        try { session.socket.close() } catch (_: Exception) {}
        session.state = State.CLOSED
    }

    fun closeAll() {
        sweeperJob?.cancel()
        sweeperJob = null
        sessions.keys.toList().forEach { closeSession(it, sendRst = false) }
    }

    companion object {
        private const val TAG = "TcpNatManager"
        private const val CONNECT_TIMEOUT_MS = 8000
        private const val DEFAULT_WINDOW = 65535
        private const val MAX_SEGMENT_SIZE = 1400 // aman di bawah MTU 1500 dikurangi header IP+TCP
        private const val READ_BUFFER_SIZE = 16384 // diperbesar dari 4096 - mengurangi jumlah syscall saat throughput tinggi (video)
        private const val INITIAL_SERVER_SEQ = 1000L
        private const val SWEEPER_INTERVAL_MS = 30_000L
        private const val IDLE_ESTABLISHED_TIMEOUT_MS = 120_000L // 2 menit
        private const val IDLE_TRANSIENT_TIMEOUT_MS = 15_000L // 15 detik
        private const val TIME_WAIT_LINGER_MS = 2_000L
        private const val MAX_SESSIONS = 500
    }
}
