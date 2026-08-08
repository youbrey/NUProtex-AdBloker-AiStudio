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
 * [Audit-2 - 2026-08-08] **BUG KRITIS ditemukan lewat audit kode** (laporan:
 * "internet lambat saat browsing/game/nonton video/reels" setelah Fase 6.10
 * mengalihkan SEMUA trafik TCP lewat NAT relay ini):
 *  - Root cause: SYN-ACK sintetis di `handleSyn()` dibangun tanpa opsi TCP
 *    apa pun (MSS/Window Scale) — `NetPacketUtils.buildIpv4TcpPacket` lama
 *    selalu membuat header 20-byte polos. Akibatnya:
 *    1. Window Scaling MATI TOTAL untuk seluruh umur setiap koneksi (RFC
 *       1323 mewajibkan opsi WS ada di SYN *dan* SYN-ACK) — window terkunci
 *       maks 65535 byte, membatasi throughput per-koneksi ke `window/RTT`
 *       (bisa serendah 3-6 Mbps di jaringan seluler ber-RTT tinggi),
 *       walau bandwidth asli jauh lebih besar.
 *    2. MSS tidak dinegosiasikan -> klien fallback ke default RFC 879 lama
 *       (536 byte, bukan ~1460), memperbanyak jumlah paket ~2.7x dan
 *       memperlambat slow-start (yang tumbuh per-RTT dalam satuan segmen).
 *  - Fix: `NetPacketUtils.parseTcpHeader` kini membaca opsi MSS/Window
 *    Scale dari SYN klien; `buildSynAckOptions()` baru menyusun opsi balik
 *    (MSS diclamp ke `MAX_SEGMENT_SIZE`, Window Scale HANYA disertakan jika
 *    klien memintanya, sesuai RFC). `windowFieldFor()` menerapkan shift
 *    window secara konsisten di semua segmen (data & kontrol) sepanjang
 *    umur sesi.
 *  Lihat CHANGELOG.md & DOKUMENTASI.md untuk detail & keterbatasan yang
 *  masih ada (mis. arah server->client belum benar-benar membaca window
 *  yang diiklankan klien untuk flow control adaptif — lihat catatan di
 *  CHANGELOG.md §Audit-2).
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
        // Fase Audit-2: true jika klien menyertakan opsi Window Scale di SYN-nya
        // (RFC 1323 — WS hanya boleh diaktifkan jika ADA di SYN *dan* SYN-ACK).
        @Volatile var windowScaleEnabled: Boolean = false
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
                try {
                    socket.receiveBufferSize = TCP_SOCKET_BUFFER_BYTES
                    socket.sendBufferSize = TCP_SOCKET_BUFFER_BYTES
                } catch (e: Exception) {
                    Log.d(TAG, "Gagal set ukuran buffer socket TCP (non-fatal): ${e.message}")
                }

                val session = Session(
                    socket = socket,
                    clientIp = ip.srcIp,
                    clientPort = tcp.srcPort,
                    remoteIp = ip.dstIp,
                    remotePort = tcp.dstPort
                )
                session.clientNextSeq = (tcp.seq + 1) and 0xFFFFFFFFL
                // Fase Audit-2 (BUG KRITIS diperbaiki): sebelumnya SYN-ACK sintetis
                // TIDAK PERNAH menyertakan opsi MSS/Window Scale, memaksa TCP stack
                // klien fallback ke MSS 536 byte & window scaling MATI TOTAL untuk
                // seluruh umur koneksi (RFC 1323: WS harus ada di SYN *dan* SYN-ACK).
                // Ini membatasi throughput per-koneksi ke ~window/RTT (bisa serendah
                // 3-6 Mbps di jaringan seluler), persis gejala video/reels buffering
                // & game lag yang dilaporkan. Sekarang: MSS diclamp ke MAX_SEGMENT_SIZE,
                // dan Window Scale ikut dibalas HANYA jika klien memang memintanya di
                // SYN (tcp.clientSupportsWindowScale) — sesuai RFC, bukan asal aktifkan.
                session.windowScaleEnabled = tcp.clientSupportsWindowScale
                val synAckOptions = NetPacketUtils.buildSynAckOptions(
                    mss = MAX_SEGMENT_SIZE,
                    includeWindowScale = session.windowScaleEnabled,
                    windowScaleShift = SERVER_WINDOW_SCALE_SHIFT
                )

                sessions[key] = session

                // Kirim SYN-ACK sintetis ke klien (kini menyertakan opsi TCP di atas).
                sendControlSegment(
                    session, key,
                    flags = NetPacketUtils.TCP_FLAG_SYN or NetPacketUtils.TCP_FLAG_ACK,
                    seqOverride = session.serverSeq,
                    ackOverride = session.clientNextSeq,
                    options = synAckOptions
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
            window = windowFieldFor(session),
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
        ackOverride: Long? = null,
        options: ByteArray = ByteArray(0)
    ) {
        val packet = NetPacketUtils.buildIpv4TcpPacket(
            srcIp = session.remoteIp,
            srcPort = session.remotePort,
            dstIp = session.clientIp,
            dstPort = session.clientPort,
            seq = seqOverride ?: session.serverSeq,
            ack = ackOverride ?: session.clientNextSeq,
            flags = flags,
            window = windowFieldFor(session),
            payload = ByteArray(0),
            identification = identification.getAndIncrement(),
            options = options
        )
        writeToTun(packet)
    }

    /**
     * Field window 16-bit yang ditulis ke wire. Fase Audit-2: jika Window
     * Scale dinegosiasikan (lihat [handleSyn]), window "asli" yang kita
     * iklankan ([ADVERTISED_WINDOW_BYTES], beberapa MB) di-shift kanan
     * sejumlah [SERVER_WINDOW_SCALE_SHIFT] sebelum ditulis ke field 16-bit
     * (sesuai RFC 1323). Jika klien tidak mendukung WS, tetap pakai
     * [DEFAULT_WINDOW] (65535) polos seperti sebelumnya — WAJIB, karena
     * mengirim window scaling tanpa negosiasi akan disalahartikan klien
     * sebagai window 16-bit biasa yang sangat kecil/aneh.
     */
    private fun windowFieldFor(session: Session): Int {
        return if (session.windowScaleEnabled) {
            (ADVERTISED_WINDOW_BYTES shr SERVER_WINDOW_SCALE_SHIFT).coerceIn(0, 65535)
        } else {
            DEFAULT_WINDOW
        }
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
        // Fase Audit-2: shift Window Scale yang KITA tawarkan ke klien (nilai
        // umum dipakai OS modern, mis. Linux/Android sering pakai 6-9). Window
        // asli yang bisa diiklankan = ADVERTISED_WINDOW_BYTES (di-shift kanan
        // shift ini sebelum ditulis ke field 16-bit wire).
        private const val SERVER_WINDOW_SCALE_SHIFT = 6
        // ~4 MB — jauh di atas batas 64KB lama, memungkinkan throughput tinggi
        // di link berlatensi lebih tinggi (video/reels/game di jaringan seluler).
        private const val ADVERTISED_WINDOW_BYTES = 65535 shl SERVER_WINDOW_SCALE_SHIFT
        // Fase Audit-3: buffer kernel socket TCP diperbesar (default OS
        // seringkali cukup kecil), membantu throughput di link berlatensi
        // lebih tinggi bersamaan dengan Window Scale (Audit-2).
        private const val TCP_SOCKET_BUFFER_BYTES = 1_048_576 // 1 MB
    }
}
