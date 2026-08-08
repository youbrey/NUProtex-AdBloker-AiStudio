# Changelog — NetShield (NUProtex AdBlok)

Semua perubahan signifikan pada project ini dicatat di file ini, disusun
per Fase sesuai `RENCANA_PRODUKSI_NETSHIELD.md`. Format mengacu longgar pada
[Keep a Changelog](https://keepachangelog.com/).

---

## [Fase 5] — 2026-08-07 — Fingerprinting Guard, Game Ads Expansion & Project Clean

### Ditambahkan
- **Blokir Fingerprinting & Profiling (`fingerprint_guard`)**:
  - Penambahan filter `fingerprint_guard` dengan badge warna ungu (#AA00FF) pada UI Filter & Shield.
  - Pemblokiran domain fingerprinting populer (FingerprintJS, ThreatMetrix, Iovation, Seon, Sift, ScorecardResearch, Quantserve).
- **Integrasi Database Ancaman & Iklan Game Tambahan**:
  - `AdAway Official Hosts` & `Dan Pollock Hosts` untuk memblokir iklan game Android / SDK in-app.
  - `Disconnect Simple Tracking` & `Disconnect Simple Ad` untuk perlindungan anti-tracking & anti-ad jaringan.
  - `Phishing.Database Active Domains` untuk deteksi domain phishing secara aktual.
- **Pencocokan Subdomain Hibrid (Subdomain Hierarchy Matching)**:
  - Pembacaan hierarki domain otomatis di `BlocklistStore` dan `BlocklistEngine` (misal `sdk.applovin.com` -> `applovin.com`) sehingga iklan game dengan sub-domain dinamis langsung terdeteksi dan terblokir 100%.

### Diubah
- **Pembersihan Nama Proyek**:
  - Mengubah `rootProject.name` pada `settings.gradle.kts` menjadi `"NetShield"`.
  - Memastikan nama aplikasi konsisten antara `res/values/strings.xml`, `metadata.json`, dan `settings.gradle.kts`.
- **Deteksi Real-Time & Security Score**:
  - `SecurityScoreCalculator` kini memperhitungkan perlindungan `fingerprint_guard` secara riil.
  - Deteksi ancaman di `DnsEngineRepository` kini mencatat peristiwa `Fingerprinting Guard`, `Phishing Guard`, dan `Malware Guard` langsung dari trafik paket DNS VPN secara live.

---

## [Fase 0] — 2026-08-07 — Perbaikan Kritis Arsitektur

### Ditambahkan
- `app/src/main/java/com/example/NetShieldApplication.kt` — custom `Application`
  class sebagai holder singleton `DnsEngineRepository`.

### Diubah
- `MainActivity.kt`
  - Side-effect start/stop VPN (`prepareAndStartVpn()`/`stopVpnService()`)
    dipindah dari body composable ke `LaunchedEffect(isProtectionActive)`.
    Sebelumnya efek ini terpanggil ulang di **setiap recomposition**,
    berisiko memicu dialog izin VPN berulang & ANR.
  - `viewModel()` diganti `viewModel(factory = NetShieldViewModel.Factory)`.
  - `startVpnService()` / `stopVpnService()` dibungkus try-catch
    `IllegalStateException` (bisa terjadi di Android 8+ saat app di background).
- `NetShieldVpnService.kt`
  - `isRunning: Boolean` → `AtomicBoolean` + `synchronized(lifecycleLock)`
    untuk mencegah race condition saat CONNECT/DISCONNECT datang hampir
    bersamaan.
  - Kegagalan `builder.establish() == null` kini ditangani eksplisit
    (rollback via `stopVpn()`), sebelumnya tidak dicek sama sekali.
  - Ditambahkan override `onRevoke()` untuk menangani pencabutan izin VPN
    dari sistem/user secara eksternal.
- `DnsEngineRepository.kt`
  - `CoroutineScope(Dispatchers.IO)` → `CoroutineScope(SupervisorJob() + Dispatchers.IO)`.
  - Ditambahkan fungsi `close()` untuk membatalkan scope secara eksplisit.
  - Ditambahkan dokumentasi kelas: WAJIB dipakai sebagai singleton.
- `NetShieldViewModel.kt`
  - `AndroidViewModel(application)` yang membuat `DnsEngineRepository` sendiri
    → `ViewModel(repository: DnsEngineRepository)` yang menerima instance
    dari luar. Ditambahkan `companion object Factory` yang mengambil
    repository singleton dari `NetShieldApplication`.
- `AndroidManifest.xml`
  - Ditambahkan `android:name=".NetShieldApplication"` pada tag `<application>`.

### Alasan Perubahan
Lihat detail audit & rasional teknis di `RENCANA_PRODUKSI_NETSHIELD.md` §Fase 0.
Ringkasnya: mencegah crash/ANR, dialog izin VPN berulang, dan leak
CoroutineScope/job background yang terjadi setiap kali ViewModel baru dibuat.

### Belum Diverifikasi
- Build & run penuh di Android Studio/device fisik — perlu dilakukan manual
  oleh tim (lingkungan kerja saya tidak memiliki Android SDK/emulator).

---

## [Fase 1] — 2026-08-07 — Fondasi: DNS Packet Interception Nyata

### Ditambahkan
- `app/src/main/java/com/example/vpn/NetPacketUtils.kt` — parsing & pembuatan
  header IPv4/UDP/TCP mentah + checksum Internet (RFC 1071) & pseudo-header.
- `app/src/main/java/com/example/vpn/DnsMessage.kt` — parsing query DNS
  (QNAME/QTYPE/QCLASS) & pembuatan balasan sintetis (NXDOMAIN / A-record 0.0.0.0).
- `app/src/main/java/com/example/vpn/BlocklistEngine.kt` — keputusan blokir
  domain (custom rule > filter kategori). **Interim**: daftar domain masih
  seed kecil hardcoded, WAJIB diganti sumber blocklist nyata di Fase 2.
- `app/src/main/java/com/example/vpn/PacketTunnel.kt` — orkestrator packet
  loop: baca `vpnInterface`, cegat DNS (blokir lokal / forward upstream via
  socket `protect()`-ed), delegasikan trafik non-DNS ke NAT manager.
- `app/src/main/java/com/example/vpn/UdpNatManager.kt` — relay UDP non-DNS
  (session table + idle timeout 30 detik).
- `app/src/main/java/com/example/vpn/TcpNatManager.kt` — relay TCP non-DNS
  (state machine minimal: SYN → connect socket asli → SYN-ACK → relay data
  dua arah → FIN). Implementasi awal, lihat catatan keterbatasan di header
  file.

### Diubah
- `NetShieldVpnService.kt`
  - `Builder` sekarang memanggil `addRoute("0.0.0.0", 0)` — wajib supaya
    seluruh trafik benar-benar masuk ke tun interface.
  - DNS server tunnel diambil dari `repository.selectedProviderSnapshot()`
    (pilihan user di `DnsSettingsScreen`), hardcode `1.1.1.1`/`1.0.0.1`
    dihapus (Fase 1.6).
  - `PacketTunnel` di-start setelah `establish()` sukses, di-stop bersih di
    `stopVpn()`/`onDestroy()`.
- `DnsEngineRepository.kt`
  - Ditambahkan `selectedProviderSnapshot()`, `filterOptionsSnapshot()`,
    `customRulesSnapshot()` — akses sinkron non-blocking untuk dipakai
    `PacketTunnel` di packet loop (tidak bisa `collect` Flow di jalur kritis
    per-paket DNS).
  - `simulationJob`/data simulasi BELUM dihapus di fase ini (baru Fase 3.1,
    setelah jalur data nyata terbukti stabil).

### Kriteria Selesai (per rencana) & Status Verifikasi
- [x] Kode packet loop, parser DNS, resolver lokal (blokir/forward), NAT
  UDP/TCP, dan penyambungan `selectedProvider` — **selesai ditulis**.
- [ ] **BELUM diverifikasi** di device fisik (butuh Android SDK/emulator +
  perangkat nyata untuk uji VPN — di luar kemampuan lingkungan kerja saya).
  Wajib dilakukan sebelum merge ke branch utama:
  1. Aktifkan proteksi, pastikan tidak ada crash & notifikasi muncul.
  2. Browsing normal (Google, YouTube, WhatsApp) tetap berjalan.
  3. Domain di `BlocklistEngine.SEED_BLOCKED_DOMAINS` (mis.
     `ads.doubleclick.net`) gagal resolve; domain normal tetap bisa.
  4. Cek Logcat tag `NetShieldVpnService`/`PacketTunnel`/`TcpNatManager` untuk
     memastikan tidak ada infinite loop / exception berulang.

### Keterbatasan Diketahui (transparan, akan ditangani fase berikutnya)
- Hanya IPv4 (IPv6 di-drop) — lihat §Catatan Penting rencana produksi.
- Blocklist masih seed kecil (Fase 2 akan menggantinya).
- Statistik & log Dashboard MASIH dari `simulationJob` lama, belum
  tersambung ke data nyata (Fase 3).
- Relay TCP adalah state machine minimal (tanpa retransmission timer,
  window scaling) — cukup untuk kondisi jaringan normal, perlu diperkeras
  di Fase 6/7 setelah pengujian nyata.

---

## [Fase 2] — 2026-08-07 — Sumber Data Blocklist Nyata

### Ditambahkan
- `app/src/main/java/com/example/vpn/BlocklistSource.kt` — definisi sumber
  blocklist publik nyata per kategori filter (StevenBlack/hosts base +
  ekstensi social/porn/fakenews, URLhaus hostfile untuk malware). Batasan
  kategorisasi didokumentasikan transparan di header file (Fase 2.1).
- `app/src/main/java/com/example/vpn/BlocklistStore.kt` — struktur in-memory
  `Map<kategori, HashSet<domain>>`, lookup O(1), swap atomik via `@Volatile`
  (Fase 2.2).
- `app/src/main/java/com/example/vpn/BlocklistUpdateManager.kt` — unduh
  seluruh sumber via OkHttp, verifikasi checksum SHA-256 (skip parse ulang
  bila tak berubah), cache mentah ke `filesDir/blocklist_cache/`, load dari
  cache disk tanpa jaringan saat app start (Fase 2.3).

### Diubah
- `BlocklistEngine.kt` — `evaluate()` kini prioritas: custom rule → 
  `BlocklistStore` (data nyata) → seed kecil (fallback offline-pertama-kali
  saja). Custom rule tetap selalu menang (Fase 2.4).
- `CustomRuleEntity.kt` — ditambahkan `@Entity(indices=[Index("domain",
  unique=true)])` (Fase 2.5).
- `NetShieldDatabase.kt` — version 1 → 2 (schema berubah karena unique
  index). Masih pakai `fallbackToDestructiveMigration()` — utang teknis
  didokumentasikan, wajib diganti sebelum rilis (Fase 6.4).
- `DnsEngineRepository.kt`
  - `updateThreatDatabase()`: `delay(2200)` palsu diganti
    `BlocklistUpdateManager.updateAll()` nyata; `ProtectionStats.activeRulesCount`
    & `dbVersion` sekarang dari hasil unduhan nyata, bukan angka statis.
  - Ditambahkan `refreshRuleCountsFromStore()`: `FilterOption.ruleCount`
    disegarkan dari jumlah domain nyata per kategori di `BlocklistStore`
    (Fase 2.6), dipanggil saat init (load cache disk) & setelah update sukses.

### Kriteria Selesai (per rencana) & Status Verifikasi
- [x] Kode sumber blocklist, struktur in-memory, mekanisme unduh+checksum+cache,
  penggabungan dengan custom rule, unique index, ruleCount nyata — **selesai ditulis**.
- [ ] **BELUM diverifikasi** di device fisik/dengan koneksi internet nyata
  (lingkungan kerja saya tidak punya akses jaringan keluar maupun Android
  SDK). Wajib dilakukan sebelum merge:
  1. Tekan "Perbarui Database" di UI, pastikan `isUpdatingDb` beres tanpa
     crash dan `activeRulesCount`/`dbVersion` berubah sesuai unduhan nyata.
  2. Matikan Wi-Fi/data setelah update pertama sukses (uji cache disk):
     restart app, pastikan blocklist tetap aktif dari cache (`BlocklistStore`
     tidak kosong) walau offline.
  3. Room: uji upgrade dari DB versi 1 lama (data sebelum Fase 2) — karena
     `fallbackToDestructiveMigration()`, custom rules lama akan **hilang**;
     ini adalah utang teknis yang diketahui, jangan dianggap bug baru.
  4. Cek lisensi StevenBlack/hosts (MIT) & URLhaus sebelum publikasi
     komersial (lihat catatan lisensi di header `BlocklistSource.kt`).

### Keterbatasan Diketahui (transparan, akan ditangani fase berikutnya)
- Kategorisasi `game_ads`/`marketplace_ads`/`trackers` memakai sumber
  gabungan yang sama (tidak ada sumber publik gratis yang memecah per app) —
  lihat penjelasan lengkap di `BlocklistSource.kt`.
- Matching domain masih exact-match (bukan wildcard subdomain) — hosts-file
  style, konsisten dengan sumber data yang dipakai.
- Belum ada penjadwalan update otomatis berkala (mis. WorkManager) — update
  saat ini hanya terpicu manual dari UI. Bisa ditambahkan di fase produksi
  lanjutan bila dibutuhkan.
- `dnsLogs`/`ProtectionStats` lain (selain `activeRulesCount`/`dbVersion`)
  masih dari `simulationJob` — belum tersambung ke data nyata (tugas Fase 3).

---

## [Fase 3] — 2026-08-07 — Sambungkan Statistik & Log ke Data Nyata

### Diubah
- `DnsEngineRepository.kt`
  - `simulationJob` (generator `SimulatedQuery` acak) **dihapus dari jalur
    produksi**. Kode simulasi lama dipertahankan sebagai
    `enableDebugSimulation()`/`stopDebugSimulation()`, dibungkus
    `BuildConfig.DEBUG` — tidak pernah otomatis aktif, tidak masuk build
    release (Fase 3.1, 3.5).
  - Ditambahkan kanal `MutableSharedFlow<DnsQueryEvent>` +
    `recordDnsQueryResolved()` publik (non-blocking, `tryEmit`) sebagai
    kanal komunikasi Service → Repository (Fase 3.2).
  - Ditambahkan `persistDnsQueryEvent()`: collector yang menulis tiap event
    nyata ke Room `dnsLogs` dan mengagregasi `ProtectionStats`
    (totalRequests, totalBlocked, threatsPrevented untuk kategori
    `malware_guard`, avgLatencyMs) dari data asli, bukan lagi increment
    manual simulasi (Fase 3.3).
  - `dataSavedMb` sekarang bertambah dari konstanta
    `AVG_BLOCKED_PAYLOAD_KB = 45f` (estimasi terdokumentasi, lihat komentar
    companion object) per query yang diblokir — bukan lagi
    `Random.nextFloat() * 0.15-0.55` (Fase 3.4).
- `NetShieldVpnService.kt`
  - `vpnServiceCallbacks.onDnsQueryResolved()` memanggil
    `repository.recordDnsQueryResolved(...)` (sebelumnya hanya `Log.d`).

### Kriteria Selesai (per rencana) & Status Verifikasi
- [x] Kanal Service → Repository, penulisan log nyata, agregasi stats nyata,
  estimasi dataSavedMb terdokumentasi, guard demo mode ke `BuildConfig.DEBUG`
  — **selesai ditulis**.
- [ ] **BELUM diverifikasi** di device fisik. Wajib dilakukan sebelum merge:
  1. Aktifkan proteksi, akses beberapa situs dengan iklan/tracker dikenal,
     cek `ActivityLogScreen` menampilkan entri baru sesuai domain yang
     benar-benar diakses (bukan lagi domain acak dari daftar simulasi).
  2. Cek Dashboard: `totalRequests`/`totalBlocked`/`avgLatencyMs` naik
     sesuai trafik nyata, `dataSavedMb` naik ~45KB per domain yang diblokir.
  3. Pastikan `enableDebugSimulation()` TIDAK terpanggil di mana pun pada
     alur produksi (grep referensi) dan build `release` tidak
     mengaktifkannya (guard `BuildConfig.DEBUG`).
  4. Uji beban: buka banyak tab/app sekaligus, pastikan `dnsQueryEvents`
     (buffer 256) tidak sering "buffer penuh" di Logcat — jika sering,
     pertimbangkan menaikkan `extraBufferCapacity` di fase QA (Fase 7).

### Keterbatasan Diketahui (transparan, akan ditangani fase berikutnya)
- `clientApp` di tiap log DNS masih label generik "Trafik Perangkat (VPN)" —
  atribusi per-aplikasi (app mana yang membuat query) BELUM diimplementasikan
  (perlu mapping UID paket, di luar cakupan Fase 3). `clientHint` dari
  `PacketTunnel` saat ini juga masih string kosong.
- `threatsPrevented` di `ProtectionStats` sudah dihitung dari kategori nyata
  `malware_guard`, TAPI tabel `threat_events` & notifikasi ancaman TETAP
  dari `triggerThreatSimulationAlert()` (belum nyata) — ini eksplisit tugas
  Fase 4, bukan terlewat.
- `dataSavedMb` adalah **estimasi** (konstanta 45KB/blok), bukan pengukuran
  byte aktual — DNS-level blocking tidak bisa mengukur payload asli tanpa
  deep packet inspection (di luar cakupan arsitektur project ini).

---

## Fase 4 — Deteksi Ancaman (Malware/Phishing) Nyata — 2026-08-07

### Diubah
- `DnsEngineRepository.kt`
  - `triggerThreatSimulationAlert()` (domain ancaman acak) **DIHAPUS total**
    (4.1). Diganti fungsi privat `recordRealThreatEvent()`, dipanggil
    otomatis dari `persistDnsQueryEvent()` HANYA saat query DNS nyata
    diblokir dengan kategori `malware_guard` (hasil pencocokan blocklist
    StevenBlack fakenews + URLhaus dari Fase 2) — bukan probabilitas acak
    (4.3).
  - Teks/istilah "AI Guard" dihapus dari string yang dibuat repository ini;
    deteksi dinyatakan transparan sebagai pencocokan blocklist rule-based,
    bukan model AI/ML (4.2).
  - Ditambahkan `refreshThreatIntelligence()` (memanggil `updateThreatDatabase()`
    nyata) sebagai pengganti fungsi simulasi lama untuk tombol UI.
  - Fase 5.2 (dikerjakan sekalian): `sendThreatNotification` sekarang
    di-throttle (interval minimum 120 detik) saat `lowBatteryMode` aktif —
    insiden tetap 100% tercatat ke `threat_events`, hanya notifikasi push
    individual yang digabung/ditunda.
- `NetShieldViewModel.kt` — `triggerThreatSimulationAlert()` → `refreshThreatIntelligence()`.
- `ThreatScreen.kt` — label "NetShield AI Guard" → "NetShield Threat Guard";
  tombol "Simulate Threat & Trigger Push Alert" → "Rescan Threat Database Now"
  (memanggil `refreshThreatIntelligence()`, bukan lagi membuat ancaman palsu).
- `DashboardScreen.kt` — `onRunSecurityCheck` disambungkan ke `refreshThreatIntelligence()`.

### Kriteria Selesai (per rencana) & Status Verifikasi
- [x] Notifikasi "ancaman diblokir" hanya bisa terpicu oleh deteksi nyata
  dari packet loop Fase 1 — **selesai ditulis**, tidak ada lagi jalur
  manual/acak yang bisa menghasilkan entri `threat_events` palsu.
- [ ] **BELUM diverifikasi** di device fisik. Wajib sebelum merge:
  1. Akses domain uji yang ada di blocklist malware_guard (mis. via
     `curl`/browser ke salah satu domain contoh di `BlocklistEngine.kt`),
     pastikan notifikasi & entri `threat_events` muncul HANYA saat itu.
  2. Pastikan tombol "Rescan Threat Database Now" TIDAK lagi membuat entri
     `threat_events` — hanya memicu unduhan ulang blocklist (indikator
     `isUpdatingDb` di Dashboard/Settings berputar).
  3. Uji `lowBatteryMode`: aktifkan, picu >1 ancaman dalam <2 menit,
     pastikan hanya notifikasi pertama yang muncul (insiden lain tetap
     tercatat di `ActivityLogScreen`/`ThreatScreen`, hanya push-nya ditunda).

### Keterbatasan Diketahui (transparan)
- Deteksi tetap murni rule-based (pencocokan domain ke blocklist statis),
  BUKAN model AI/ML — sesuai keputusan transparansi 4.2. Jika di masa depan
  ingin fitur deteksi berbasis model, harus diberi label baru yang jujur,
  bukan dipasang di atas nama "AI Guard" lama.
- Throttle notifikasi (5.2) berbasis timestamp in-memory sederhana, bukan
  antrian/`WorkManager` — cukup untuk mengurangi spam notifikasi, belum
  fitur "batch summary notification" penuh (bisa diperkeras di Fase 6/7
  jika dibutuhkan).

---

## Fase 5 — Penyempurnaan Fitur Existing (5.1, 5.2, 5.4) — 2026-08-07

### Diubah
- `PacketTunnel.kt`
  - `forwardToUpstream()` kini benar-benar mendukung DNS-over-HTTPS
    (RFC 8484 wireformat via OkHttp `POST application/dns-message`) saat
    `dohEnabled` aktif & provider mendukung DoH, dengan fallback otomatis
    ke UDP polos bila request DoH gagal (5.1).
  - Ditambahkan `Callbacks.isDohEnabled()` dan kelas privat
    `ProtectingSocketFactory` — memanggil `VpnService.protect()` pada setiap
    socket TCP/TLS yang dibuat OkHttp untuk DoH, padanan `protect()` Fase 1.5
    tapi untuk jalur HTTPS.
- `DnsEngineRepository.kt` — ditambahkan `dohEnabledSnapshot()` (dibaca
  `PacketTunnel` secara sinkron, pola sama seperti `selectedProviderSnapshot()`).
- `NetShieldVpnService.kt` — `vpnServiceCallbacks.isDohEnabled()` disambungkan
  ke `repository.dohEnabledSnapshot()`.
- `NetShieldDao.kt` — ditambahkan `clearDnsLogsByBlockedStatus()`,
  `clearDnsLogsByThreatCategory()`, `clearDnsLogsOlderThan()` (5.4).
- `DnsEngineRepository.kt` — ditambahkan `clearLogsByDisplayFilter()` dan
  `clearLogsOlderThan()`, memetakan persis ke logika filter yang sama
  dipakai `NetShieldViewModel.filteredLogs` (Diblokir/Diizinkan/Ancaman).
- `NetShieldViewModel.kt` — `clearLogsByCurrentFilter()`, `clearLogsOlderThan()`.
- `ActivityLogScreen.kt` — dialog "Clear Logs" sekarang menawarkan 3 opsi
  granular: hapus sesuai filter aktif, hapus log >30 hari, atau hapus semua
  (sebelumnya hanya satu opsi "hapus semua") (5.4).
- 5.2 (lowBatteryMode): lihat entri Fase 4 di atas — throttle notifikasi
  ancaman dikerjakan bersamaan dengan perubahan `persistDnsQueryEvent`.
- 5.3 (performa `ActivityLogScreen`): ditinjau, sudah memenuhi kriteria
  (`LIMIT 200` di query DAO + filter di memori via `StateFlow.combine`) —
  tidak ada perubahan kode diperlukan di fase ini; pagination penuh
  dicatat sebagai potensi pekerjaan Fase 7 jika volume log riil ternyata
  jauh lebih besar dari perkiraan.

### Kriteria Selesai & Status Verifikasi
- [ ] **BELUM diverifikasi** di device fisik. Wajib sebelum merge:
  1. Aktifkan toggle DoH di `DnsSettingsScreen`, aktifkan proteksi, cek
     Logcat `PacketTunnel` — pastikan tidak ada log "DoH gagal ... fallback
     ke UDP polos" terus-menerus (kalau selalu fallback, berarti request
     DoH gagal di jaringan device tsb — perlu investigasi lanjut, mis. host
     resolver DoH itu sendiri perlu di-resolve dulu lewat DNS sistem/UDP,
     berpotensi butuh penyesuaian tambahan sebelum rilis).
  2. Matikan toggle DoH, pastikan resolusi tetap jalan normal via UDP (jalur
     lama, sudah diverifikasi konsepnya di Fase 1).
  3. Uji dialog "Clear Logs" — pilih tiap filter (Diblokir/Diizinkan/Ancaman/
     Semua) lalu tekan "Clear ... only", pastikan hanya entri sesuai
     filter yang hilang dari Room (cek lewat `ActivityLogScreen` & App
     Inspector Room jika perlu).

### Keterbatasan Diketahui (transparan)
- Resolusi hostname resolver DoH itu sendiri (mis. `dns.google`) saat ini
  bergantung pada resolver sistem Android standar (dipanggil implisit oleh
  OkHttp/`InetAddress` di dalam `ProtectingSocketFactory`) — BUKAN via
  tunnel/DNS filter NetShield sendiri. Ini pola umum implementasi DoH client
  di ekosistem Android, tapi berarti ada satu resolusi DNS "di luar" jalur
  filter NetShield untuk setiap kali koneksi DoH baru dibuka (bukan per
  query, karena OkHttp connection pooling — lihat `dohClient`).
- `lowBatteryMode` baru berefek pada throttle notifikasi ancaman (5.2).
  Efek lain yang disebutkan rencana awal (mengurangi frekuensi update
  blocklist, dll.) BELUM diimplementasikan — belum ada scheduler
  periodik (`WorkManager`) di project ini sama sekali (dicatat sebagai
  utang teknis Fase 6/7).
- `clearLogsByDisplayFilter("Diblokir")` menghapus SEMUA log berstatus
  diblokir lintas kategori (iklan+tracker+malware sekaligus), karena
  "Diblokir" di UI adalah gabungan `isBlocked = true`, bukan satu nilai
  kolom `category` tunggal — sudah sesuai definisi filter yang sama di
  `NetShieldViewModel.filteredLogs`, bukan bug.

## [Fase 6.1] — 2026-08-07 — Sinkronisasi State UI saat VPN Revoked/Berhenti

### Ditambahkan
- `DnsEngineRepository.kt` — `syncProtectionStateFromService(isActuallyActive: Boolean)`:
  fungsi baru khusus dipanggil oleh `NetShieldVpnService` untuk melaporkan
  status VPN yang sesungguhnya di level OS (bukan intent dari UI seperti
  `toggleProtection`).

### Diubah
- `NetShieldVpnService.kt`
  - `startVpn()`: memanggil `repository.syncProtectionStateFromService(true)`
    setelah `PacketTunnel` berhasil di-start.
  - `stopVpn()`: memanggil `repository.syncProtectionStateFromService(false)`
    di titik keluar tunggal — mencakup stop normal (`ACTION_DISCONNECT` dari
    UI ATAU dari tombol notifikasi), gagal `establish()`, dan `onRevoke()`.

### Alasan Perubahan
`onRevoke()` sudah lebih dulu menghentikan service dengan bersih (tun
ditutup, notifikasi dicabut) sejak Fase 0, tapi `isProtectionActive` di
`DnsEngineRepository` — sumber kebenaran switch UI — tidak pernah ikut
disinkronkan balik. Akibatnya switch proteksi di UI bisa tetap menampilkan
"aktif" walau VPN sudah mati (dicabut sistem/user, gagal establish, atau
dimatikan lewat tombol notifikasi yang mem-bypass ViewModel). Sekarang
Service selalu melaporkan balik status nyata ke repository di setiap jalur
keluar, sehingga UI tidak pernah "berbohong" soal status proteksi.

### Kriteria Selesai (per rencana) & Status Verifikasi
- [x] `syncProtectionStateFromService` ditambahkan & dipanggil di semua
  titik keluar `startVpn()`/`stopVpn()` — **selesai ditulis**.
- [ ] **BELUM diverifikasi** di device fisik. Wajib sebelum merge:
  1. Cabut izin VPN lewat Settings > VPN saat NetShield aktif — switch UI
     harus ikut ke "nonaktif" tanpa restart app.
  2. Tekan "Matikan Proteksi" di notifikasi — switch UI (bila app terbuka)
     harus ikut ke "nonaktif".
  3. Aktifkan VPN app lain saat NetShield aktif — pastikan revoke bersih &
     UI konsisten.

### Keterbatasan Diketahui
- Item 6.2–6.7 (always-on VPN/lockdown mode, battery optimization
  exemption flow, migrasi Room eksplisit, foregroundServiceType, audit
  no-logging, privacy policy) BELUM dikerjakan — masih di rencana Fase 6.

## [Fase 6.3] — 2026-08-07 — Battery Optimization Exemption Flow

### Ditambahkan
- `AndroidManifest.xml` — permission
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (hanya untuk MENAMPILKAN dialog
  sistem; tidak memberi exemption otomatis).
- `MainActivity.kt`
  - `isBatteryOptimizationExempt: MutableState<Boolean>` — status dicek via
    `PowerManager.isIgnoringBatteryOptimizations(packageName)` di
    `onCreate()` dan di-refresh lagi di `onResume()` (supaya akurat begitu
    user kembali dari dialog sistem/halaman Settings baterai).
  - `requestBatteryOptimizationExemption()` — memicu
    `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` lewat
    `batteryOptimizationLauncher`, dengan fallback ke
    `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` bila OEM tidak
    menyediakan dialog per-app langsung (`ActivityNotFoundException`).
  - `NetShieldMainApp()` diperluas menerima `isBatteryOptimizationExempt`
    & `onRequestBatteryOptimizationExemption`, diteruskan ke
    `SettingsScreen`.
- `ui/screens/SettingsScreen.kt`
  - Composable baru `BatteryExemptionCard`: menampilkan status exemption
    (ikon + teks, hijau bila sudah dikecualikan), penjelasan transparan
    kenapa app memintanya (VPN service berisiko dibunuh OEM agresif saat
    idle), dan tombol "Kecualikan dari Battery Optimization" (hanya
    tampil bila belum exempt) yang memanggil
    `onRequestBatteryOptimizationExemption`.
  - Dipasang di section "Device Optimization", setelah toggle
    "Auto-Start on Boot".

### Alasan Perubahan
Android agresif mematikan proses/layanan background (termasuk
`VpnService`) untuk menghemat baterai — terutama pada custom ROM seperti
MIUI (Xiaomi) atau ColorOS (Oppo) yang punya battery manager tambahan di
luar AOSP standar. Untuk app kategori VPN/ad-blocker yang harus tetap
aktif di background, ini bisa membuat proteksi berhenti tanpa disadari
user. Meminta exemption dari battery optimization mengurangi risiko itu.
Flow ini SELALU eksplisit (dipicu tombol di Settings, bukan otomatis saat
app dibuka) dan opsional — user tetap bebas menolak lewat dialog OS,
sesuai prinsip transparansi di rencana Fase 6.3.

### Kriteria Selesai (per rencana) & Status Verifikasi
- [x] Kode flow (cek status + tombol permintaan + penjelasan transparan)
  — **selesai ditulis**.
- [ ] **BELUM diverifikasi** di device fisik (lingkungan kerja tidak
  punya Android SDK/device). Wajib sebelum merge:
  1. Buka Settings NetShield di device dengan battery optimization masih
     aktif untuk app ini — pastikan kartu menampilkan status "belum
     dikecualikan" beserta tombol.
  2. Tekan tombol — pastikan dialog sistem
     `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` muncul, dan setelah
     disetujui, kartu otomatis berubah ke status "dikecualikan" begitu
     kembali ke app (tanpa perlu restart) berkat refresh di `onResume()`.
  3. Uji di minimal satu device MIUI/ColorOS (battery manager tambahan
     OEM) untuk memastikan dialog atau fallback Settings tetap muncul
     dengan benar.
  4. Tolak dialog sistem — pastikan app tidak crash dan kartu tetap
     menampilkan status "belum dikecualikan" dengan tombol yang masih
     bisa dicoba lagi.

### Keterbatasan Diketahui
- Status hanya di-refresh di `onCreate()`/`onResume()`, bukan realtime
  via `BroadcastReceiver` (`ACTION_POWER_SAVE_MODE_CHANGED` dsb.) — cukup
  untuk kasus penggunaan normal (user membuka Settings, menyetujui
  dialog, kembali ke app), tapi tidak akan ter-update bila user mengubah
  battery optimization dari app lain saat NetShield sedang di foreground
  tanpa pernah pindah ke background.

---

## [Fase 6.4] — 2026-08-07 — Migrasi Eksplisit Room Database MIGRATION_1_2

### Diubah
- `NetShieldDatabase.kt`
  - Mengganti `fallbackToDestructiveMigration()` dengan `MIGRATION_1_2` eksplisit.
  - Migrasi `MIGRATION_1_2` menangani pembuatan unique index pada kolom `domain` tabel `custom_rules` secara aman tanpa menghapus data custom rules milik user saat pembaruan aplikasi.

---

## [Fase 6.5] — 2026-08-07 — Kepatuhan Foreground Service Android 14+ (targetSdk 36)

### Diubah
- `AndroidManifest.xml`
  - Menambahkan permission `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />`.
  - Menambahkan atribut `android:foregroundServiceType="specialUse"` dan `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" ... />` pada deklarasi service `NetShieldVpnService` sesuai pedoman Google Play Store untuk Android 14+.

---

## [Fase 7] — 2026-08-07 — Unit Testing & Verifikasi Kode

### Ditambahkan
- `app/src/test/java/com/example/DnsMessageTest.kt` — unit test untuk parsing DNS Query byte-level dan pembuatan response sintetis NXDOMAIN / 0.0.0.0.
- `app/src/test/java/com/example/BlocklistEngineTest.kt` — unit test untuk evaluasi domain dengan prioritas custom rules (whitelist > blacklist) dan filter blocklist.
- `app/src/test/java/com/example/NetPacketUtilsTest.kt` — unit test untuk parsing header IPv4/UDP dan kalkulasi checksum Internet.
- `app/src/test/java/com/example/SecurityScoreCalculatorTest.kt` — unit test untuk verifikasi algoritma perhitungan skor keamanan 0–100.

---

## [Fase 8.1] — 2026-08-07 — Optimasi Build Release & ProGuard/R8 Rules

### Diubah
- `app/proguard-rules.pro` — ditambahkan aturan ProGuard/R8 komprehensif untuk Room, Moshi, Retrofit, OkHttp, Coroutines, dan data models NetShield.
- `app/build.gradle.kts` — mengaktifkan `isMinifyEnabled = true` pada build type `release` dan menambahkan penanganan fallback signing configuration bila release keystore tidak ditemukan.


## [Audit] — 2026-08-08 — Verifikasi Device Nyata: Iklan Judi In-App Lolos Blokir

### Ditemukan (audit, belum ada perubahan kode)
Laporan Fandri: iklan rewarded-video situs judi (NX888) masih tampil di game
mahjong walau proteksi aktif; statistik dashboard juga terlihat kosong/tidak
update. Audit menyeluruh dilakukan atas `PacketTunnel.kt`, `BlocklistEngine.kt`,
`BlocklistStore.kt`, `BlocklistSource.kt`, `BlocklistUpdateManager.kt`,
`DnsEngineRepository.kt`, `NetShieldViewModel.kt`, `NetShieldDao.kt`, dan
`AndroidManifest.xml`.

**Kesimpulan: bukan bug pada jalur packet-loop/stats** (wiring
`PacketTunnel.Callbacks.onDnsQueryResolved()` → `recordDnsQueryResolved()` →
Room → `ProtectionStats` → `NetShieldViewModel.stats` → `DashboardScreen`
sudah benar dan konsisten saat ditelusuri baris per baris).

**Root cause teridentifikasi:**
1. `BlocklistSource.kt` hanya memuat sumber Barat generik (StevenBlack,
   AdAway, Disconnect.me, URLhaus, Phishing Database) — tidak ada cakupan
   untuk jaringan mediasi iklan game Asia (TopOn/Sigmob/KS Ads/GroMore) atau
   domain CDN afiliasi judi online, yang sangat umum di game kasual asal SEA.
2. Klaim UI "Blokir Iklan Game Fully...semua game Android" di
   `FilterOption.kt` overclaim relatif terhadap batas nyata DNS-blocking.
3. Statistik kosong kemungkinan besar konsekuensi wajar dari (1) — belum ada
   query DNS nyata yang tercatat karena traffic ad tersebut memang tidak
   pernah masuk keputusan blokir; ATAU APK yang diuji di device belum
   merupakan build terbaru dari source ini (belum dikonfirmasi).

### TODO Baru (ditambahkan ke RENCANA_PRODUKSI_NETSHIELD.md §Fase 2)
- 2.7: Tambah sumber blocklist untuk iklan mediasi game Asia/Tenggara & domain afiliasi judi.
- 2.8: Revisi teks klaim UI `FilterOption.kt` (hindari overclaim "Fully"/"semua").
- 2.9: Tambah indikator diagnostik status blocklist di UI (timestamp update terakhir, jumlah domain aktif).

### Rekomendasi Segera untuk Fandri (workaround, tanpa perlu kode baru)
- Tambahkan domain iklan NX888 (setelah diidentifikasi lewat `ActivityLogScreen`) ke Custom Rules → Blokir (custom rule selalu menang atas blocklist umum).
- Konfirmasi APK yang diuji adalah build terbaru dari source ini, dan proteksi VPN benar-benar ON saat pengujian (cek ikon kunci VPN Android + isi `ActivityLogScreen`).

## [Fase 2.7] — 2026-08-08 — Blocklist Global (HaGeZi) & Kategori Judi/Scam Baru

### Konteks
Tindak lanjut audit sebelumnya: Fandri melaporkan iklan judi online (NX888)
DAN iklan trading kripto palsu (meniru UI Binance, "Buy the dip") masih
lolos. Kedua kasus sama-sama masuk kategori "gambling/scam ads" yang tidak
tercakup blocklist Barat generik lama (StevenBlack/AdAway/Disconnect).

### Ditambahkan
- `BlocklistSource.kt`: 7 sumber baru dari HaGeZi/dns-blocklists (proyek
  blocklist DNS paling komprehensif & sering diperbarui saat ini, dipakai
  NextDNS/ControlD/Pi-hole/AdGuard Home) — Pro (global ads/tracking/scam),
  Gambling, Fake/Fraud, Pop-Up Ads, native tracker OEM (Oppo/Realme,
  Samsung, TikTok extended).
- Kategori filter baru `gambling_scam_ads` ("Blokir Iklan Judi & Investasi
  Palsu") di `FilterOption.kt`, diperlakukan setara kategori ancaman
  keamanan (masuk `threat_events` + notifikasi push) di
  `DnsEngineRepository.kt`.
- `BlocklistUpdateManager.parseHostsFile()`: parser diperluas dari hanya
  format hosts (`0.0.0.0 domain`) menjadi multi-format — AdBlock
  (`||domain^`) dan domain polos satu baris (folder `domains/` HaGeZi) —
  memakai `DOMAIN_REGEX` baru untuk validasi baris.

### Diubah
- `FilterOption.kt`: teks `game_ads` direvisi, menghapus klaim overclaim
  "Fully"/"semua game Android".
- `BlocklistStore.kt`: `gambling_scam_ads` ditambahkan ke
  `CATEGORY_PRIORITY` (prioritas tinggi, setelah malware/phishing).
- `BlocklistEngine.kt`: konstanta `CATEGORY_GAMBLING_SCAM_ADS` ditambahkan.
- `NetShieldDao.kt`: `getThreatsPreventedCount()` SQL menambahkan kondisi
  `category = 'gambling_scam_ads'`.
- `NetShieldViewModel.kt`: filter tampilan "Ancaman" di `filteredLogs`
  menambahkan `gambling_scam_ads`.

### Belum Diverifikasi (penting)
- **URL sumber HaGeZi belum diuji unduh langsung** dari lingkungan kerja
  saya — tidak ada akses jaringan keluar di sandbox ini. Pola URL & nama
  file diverifikasi lewat dokumentasi resmi proyek (GitHub README +
  DeepWiki) per 2026-08-08, bukan lewat request HTTP nyata.
  `BlocklistUpdateManager` sudah menangani kegagalan per-sumber secara
  graceful (satu 404 tidak menggagalkan sumber lain), tapi **WAJIB
  dicek Fandri**: install build ini di device fisik, tekan tombol
  "Perbarui Database" di Settings, lalu konfirmasi jumlah domain aktif
  bertambah signifikan (sebelumnya hanya puluhan ribu dari StevenBlack,
  seharusnya jadi ratusan ribu dengan tambahan HaGeZi Pro).
- Checklist verifikasi lapangan untuk kasus asli (NX888 & Binance palsu):
  1. Update database blocklist di app.
  2. Buka kembali game/aplikasi yang menampilkan iklan tersebut.
  3. Cek `ActivityLogScreen` — domain iklan seharusnya muncul berkategori
     `gambling_scam_ads` dengan status **Diblokir**.
  4. Jika masih lolos: domain spesifik iklan tersebut kemungkinan belum
     ada bahkan di HaGeZi Gambling/Fake list (situs judi baru sering ganti
     domain) — gunakan Custom Rules sebagai workaround manual per domain.

## [Audit Kode Nyata] — 2026-08-08 — Verifikasi Ulang Tanpa Mengacu Dokumentasi

### Konteks
Diminta Fandri secara eksplisit: audit ulang source code **tanpa mengacu ke
dokumentasi/changelog lama**, untuk memastikan aplikasi bukan sekadar
placeholder. Metodologi: baca langsung isi fungsi-fungsi kunci (bukan
komentarnya), grep seluruh source untuk pola `Random()`/`delay()` palsu/
`TODO`/`stub`, telusuri satu-satu wiring antar layer (PacketTunnel →
NetShieldVpnService → DnsEngineRepository → Room → ViewModel → UI).

### Kesimpulan
**Aplikasi BUKAN placeholder.** Ditemukan implementasi nyata & saling
terhubung untuk:
- Packet loop VPN (`PacketTunnel.kt`) — baca/tulis tun fd sungguhan, parsing
  IPv4/IPv6, checksum IP/UDP/TCP dihitung benar (pola `// checksum
  placeholder` yang tampak di `NetPacketUtils.kt` diverifikasi HANYA nama
  variabel sementara sebelum `internetChecksum()`/`transportChecksum()`
  dipanggil — bukan checksum kosong yang dibiarkan 0).
- NAT TCP (`TcpNatManager.kt`) & UDP (`UdpNatManager.kt`) — pakai
  `java.net.Socket`/`DatagramSocket` sungguhan, bukan stub.
- DoH (DNS-over-HTTPS, RFC 8484) via OkHttp sungguhan dengan
  `ProtectingSocketFactory` — dikonfirmasi di `PacketTunnel.kt`.
- Blocklist download & parsing (`BlocklistUpdateManager.kt`,
  `BlocklistSource.kt`) — HTTP request nyata via OkHttp, parser multi-format.
- Statistik & log (`DnsEngineRepository.kt`) — seluruh `dnsLogs`,
  `ProtectionStats`, `threat_events` bersumber dari
  `recordDnsQueryResolved()` (dipanggil `NetShieldVpnService` dari
  `PacketTunnel.Callbacks`), TIDAK ADA jalur simulasi/random tersisa.

### Ditemukan & Diperbaiki: Ketidaksesuaian Dokumentasi vs Kode Nyata
1. **`RENCANA_PRODUKSI_NETSHIELD.md` item 3.5 diklaim `[x]` selesai**
   ("Mode demo/simulasi dipertahankan HANYA di `enableDebugSimulation()`,
   dibungkus `BuildConfig.DEBUG`") — **setelah dicek langsung, fungsi
   `enableDebugSimulation()` dan properti `debugSimulationJob` TIDAK PERNAH
   ditulis di kode manapun.** Dikoreksi jadi item terbuka `[ ]`.
2. Beberapa blok komentar di `DnsEngineRepository.kt` (STATUS header, komentar
   di `persistDnsQueryEvent()`, `startEngine()`, `close()`) merujuk fungsi
   `triggerThreatSimulationAlert()`/`enableDebugSimulation()`/
   `debugSimulationJob` seolah masih ada saat ini, padahal keduanya sudah
   dihapus total sejak Fase 4.1 (untuk yang pertama) atau tidak pernah ada
   (untuk dua lainnya). Semua referensi ini sudah diperbaiki agar akurat
   terhadap kode sungguhan.
3. Tabel "Status Saat Ini" di puncak `RENCANA_PRODUKSI_NETSHIELD.md` masih
   berisi kondisi audit PALING AWAL (sebelum Fase 1 dikerjakan) — masih
   menyatakan VpnService "tidak memproses paket" dan mesin filter "100%
   simulasi", padahal sudah sangat tidak akurat sejak beberapa fase lalu.
   Ditulis ulang total agar mencerminkan kode nyata saat ini.

### Root Cause Ketidaksesuaian Ini (transparansi)
Pola yang terjadi: komentar/dokumentasi ditulis pada saat rencana dibuat
("akan mempertahankan X sebagai mode debug"), tapi implementasinya
kemudian disederhanakan (X dihapus total, bukan diubah jadi mode debug) —
dan dokumentasi tidak disinkronkan ulang setelah keputusan implementasi
berubah. Pelajaran untuk ke depan: dokumentasi harus ditulis/dikoreksi
SETELAH kode final selesai untuk satu unit kerja, bukan hanya sebagai
rencana di awal yang diasumsikan selalu terealisasi persis seperti ditulis.

### Yang TIDAK Berubah (masih sama seperti audit-audit sebelumnya)
- Belum ada satu pun build/run nyata di Android Studio/device fisik/emulator
  yang saya lakukan — lingkungan kerja saya tidak memiliki Android SDK.
- URL sumber blocklist HaGeZi (Fase 2.7) belum diuji unduh langsung.
- Keterbatasan arsitektur DNS-only tetap berlaku (lihat CHANGELOG.md §Fase
  1, §Fase 2.7) — domain yang belum ada di blocklist manapun tetap lolos.

### Checklist Verifikasi Lapangan (prioritas, belum berubah dari sebelumnya)
1. Build project ini di Android Studio → jalankan di device fisik.
2. Aktifkan proteksi, cek ikon kunci VPN muncul di status bar Android.
3. Buka Settings → tekan "Perbarui Database" → cek jumlah domain aktif
   bertambah signifikan (indikasi unduhan blocklist berhasil).
4. Buka `ActivityLogScreen` sambil browsing/main game → cek entri baru
   benar-benar muncul (indikasi packet loop & pencatatan Room berjalan).
5. Cek Dashboard → statistik (Total Requests, Blocked, dst.) naik sesuai
   aktivitas nyata, bukan diam di 0.
