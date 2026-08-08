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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Relay UDP non-DNS: trafik selain port 53 (mis. QUIC/HTTP3 — dipakai
 * YouTube/Instagram/TikTok, game online, dsb.) di-relay apa adanya lewat
 * socket ter-protect() TANPA filtering/inspeksi konten.
 *
 * === CHANGELOG ===
 * [Fase 1 - 2026-08-07] Baru dibuat.
 * [Audit - 2026-08-08] Perbaikan performa (bagian dari audit "video/reels
 * sangat lambat"): `onOutboundPacket` sebelumnya membuat `scope.launch()`
 * BARU untuk setiap paket UDP keluar. Di saat trafik video (QUIC) sangat
 * padat (ratusan-ribuan paket/detik per sesi), ini membebani scheduler
 * Dispatchers.IO yang dipakai bersama seluruh sesi TCP/UDP/DNS lain,
 * menambah latensi terasa di semua trafik sekaligus. Sekarang memakai pola
 * yang sama dengan `TcpNatManager`: satu `outboundChannel` FIFO + satu
 * writer coroutine per sesi, `onOutboundPacket` hanya `trySend()` (murah,
 * non-blocking, tidak membuat coroutine baru per paket).
 * Catatan: UDP secara inheren tidak menjamin urutan di jaringan nyata,
 * jadi perbaikan ini murni soal EFISIENSI (mengurangi overhead scheduler),
 * bukan soal korupsi data seperti pada kasus TCP.
 * [Audit-3 - 2026-08-08] **BUG KRITIS ditemukan** (laporan: reels/video
 * tetap buffering & game tetap lambat SETELAH fix MSS/Window Scale TCP di
 * Audit-2 — terbukti dari device fisik: proteksi ON=lambat, OFF=normal):
 *  - Root cause: `readLoop()` lama membaca 1 paket dari socket UDP lalu
 *    LANGSUNG memanggil `writeToTun()` (lock global, bisa menunggu sesi
 *    lain) sebelum `receive()` berikutnya. Selama menunggu lock itu, socket
 *    UDP tidak sedang dibaca — buffer kernel utk socket ini terus terisi
 *    paket baru dari server. UDP tidak reliable: begitu buffer kernel
 *    penuh, OS MEMBUANG paket diam-diam (beda dari TCP yang retransmit).
 *    Video/game yang trafiknya QUIC (UDP, dipakai luas oleh Reels & game
 *    modern) jadi kehilangan data -> buffering/lag, TIDAK tersentuh sama
 *    sekali oleh fix TCP Audit-2 karena memang beda jalur kode.
 *  - Fix: `readLoop()` sekarang HANYA `receive()` + `trySend()` ke channel
 *    baru `inboundChannel` (kapasitas 256, bukan unlimited — sengaja biar
 *    drop yang ADA jejak lognya kalau tun benar-benar tidak sanggup
 *    mengimbangi, bukan drop diam-diam di kernel). `tunWriterLoop()` baru
 *    jadi satu-satunya consumer yang memanggil `writeToTun()`, terpisah
 *    dari readLoop — socket.receive() jadi tidak pernah tertahan menunggu
 *    lock global. Ditambah `socket.receiveBufferSize`/`sendBufferSize`
 *    diperbesar ke 1MB (dari default OS yang seringkali jauh lebih kecil)
 *    sebagai bantalan tambahan.
 *  Lihat CHANGELOG-v2.md §Audit-3 untuk detail & status verifikasi.
 */
class UdpNatManager(
    private val vpnService: VpnService,
    private val scope: CoroutineScope,
    private val writeToTun: (ByteArray) -> Unit
) {
    private data class SessionKey(val srcPort: Int, val dstIp: String, val dstPort: Int)

    private class Session(
        val socket: DatagramSocket,
        val clientIp: ByteArray,
        val clientPort: Int,
        val remoteIp: ByteArray,
        val remotePort: Int,
        var readerJob: Job? = null,
        var writerJob: Job? = null,
        var tunWriterJob: Job? = null
    ) {
        val outboundChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)
        // Fase Audit-3: channel terpisah untuk arah masuk (server -> klien).
        // Kapasitas dibatasi (bukan UNLIMITED) supaya tidak jadi bufferbloat
        // tanpa batas kalau tun sedang lambat ditulis — lebih baik paket lama
        // di-drop di sini (kita masih punya data lengkap, tinggal ulang di
        // level QUIC/app) daripada di kernel socket buffer (drop TANPA jejak
        // yang bisa diproses & lebih mudah bikin starvation total).
        val inboundChannel = Channel<ByteArray>(capacity = 256)
        @Volatile var lastActive: Long = System.currentTimeMillis()
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
        val expired = sessions.filter { (_, session) -> now - session.lastActive > SESSION_IDLE_TIMEOUT_MS }
        expired.keys.forEach { closeSession(it) }
    }

    private fun evictOldestIfFull() {
        if (sessions.size >= MAX_SESSIONS) {
            val oldest = sessions.entries.minByOrNull { it.value.lastActive }
            oldest?.key?.let { closeSession(it) }
        }
    }

    fun onOutboundPacket(packet: ByteArray, ip: NetPacketUtils.Ipv4Header, udp: NetPacketUtils.UdpHeader) {
        val dstIpStr = NetPacketUtils.ipToString(ip.dstIp)
        val key = SessionKey(udp.srcPort, dstIpStr, udp.dstPort)
        val payload = packet.copyOfRange(udp.payloadOffset, packet.size)

        var session = sessions[key]
        if (session == null) {
            evictOldestIfFull()
            session = createSession(ip, udp, key) ?: return
            val existing = sessions.putIfAbsent(key, session)
            if (existing != null) {
                // Sesi dibuat bersamaan oleh thread lain -> tutup socket duplikat
                session.outboundChannel.close()
                session.readerJob?.cancel()
                session.writerJob?.cancel()
                try { session.socket.close() } catch (_: Exception) {}
                session = existing
            }
        }
        session.lastActive = System.currentTimeMillis()

        val result = session.outboundChannel.trySend(payload)
        if (result.isFailure) {
            Log.w(TAG, "Gagal antre payload UDP untuk $key")
        }
    }

    private fun createSession(
        ip: NetPacketUtils.Ipv4Header,
        udp: NetPacketUtils.UdpHeader,
        key: SessionKey
    ): Session? {
        return try {
            val socket = DatagramSocket()
            vpnService.protect(socket) // WAJIB (Fase 1.5) — cegah loop balik ke tunnel sendiri.
            // Fase Audit-3: perbesar buffer socket kernel (default OS seringkali
            // cuma puluhan-ratusan KB) — memberi "bantalan" tambahan saat readLoop
            // sesaat sibuk, mengurangi risiko OS men-drop paket UDP masuk (video/
            // game) sebelum sempat kita baca.
            try {
                socket.receiveBufferSize = SOCKET_BUFFER_BYTES
                socket.sendBufferSize = SOCKET_BUFFER_BYTES
            } catch (e: Exception) {
                Log.d(TAG, "Gagal set ukuran buffer socket UDP (non-fatal): ${e.message}")
            }
            val session = Session(
                socket = socket,
                clientIp = ip.srcIp,
                clientPort = udp.srcPort,
                remoteIp = ip.dstIp,
                remotePort = udp.dstPort
            )
            session.readerJob = scope.launch(Dispatchers.IO) { readLoop(key, session) }
            session.writerJob = scope.launch(Dispatchers.IO) { writerLoop(session) }
            // Fase Audit-3 (BUG KRITIS diperbaiki, lihat CHANGELOG.md §Audit-3):
            // consumer TERPISAH untuk menulis balik ke tun, supaya readLoop bisa
            // langsung receive() lagi tanpa menunggu giliran lock writeToTun —
            // mencegah buffer kernel socket UDP overflow (paket video/game
            // di-drop diam-diam oleh OS) saat writeToTun sedang dipakai sesi lain.
            session.tunWriterJob = scope.launch(Dispatchers.IO) { tunWriterLoop(session) }
            session
        } catch (e: Exception) {
            Log.w(TAG, "Gagal membuat UDP NAT session: ${e.message}")
            null
        }
    }

    private suspend fun writerLoop(session: Session) = withContext(Dispatchers.IO) {
        try {
            val addr = InetAddress.getByAddress(session.remoteIp)
            for (payload in session.outboundChannel) {
                try {
                    session.socket.send(DatagramPacket(payload, payload.size, addr, session.remotePort))
                } catch (e: Exception) {
                    Log.w(TAG, "Gagal kirim UDP relay: ${e.message}")
                    break
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "UDP writerLoop berhenti: ${e.message}")
        }
    }

    /**
     * HANYA `receive()` lalu `trySend()` ke [Session.inboundChannel] —
     * TIDAK PERNAH memanggil `writeToTun()` langsung di sini (Fase Audit-3).
     * Ini memastikan socket UDP dibaca kembali secepat mungkin, tidak
     * pernah tertahan menunggu lock global `writeToTun` — mengurangi risiko
     * kernel men-drop paket video/game yang masuk sebelum kita sempat baca.
     */
    private suspend fun readLoop(key: SessionKey, session: Session) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(PacketTunnel.MTU_BUFFER_SIZE)
        try {
            session.socket.soTimeout = SESSION_IDLE_TIMEOUT_MS
            while (isActive) {
                val responsePacket = DatagramPacket(buffer, buffer.size)
                try {
                    session.socket.receive(responsePacket)
                } catch (e: java.net.SocketTimeoutException) {
                    val idleFor = System.currentTimeMillis() - session.lastActive
                    if (idleFor > SESSION_IDLE_TIMEOUT_MS) break else continue
                }
                session.lastActive = System.currentTimeMillis()

                val payload = buffer.copyOf(responsePacket.length)
                val result = session.inboundChannel.trySend(payload)
                if (result.isFailure) {
                    // inboundChannel penuh (256) -> tun sedang jauh lebih lambat
                    // dari laju data masuk. Lebih baik drop di sini (jelas &
                    // ter-log) daripada biarkan kernel drop diam-diam TANPA jejak.
                    Log.w(TAG, "inboundChannel UDP penuh untuk $key, satu paket di-drop.")
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "UDP NAT read loop berhenti untuk $key: ${e.message}")
        } finally {
            closeSession(key)
        }
    }

    /**
     * Consumer terpisah (Fase Audit-3) yang menguras [Session.inboundChannel]
     * dan baru DI SINI memanggil `writeToTun()` (yang bisa menunggu lock
     * global). Dipisah dari [readLoop] supaya `socket.receive()` tidak
     * pernah terhalang oleh kontensi penulisan ke tun.
     */
    private suspend fun tunWriterLoop(session: Session) = withContext(Dispatchers.IO) {
        try {
            for (payload in session.inboundChannel) {
                val reply = NetPacketUtils.buildIpv4UdpPacket(
                    srcIp = session.remoteIp,
                    srcPort = session.remotePort,
                    dstIp = session.clientIp,
                    dstPort = session.clientPort,
                    payload = payload,
                    identification = identification.getAndIncrement()
                )
                writeToTun(reply)
            }
        } catch (e: Exception) {
            Log.d(TAG, "UDP tunWriterLoop berhenti: ${e.message}")
        }
    }

    private fun closeSession(key: SessionKey) {
        sessions.remove(key)?.let {
            it.outboundChannel.close()
            it.inboundChannel.close()
            it.readerJob?.cancel()
            it.writerJob?.cancel()
            it.tunWriterJob?.cancel()
            try { it.socket.close() } catch (_: Exception) {}
        }
    }

    fun closeAll() {
        sweeperJob?.cancel()
        sweeperJob = null
        sessions.keys.toList().forEach { closeSession(it) }
    }

    companion object {
        private const val TAG = "UdpNatManager"
        private const val SESSION_IDLE_TIMEOUT_MS = 30_000
        private const val SWEEPER_INTERVAL_MS = 15_000L
        private const val MAX_SESSIONS = 500
        // Fase Audit-3: buffer kernel socket UDP diperbesar dari default OS
        // (seringkali cuma puluhan-ratusan KB) ke ~1MB — bantalan tambahan
        // saat readLoop/tunWriterLoop sesaat sibuk, mengurangi risiko drop
        // paket video/game oleh OS sebelum sempat kita proses.
        private const val SOCKET_BUFFER_BYTES = 1_048_576 // 1 MB
    }
}
