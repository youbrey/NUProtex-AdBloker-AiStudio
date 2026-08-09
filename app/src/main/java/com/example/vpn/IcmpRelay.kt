package com.example.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileDescriptor
import java.net.InetAddress
import java.util.concurrent.Semaphore

/**
 * Relay ICMP Echo Request/Reply (ping) — Fase Audit-14.
 *
 * BUG KRITIS DITEMUKAN: sejak paket pertama kali diproses (Fase 1),
 * `PacketTunnel.handlePacket()` HANYA menangani `PROTOCOL_UDP` dan
 * `PROTOCOL_TCP` — semua protokol lain (termasuk ICMP/ping) masuk ke
 * cabang `else -> { /* ICMP/protokol lain */ }` yang TIDAK melakukan
 * apa-apa, alias di-drop diam-diam. Ini tidak masalah selama routing VPN
 * masih whitelist sempit (belum menangkap ICMP sama sekali) — tapi sejak
 * Fase 6.10 (routing catch-all `0.0.0.0/0`), SEMUA paket ICMP dari device
 * (termasuk ping yang dipicu tool diagnostik game) ikut masuk tunnel dan
 * lenyap total, tidak pernah dibalas.
 *
 * Dampak nyata yang cocok 1:1 dengan laporan user (2026-08-09):
 *  - Tool "Deteksi Jaringan"/"Tes Performa" bawaan game (Mobile Legends
 *    dkk.) — hampir semua game mobile memakai ICMP echo untuk mengukur
 *    ping/kestabilan jaringan ke server sebelum mengizinkan masuk lobby.
 *    Karena balasannya tidak pernah datang, tool ini SELALU melaporkan
 *    gagal/tidak stabil ("Tes gagal. Silakan mencoba kembali nanti.").
 *  - "Gagal membuat lobby... jaringan tidak stabil" — pre-check koneksi
 *    berbasis ping yang sama.
 *  - TIDAK muncul di Speedtest berbasis browser karena Speedtest mengukur
 *    latensi lewat HTTP (TCP), bukan ICMP — jalur itu tetap berfungsi
 *    normal (cocok dengan hasil speedtest yang normal di laporan user).
 *
 * Fix: relay ICMP Echo Request (type 8) lewat "unprivileged ping socket"
 * Android (`SOCK_DGRAM` + `IPPROTO_ICMP` — TIDAK butuh root, didukung
 * kernel Android sejak lama lewat `ping_group_range`, dipakai juga oleh
 * `InetAddress.isReachable()` internal). Socket WAJIB di-protect() seperti
 * socket TCP/UDP lain (lihat dokumentasi Fase 1.5 di kelas lain).
 *
 * KETERBATASAN (didokumentasikan transparan):
 *  - Hanya Echo Request/Reply (type 8/0) yang direlay — cukup untuk
 *    kebutuhan ping/diagnostik game. Tipe ICMP lain (Destination
 *    Unreachable, Time Exceeded/traceroute, dst.) belum ditangani.
 *  - Fire-and-forget per request (tidak ada session/state persisten
 *    seperti TCP/UDP) — cukup untuk pola ping (request lalu tunggu reply),
 *    tidak dirancang untuk trafik ICMP volume tinggi.
 *  - BELUM diuji di device fisik (tidak ada akses raw socket di sandbox
 *    kerja ini) — perilaku `android.system.Os` untuk ping socket WAJIB
 *    diverifikasi Fandri di HP nyata sebelum dianggap final.
 *
 * [Audit-14 — KOREKSI DIRI, sama hari] BUG REGRESI ditemukan oleh user:
 * versi awal kelas ini memanggil `scope.launch(Dispatchers.IO) {}` BARU
 * untuk SETIAP paket Echo Request tanpa batas atas sama sekali — PERSIS
 * pola "launch tak terbatas per-paket" yang jadi akar bug pertama kali
 * (race condition penulisan TCP) dan yang coba dibatasi lewat
 * `MAX_SESSIONS` di `TcpNatManager`/`UdpNatManager` (Audit-13). Tool
 * diagnostik jaringan game lazimnya mengirim ping BERUNTUN (burst
 * 10-50+ dalam beberapa detik untuk mengukur jitter/packet loss), yang
 * berarti versi awal ini bisa memicu ledakan coroutine+raw-socket
 * bersamaan justru saat tool itu dijalankan — mengulang kelas masalah
 * yang sama yang baru saja diperbaiki Audit-13, hanya berpindah lokasi.
 * Fix: [inFlightLimiter] membatasi jumlah relay ICMP yang boleh berjalan
 * BERSAMAAN; permintaan yang melebihi batas di-drop (bukan diantre tanpa
 * batas) — ping yang di-drop sesekali tidak masalah bagi tool ping (akan
 * dianggap "timeout" satu kali, bukan gagal total), jauh lebih aman
 * daripada membiarkan socket/coroutine menumpuk tak terbatas.
 */
class IcmpRelay(
    private val vpnService: VpnService,
    private val scope: CoroutineScope,
    private val writeToTun: (ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "IcmpRelay"
        const val PROTOCOL_ICMP = 1
        private const val ICMP_TYPE_ECHO_REQUEST = 8
        private const val ICMP_TYPE_ECHO_REPLY = 0
        private const val REPLY_TIMEOUT_MS = 3000
        private const val RECV_BUFFER_SIZE = 1500
        // Audit-14 koreksi: batas relay ICMP konkuren. 32 jauh lebih dari
        // cukup untuk burst ping tool diagnostik game (biasanya <10 sekaligus),
        // sambil mencegah ledakan socket/thread tak terbatas seperti versi awal.
        private const val MAX_CONCURRENT_ICMP_RELAYS = 32
    }

    private val inFlightLimiter = Semaphore(MAX_CONCURRENT_ICMP_RELAYS)

    /** Dipanggil sinkron dari packet loop (PacketTunnel) untuk paket ICMP keluar. */
    fun onOutboundPacket(packet: ByteArray, ip: NetPacketUtils.Ipv4Header) {
        val icmpOffset = ip.headerLength
        if (packet.size - icmpOffset < 8) return // header ICMP minimal 8 byte

        val type = packet[icmpOffset].toInt() and 0xFF
        if (type != ICMP_TYPE_ECHO_REQUEST) {
            // Tipe lain (reply datang dari perangkat sendiri, unreachable, dst.) — belum ditangani, abaikan aman.
            return
        }

        // Audit-14 koreksi: batasi konkurensi — kalau sudah penuh, DROP paket
        // ini (bukan launch coroutine baru tanpa batas). Log di level debug
        // saja karena ini kondisi yang diharapkan bisa terjadi sesekali saat
        // burst ping, bukan error.
        if (!inFlightLimiter.tryAcquire()) {
            Log.d(TAG, "Relay ICMP penuh ($MAX_CONCURRENT_ICMP_RELAYS konkuren), drop 1 echo request")
            return
        }

        val icmpPayload = packet.copyOfRange(icmpOffset, packet.size)
        scope.launch(Dispatchers.IO) {
            try {
                relayEchoRequest(ip, icmpPayload)
            } finally {
                inFlightLimiter.release()
            }
        }
    }

    private suspend fun relayEchoRequest(ip: NetPacketUtils.Ipv4Header, icmpRequest: ByteArray) = withContext(Dispatchers.IO) {
        var fd: FileDescriptor? = null
        try {
            fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_ICMP)

            // WAJIB (sama seperti socket TCP/UDP lain) — cegah balasan ping kita
            // sendiri ikut masuk lagi ke tunnel (infinite loop / semua ping gagal).
            val dupForProtect = ParcelFileDescriptor.dup(fd)
            try {
                val protected = vpnService.protect(dupForProtect.fd)
                if (!protected) {
                    Log.w(TAG, "vpnService.protect() gagal untuk socket ICMP, batalkan relay")
                    return@withContext
                }
            } finally {
                dupForProtect.close()
            }

            Os.setsockoptTimeval(
                fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO,
                StructTimeval.fromMillis(REPLY_TIMEOUT_MS.toLong())
            )

            val destAddr = InetAddress.getByAddress(ip.dstIp)
            // Kernel akan menulis ulang bagian "identifier" ICMP di paket yang
            // benar-benar dikirim ke jaringan (disesuaikan dengan port lokal
            // socket ping ini) — ini perilaku normal "unprivileged ping socket"
            // Linux, bukan bug. Kernel juga menerjemahkannya kembali saat kita
            // recvfrom() di bawah, jadi payload yang kita terima tetap konsisten
            // secara internal untuk pasangan request/reply KITA sendiri.
            Os.sendto(fd, icmpRequest, 0, icmpRequest.size, 0, destAddr, 0)

            val recvBuffer = ByteArray(RECV_BUFFER_SIZE)
            val n = try {
                Os.recvfrom(fd, recvBuffer, 0, recvBuffer.size, 0, null)
            } catch (e: ErrnoException) {
                Log.d(TAG, "ICMP recvfrom timeout/gagal ke ${NetPacketUtils.ipToString(ip.dstIp)}: ${e.message}")
                return@withContext
            }
            if (n <= 0) return@withContext

            // Balasan dari SOCK_DGRAM+IPPROTO_ICMP berupa header ICMP mentah
            // (TANPA header IP di depannya, beda dari raw socket biasa).
            val icmpReply = recvBuffer.copyOf(n)
            if (icmpReply.isEmpty() || (icmpReply[0].toInt() and 0xFF) != ICMP_TYPE_ECHO_REPLY) {
                return@withContext
            }

            // WAJIB: pakai identifier & sequence dari REQUEST ASLI klien (bukan
            // yang ditulis ulang kernel), supaya game/tool ping klien mengenali
            // balasan ini sebagai jawaban valid atas ping yang ia kirim.
            if (icmpReply.size >= 8 && icmpRequest.size >= 8) {
                icmpReply[4] = icmpRequest[4]
                icmpReply[5] = icmpRequest[5]
                icmpReply[6] = icmpRequest[6]
                icmpReply[7] = icmpRequest[7]
                // Checksum ICMP harus dihitung ulang setelah identifier/sequence diubah.
                icmpReply[2] = 0; icmpReply[3] = 0
                val checksum = NetPacketUtils.internetChecksum(icmpReply, 0, icmpReply.size)
                icmpReply[2] = ((checksum shr 8) and 0xFF).toByte()
                icmpReply[3] = (checksum and 0xFF).toByte()
            }

            val replyPacket = buildIcmpIpv4Packet(srcIp = ip.dstIp, dstIp = ip.srcIp, icmpPayload = icmpReply)
            writeToTun(replyPacket)
        } catch (e: Exception) {
            Log.d(TAG, "Relay ICMP gagal untuk ${runCatching { NetPacketUtils.ipToString(ip.dstIp) }.getOrDefault("?")}: ${e.message}")
        } finally {
            try { fd?.let { Os.close(it) } } catch (_: Exception) {}
        }
    }

    private fun buildIcmpIpv4Packet(srcIp: ByteArray, dstIp: ByteArray, icmpPayload: ByteArray): ByteArray {
        val totalLength = 20 + icmpPayload.size
        val out = ByteArray(totalLength)
        NetPacketUtils.writeIpv4Header(
            out = out, offset = 0, totalLength = totalLength,
            protocol = PROTOCOL_ICMP, srcIp = srcIp, dstIp = dstIp,
            identification = 0
        )
        System.arraycopy(icmpPayload, 0, out, 20, icmpPayload.size)
        return out
    }
}
