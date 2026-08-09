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
import kotlinx.coroutines.channels.Channel
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
 * [Audit-11 - 2026-08-09] **BUG KRITIS ditemukan lewat verifikasi device
 * fisik nyata**: user melaporkan build Audit-10 (fix redundansi memori
 * blocklist) MASIH sangat lambat/buffering saat nonton video media sosial.
 * Audit-10 tidak menyentuh sama sekali kandidat yang sudah diflag terbuka
 * sejak Audit-4 (lihat "Keterbatasan/Kandidat Audit Berikutnya" di
 * TcpNatManager.kt): satu `synchronized(writeLock)` di [writeToTun] yang
 * dipakai BERSAMA oleh SEMUA sesi TCP+UDP+DNS sekaligus.
 *  - Root cause: `synchronized` di Kotlin memblokir THREAD OS ASLI, bukan
 *    cuma coroutine-nya. Saat scroll Reels/TikTok, puluhan sesi TCP paralel
 *    (tiap sesi punya `tunWriterLoop` sendiri di `Dispatchers.IO`) berebut
 *    lock yang sama. Kalau satu `outputStream.write()` sempat lambat
 *    (buffer kernel tun penuh — wajar di beban tinggi), SEMUA thread lain
 *    yang antre lock itu ikut terblokir, termasuk berpotensi menghabiskan
 *    thread pool `Dispatchers.IO` yang dipakai bersama SELURUH app
 *    (termasuk `BlocklistUpdateManager`). Ini match persis dengan gejala
 *    yang dilaporkan user: lambat spesifik saat banyak koneksi paralel
 *    (video sosial), bukan aktivitas ringan seperti baca teks.
 *  - Fix: `writeLock`/`synchronized` DIHAPUS TOTAL. Diganti
 *    `tunOutboundChannel` (Channel.UNLIMITED) + SATU coroutine
 *    `tunWriterJob` yang jadi satu-satunya pemanggil `outputStream.write()`.
 *    Semua pemanggil `writeToTun()` (TcpNatManager/UdpNatManager/DNS reply)
 *    sekarang cukup `trySend()` — operasi cepat non-blocking, TIDAK PERNAH
 *    menunggu syscall write selesai. Signature `writeToTun` SENGAJA
 *    dipertahankan sama (bukan suspend) supaya nol perubahan di pemanggil
 *    manapun — meminimalkan risiko regresi.
 *  - INI BUKAN JAMINAN menyelesaikan 100% masalah buffering — ini kandidat
 *    root cause paling kuat berdasarkan bukti kode (lock blocking thread
 *    pool bersama) + gejala device fisik yang cocok, tapi WAJIB diverifikasi
 *    ulang. Jika masih lambat setelah ini, kandidat berikutnya: throughput
 *    fundamental `ParcelFileDescriptor`/tun device Android sendiri (di luar
 *    kendali kode aplikasi) — lihat catatan Audit-4.
 *  Lihat CHANGELOG.md & RENCANA_PRODUKSI_NETSHIELD.md §Audit-11 untuk
 *  detail & checklist verifikasi lengkap.
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
    private var icmpNotifySweeperJob: Job? = null
    private val identificationCounter = AtomicInteger(1)

    private val udpNat = UdpNatManager(vpnService, tunnelScope) { data -> writeToTunSuspend(data) }
    private val tcpNat = TcpNatManager(vpnService, tunnelScope, { data -> writeToTun(data) }, { data -> writeToTunSuspend(data) })

    // Fase Audit-6: dedupe key untuk balasan ICMPv6 Port Unreachable (lihat
    // handlePacket cabang IPv6 UDP) — WAJIB, lihat dokumentasi bug kritis di
    // atas kelas ini. Key = (srcPort klien, dstIp tujuan, dstPort tujuan),
    // value = waktu terakhir kali kita membalas ICMP untuk kombinasi ini.
    private data class Ipv6UdpNotifyKey(val srcPort: Int, val dstIp: String, val dstPort: Int)
    private val ipv6UdpIcmpNotified = java.util.concurrent.ConcurrentHashMap<Ipv6UdpNotifyKey, Long>()

    @Volatile private var outputStream: FileOutputStream? = null
    // [Fase Audit-12 - 2026-08-09] PERBAIKAN REGRESI KRITIS dari Audit-11.
    // Audit-11 mengganti `synchronized(writeLock)` dengan
    // `Channel.UNLIMITED` + `trySend()` untuk menghilangkan thread-blocking
    // di packet loop. Itu BERHASIL menghapus kontensi lock, TAPI membuka
    // bug baru yang lebih berbahaya: karena channel TIDAK PERNAH penuh dan
    // `trySend()` TIDAK PERNAH menunggu, begitu laju produksi paket (mis.
    // puluhan sesi TCP paralel saat scroll Reels/TikTok) melebihi laju
    // nyata `outputStream.write()` ke tun device, paket menumpuk di RAM
    // TANPA BATAS alih-alih memberi tekanan balik (backpressure) ke
    // pengirimnya. Channel ini adalah titik keluar TUNGGAL untuk SELURUH
    // trafik device (TCP+UDP+DNS semua app), jadi begitu satu burst video
    // membuatnya membengkak, SEMUA trafik lain (browsing/download/game)
    // ikut antre di belakangnya (head-of-line blocking) — persis
    // menjelaskan laporan user: awalnya lancar, lalu SETELAH beberapa
    // video (channel sempat menumpuk banyak), SEMUA jenis trafik (bukan
    // cuma video) mendadak lambat/buffer, bahkan berpotensi OOM kalau
    // dibiarkan cukup lama.
    //
    // Fix: channel dibatasi ([TUN_OUTBOUND_CHANNEL_CAPACITY]) sehingga
    // memori TIDAK PERNAH bisa tumbuh tanpa batas — di bawah beban ekstrem,
    // kelebihan paket di-drop dengan log peringatan (bukan menumpuk diam-
    // diam), sama seperti pola yang SUDAH BENAR diterapkan di level sesi
    // (`inboundChannel` UDP kapasitas 256, `TCP_INBOUND_CHANNEL_CAPACITY`
    // 64 di TcpNatManager). Untuk jalur DATA TCP arah server->client
    // (byte video/gambar/download sesungguhnya — paling sensitif terhadap
    // kehilangan data, lihat dokumentasi Audit-4 di TcpNatManager.kt),
    // ditambahkan [writeToTunSuspend] yang memakai `send()` suspend asli
    // (backpressure sejati, tidak pernah drop) — dipanggil HANYA dari
    // context suspend yang sudah ada (`tunWriterLoop` TCP & UDP), sehingga
    // data video/download tidak pernah korup akibat drop di titik ini.
    // Jalur kontrol (ACK/SYN-ACK/RST/FIN/ICMP/balasan DNS) tetap memakai
    // [writeToTun] (trySend, boleh drop) karena secara alami sudah toleran
    // kehilangan sesekali (retransmisi/timeout standar TCP/UDP/DNS) dan
    // sebagian dipanggil dari context sinkron (mis. RST saat eviction sesi
    // penuh di TcpNatManager.evictOldestIfFull()) yang tidak bisa `suspend`.
    // Lihat CHANGELOG.md & RENCANA_PRODUKSI_NETSHIELD.md §Audit-12 untuk
    // detail analisis & checklist verifikasi.
    private val tunOutboundChannel = Channel<ByteArray>(capacity = TUN_OUTBOUND_CHANNEL_CAPACITY)
    private var tunWriterJob: Job? = null

    fun start(vpnInterface: ParcelFileDescriptor) {
        stop() // jaga-jaga tidak ada loop ganda

        val input = FileInputStream(vpnInterface.fileDescriptor)
        val output = FileOutputStream(vpnInterface.fileDescriptor)
        outputStream = output

        // Fase Audit-11: satu-satunya coroutine yang benar-benar melakukan
        // syscall write() ke tun, menguras tunOutboundChannel secara
        // berurutan. Karena hanya SATU coroutine yang pernah menyentuh
        // outputStream, tidak ada lagi kebutuhan `synchronized` sama sekali
        // — dan yang lebih penting, tidak ada thread lain yang pernah
        // terblokir menunggu syscall write selesai.
        tunWriterJob = tunnelScope.launch(Dispatchers.IO) {
            for (data in tunOutboundChannel) {
                try {
                    outputStream?.write(data)
                } catch (e: IOException) {
                    Log.w(TAG, "Gagal menulis balik ke tun (mungkin sudah ditutup): ${e.message}")
                }
            }
        }

        // Fase Audit-6: bersihkan entri dedupe ICMPv6 kadaluarsa secara
        // berkala — lihat dokumentasi `ipv6UdpIcmpNotified` & konstanta
        // ICMP_NOTIFY_SWEEP_INTERVAL_MS.
        icmpNotifySweeperJob = tunnelScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(ICMP_NOTIFY_SWEEP_INTERVAL_MS)
                val cutoff = System.currentTimeMillis() - ICMP_NOTIFY_COOLDOWN_MS
                ipv6UdpIcmpNotified.entries.removeIf { it.value < cutoff }
            }
        }

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
        icmpNotifySweeperJob?.cancel()
        icmpNotifySweeperJob = null
        tunWriterJob?.cancel()
        tunWriterJob = null
        ipv6UdpIcmpNotified.clear()
        udpNat.closeAll()
        tcpNat.closeAll()
        outputStream = null
    }

    /** Membersihkan seluruh coroutine scope. Panggil hanya saat service benar-benar dihancurkan. */
    fun destroy() {
        stop()
        tunnelScope.cancel()
    }

    /**
     * Jalur KONTROL (boleh drop): meng-enqueue ke [tunOutboundChannel] lewat
     * `trySend()` — cepat, non-blocking, TIDAK PERNAH menunggu. Dipakai untuk
     * paket yang secara alami toleran kehilangan sesekali (ACK/SYN-ACK/RST/
     * FIN/ICMP/balasan DNS — semua punya mekanisme retry/timeout standar di
     * level protokol), dan untuk call site yang memang sinkron (bukan
     * suspend context), mis. RST saat `evictOldestIfFull()` di
     * TcpNatManager. Sejak [Fase Audit-12], channel ini BERKAPASITAS
     * TERBATAS ([TUN_OUTBOUND_CHANNEL_CAPACITY]) — saat penuh, paket
     * di-drop dengan log peringatan alih-alih menumpuk tanpa batas di RAM
     * (lihat dokumentasi lengkap di deklarasi [tunOutboundChannel]).
     */
    private fun writeToTun(data: ByteArray) {
        val result = tunOutboundChannel.trySend(data)
        if (result.isFailure) {
            Log.w(TAG, "tunOutboundChannel penuh, satu paket kontrol di-drop (bukan menumpuk tanpa batas): ${result.exceptionOrNull()?.message}")
        }
    }

    /**
     * Jalur DATA (TIDAK boleh drop): `send()` suspend asli ke
     * [tunOutboundChannel] — memberi backpressure sejati. Saat channel
     * penuh, pemanggil (satu-satunya: `tunWriterLoop` TCP/UDP, sudah dalam
     * suspend context) akan menunggu sejenak alih-alih paket hilang diam-
     * diam — persis pola yang sudah benar diterapkan di
     * `TcpNatManager.Session.inboundChannel` (kapasitas 64, `send()`
     * suspend) untuk mencegah korupsi stream video/download. Lihat
     * dokumentasi [Fase Audit-12] di deklarasi [tunOutboundChannel].
     */
    private suspend fun writeToTunSuspend(data: ByteArray) {
        tunOutboundChannel.send(data)
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
                        // Fix: balas ICMPv6 Port Unreachable supaya client langsung
                        // tahu jalur IPv6 ini buntu dan (lewat Happy Eyeballs/QUIC
                        // connection migration bawaan OS/app) berpindah ke jalur IPv4
                        // yang SUDAH benar-benar di-NAT & difilter (UdpNatManager).
                        //
                        // Fase Audit-6 (BUG KRITIS REGRESI ditemukan — laporan: SETELAH
                        // Audit-5 dipasang, internet malah JAUH LEBIH PARAH: Play Store
                        // search timeout, Claude app sendiri gagal kirim chat/lampiran,
                        // tes jaringan Mobile Legends gagal total): kode Audit-5 di atas
                        // mengirim SATU paket ICMPv6 balasan untuk **SETIAP** paket UDP
                        // IPv6 non-DNS yang lewat — bukan cuma sekali per "sesi". UDP
                        // tidak punya flag SYN seperti TCP untuk menandai "paket
                        // pertama", jadi versi Audit-5 salah asumsi tiap paket harus
                        // dibalas. Di jaringan dual-stack (banyak app/SDK Google —
                        // termasuk Play Store & kemungkinan besar backend Claude app
                        // sendiri — memakai QUIC/UDP lewat IPv6 secara default, bisa
                        // ratusan-ribuan paket/detik saat banyak app aktif), ini
                        // menciptakan "badai" balasan ICMP yang ditulis lewat
                        // `writeToTun()` (lock GLOBAL yang sama dipakai SEMUA trafik
                        // TCP+UDP+DNS, termasuk trafik IPv4 yang sudah sehat) — jauh
                        // lebih membebani tunnel daripada kondisi SEBELUM Audit-5 sama
                        // sekali (yang minimal "cuma" diam, tidak menambah beban tulis).
                        // Inilah kenapa SEMUA hal sempat gagal total, bukan cuma lambat.
                        //
                        // Fix: dedupe per key (srcPort, dstIp, dstPort) — HANYA kirim
                        // ICMPv6 Port Unreachable SEKALI per kombinasi ini per
                        // [ICMP_NOTIFY_COOLDOWN_MS], meniru semantik "hanya balas SYN"
                        // di jalur TCP. Client tetap dapat sinyal cepat pada percobaan
                        // PERTAMA (tujuan awal Audit-5 tercapai), tapi paket ke-2 dst.
                        // dalam sesi/QUIC-retry yang sama tidak lagi memicu balasan
                        // baru — menghilangkan badai tulis ke tun.
                        val notifyKey = Ipv6UdpNotifyKey(udp.srcPort, NetPacketUtils.ipToString(ipv6.dstIp), udp.dstPort)
                        val now = System.currentTimeMillis()
                        val lastNotified = ipv6UdpIcmpNotified.putIfAbsent(notifyKey, now)
                        val shouldNotify = lastNotified == null || (now - lastNotified) > ICMP_NOTIFY_COOLDOWN_MS
                        if (shouldNotify) {
                            ipv6UdpIcmpNotified[notifyKey] = now
                            val icmp = NetPacketUtils.buildIpv6IcmpPortUnreachable(
                                srcIp = ipv6.dstIp,
                                dstIp = ipv6.srcIp,
                                originalPacket = packet
                            )
                            writeToTun(icmp)
                        }
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
        // [Fase Audit-12]: batas kapasitas tunOutboundChannel — lihat
        // dokumentasi lengkap di deklarasi `tunOutboundChannel` di atas.
        // 4096 slot x ~1500 byte (MTU) maks ~6MB buffer terburuk, jauh
        // lebih aman daripada UNLIMITED (bisa ratusan MB-GB dalam
        // hitungan detik saat streaming berat), tapi tetap cukup besar
        // untuk menyerap burst wajar (mis. beberapa sesi TCP video paralel)
        // tanpa membuang paket di kondisi pemakaian normal.
        const val TUN_OUTBOUND_CHANNEL_CAPACITY = 4096
        const val UPSTREAM_TIMEOUT_MS = 2500
        // Fase Audit-6: jeda minimum antar balasan ICMPv6 Port Unreachable
        // untuk kombinasi (srcPort, dstIp, dstPort) UDP-over-IPv6 yang sama —
        // lihat dokumentasi bug kritis regresi di handlePacket. 10 detik cukup
        // untuk membuat client gagal-cepat di percobaan pertama tanpa
        // menciptakan badai balasan saat QUIC retry berkali-kali dalam sesi
        // yang sama.
        private const val ICMP_NOTIFY_COOLDOWN_MS = 10_000L
        // Fase Audit-6: interval pembersihan entri kadaluarsa di
        // ipv6UdpIcmpNotified — tanpa ini map akan tumbuh tanpa batas
        // selama VPN aktif (setiap kombinasi port sumber baru menambah
        // entri baru, port sumber acak/ephemeral tiap koneksi baru).
        private const val ICMP_NOTIFY_SWEEP_INTERVAL_MS = 60_000L
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
