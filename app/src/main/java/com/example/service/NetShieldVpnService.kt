package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.NetShieldApplication
import com.example.R
import com.example.data.local.CustomRuleEntity
import com.example.model.DnsProvider
import com.example.model.FilterOption
import com.example.vpn.PacketTunnel
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VpnService inti NetShield. Mengelola tun interface lokal untuk
 * penyaringan DNS.
 *
 * STATUS (Fase 1 selesai): tunnel VPN sekarang benar-benar memproses paket
 * lewat [PacketTunnel] — query DNS dicegat, dicocokkan ke aturan blokir,
 * dan diputuskan (blokir lokal / forward ke upstream terpilih); trafik
 * non-DNS di-relay apa adanya lewat NAT sederhana (UDP/TCP) supaya internet
 * tetap berjalan normal. Lihat RENCANA_PRODUKSI_NETSHIELD.md §Fase 1.
 *
 * === CHANGELOG ===
 * [Fase 0 - 2026-08-07]
 *  - isRunning: Boolean -> AtomicBoolean, dibungkus synchronized(lifecycleLock)
 *    untuk mencegah race condition saat CONNECT/DISCONNECT datang hampir
 *    bersamaan.
 *  - Kegagalan builder.establish() == null kini ditangani eksplisit
 *    (rollback via stopVpn()), sebelumnya tidak dicek sama sekali.
 *  - Ditambahkan override onRevoke() untuk menangani pencabutan izin VPN
 *    dari luar (Settings > VPN, atau VPN app lain mengambil alih).
 *  Lihat CHANGELOG.md & RENCANA_PRODUKSI_NETSHIELD.md §Fase 0.
 * [Fase 1 - 2026-08-07]
 *  - Ditambahkan `Builder.addRoute("0.0.0.0", 0)` — WAJIB supaya seluruh
 *    trafik (bukan cuma DNS) benar-benar masuk ke tun interface; tanpa ini
 *    packet loop tidak akan pernah menerima paket apa pun.
 *  - DNS server tunnel (`addDnsServer`) sekarang dari
 *    `repository.selectedProviderSnapshot()`, hardcode 1.1.1.1/1.0.0.1
 *    dihapus (Fase 1.6).
 *  - `PacketTunnel` di-start setelah `establish()` sukses, di-stop di
 *    `stopVpn()`/`onDestroy()`.
 *  Lihat CHANGELOG.md & RENCANA_PRODUKSI_NETSHIELD.md §Fase 1.
 * [Fase 3 - 2026-08-07]
 *  - `vpnServiceCallbacks.onDnsQueryResolved()` sekarang memanggil
 *    `repository.recordDnsQueryResolved()` (kanal SharedFlow non-blocking)
 *    alih-alih hanya `Log.d`, sehingga setiap query DNS nyata benar-benar
 *    tercatat ke Room & ProtectionStats. Lihat RENCANA_PRODUKSI_NETSHIELD.md §Fase 3.
 * [Fase 5 - 2026-08-07]
 *  - `vpnServiceCallbacks.isDohEnabled()` ditambahkan, menyambungkan toggle
 *    DoH di SettingsScreen ke `PacketTunnel.forwardToUpstream` (5.1).
 *  Lihat CHANGELOG.md & RENCANA_PRODUKSI_NETSHIELD.md §Fase 5.
 * [Fase 6.1 - 2026-08-07]
 *  - `startVpn()`/`stopVpn()` sekarang memanggil
 *    `repository.syncProtectionStateFromService(...)` di setiap titik
 *    keluar (sukses establish, gagal establish, stop normal via
 *    ACTION_DISCONNECT, ATAU `onRevoke()`). Sebelumnya `onRevoke()` sudah
 *    menghentikan service dengan bersih (notifikasi hilang, tun ditutup),
 *    TAPI `isProtectionActive` di repository/UI tidak pernah ikut
 *    disinkronkan balik — switch di MainActivity bisa tetap menampilkan
 *    "aktif" walau VPN sudah benar-benar mati di OS. Juga memperbaiki
 *    celah lama: tombol "Matikan Proteksi" di notifikasi mengirim
 *    ACTION_DISCONNECT LANGSUNG ke service (tanpa lewat ViewModel/
 *    toggleProtection), jadi tanpa sinkronisasi ini UI tidak pernah tahu
 *    proteksi sudah dimatikan dari notifikasi.
 *  Lihat CHANGELOG.md & RENCANA_PRODUKSI_NETSHIELD.md §Fase 6.1.
 */
class NetShieldVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var packetTunnel: PacketTunnel? = null

    // AtomicBoolean dipakai (bukan `var` biasa) karena onStartCommand() bisa
    // dipanggil dari intent CONNECT dan DISCONNECT yang datang hampir
    // bersamaan (mis. sistem Android memanggil ulang service, atau user
    // menekan tombol notifikasi tepat saat UI juga memicu start). Flag
    // boolean biasa tidak menjamin visibility antar-thread dan bisa
    // menyebabkan state isRunning tidak konsisten dengan vpnInterface
    // yang sesungguhnya.
    private val isRunning = AtomicBoolean(false)

    // Mengunci start/stop agar tidak ada dua eksekusi startVpn()/stopVpn()
    // berjalan bersamaan yang bisa membuat vpnInterface diakses/ditutup
    // dari dua tempat sekaligus.
    private val lifecycleLock = Any()

    private val repository by lazy { (application as NetShieldApplication).dnsEngineRepository }

    private var connectivityManager: android.net.ConnectivityManager? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_DISCONNECT) {
            stopVpn()
            return START_NOT_STICKY
        }

        // Jika intent == null, artinya service di-restart otomatis oleh Android OS setelah di-kill.
        // Cek status proteksi di repository; jika aktif, jalankan ulang VPN secara otomatis.
        if (intent == null) {
            Log.d(TAG, "VPN Service di-restart oleh sistem (intent == null).")
            if (!repository.isProtectionActive.value) {
                stopVpn()
                return START_NOT_STICKY
            }
        }

        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        synchronized(lifecycleLock) {
            if (isRunning.get()) {
                Log.d(TAG, "startVpn() dipanggil saat VPN sudah berjalan, diabaikan.")
                return
            }

            try {
                createNotificationChannel()
                val notification = createNotification(
                    "NetShield DNS Proteksi Aktif",
                    "Seluruh iklan, tracker, & malware diblokir secara otomatis."
                )
                startForeground(NOTIFICATION_ID, notification)

                // Fase 1.6: provider DNS upstream diambil dari pilihan user di
                // DnsSettingsScreen (lewat repository), bukan hardcode lagi.
                val provider = repository.selectedProviderSnapshot()

                val builder = Builder()
                    .setSession("NetShield DNS")
                    .addAddress(TUNNEL_ADDRESS, 32)
                    .addAddress(LOCAL_DNS_IPV6, 128)
                    .addDnsServer(TUNNEL_ADDRESS)
                    .addDnsServer(LOCAL_DNS_IPV6)
                    // Rute khusus IP DNS agar HANYA trafik query DNS yang masuk ke TUN interface.
                    // Seluruh trafik non-DNS (HTTP/HTTPS, video, audio, gaming, download)
                    // berjalan langsung via Wi-Fi / Cellular dengan kecepatan 100% penuh.
                    .addRoute(TUNNEL_ADDRESS, 32)
                    .addRoute(LOCAL_DNS_IPV6, 128)
                    .addRoute("1.1.1.1", 32)
                    .addRoute("1.0.0.1", 32)
                    .addRoute("8.8.8.8", 32)
                    .addRoute("8.8.4.4", 32)
                    .addRoute("9.9.9.9", 32)
                    .addRoute("149.112.112.112", 32)
                    .addRoute("206.189.255.1", 32)
                    .addRoute("2606:4700:4700::1111", 128)
                    .addRoute("2001:4860:4860::8888", 128)
                    .setMtu(PacketTunnel.MTU_BUFFER_SIZE)

                try {
                    if (provider.primaryIp.isNotBlank()) builder.addRoute(provider.primaryIp, 32)
                    if (provider.secondaryIp.isNotBlank()) builder.addRoute(provider.secondaryIp, 32)
                } catch (e: Exception) {
                    Log.w(TAG, "Tidak dapat menambah provider IP ke route: ${e.message}")
                }

                try {
                    builder.addDisallowedApplication(packageName)
                } catch (e: Exception) {
                    Log.w(TAG, "Gagal mengesampingkan paket NetShield dari VPN: ${e.message}")
                }

                val establishedInterface = builder.establish()

                if (establishedInterface == null) {
                    Log.e(TAG, "VpnService.Builder.establish() mengembalikan null. Membatalkan start.")
                    stopVpn()
                    return
                }

                vpnInterface = establishedInterface
                isRunning.set(true)

                // Registrasi network callback untuk memantau perubahan WiFi <-> Mobile
                registerNetworkCallback()

                val tunnel = PacketTunnel(this, vpnServiceCallbacks)
                tunnel.start(establishedInterface)
                packetTunnel = tunnel

                repository.syncProtectionStateFromService(true)

                Log.d(TAG, "NetShield VPN Service started successfully. Upstream=${provider.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VPN Service", e)
                stopVpn()
            }
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            connectivityManager = cm
            val request = android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    Log.d(TAG, "Koneksi jaringan baru tersedia: $network. Memperbarui underlying networks.")
                    setUnderlyingNetworks(arrayOf(network))
                }

                override fun onLost(network: android.net.Network) {
                    Log.d(TAG, "Koneksi jaringan terputus: $network.")
                    setUnderlyingNetworks(null)
                }

                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    networkCapabilities: android.net.NetworkCapabilities
                ) {
                    if (networkCapabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        setUnderlyingNetworks(arrayOf(network))
                    }
                }
            }
            cm?.registerNetworkCallback(request, cb)
            networkCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "Gagal mendaftarkan ConnectivityManager NetworkCallback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Gagal menghapus NetworkCallback: ${e.message}")
        } finally {
            networkCallback = null
            connectivityManager = null
        }
    }

    private fun stopVpn() {
        synchronized(lifecycleLock) {
            unregisterNetworkCallback()
            packetTunnel?.destroy()
            packetTunnel = null
            try {
                vpnInterface?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing VPN interface", e)
            } finally {
                vpnInterface = null
            }
            isRunning.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()

            repository.syncProtectionStateFromService(false)

            Log.d(TAG, "NetShield VPN Service stopped.")
        }
    }

    /** Jembatan PacketTunnel -> repository, dijaga tetap tipis (baca snapshot sinkron saja, lihat DnsEngineRepository). */
    private val vpnServiceCallbacks = object : PacketTunnel.Callbacks {
        override fun currentDnsProvider(): DnsProvider = repository.selectedProviderSnapshot()
        override fun currentFilterOptions(): List<FilterOption> = repository.filterOptionsSnapshot()
        override fun currentCustomRules(): List<CustomRuleEntity> = repository.customRulesSnapshot()
        override fun isDohEnabled(): Boolean = repository.dohEnabledSnapshot()

        override fun onDnsQueryResolved(
            domain: String,
            isBlocked: Boolean,
            category: String,
            latencyMs: Long,
            clientHint: String
        ) {
            // Fase 3.2: kirim event ke DnsEngineRepository (via SharedFlow
            // non-blocking) untuk ditulis ke Room (dnsLogs) & diagregasi ke
            // ProtectionStats nyata. Menggantikan Log.d-only Fase 1.
            repository.recordDnsQueryResolved(domain, isBlocked, category, latencyMs, clientHint)
        }
    }

    /**
     * Dipanggil sistem Android saat user mencabut izin VPN dari luar app
     * (mis. lewat Settings > VPN, atau mengaktifkan VPN app lain yang
     * menggantikan koneksi ini). Wajib ditangani agar state internal
     * service (isRunning, vpnInterface) tetap konsisten dan notifikasi
     * foreground service tidak menggantung.
     */
    override fun onRevoke() {
        Log.d(TAG, "VPN permission revoked by system/user.")
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NetShield Protection Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifikasi status pemblokir iklan dan DNS NetShield"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, NetShieldVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Matikan Proteksi", stopPendingIntent)
            .build()
    }

    companion object {
        const val TAG = "NetShieldVpnService"
        const val ACTION_CONNECT = "com.example.netshield.CONNECT"
        const val ACTION_DISCONNECT = "com.example.netshield.DISCONNECT"
        const val CHANNEL_ID = "netshield_vpn_channel"
        const val NOTIFICATION_ID = 1001
        // Alamat lokal tun interface (sisi klien di dalam tunnel). Dipakai
        // juga sebagai "srcIp"/"dstIp" balasan DNS sintetis di PacketTunnel.
        const val TUNNEL_ADDRESS = "10.0.0.2"
        const val LOCAL_DNS_IPV6 = "fd00::1"
    }
}
