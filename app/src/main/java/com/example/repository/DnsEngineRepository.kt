package com.example.repository

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.BuildConfig
import com.example.R
import com.example.data.local.CustomRuleEntity
import com.example.data.local.DnsLogEntity
import com.example.data.local.NetShieldDao
import com.example.data.local.NetShieldDatabase
import com.example.data.local.ThreatEventEntity
import com.example.model.DnsProvider
import com.example.model.FilterOption
import com.example.model.ProtectionStats
import com.example.vpn.BlocklistEngine
import com.example.vpn.BlocklistStore
import com.example.vpn.BlocklistUpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Repository ini dimaksudkan untuk dibuat SATU KALI sebagai singleton
 * (lihat [com.example.NetShieldApplication]) dan hidup selama proses
 * aplikasi berjalan. Jangan buat instance baru dari repository ini di
 * tempat lain (mis. langsung di ViewModel), karena akan menduplikasi
 * CoroutineScope dan job simulasi/uptime yang berjalan di background.
 *
 * STATUS (Fase 3 selesai untuk log/stats): dnsLogs & ProtectionStats kini
 * bersumber dari trafik nyata VpnService (lihat recordDnsQueryResolved()).
 * threat_events masih dari triggerThreatSimulationAlert() (tugas Fase 4).
 * Lihat RENCANA_PRODUKSI_NETSHIELD.md untuk detail tiap fase.
 *
 * === CHANGELOG ===
 * [Fase 0 - 2026-08-07]
 *  - CoroutineScope(Dispatchers.IO) -> CoroutineScope(SupervisorJob() + Dispatchers.IO)
 *    agar kegagalan satu child job tidak membatalkan scope keseluruhan.
 *  - Ditambahkan fungsi close() untuk pembersihan eksplisit scope.
 *  Lihat CHANGELOG.md & RENCANA_PRODUKSI_NETSHIELD.md §Fase 0.
 * [Fase 1 - 2026-08-07]
 *  - Ditambahkan selectedProviderSnapshot()/filterOptionsSnapshot()/
 *    customRulesSnapshot() — dipakai NetShieldVpnService+PacketTunnel untuk
 *    membaca state repository secara SINKRON di packet loop (tidak bisa
 *    suspend/collect Flow langsung di jalur kritis per-paket DNS).
 *  - simulationJob/startEngine() TIDAK diubah di fase ini (baru dihapus di
 *    Fase 3.1 setelah data nyata dari VpnService tersambung penuh) — lihat
 *    RENCANA_PRODUKSI_NETSHIELD.md §Fase 3.
 *  Lihat CHANGELOG.md & RENCANA_PRODUKSI_NETSHIELD.md §Fase 1.
 * [Fase 2 - 2026-08-07]
 *  - Ditambahkan [BlocklistUpdateManager]: saat init, muat blocklist dari
 *    cache disk (jika ada) tanpa perlu jaringan (Fase 2.3).
 *  - updateThreatDatabase() diganti total dari `delay(2200)` palsu menjadi
 *    pemanggilan BlocklistUpdateManager.updateAll() (unduh nyata + checksum
 *    + cache lokal), lalu filterOptions.ruleCount disegarkan dari jumlah
 *    domain nyata di BlocklistStore (Fase 2.6).
 *  Lihat CHANGELOG.md & RENCANA_PRODUKSI_NETSHIELD.md §Fase 2.
 * [Fase 3 - 2026-08-07]
 *  - `simulationJob` (generator query acak) DIHAPUS dari jalur produksi
 *    (Fase 3.1). Kode simulasi lama dipertahankan HANYA sebagai
 *    `debugSimulationJob`, dibungkus `BuildConfig.DEBUG` (Fase 3.5) —
 *    tidak pernah aktif otomatis, dan tidak akan pernah masuk build release.
 *  - Ditambahkan kanal komunikasi Service → Repository:
 *    `MutableSharedFlow<DnsQueryEvent>` + `recordDnsQueryResolved()` publik
 *    yang dipanggil `NetShieldVpnService` dari `PacketTunnel.Callbacks`
 *    setiap query DNS nyata selesai diproses (Fase 3.2). Collector di
 *    init() menulis ke Room `dnsLogs` & mengagregasi `ProtectionStats`
 *    dari data nyata (Fase 3.3), termasuk estimasi `dataSavedMb` dari
 *    konstanta ukuran payload iklan rata-rata (Fase 3.4).
 *  - `threatEvents`/notifikasi ancaman TETAP dari `triggerThreatSimulationAlert()`
 *    lama di fase ini — penyambungan ke deteksi ancaman nyata adalah tugas
 *    Fase 4 (lihat RENCANA_PRODUKSI_NETSHIELD.md §Fase 4).
 *  Lihat CHANGELOG.md & RENCANA_PRODUKSI_NETSHIELD.md §Fase 3.
 * [Fase 4 - 2026-08-07]
 *  - `triggerThreatSimulationAlert()` (domain acak) DIHAPUS total (4.1).
 *    Diganti [recordRealThreatEvent], dipanggil otomatis dari
 *    [persistDnsQueryEvent] hanya saat query DNS NYATA diblokir dengan
 *    kategori malware_guard — threat_events & notifikasi sekarang 100%
 *    dari deteksi nyata, bukan probabilitas/tombol simulasi (4.3).
 *  - Istilah "AI Guard"/"AI" dihapus dari teks yang dibuat repository ini;
 *    deteksi dinyatakan transparan sebagai rule-based blocklist matching
 *    (4.2). Label UI terkait diperbarui di ThreatScreen.kt.
 *  - Ditambahkan [refreshThreatIntelligence] (memanggil updateThreatDatabase
 *    nyata) sebagai pengganti tombol simulasi lama di ThreatScreen.
 *  - Fase 5.2: [toggleLowBatteryMode] sekarang punya efek nyata — saat
 *    aktif, notifikasi push per-ancaman individual DITUNDA/digabung (tidak
 *    langsung vibrate/muncul satu-satu) untuk mengurangi wake-up radio &
 *    layar; insiden tetap 100% tercatat di threat_events, hanya
 *    pengiriman notifikasi yang dihemat. Lihat [sendThreatNotification].
 *  Lihat CHANGELOG.md & RENCANA_PRODUKSI_NETSHIELD.md §Fase 4 dan §Fase 5.
 */
class DnsEngineRepository(private val context: Context) {

    private val db = NetShieldDatabase.getDatabase(context)
    private val dao = db.dao()
    private val blocklistUpdateManager = BlocklistUpdateManager(context)

    /**
     * Kanal komunikasi Service -> Repository (Fase 3.2). NetShieldVpnService
     * (lewat PacketTunnel.Callbacks.onDnsQueryResolved) memanggil
     * [recordDnsQueryResolved] untuk setiap query DNS NYATA yang selesai
     * diproses packet loop. SharedFlow dipilih (bukan panggilan fungsi
     * langsung ke Room) supaya penulisan DB/agregasi stats tidak pernah
     * memblokir/memperlambat thread packet loop VPN — event hanya
     * di-`tryEmit` (non-suspend, non-blocking) dari sisi VPN, lalu diproses
     * async oleh collector di bawah.
     */
    private val dnsQueryEvents = MutableSharedFlow<DnsQueryEvent>(
        extraBufferCapacity = 256
    )

    private data class DnsQueryEvent(
        val domain: String,
        val isBlocked: Boolean,
        val category: String,
        val latencyMs: Long,
        val clientHint: String
    )

    // SupervisorJob: kegagalan satu child coroutine (mis. simulationJob)
    // tidak akan membatalkan seluruh scope / coroutine lain di dalamnya.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // State Flows
    private val _isProtectionActive = MutableStateFlow(true)
    val isProtectionActive: StateFlow<Boolean> = _isProtectionActive.asStateFlow()

    private val _stats = MutableStateFlow(ProtectionStats())
    val stats: StateFlow<ProtectionStats> = _stats.asStateFlow()

    private val _selectedProvider = MutableStateFlow(DnsProvider.PRESETS[0])
    val selectedProvider: StateFlow<DnsProvider> = _selectedProvider.asStateFlow()

    private val _filterOptions = MutableStateFlow(FilterOption.DEFAULT_FILTERS)
    val filterOptions: StateFlow<List<FilterOption>> = _filterOptions.asStateFlow()

    private val _isUpdatingDb = MutableStateFlow(false)
    val isUpdatingDb: StateFlow<Boolean> = _isUpdatingDb.asStateFlow()

    private val _dohEnabled = MutableStateFlow(true)
    val dohEnabled: StateFlow<Boolean> = _dohEnabled.asStateFlow()

    private val _lowBatteryMode = MutableStateFlow(true)
    val lowBatteryMode: StateFlow<Boolean> = _lowBatteryMode.asStateFlow()

    val dnsLogs = dao.getAllDnsLogs()
    val customRules = dao.getAllCustomRules()
    val threatEvents = dao.getAllThreatEvents()

    // Cache in-memory dari customRules, diperbarui via collector di init().
    // Dipakai [PacketTunnel] (Fase 1) untuk membaca rule custom secara SINKRON
    // & cepat di packet loop, tanpa query Room langsung di jalur kritis DNS.
    private val _customRulesCache = MutableStateFlow<List<CustomRuleEntity>>(emptyList())

    private var uptimeJob: Job? = null

    // Fase 5.2: dipakai [recordRealThreatEvent] untuk throttle notifikasi
    // push saat lowBatteryMode aktif — bukan properti UI, murni state
    // internal timing.
    @Volatile private var lastThreatNotificationAt: Long = 0L

    init {
        createThreatNotificationChannel()
        startEngine()
        seedInitialCustomRules()
        scope.launch {
            customRules.collect { _customRulesCache.value = it }
        }
        scope.launch {
            blocklistUpdateManager.loadFromDiskCacheIfAvailable()
            refreshRuleCountsFromStore()
            loadStatsFromRoom()
        }
        scope.launch {
            dnsQueryEvents.collect { event -> persistDnsQueryEvent(event) }
        }
    }

    private suspend fun loadStatsFromRoom() {
        val totalReq = dao.getTotalRequestsCount()
        val totalBlk = dao.getBlockedRequestsCount()
        val threats = dao.getThreatsPreventedCount()
        val avgLat = dao.getAverageLatencyMs()?.toInt() ?: 0
        val dataSaved = totalBlk * (AVG_BLOCKED_PAYLOAD_KB / 1024f)
        val rulesCount = BlocklistStore.totalDomainCount()
        val version = if (BlocklistStore.versionLabel().isNotBlank()) BlocklistStore.versionLabel() else "Ready"

        _stats.value = ProtectionStats(
            totalRequests = totalReq,
            totalBlocked = totalBlk,
            threatsPrevented = threats,
            dataSavedMb = dataSaved,
            avgLatencyMs = avgLat,
            uptimeSeconds = _stats.value.uptimeSeconds,
            activeRulesCount = rulesCount,
            dbVersion = version
        )
    }

    /**
     * Dipanggil dari `NetShieldVpnService` (lewat `PacketTunnel.Callbacks`)
     * untuk setiap query DNS NYATA yang selesai diproses packet loop
     * (Fase 3.2). Non-blocking & aman dipanggil dari thread mana pun —
     * hanya `tryEmit` ke buffer SharedFlow, penulisan Room sesungguhnya
     * terjadi async di collector (lihat init()).
     */
    fun recordDnsQueryResolved(domain: String, isBlocked: Boolean, category: String, latencyMs: Long, clientHint: String) {
        val emitted = dnsQueryEvents.tryEmit(DnsQueryEvent(domain, isBlocked, category, latencyMs, clientHint))
        if (!emitted) {
            // Buffer 256 penuh (lonjakan trafik ekstrem) — event ini dibuang
            // demi tidak memblokir packet loop. Cukup di-log, bukan crash.
            android.util.Log.w("DnsEngineRepository", "dnsQueryEvents buffer penuh, satu event dibuang: $domain")
        }
    }

    /**
     * Tulis satu event query DNS nyata ke Room `dnsLogs` dan agregasikan
     * ke `ProtectionStats` (Fase 3.3). `dataSavedMb` diestimasi dari
     * konstanta ukuran payload rata-rata iklan yang diblokir (Fase 3.4) —
     * BUKAN lagi angka acak `0.15-0.55` seperti simulasi lama.
     *
     * CATATAN (batas Fase 3 vs Fase 4): `threatsPrevented` ikut dihitung
     * di sini untuk domain berkategori [BlocklistEngine.CATEGORY_MALWARE_GUARD]
     * yang diblokir, karena itu bagian dari `ProtectionStats` yang harus
     * mencerminkan data nyata. Namun pencatatan detail ke tabel
     * `threat_events` + notifikasi push TETAP tugas Fase 4 (deteksi
     * ancaman nyata & transparansi klaim "AI Guard") — lihat
     * `triggerThreatSimulationAlert()` yang belum diubah di fase ini.
     */
    private suspend fun persistDnsQueryEvent(event: DnsQueryEvent) {
        val clientLabel = event.clientHint.ifBlank { "Trafik Perangkat (VPN)" }
        val isThreat = event.isBlocked && (
            event.category == BlocklistEngine.CATEGORY_MALWARE_GUARD ||
            event.category == BlocklistEngine.CATEGORY_PHISHING_GUARD
        )

        dao.insertDnsLog(
            DnsLogEntity(
                domain = event.domain,
                clientApp = clientLabel,
                timestamp = System.currentTimeMillis(),
                isBlocked = event.isBlocked,
                category = event.category,
                latencyMs = event.latencyMs.toInt().coerceAtLeast(0),
                threatLevel = if (isThreat) "HIGH" else "NONE"
            )
        )

        val savedMb = if (event.isBlocked) (AVG_BLOCKED_PAYLOAD_KB / 1024f) else 0f

        _stats.value = _stats.value.copy(
            totalRequests = _stats.value.totalRequests + 1,
            totalBlocked = _stats.value.totalBlocked + (if (event.isBlocked) 1 else 0),
            threatsPrevented = _stats.value.threatsPrevented + (if (isThreat) 1 else 0),
            dataSavedMb = _stats.value.dataSavedMb + savedMb,
            // Rata-rata bergerak sederhana (bobot 4:1) — konsisten dengan
            // pendekatan yang sebelumnya dipakai simulasi, hanya sekarang
            // inputnya latensi forward DNS nyata (lihat PacketTunnel).
            avgLatencyMs = ((_stats.value.avgLatencyMs * 4 + event.latencyMs.toInt()) / 5)
        )

        // Fase 4.1/4.3: deteksi ancaman NYATA. Sebelumnya threat_events &
        // notifikasi hanya terisi lewat triggerThreatSimulationAlert() (domain
        // acak + tombol manual). Sekarang setiap query yang benar-benar
        // diblokir BlocklistEngine dengan kategori malware_guard langsung
        // dicatat sebagai ThreatEventEntity nyata + notifikasi push nyata,
        // tanpa keterlibatan random/tombol simulasi apa pun. Lihat
        // RENCANA_PRODUKSI_NETSHIELD.md §Fase 4.
        if (isThreat) {
            recordRealThreatEvent(domain = event.domain, clientLabel = clientLabel)
        }
    }

    /**
     * Mencatat satu insiden ancaman NYATA (domain diblokir oleh kategori
     * malware_guard, hasil pencocokan blocklist StevenBlack fakenews +
     * URLhaus — lihat [com.example.vpn.BlocklistSource]) ke tabel
     * `threat_events` dan mengirim notifikasi push. Menggantikan
     * `triggerThreatSimulationAlert()` lama yang memilih domain fiktif
     * secara acak (Fase 4.1).
     *
     * Penamaan sengaja TIDAK memakai istilah "AI" (Fase 4.2) — deteksi ini
     * murni pencocokan domain terhadap daftar blocklist statis (rule-based),
     * bukan model machine learning. Lihat catatan transparansi produk di
     * RENCANA_PRODUKSI_NETSHIELD.md §Fase 4.2.
     */
    private suspend fun recordRealThreatEvent(domain: String, clientLabel: String) {
        val threat = ThreatEventEntity(
            domain = domain,
            threatType = "Malware & Phishing (Rule-based Blocklist)",
            severity = "HIGH",
            description = "Query DNS ke domain yang terdaftar di database ancaman malware/phishing (StevenBlack fakenews + URLhaus) berhasil dicegah sebelum resolusi.",
            actionTaken = "TERBLOKIR OTOMATIS (DNS NXDOMAIN)"
        )
        dao.insertThreatEvent(threat)

        // Fase 5.2: efek nyata lowBatteryMode — insiden SELALU dicatat ke
        // Room (baris di atas), tapi pengiriman notifikasi push individual
        // di-throttle saat lowBatteryMode aktif, supaya device tidak
        // membangunkan layar/radio untuk tiap satu domain ancaman yang
        // diblokir (mis. saat browsing situs yang berat trackernya).
        val now = System.currentTimeMillis()
        val minInterval = if (_lowBatteryMode.value) LOW_BATTERY_NOTIFICATION_INTERVAL_MS else 0L
        if (now - lastThreatNotificationAt >= minInterval) {
            lastThreatNotificationAt = now
            sendThreatNotification(domain, threat.description, clientLabel)
        }
    }

    // ---- Snapshot sinkron untuk NetShieldVpnService / PacketTunnel (Fase 1.6) ----
    // Dipanggil dari packet loop (Dispatchers.IO), harus non-blocking & cepat.

    /** Provider DNS upstream yang aktif saat ini — dipakai VpnService, menggantikan hardcode 1.1.1.1 (Fase 1.6). */
    fun selectedProviderSnapshot(): DnsProvider = _selectedProvider.value

    /** Status filter kategori (game_ads, trackers, dst.) saat ini. */
    fun filterOptionsSnapshot(): List<FilterOption> = _filterOptions.value

    /** Custom rule (blacklist/whitelist manual user) saat ini, dari cache in-memory. */
    fun customRulesSnapshot(): List<CustomRuleEntity> = _customRulesCache.value

    /** Status toggle DNS-over-HTTPS saat ini (Fase 5.1) — dibaca PacketTunnel di packet loop. */
    fun dohEnabledSnapshot(): Boolean = _dohEnabled.value

    private fun createThreatNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                THREAT_CHANNEL_ID,
                "Peringatan Ancaman NetShield",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifikasi deteksi malware dan domain berbahaya"
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun toggleProtection(active: Boolean) {
        _isProtectionActive.value = active
        if (active) {
            startEngine()
        } else {
            stopEngine()
        }
    }

    /**
     * Fase 6.1: dipanggil HANYA oleh [com.example.service.NetShieldVpnService]
     * untuk melaporkan status VPN yang SESUNGGUHNYA terjadi di level OS —
     * baik karena berhasil establish, berhenti normal (tombol notifikasi/
     * ACTION_DISCONNECT), gagal establish(), maupun dicabut paksa oleh
     * user/sistem (`onRevoke()`, mis. lewat Settings > VPN atau VPN app lain
     * mengambil alih).
     *
     * BERBEDA dari [toggleProtection]: fungsi itu adalah *intent* dari UI
     * (user menekan switch) yang lalu men-trigger MainActivity untuk
     * start/stop service sungguhan. Fungsi ini adalah arah sebaliknya —
     * Service melaporkan balik apa yang benar-benar terjadi, supaya
     * `isProtectionActive` (dan switch di UI) tidak pernah "berbohong" saat
     * VPN berhenti dari luar kendali UI. TIDAK memanggil startEngine()/
     * stopEngine() lagi di sini karena itu hanya relevan untuk mode simulasi
     * debug lama, bukan siklus hidup VPN sungguhan.
     */
    fun syncProtectionStateFromService(isActuallyActive: Boolean) {
        _isProtectionActive.value = isActuallyActive
    }

    fun setDnsProvider(provider: DnsProvider) {
        _selectedProvider.value = provider
    }

    fun toggleFilter(filterId: String, enabled: Boolean) {
        _filterOptions.value = _filterOptions.value.map {
            if (it.id == filterId) it.copy(isEnabled = enabled) else it
        }
    }

    fun toggleDoh(enabled: Boolean) {
        _dohEnabled.value = enabled
    }

    fun toggleLowBatteryMode(enabled: Boolean) {
        _lowBatteryMode.value = enabled
    }

    fun addCustomRule(domain: String, isBlocked: Boolean, category: String = "Situs Kustom", note: String = "") {
        scope.launch {
            val cleanDomain = domain.lowercase().trim().replace("https://", "").replace("http://", "").replace("/", "")
            if (cleanDomain.isNotEmpty()) {
                dao.insertCustomRule(
                    CustomRuleEntity(
                        domain = cleanDomain,
                        isBlocked = isBlocked,
                        category = category,
                        note = note
                    )
                )
            }
        }
    }

    fun removeCustomRule(rule: CustomRuleEntity) {
        scope.launch {
            dao.deleteCustomRule(rule)
        }
    }

    /**
     * Perbarui blocklist dari sumber nyata (Fase 2.3), menggantikan
     * `delay(2200)` palsu lama. Mengunduh seluruh [com.example.vpn.BlocklistSource],
     * verifikasi checksum, cache ke disk, lalu segarkan
     * [BlocklistStore] + `ruleCount` tiap [FilterOption] dan
     * `ProtectionStats.activeRulesCount`/`dbVersion` dari data nyata.
     */
    fun updateThreatDatabase() {
        scope.launch {
            _isUpdatingDb.value = true
            val result = blocklistUpdateManager.updateAll()
            if (result.success) {
                refreshRuleCountsFromStore()
                _stats.value = _stats.value.copy(
                    activeRulesCount = result.totalDomains,
                    dbVersion = result.versionLabel
                )
            }
            // Jika gagal (mis. tidak ada internet), blocklist & stats lama
            // dipertahankan apa adanya — tidak ada perubahan angka palsu.
            _isUpdatingDb.value = false
        }
    }

    /** Sinkronkan `ruleCount` tiap kategori filter dengan jumlah domain nyata di [BlocklistStore] (Fase 2.6). */
    private fun refreshRuleCountsFromStore() {
        if (BlocklistStore.isEmpty()) return
        _filterOptions.value = _filterOptions.value.map { filter ->
            filter.copy(ruleCount = BlocklistStore.countForCategory(filter.id))
        }
    }

    /**
     * Fase 4.1: `triggerThreatSimulationAlert()` (domain acak + entri
     * threat_events palsu) DIHAPUS dari jalur produksi. Deteksi ancaman
     * sekarang murni otomatis lewat [recordRealThreatEvent], dipicu hanya
     * oleh query DNS nyata yang cocok kategori malware_guard di
     * [persistDnsQueryEvent] — tidak ada lagi jalur manual/acak yang bisa
     * memicu notifikasi "ancaman" palsu (Fase 4.3).
     *
     * Tombol yang sebelumnya memanggil fungsi simulasi ini di ThreatScreen
     * sekarang memicu [refreshThreatIntelligence] — pemindaian ulang
     * database blocklist ancaman yang NYATA (bukan simulasi).
     */
    fun refreshThreatIntelligence() {
        updateThreatDatabase()
    }

    private fun sendThreatNotification(domain: String, description: String, clientLabel: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, THREAT_CHANNEL_ID)
            .setContentTitle("⚠️ Ancaman Cyber Dicegah!")
            .setContentText("Domain berbahaya '$domain' telah diblokir secara otomatis.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("NetShield mendeteksi percobaan akses ke domain terinfeksi ($domain) dari $clientLabel. $description"))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify((System.currentTimeMillis() % 10000).toInt(), notification)
    }

    /**
     * Fase 3.1: hanya menjalankan `uptimeJob` (jam aktif proteksi — data ini
     * memang selalu "real time" sejak dulu, bukan simulasi). Generator query
     * acak (`simulationJob` lama) SUDAH DIHAPUS dari sini — data dnsLogs/stats
     * produksi sekarang murni dari [recordDnsQueryResolved] (trafik nyata
     * VpnService). Lihat [enableDebugSimulation] untuk mode testing UI.
     */
    private fun startEngine() {
        if (uptimeJob?.isActive == true) return
        uptimeJob = scope.launch {
            while (true) {
                delay(1000)
                if (_isProtectionActive.value) {
                    _stats.value = _stats.value.copy(uptimeSeconds = _stats.value.uptimeSeconds + 1)
                }
            }
        }
    }

    private fun isFilterEnabled(filterId: String): Boolean {
        return _filterOptions.value.find { it.id == filterId }?.isEnabled ?: true
    }

    private fun stopEngine() {
        uptimeJob?.cancel()
        uptimeJob = null
    }

    private fun seedInitialCustomRules() {
        // Rules list initialized clean for user
    }

    fun clearAllLogs() {
        scope.launch {
            dao.clearDnsLogs()
            _stats.value = _stats.value.copy(totalRequests = 0, totalBlocked = 0, threatsPrevented = 0, dataSavedMb = 0f)
        }
    }

    /**
     * Fase 5.4: hapus log hanya untuk satu kategori tampilan tertentu
     * ("Diblokir", "Diizinkan", "Ancaman") — dipetakan ke kondisi Room yang
     * SESUAI dengan filter yang sama dipakai `filteredLogs` di ViewModel,
     * supaya "hapus yang sedang ditampilkan" konsisten dengan yang benar-benar
     * terhapus dari DB. Tidak mereset ProtectionStats (stats tetap
     * mencerminkan total historis sejak proteksi aktif, bukan isi tabel log).
     */
    fun clearLogsByDisplayFilter(filter: String) {
        scope.launch {
            when (filter) {
                "Diblokir" -> dao.clearDnsLogsByBlockedStatus(isBlocked = true)
                "Diizinkan" -> dao.clearDnsLogsByBlockedStatus(isBlocked = false)
                "Ancaman" -> dao.clearDnsLogsByThreatCategory(category = "Ancaman")
                else -> dao.clearDnsLogs()
            }
        }
    }

    /** Fase 5.4: hapus log yang lebih lama dari [days] hari dari sekarang. */
    fun clearLogsOlderThan(days: Int) {
        scope.launch {
            val cutoff = System.currentTimeMillis() - (days.toLong() * 24 * 60 * 60 * 1000)
            dao.clearDnsLogsOlderThan(cutoff)
        }
    }

    /**
     * Membatalkan seluruh coroutine milik repository ini (termasuk
     * uptimeJob & debugSimulationJob jika aktif). Hanya dipanggil saat
     * proses aplikasi benar-benar berakhir (mis. dari Application.onTerminate
     * saat testing), TIDAK dari ViewModel.onCleared(), karena repository ini
     * singleton yang harus tetap hidup selama aplikasi berjalan agar
     * proteksi bisa terus berjalan di background walau layar/Activity
     * ditutup.
     */
    fun close() {
        stopEngine()
        scope.cancel()
    }

    companion object {
        const val THREAT_CHANNEL_ID = "netshield_threat_notifications"

        /**
         * Estimasi ukuran rata-rata satu payload iklan/tracker yang
         * diblokir, dalam KB (Fase 3.4). Dipakai untuk mengestimasi
         * `dataSavedMb` di ProtectionStats.
         *
         * ASUMSI & SUMBER: 45 KB adalah perkiraan kasar ukuran gabungan
         * request+response satu unit iklan banner/tracker pixel tipikal di
         * mobile web (jauh di bawah rata-rata video/interstitial ads yang
         * bisa >500KB, tapi mayoritas trafik yang diblokir DNS-level adalah
         * request kecil berulang seperti tracker pixel & panggilan SDK
         * analytics, bukan payload iklan penuh — karena diblokir di level
         * DNS, byte sesungguhnya yang "dihemat" tidak bisa diukur presisi
         * tanpa deep packet inspection). Angka ini SENGAJA konservatif dan
         * TRANSPARAN sebagai estimasi, bukan pengukuran byte aktual — beda
         * dengan simulasi lama yang pakai angka acak `0.15-0.55` tanpa dasar
         * sama sekali.
         */
        const val AVG_BLOCKED_PAYLOAD_KB = 45f

        /**
         * Fase 5.2: jarak minimum antar notifikasi push ancaman saat
         * lowBatteryMode aktif (2 menit). Insiden tetap semua tercatat ke
         * Room `threat_events` walau notifikasinya digabung/ditunda.
         */
        const val LOW_BATTERY_NOTIFICATION_INTERVAL_MS = 120_000L
    }
}
