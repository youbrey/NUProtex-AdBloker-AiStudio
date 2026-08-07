package com.example.vpn

import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Relay UDP non-DNS (Fase 1.7): trafik selain port 53 (mis. QUIC/HTTP3,
 * game online, dsb.) di-relay apa adanya lewat socket ter-protect() supaya
 * internet tetap jalan normal, TANPA filtering/inspeksi konten.
 *
 * Session di-key oleh (srcPort klien, dstIp, dstPort) dan otomatis ditutup
 * setelah [SESSION_IDLE_TIMEOUT_MS] tidak ada aktivitas.
 *
 * === CHANGELOG ===
 * [Fase 1 - 2026-08-07] Baru dibuat.
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
        var readerJob: Job? = null
    ) {
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
            now - session.lastActive > SESSION_IDLE_TIMEOUT_MS
        }
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
                try { session.socket.close() } catch (_: Exception) {}
                session = existing
            }
        }
        session.lastActive = System.currentTimeMillis()

        scope.launch(Dispatchers.IO) {
            try {
                val addr = InetAddress.getByAddress(session.remoteIp)
                session.socket.send(DatagramPacket(payload, payload.size, addr, session.remotePort))
            } catch (e: Exception) {
                Log.w(TAG, "Gagal kirim UDP relay ke $dstIpStr:${udp.dstPort}: ${e.message}")
                closeSession(key)
            }
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
            val session = Session(
                socket = socket,
                clientIp = ip.srcIp,
                clientPort = udp.srcPort,
                remoteIp = ip.dstIp,
                remotePort = udp.dstPort
            )
            session.readerJob = scope.launch(Dispatchers.IO) { readLoop(key, session) }
            session
        } catch (e: Exception) {
            Log.w(TAG, "Gagal membuat UDP NAT session: ${e.message}")
            null
        }
    }

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

                val reply = NetPacketUtils.buildIpv4UdpPacket(
                    srcIp = session.remoteIp,
                    srcPort = session.remotePort,
                    dstIp = session.clientIp,
                    dstPort = session.clientPort,
                    payload = buffer.copyOf(responsePacket.length),
                    identification = identification.getAndIncrement()
                )
                writeToTun(reply)
            }
        } catch (e: Exception) {
            Log.d(TAG, "UDP NAT read loop berhenti untuk $key: ${e.message}")
        } finally {
            closeSession(key)
        }
    }

    private fun closeSession(key: SessionKey) {
        sessions.remove(key)?.let {
            it.readerJob?.cancel()
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
    }
}
