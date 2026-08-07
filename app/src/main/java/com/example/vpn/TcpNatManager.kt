package com.example.vpn

import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Relay TCP non-DNS (Fase 1.7): setiap koneksi TCP baru dari klien (paket SYN)
 * dipetakan ke satu [Socket] nyata (ter-protect()) ke tujuan yang sama, lalu
 * byte-nya direlay dua arah. Ini adalah "state machine TCP" MINIMAL —
 * cukup untuk trafik HTTP/HTTPS umum di kondisi jaringan normal, BUKAN
 * implementasi TCP/IP lengkap (tanpa retransmission timer, tanpa
 * penanganan out-of-order segment, tanpa window scaling).
 *
 * Sengaja disederhanakan sesuai arahan RENCANA_PRODUKSI_NETSHIELD.md §1.7:
 * "bisa fase awal: cukup relai TCP/UDP non-DNS apa adanya tanpa filtering,
 * supaya internet tetap jalan normal untuk trafik selain DNS." Hardening
 * lebih lanjut (retransmit, MSS negotiation penuh, dsb.) adalah pekerjaan
 * Fase 6/7 (QA & keandalan) setelah diuji nyata di device fisik.
 *
 * === CHANGELOG ===
 * [Fase 1 - 2026-08-07] Baru dibuat.
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
        @Volatile var serverSeq: Long = INITIAL_SERVER_SEQ
        @Volatile var clientNextSeq: Long = 0L // byte berikutnya yang kita harapkan dari klien
        var readerJob: Job? = null
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
                kotlinx.coroutines.delay(SWEEPER_INTERVAL_MS)
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
                State.TIME_WAIT, State.CLOSING, State.LAST_ACK, State.FIN_WAIT_1, State.FIN_WAIT_2, State.CLOSE_WAIT -> idleTime > IDLE_TRANSIENT_TIMEOUT_MS
                State.SYN_RECEIVED, State.SYN_SENT -> idleTime > IDLE_TRANSIENT_TIMEOUT_MS
                State.CLOSED, State.LISTEN -> true
            }
        }
        expired.keys.forEach { closeSession(it, sendRst = false) }
    }

    private fun evictOldestIfFull() {
        if (sessions.size >= MAX_SESSIONS) {
            val oldest = sessions.entries.minByOrNull { it.value.lastActive }
            oldest?.key?.let { closeSession(it, sendRst = true) }
        }
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
            } catch (e: Exception) {
                Log.w(TAG, "TCP connect gagal ke ${NetPacketUtils.ipToString(ip.dstIp)}:${tcp.dstPort}: ${e.message}")
                // Beri tahu klien koneksi ditolak, supaya app tidak menggantung menunggu timeout lama.
                closeSession(key, sendRst = false)
            }
        }
    }

    private fun handleData(session: Session, key: SessionKey, packet: ByteArray, tcp: NetPacketUtils.TcpHeader) {
        val payload = packet.copyOfRange(tcp.payloadOffset, tcp.payloadOffset + tcp.payloadLength)
        scope.launch(Dispatchers.IO) {
            try {
                session.socket.getOutputStream().write(payload)
                session.socket.getOutputStream().flush()
                session.clientNextSeq = (session.clientNextSeq + payload.size) and 0xFFFFFFFFL
                // ACK segera supaya klien tidak retransmit.
                sendControlSegment(session, key, flags = NetPacketUtils.TCP_FLAG_ACK)
            } catch (e: IOException) {
                Log.w(TAG, "Gagal menulis data TCP relay: ${e.message}")
                closeSession(key, sendRst = true)
            }
        }
    }

    private fun handleFin(session: Session, key: SessionKey, tcp: NetPacketUtils.TcpHeader) {
        session.clientNextSeq = (session.clientNextSeq + 1) and 0xFFFFFFFFL
        sendControlSegment(session, key, flags = NetPacketUtils.TCP_FLAG_ACK)
        try {
            session.socket.shutdownOutput()
        } catch (_: Exception) {
        }
        session.state = State.CLOSING
    }

    private suspend fun readLoop(key: SessionKey, session: Session) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(4096)
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
        session.readerJob?.cancel()
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
        private const val INITIAL_SERVER_SEQ = 1000L
        private const val SWEEPER_INTERVAL_MS = 30_000L
        private const val IDLE_ESTABLISHED_TIMEOUT_MS = 120_000L // 2 menit
        private const val IDLE_TRANSIENT_TIMEOUT_MS = 15_000L // 15 detik
        private const val MAX_SESSIONS = 500
    }
}
