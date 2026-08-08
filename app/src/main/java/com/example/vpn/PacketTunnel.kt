package com.example.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.data.local.CustomRuleEntity
import com.example.model.DnsProvider
import com.example.model.FilterOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * Jantung Fase 1: membaca paket mentah dari tun interface, mencegat &
 * memutuskan query DNS (blokir lokal atau forward ke upstream terpilih),
 * dan mem-forward trafik non-DNS (UDP/TCP) apa adanya via NAT sederhana
 * berbasis socket ter-protect() supaya internet tetap berjalan normal
 * untuk trafik selain DNS.
 *
 * Referensi arsitektur (dipelajari pola umumnya, bukan disalin kodenya):
 * pendekatan "socket-based NAT di atas VpnService" ini umum dipakai
 * project ad-blocker VPN-based open source seperti DNS66, RethinkDNS,
 * NetGuard — lihat RENCANA_PRODUKSI_NETSHIELD.md §Catatan Penting.
 *
 * PENTING (Fase 1.5): setiap socket upstream yang dibuat kelas ini WAJIB
 * di-protect() lewat [VpnService.protect] sebelum connect/send, kalau
 * tidak maka trafik itu akan masuk lagi ke tunnel sendiri (infinite loop,
 * seluruh internet macet). Cek [VpnCallbacks.protectSocket] di setiap
 * titik socket dibuat.
 *
 * === CHANGELOG ===
 * [Fase 1 - 2026-08-07] Baru dibuat. Implementasi Fase 1.1–1.7:
 *  - Packet loop di Dispatchers.IO membaca vpnInterface (1.1)
 *  - Parsing IPv4/UDP/DNS (1.2, 1.3)
 *  - Resolver lokal: blokir → NXDOMAIN/0.0.0.0, izin → forward upstream (1.4)
 *  - protect() wajib pada semua socket upstream (1.5)
 *  - selectedProvider dari repository dipakai sebagai upstream, hardcode
 *    1.1.1.1 dihapus dari VpnService (1.6)
 *  - Pass-through UDP & TCP non-DNS via NAT sederhana (1.7) — implementasi
 *    awal, direkomendasikan diperkeras lebih lanjut di Fase 6/7 QA
 *    (mis. idle timeout tuning, IPv6, fragmentasi paket besar).
 *
 * KETERBATASAN DIKETAHUI (didokumentasikan secara transparan, lihat 1.8):
 *  - Hanya IPv4 yang didukung.
 *  - Fragmentasi IP (paket >MTU yang terpecah) belum ditangani khusus.
 *  - TCP window scaling / opsi TCP lanjutan diabaikan (dianggap MSS default).
 *  Ini semua WAJIB diuji di device fisik sebelum rilis (§Fase 1.8, §Fase 7).
 *
 * [Fase 5 - 2026-08-07]
 *  - 5.1: `forwardToUpstream` sekarang benar-benar memakai DNS-over-HTTPS
 *    (RFC 8484 wireformat, via OkHttp) saat `dohEnabled` aktif & provider
 *    mendukung DoH, dengan fallback otomatis ke UDP polos jika request DoH
 *    gagal. Socket TLS yang dibuat OkHttp untuk DoH di-protect() lewat
 *    [ProtectingSocketFactory] khusus (padanan `VpnService.protect()` untuk
 *    koneksi TCP/TLS, bukan cuma DatagramSocket seperti Fase 1.5).
 *  Lihat CHANGELOG.md & RENCANA_PRODUKSI_NETSHIELD.md §Fase 5.
 * [Audit-5 - 2026-08-09] **BUG KRITIS ditemukan** (laporan: "internet SANGAT
 * lambat saat proteksi aktif — video FB/IG/TikTok/WA tidak mau main, game
 * & download/upload turun drastis, TAPI speedtest normal"):
 *  - Root cause: sejak Fase 6.10 (`addRoute("::", 0)` catch-all IPv6
 *    ditambahkan di `NetShieldVpnService`), SELURUH trafik IPv6 non-DNS
 *    (TCP & UDP) masuk ke tunnel ini tapi diam-diam DIABAIKAN — tidak ada
 *    balasan RST/ICMP apa pun dikirim. Client (browser/app) yang mencoba
 *    connect lewat IPv6 (CDN Meta — Facebook/Instagram/WhatsApp — sangat
 *    dikenal memprioritaskan IPv6) menunggu FULL connect timeout OS
 *    (puluhan detik) sebelum akhirnya fallback ke IPv4 lewat mekanisme
 *    Happy Eyeballs, alih-alih gagal-cepat. Speedtest.net TIDAK terdampak
 *    karena test node-nya di-resolve/dipilih via IPv4 eksplisit — inilah
 *    kenapa speedtest terlihat normal padahal app lain terasa sangat
 *    lambat, PERSIS gejala yang dilaporkan.
 *  - Fix: `handlePacket` sekarang membalas SEGERA untuk trafik IPv6
 *    non-DNS — TCP RST+ACK untuk setiap SYN (`NetPacketUtils.buildIpv6TcpRst`),
 *    ICMPv6 Port Unreachable untuk UDP (`buildIpv6IcmpPortUnreachable`).
 *    Client jadi tahu SEGERA bahwa jalur IPv6 buntu dan berpindah ke jalur
 *    IPv4 yang SUDAH benar-benar di-NAT & difilter oleh `TcpNatManager`/
 *    `UdpNatManager` (termasuk seluruh perbaikan performa Audit-1..4).
 *  - KETERBATASAN TETAP ADA (disengaja, transparan): ini BUKAN implementasi
 *    relay IPv6 penuh — trafik IPv6 non-DNS tetap tidak diteruskan/difilter
 *    langsung oleh NetShield, hanya digagalkan cepat supaya OS/app pindah
 *    ke IPv4. Konsekuensi: iklan/tracker yang HANYA bisa diakses lewat
 *    IPv6 murni (tanpa fallback IPv4 sama sekali) tidak akan terblokir DNS
 *    di level ini — kasus sangat jarang karena hampir seluruh domain publik
 *    modern dual-stack. Implementasi relay IPv6 penuh (setara
 *    TcpNatManager/UdpNatManager IPv4) tetap dicatat sebagai potensi kerja
 *    lanjutan, bukan prioritas karena dampak nyata dari fix gagal-cepat
 *    ini sudah menyelesaikan gejala performa yang dilaporkan.
 *  Lihat CHANGELOG.md §Audit-5 untuk detail & status verifikasi.
 */
class PacketTunnel(
    private val vpnService: VpnService,
    private val callbacks: Callbacks
) {

    /** Jembatan tipis ke NetShieldVpnService/DnsEngineRepository agar kelas ini tidak coupled langsung ke Room/UI. */
    interface Callbacks {
        fun currentDnsProvider(): DnsProvider
        fun currentFilterOptions(): List<FilterOption>
        fun currentCustomRules(): List<CustomRuleEntity>
        /** Fase 5.1: dipakai untuk memutuskan forward via DoH (HTTPS) atau UDP polos ke [DnsProvider.primaryIp]. */
        fun isDohEnabled(): Boolean
        /** Dipanggil untuk setiap query DNS yang selesai diproses (dipakai Fase 3 untuk log & stats nyata). */
        fun onDnsQueryResolved(domain: String, isBlocked: Boolean, category: String, latencyMs: Long, clientHint: String)
    }

    private val tunnelScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var packetLoopJob: Job? = null
    private val identificationCounter = AtomicInteger(1)

    private val udpNat = UdpNatManager(vpnService, tunnelScope) { data -> writeToTun(data) }
    private val tcpNat = TcpNatManager(vpnService, tunnelScope) { data -> writeToTun(data) }

    @Volatile private var outputStream: FileOutputStream? = null
    private val writeLock = Any()

    fun start(vpnInterface: ParcelFileDescriptor) {
        stop() // jaga-jaga tidak ada loop ganda

        val input = FileInputStream(vpnInterface.fileDescriptor)
        val output = FileOutputStream(vpnInterface.fileDescriptor)
        outputStream = output

        packetLoopJob = tunnelScope.launch {
            val buffer = ByteArray(MTU_BUFFER_SIZE)
            Log.d(TAG, "Packet loop dimulai.")
            try {
                while (isActive) {
                    val length = try {
                        input.read(buffer)
                    } catch (e: IOException) {
                        // Terjadi wajar saat tun interface ditutup dari stopVpn().
                        Log.d(TAG, "Tun input stream ditutup, keluar dari packet loop.")
                        break
                    }
                    if (length <= 0) continue

                    try {
                        handlePacket(buffer, length)
                    } catch (e: Exception) {
                        // Satu paket rusak/tak terduga tidak boleh mematikan seluruh loop.
                        Log.w(TAG, "Gagal memproses satu paket, dilewati: ${e.message}")
                    }
                }
            } finally {
                Log.d(TAG, "Packet loop berhenti.")
            }
        }
    }

    fun stop() {
        packetLoopJob?.cancel()
        packetLoopJob = null
        udpNat.closeAll()
        tcpNat.closeAll()
        outputStream = null
    }

    /** Membersihkan seluruh coroutine scope. Panggil hanya saat service benar-benar dihancurkan. */
    fun destroy() {
        stop()
        tunnelScope.cancel()
    }

    private fun writeToTun(data: ByteArray) {
        synchronized(writeLock) {
            try {
                outputStream?.write(data)
            } catch (e: IOException) {
                Log.w(TAG, "Gagal menulis balik ke tun (mungkin sudah ditutup): ${e.message}")
            }
        }
    }

    private suspend fun handlePacket(buffer: ByteArray, length: Int) {
        val ipv4 = NetPacketUtils.parseIpv4Header(buffer, length)
        if (ipv4 != null) {
            if (ipv4.isFragmented) {
                // Fragmentasi IP dikirim langsung / diabaikan
                Log.w(TAG, "Paket IPv4 terfragmentasi diterima (offset ${ipv4.fragmentOffset})")
            }
            val packet = buffer.copyOf(length)
            when (ipv4.protocol) {
                NetPacketUtils.PROTOCOL_UDP -> {
                    val udp = NetPacketUtils.parseUdpHeader(packet, ipv4.headerLength, length) ?: return
                    if (udp.dstPort == DNS_PORT) {
                        handleDnsQuery(packet, ipv4, udp)
                    } else {
                        udpNat.onOutboundPacket(packet, ipv4, udp)
                    }
                }
                NetPacketUtils.PROTOCOL_TCP -> {
                    val tcp = NetPacketUtils.parseTcpHeader(packet, ipv4.headerLength, length) ?: return
                    tcpNat.onOutboundPacket(packet, ipv4, tcp)
                }
                else -> {
                    // ICMP/protokol lain
                }
            }
            return
        }

        val ipv6 = NetPacketUtils.parseIpv6Header(buffer, length)
        if (ipv6 != null) {
            val packet = buffer.copyOf(length)
            when (ipv6.nextHeader) {
                NetPacketUtils.PROTOCOL_UDP -> {
                    val udp = NetPacketUtils.parseUdpHeader(packet, ipv6.headerLength, length) ?: return
                    if (udp.dstPort == DNS_PORT) {
                        handleDnsQueryIpv6(packet, ipv6, udp)
                    } else {
                        // Fase Audit-5 (BUG KRITIS): sebelumnya trafik UDP-over-IPv6
                        // non-DNS diam-diam DIABAIKAN (tidak ada balasan sama sekali)
                        // walau `Builder.addRoute("::", 0)` (Fase 6.10) sudah menangkap
                        // SEMUA trafik IPv6 masuk ke tunnel ini. Client menunggu
                        // timeout QUIC/UDP yang bisa berlangsung lama tanpa tahu
                        // jalurnya buntu — persis gejala "video/reels buffering lama,
                        // upload/download lambat" yang dilaporkan pada app yang CDN-nya
                        // memprioritaskan IPv6 (mis. Meta: Facebook/Instagram/WhatsApp).
                        // Fix: balas ICMPv6 Port Unreachable SEGERA supaya client
                        // langsung tahu jalur IPv6 ini buntu dan (lewat Happy Eyeballs/
                        // QUIC connection migration bawaan OS/app) berpindah ke jalur
                        // IPv4 yang SUDAH benar-benar di-NAT & difilter
                        // (UdpNatManager). IPv6 non-DNS SENGAJA tetap tidak di-relay
                        // (bukan cuma dibuat tidak-blackhole) — implementasi relay IPv6
                        // penuh tetap keterbatasan terdokumentasi, lihat catatan class
                        // ini & RENCANA_PRODUKSI_NETSHIELD.md §Catatan Penting.
                        val icmp = NetPacketUtils.buildIpv6IcmpPortUnreachable(
                            srcIp = ipv6.dstIp,
                            dstIp = ipv6.srcIp,
                            originalPacket = packet
                        )
                        writeToTun(icmp)
                    }
                }
                NetPacketUtils.PROTOCOL_TCP -> {
                    // Fase Audit-5 (BUG KRITIS, bagian paling berdampak): TCP-over-IPv6
                    // (jalur utama video/gambar/API non-QUIC) sebelumnya juga diam-diam
                    // diabaikan. SYN yang masuk tidak pernah dibalas apa pun -> klien
                    // menunggu penuh connect timeout OS (bisa 20-75 detik tergantung
                    // platform) sebelum akhirnya mencoba IPv4 — inilah penyebab utama
                    // laporan "internet sangat lambat saat proteksi aktif" padahal
                    // speedtest (yang memaksa IPv4 eksplisit ke server ujinya) terlihat
                    // normal. Fix: balas TCP RST+ACK segera untuk SYN di jalur ini,
                    // supaya OS/browser langsung gagal-cepat dan pindah ke IPv4.
                    val tcp = NetPacketUtils.parseTcpHeader(packet, ipv6.headerLength, length) ?: return
                    if (tcp.flags and NetPacketUtils.TCP_FLAG_SYN != 0) {
                        val rst = NetPacketUtils.buildIpv6TcpRst(
                            srcIp = ipv6.dstIp,
                            srcPort = tcp.dstPort,
                            dstIp = ipv6.srcIp,
                            dstPort = tcp.srcPort,
                            seq = 0L,
                            ack = (tcp.seq + 1) and 0xFFFFFFFFL
                        )
                        writeToTun(rst)
                    }
                    // Segmen non-SYN (data/FIN/ACK) untuk sesi IPv6 yang memang
                    // tidak pernah ter-establish di sisi kita diabaikan saja — RST
                    // di atas sudah dikirim saat SYN pertama, klien seharusnya
                    // sudah berhenti mencoba jalur ini.
                }
                else -> {
                    // ICMPv6 & protokol IPv6 lain di luar cakupan (Neighbor
                    // Discovery dkk. ditangani sistem Android sendiri di luar
                    // tun, bukan lewat jalur ini).
                }
            }
        }
    }

    private suspend fun handleDnsQueryIpv6(
        packet: ByteArray,
        ip: NetPacketUtils.Ipv6Header,
        udp: NetPacketUtils.UdpHeader
    ) {
        val dnsPayload = packet.copyOfRange(udp.payloadOffset, packet.size)
        val query = DnsMessage.parseQuery(dnsPayload) ?: return

        val decision = BlocklistEngine.evaluate(
            domain = query.domain,
            customRules = callbacks.currentCustomRules(),
            filterOptions = callbacks.currentFilterOptions()
        )

        val startTime = System.currentTimeMillis()

        if (decision.isBlocked) {
            val response = DnsMessage.buildBlockedResponse(dnsPayload, query, nxDomain = query.qType != 1)
            val replyPacket = NetPacketUtils.buildIpv6UdpPacket(
                srcIp = ip.dstIp,
                srcPort = udp.dstPort,
                dstIp = ip.srcIp,
                dstPort = udp.srcPort,
                payload = response
            )
            writeToTun(replyPacket)
            val latency = System.currentTimeMillis() - startTime
            callbacks.onDnsQueryResolved(query.domain, true, decision.category, latency, "")
            return
        }

        tunnelScope.launch {
            try {
                val provider = callbacks.currentDnsProvider()
                val upstreamResponse = forwardToUpstream(dnsPayload, provider)
                if (upstreamResponse != null) {
                    val replyPacket = NetPacketUtils.buildIpv6UdpPacket(
                        srcIp = ip.dstIp,
                        srcPort = udp.dstPort,
                        dstIp = ip.srcIp,
                        dstPort = udp.srcPort,
                        payload = upstreamResponse
                    )
                    writeToTun(replyPacket)
                }
                val latency = System.currentTimeMillis() - startTime
                callbacks.onDnsQueryResolved(query.domain, false, decision.category, latency, "")
            } catch (e: Exception) {
                Log.w(TAG, "Forward DNS IPv6 ke upstream gagal untuk ${query.domain}: ${e.message}")
            }
        }
    }

    private suspend fun handleDnsQuery(
        packet: ByteArray,
        ip: NetPacketUtils.Ipv4Header,
        udp: NetPacketUtils.UdpHeader
    ) {
        val dnsPayload = packet.copyOfRange(udp.payloadOffset, packet.size)
        val query = DnsMessage.parseQuery(dnsPayload) ?: run {
            // Bukan query DNS standar yang bisa kita parse (mis. DNS-over-TCP dobel, EDNS aneh) — forward mentah via NAT.
            udpNat.onOutboundPacket(packet, ip, udp)
            return
        }

        val decision = BlocklistEngine.evaluate(
            domain = query.domain,
            customRules = callbacks.currentCustomRules(),
            filterOptions = callbacks.currentFilterOptions()
        )

        val startTime = System.currentTimeMillis()

        if (decision.isBlocked) {
            val response = DnsMessage.buildBlockedResponse(dnsPayload, query, nxDomain = query.qType != 1)
            val replyPacket = NetPacketUtils.buildIpv4UdpPacket(
                srcIp = ip.dstIp, // balasan datang dari alamat yang "ditanya" klien (server DNS tunnel)
                srcPort = udp.dstPort,
                dstIp = ip.srcIp,
                dstPort = udp.srcPort,
                payload = response,
                identification = identificationCounter.getAndIncrement()
            )
            writeToTun(replyPacket)
            val latency = System.currentTimeMillis() - startTime
            callbacks.onDnsQueryResolved(query.domain, true, decision.category, latency, "")
            return
        }

        // Diizinkan -> forward ke upstream DNS pilihan user (Fase 1.6), lewat socket protect()-ed (Fase 1.5).
        tunnelScope.launch {
            try {
                val provider = callbacks.currentDnsProvider()
                val upstreamResponse = forwardToUpstream(dnsPayload, provider)
                if (upstreamResponse != null) {
                    val replyPacket = NetPacketUtils.buildIpv4UdpPacket(
                        srcIp = ip.dstIp,
                        srcPort = udp.dstPort,
                        dstIp = ip.srcIp,
                        dstPort = udp.srcPort,
                        payload = upstreamResponse,
                        identification = identificationCounter.getAndIncrement()
                    )
                    writeToTun(replyPacket)
                }
                val latency = System.currentTimeMillis() - startTime
                callbacks.onDnsQueryResolved(query.domain, false, decision.category, latency, "")
            } catch (e: Exception) {
                Log.w(TAG, "Forward DNS ke upstream gagal untuk ${query.domain}: ${e.message}")
            }
        }
    }

    /**
     * Forward payload DNS mentah ke resolver upstream — via DNS-over-HTTPS
     * (Fase 5.1) jika `dohEnabled` aktif & provider mendukungnya, atau UDP
     * polos ke [DnsProvider.primaryIp] sebagai fallback. Socket/koneksi
     * WAJIB di-protect() (Fase 1.5) agar tidak masuk balik ke tunnel.
     */
    private suspend fun forwardToUpstream(dnsPayload: ByteArray, provider: DnsProvider): ByteArray? {
        val useDoh = callbacks.isDohEnabled() && provider.supportsDoH && provider.dohUrl.isNotBlank()
        if (useDoh) {
            val dohResult = forwardViaDoh(dnsPayload, provider)
            if (dohResult != null) return dohResult
            // DoH gagal (mis. jaringan memblokir 443 ke resolver tertentu,
            // atau timeout) -> fallback ke UDP polos supaya resolusi DNS
            // tetap jalan, bukan device kehilangan internet total.
            Log.w(TAG, "DoH gagal untuk ${provider.name}, fallback ke UDP polos.")
        }
        return forwardViaUdp(dnsPayload, provider)
    }

    /**
     * DNS-over-HTTPS (RFC 8484, "wireformat" via POST) — Fase 5.1.
     * Memakai OkHttp (sudah jadi dependency project, dipakai juga oleh
     * [BlocklistUpdateManager]) alih-alih menulis HTTP client sendiri.
     * Koneksi socket OkHttp TIDAK bisa langsung di-protect() seperti
     * DatagramSocket biasa, jadi client dibangun dengan
     * [ProtectingSocketFactory] yang memanggil [VpnService.protect] pada
     * setiap socket TCP/TLS yang dibuatnya sebelum connect — WAJIB, kalau
     * tidak trafik HTTPS ke resolver akan masuk balik ke tunnel sendiri
     * (infinite loop, lihat dokumentasi kelas PacketTunnel).
     */
    private suspend fun forwardViaDoh(dnsPayload: ByteArray, provider: DnsProvider): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val client = dohClient
                val request = okhttp3.Request.Builder()
                    .url(provider.dohUrl)
                    .header("Accept", "application/dns-message")
                    .header("Content-Type", "application/dns-message")
                    .post(dnsPayload.toRequestBody("application/dns-message".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "DoH HTTP ${response.code} dari ${provider.dohUrl}")
                        return@withContext null
                    }
                    response.body?.bytes()
                }
            } catch (e: Exception) {
                Log.w(TAG, "forwardViaDoh gagal (${provider.name}): ${e.message}")
                null
            }
        }

    /** Forward UDP polos ke [DnsProvider.primaryIp] (dan fallback ke [DnsProvider.secondaryIp]) port 53. */
    private suspend fun forwardViaUdp(dnsPayload: ByteArray, provider: DnsProvider): ByteArray? =
        withContext(Dispatchers.IO) {
            var response = queryUdpAddress(dnsPayload, provider.primaryIp)
            if (response == null && provider.secondaryIp.isNotBlank()) {
                Log.d(TAG, "Primary IP DNS ${provider.primaryIp} gagal/timeout, mencoba secondary IP ${provider.secondaryIp}")
                response = queryUdpAddress(dnsPayload, provider.secondaryIp)
            }
            response
        }

    private fun queryUdpAddress(dnsPayload: ByteArray, ipStr: String): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            vpnService.protect(socket) // WAJIB — lihat dokumentasi kelas di atas.
            socket.soTimeout = UPSTREAM_TIMEOUT_MS

            val upstreamAddr = InetAddress.getByName(ipStr)
            val requestPacket = DatagramPacket(dnsPayload, dnsPayload.size, upstreamAddr, DNS_PORT)
            socket.send(requestPacket)

            val responseBuffer = ByteArray(MTU_BUFFER_SIZE)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(responsePacket)

            responseBuffer.copyOf(responsePacket.length)
        } catch (e: Exception) {
            Log.w(TAG, "queryUdpAddress gagal ($ipStr): ${e.message}")
            null
        } finally {
            socket?.close()
        }
    }

    /**
     * OkHttpClient khusus DoH, socketFactory-nya memanggil
     * [VpnService.protect] pada tiap socket baru (Fase 1.5 diterapkan ke
     * jalur HTTPS). Dibuat sekali & dipakai ulang (bukan per-request) demi
     * connection pooling & menghindari overhead TLS handshake berulang.
     */
    private val dohClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .socketFactory(ProtectingSocketFactory(vpnService, javax.net.SocketFactory.getDefault()))
            .connectTimeout(java.time.Duration.ofMillis(UPSTREAM_TIMEOUT_MS.toLong()))
            .readTimeout(java.time.Duration.ofMillis(UPSTREAM_TIMEOUT_MS.toLong()))
            .callTimeout(java.time.Duration.ofMillis(UPSTREAM_TIMEOUT_MS.toLong()))
            .build()
    }

    companion object {
        private const val TAG = "PacketTunnel"
        const val DNS_PORT = 53
        const val MTU_BUFFER_SIZE = 1500
        const val UPSTREAM_TIMEOUT_MS = 2500
    }
}

/**
 * SocketFactory yang membungkus factory default dan memanggil
 * [VpnService.protect] pada setiap socket yang dibuat, SEBELUM socket
 * tersebut sempat connect. Dipakai [PacketTunnel.dohClient] agar trafik
 * DoH (HTTPS) tidak ikut masuk balik ke tun interface milik VPN sendiri —
 * padanan `protect()` untuk DatagramSocket (Fase 1.5) tapi untuk koneksi
 * TCP/TLS yang dibuat OkHttp (Fase 5.1).
 */
private class ProtectingSocketFactory(
    private val vpnService: VpnService,
    private val delegate: javax.net.SocketFactory
) : javax.net.SocketFactory() {
    override fun createSocket(): java.net.Socket =
        delegate.createSocket().also { vpnService.protect(it) }

    override fun createSocket(host: String?, port: Int): java.net.Socket =
        createSocket().apply { connect(java.net.InetSocketAddress(host, port)) }

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): java.net.Socket =
        createSocket().apply {
            localHost?.let { bind(java.net.InetSocketAddress(it, localPort)) }
            connect(java.net.InetSocketAddress(host, port))
        }

    override fun createSocket(host: InetAddress?, port: Int): java.net.Socket =
        createSocket().apply { connect(java.net.InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): java.net.Socket =
        createSocket().apply {
            localAddress?.let { bind(java.net.InetSocketAddress(it, localPort)) }
            connect(java.net.InetSocketAddress(address, port))
        }
}
