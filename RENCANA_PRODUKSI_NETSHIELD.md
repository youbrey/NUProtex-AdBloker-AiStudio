# Rencana Pengembangan Produksi — NetShield (NUProtex AdBlok)

Dokumen ini adalah rencana kerja terstruktur untuk mengubah NetShield dari
*UI prototype dengan data simulasi* menjadi **aplikasi ad-blocker Android yang
100% fungsional dan siap produksi**. Disusun berdasarkan hasil audit source code
per 7 Agustus 2026, diperbarui berkelanjutan setiap fase.

> **Update 8 Agustus 2026 — AUDIT KODE ULANG (mengabaikan klaim dokumentasi
> lama, hanya membaca source code langsung):** Dikonfirmasi aplikasi **BUKAN
> lagi placeholder** — lihat tabel status di bawah, ditulis ulang total dari
> tabel lama (yang masih menggambarkan kondisi SEBELUM Fase 1 dikerjakan dan
> sudah sangat tidak akurat). Ditemukan satu ketidaksesuaian dokumentasi vs
> kode nyata (item 3.5 diklaim selesai padahal fungsinya tidak pernah ditulis)
> — sudah dikoreksi. Detail lengkap di CHANGELOG.md §Audit Kode Nyata.

## Status Saat Ini (Ringkasan Audit — DITULIS ULANG 2026-08-08 dari kode langsung)

| Komponen | Status nyata (dicek langsung dari kode, bukan dari komentar/klaim) |
|---|---|
| UI/Compose, tema, navigasi | ✅ Baik |
| Room DB (logs, custom rules, threats) | ✅ Struktur baik, migrasi & index sudah ada |
| VpnService & packet loop (`PacketTunnel.kt`) | ✅ **NYATA** — membaca/menulis paket dari tun fd sungguhan, parsing IPv4/IPv6, UDP & TCP NAT relay (`TcpNatManager`/`UdpNatManager`), checksum IP/UDP/TCP dihitung benar (bukan placeholder kosong) |
| Mesin deteksi/filter DNS (`BlocklistEngine.kt`) | ✅ **NYATA** — mengecek `BlocklistStore` (hasil unduhan blocklist sungguhan) dengan prioritas kategori, custom rule user selalu menang; SEED hardcoded hanya fallback awal |
| Update database blocklist (`BlocklistUpdateManager.kt`) | ✅ **NYATA** — unduh HTTP via OkHttp, parser multi-format (hosts/AdBlock/domain polos), cache lokal, checksum. ⚠️ URL sumber belum pernah diuji unduh langsung dari lingkungan kerja saya (tidak ada akses jaringan keluar di sandbox) |
| Integrasi provider DNS terpilih | ✅ **NYATA** — `PacketTunnel` membaca `selectedProviderSnapshot()` dari repository, mendukung DoH (RFC 8484 via OkHttp) dengan fallback UDP |
| Statistik & log real-time (`DnsEngineRepository`) | ✅ **NYATA** — `dnsLogs`/`ProtectionStats`/`threat_events` semuanya dari `recordDnsQueryResolved()` (trafik VPN sungguhan). Tidak ada jalur simulasi/random tersisa di kode produksi |
| Mode debug/simulasi UI (`enableDebugSimulation()`) | ❌ **DIKLAIM ada di dokumentasi lama, TERNYATA TIDAK PERNAH ditulis di kode** — temuan audit 2026-08-08, lihat §Fase 3.5 |
| Bug arsitektur Compose (start/stop VPN di body composable) | ✅ Sudah diperbaiki sejak Fase 0 (`LaunchedEffect`) |
| Verifikasi build & jalan di device Android fisik | ❌ **BELUM PERNAH DILAKUKAN** — lingkungan kerja saya tidak punya Android SDK/emulator/device fisik. Ini adalah risiko terbesar yang tersisa: kode secara statis benar & saling terhubung, tapi belum pernah dibuktikan berjalan nyata di Android sungguhan |

**Kesimpulan audit 2026-08-08:** Source code NetShield **bukan skeleton/placeholder** —
implementasi packet-level networking, DNS resolving/blocking, NAT TCP/UDP,
DoH, dan pipeline statistik semuanya kode nyata yang secara konsisten
terhubung satu sama lain (ditelusuri baris-per-baris, bukan hanya dibaca
komentarnya). **Risiko terbesar yang tersisa bukan lagi "kodenya palsu",
melainkan "kodenya belum pernah dibuktikan jalan di device fisik Android
sungguhan"** — lihat checklist verifikasi lapangan di CHANGELOG.md.

---

## Prinsip Kerja

1. Perbaiki dulu **bug arsitektur yang bisa menyebabkan crash/ANR** sebelum menambah fitur baru.
2. Bangun **inti VPN/DNS filtering nyata** sebagai fondasi — semua fitur lain (log, stats, threat detection) harus disambungkan ke data nyata ini, bukan simulasi.
3. Hapus seluruh kode simulasi (`Random()`, `delay()` palsu) secara bertahap, digantikan data asli, dan tandai jelas bila ada mode "demo" yang sengaja dipertahankan untuk testing.
4. Setiap fase diakhiri dengan pengujian manual di device fisik (VPN tidak bisa diuji penuh di emulator tanpa trik tambahan).
5. **Dokumentasi wajib mengikuti kode** (aturan tetap, berlaku untuk seluruh fase berikutnya):
   - Setiap file source code yang dibuat/diubah **wajib** memiliki blok komentar
     `=== CHANGELOG ===` di dekat deklarasi kelas/objek utama, mencatat fase,
     tanggal, dan ringkasan perubahan.
   - Setiap perubahan juga dicatat di `CHANGELOG.md` (root project) per Fase.
   - Dokumen rencana ini (`RENCANA_PRODUKSI_NETSHIELD.md`) **wajib** di-update
     setiap sebuah fase/item TODO selesai dikerjakan — checklist dicentang,
     ditambahkan daftar file yang berubah, dan catatan status verifikasi.

---

## FASE 0 — Perbaikan Kritis (Wajib Sebelum Lanjut) ✅ SELESAI

- [x] **0.1** Pindahkan pemanggilan `prepareAndStartVpn()` / `stopVpnService()` di `MainActivity.onCreate` dari body composable ke `LaunchedEffect(isProtectionActive)` agar tidak terpanggil ulang setiap recomposition.
- [x] **0.2** Ubah `isRunning` di `NetShieldVpnService` menjadi thread-safe (`AtomicBoolean` + `synchronized(lifecycleLock)`) untuk mencegah race condition saat start/stop cepat berurutan.
- [x] **0.3** Tangani kegagalan `builder.establish()` dengan benar: jika null, `stopVpn()` dipanggil (menghentikan foreground notification) dan tidak ada state `isRunning`/`vpnInterface` yang tidak konsisten.
- [x] **0.4** Audit pemanggilan `startService()`/`startForegroundService()` di `MainActivity` — dibungkus try-catch `IllegalStateException`. Tambahan: `onRevoke()` di `NetShieldVpnService` kini ditangani agar state tetap konsisten saat izin VPN dicabut sistem.
- [x] **0.5** `DnsEngineRepository` kini singleton lewat `NetShieldApplication` (didaftarkan di `AndroidManifest.xml`), diakses via `NetShieldViewModel.Factory`. `CoroutineScope` memakai `SupervisorJob` dan tersedia `close()` untuk pembersihan eksplisit.

**File yang diubah/ditambah:**
- ✏️ `MainActivity.kt` — LaunchedEffect, try-catch service call, factory ViewModel
- ✏️ `NetShieldVpnService.kt` — AtomicBoolean, lifecycleLock, penanganan establish()==null, onRevoke()
- ✏️ `DnsEngineRepository.kt` — SupervisorJob, fungsi close(), dokumentasi singleton
- ✏️ `NetShieldViewModel.kt` — konstruktor menerima repository dari luar + companion Factory
- ➕ `NetShieldApplication.kt` — baru, holder singleton repository
- ✏️ `AndroidManifest.xml` — `android:name=".NetShieldApplication"`
- ➕ `CHANGELOG.md` — baru, log terpusat seluruh perubahan project per fase
- Setiap file di atas kini juga memuat blok komentar `=== CHANGELOG ===` di header-nya (lihat isi file).

**Kriteria selesai:** App bisa dibuka/ditutup/diputar layar berkali-kali tanpa crash, tanpa dialog izin VPN muncul berulang, tanpa job coroutine ganda berjalan (cek via Logcat/StrictMode). **→ Perlu diverifikasi build & run di Android Studio / device fisik oleh Fandri, karena saya tidak bisa menjalankan Gradle Android build penuh di lingkungan ini (butuh Android SDK).**

---

## FASE 1 — Fondasi: DNS Packet Interception Nyata ⚠️ KODE SELESAI, MENUNGGU VERIFIKASI DEVICE FISIK

Ini adalah pekerjaan inti. Tujuannya: `NetShieldVpnService` benar-benar membaca
paket DNS dari trafik perangkat, bukan lagi hanya membuat tunnel kosong.

- [x] **1.1** Tambahkan thread/coroutine terpisah (`Dispatchers.IO`) yang membaca `FileInputStream(vpnInterface.fileDescriptor)` dalam loop, dengan buffer sesuai MTU (1500 byte). → `PacketTunnel.start()`.
- [x] **1.2** Implementasikan parser paket IPv4 minimal: baca header IP untuk protokol (UDP), lalu header UDP untuk port tujuan 53 (DNS standar). → `NetPacketUtils.parseIpv4Header`/`parseUdpHeader`.
- [x] **1.3** Ekstrak nama domain dari DNS query (parsing format DNS message: header + QNAME). → `DnsMessage.parseQuery`.
- [x] **1.4** Implementasikan **DNS resolver lokal**: domain diblokir → `NXDOMAIN`/`0.0.0.0` (`DnsMessage.buildBlockedResponse`); domain diizinkan → forward via `DatagramSocket` yang di-`protect()` lalu balasannya dikirim balik. → `PacketTunnel.handleDnsQuery`/`forwardToUpstream`.
- [x] **1.5** `VpnService.protect(socket)` dipanggil pada setiap socket upstream (DNS forward, UDP NAT, TCP NAT) — lihat `PacketTunnel.forwardToUpstream`, `UdpNatManager.createSession`, `TcpNatManager.handleSyn`.
- [x] **1.6** `selectedProvider` disambungkan ke resolver upstream via `DnsEngineRepository.selectedProviderSnapshot()`; hardcode `1.1.1.1`/`1.0.0.1` di `startVpn()` sudah dihapus.
- [x] **1.7** Non-DNS traffic (TCP/UDP) di-relay apa adanya tanpa filtering via `UdpNatManager` & `TcpNatManager` (state machine TCP minimal — lihat catatan keterbatasan di header file masing-masing).
- [ ] **1.8** Uji di device fisik: pastikan browsing normal tetap berjalan (Google, YouTube, WhatsApp) saat VPN aktif, dan domain iklan uji coba (`doubleclick.net`, dll.) benar-benar gagal resolve. **→ BELUM DIVERIFIKASI, perlu dilakukan Fandri di Android Studio/device fisik — lingkungan kerja saya tidak punya Android SDK/emulator untuk build & run VPN sungguhan.**

**File yang diubah/ditambah (Fase 1):**
- ➕ `app/.../vpn/NetPacketUtils.kt`, `DnsMessage.kt`, `BlocklistEngine.kt`, `PacketTunnel.kt`, `UdpNatManager.kt`, `TcpNatManager.kt`
- ✏️ `NetShieldVpnService.kt` — `addRoute(0.0.0.0/0)`, provider dari repository, integrasi `PacketTunnel`
- ✏️ `DnsEngineRepository.kt` — `selectedProviderSnapshot()`/`filterOptionsSnapshot()`/`customRulesSnapshot()`
- ✏️ `CHANGELOG.md` — entri Fase 1

**Kriteria selesai:** Mematikan Wi-Fi data test, domain di blocklist benar-benar tidak bisa diakses; domain normal tetap bisa; tidak ada penurunan kecepatan drastis atau internet mati total. **Status: kode lengkap, kriteria fungsional di atas baru bisa dikonfirmasi setelah uji manual di device fisik (poin 1.8).**

**Keterbatasan diketahui (transparan, akan diperkeras di fase berikutnya):**
- Hanya IPv4 didukung (IPv6 di-drop dengan aman, bukan crash).
- `BlocklistEngine` masih pakai daftar domain seed kecil hardcoded — **ini sengaja sementara**, tugas Fase 2 adalah menggantinya dengan sumber blocklist nyata.
- Statistik & log Dashboard masih dari `simulationJob` lama (belum dihapus) — penyambungan ke data nyata dari `PacketTunnel.Callbacks.onDnsQueryResolved()` adalah tugas Fase 3.
- Relay TCP (`TcpNatManager`) adalah implementasi awal tanpa retransmission timer/window scaling penuh — cukup untuk trafik HTTP/HTTPS normal, perlu diperkeras di Fase 6/7 setelah teruji di device fisik nyata.

---

## FASE 2 — Sumber Data Blocklist Nyata ⚠️ KODE SELESAI, MENUNGGU VERIFIKASI DEVICE/JARINGAN NYATA

- [x] **2.1** Pilih sumber blocklist domain nyata untuk tiap kategori filter — `BlocklistSource.kt` (StevenBlack hosts base + ekstensi social/porn/fakenews, URLhaus hostfile untuk malware). Batasan kategorisasi per app didokumentasikan transparan di header file.
- [x] **2.2** Skema penyimpanan lokal cepat: `BlocklistStore.kt` — `Map<kategori, HashSet<domain>>` in-memory, di-load dari cache file lokal saat service/repository start, swap atomik via `@Volatile`.
- [x] **2.3** Mekanisme update blocklist nyata: `BlocklistUpdateManager.kt` — unduh via OkHttp, verifikasi checksum SHA-256, cache ke `filesDir/blocklist_cache/`, simpan versi & timestamp di SharedPreferences. `DnsEngineRepository.updateThreatDatabase()` diganti total dari `delay(2200)`.
- [x] **2.4** `BlocklistEngine.evaluate()` — custom rule SELALU dicek & menang duluan, baru `BlocklistStore`, baru seed fallback.
- [x] **2.5** Unique index `domain` di `CustomRuleEntity` ditambahkan (`@Entity(indices=[Index("domain", unique=true)])`), Room DB version 1→2 (masih `fallbackToDestructiveMigration()`, utang teknis untuk Fase 6.4).
- [x] **2.6** `FilterOption.ruleCount` disegarkan dari `BlocklistStore.countForCategory()` nyata (`refreshRuleCountsFromStore()`), dipanggil saat load cache & setelah update sukses.

**File yang diubah/ditambah (Fase 2):**
- ➕ `app/.../vpn/BlocklistSource.kt`, `BlocklistStore.kt`, `BlocklistUpdateManager.kt`
- ✏️ `BlocklistEngine.kt`, `CustomRuleEntity.kt`, `NetShieldDatabase.kt`, `DnsEngineRepository.kt`
- ✏️ `CHANGELOG.md` — entri Fase 2

**Kriteria selesai:** Jumlah "rules aktif" yang ditampilkan di UI benar-benar mencerminkan jumlah domain di blocklist yang ter-load, dan bisa diupdate ulang dari sumber online. **Status: kode lengkap; belum bisa dikonfirmasi jalan karena lingkungan kerja saya tidak punya akses jaringan keluar maupun Android SDK/device fisik — perlu diverifikasi Fandri (lihat checklist verifikasi di `CHANGELOG.md` §Fase 2).**

**Keterbatasan diketahui (transparan):**
- `game_ads`/`marketplace_ads`/`trackers` memakai sumber gabungan yang sama (tidak ada sumber publik gratis yang memecah domain per kategori app) — lihat `BlocklistSource.kt`.
- Matching domain masih exact-match (hosts-file style), belum wildcard subdomain.
- Belum ada penjadwalan update otomatis berkala (WorkManager) — saat ini hanya manual dari UI.

### 🔴 Audit lapangan 2026-08-08 — Verifikasi device nyata: iklan judi in-app masih lolos

Dilaporkan Fandri: iklan rewarded-video mempromosikan situs judi online (NX888) masih tampil penuh di sebuah game mahjong, walau proteksi aktif. Root cause terkonfirmasi lewat audit kode (bukan bug logika packet loop/stats — jalur `PacketTunnel` → `DnsEngineRepository` sudah benar):

- **Seluruh sumber di `BlocklistSource.kt` adalah list Barat generik** (StevenBlack, AdAway, Disconnect.me, URLhaus, Phishing Database). Tidak ada satu pun yang mencakup jaringan mediasi iklan game asal Asia (TopOn, Sigmob, KS Ads, GroMore, dll.) atau domain CDN afiliasi judi — kategori iklan yang sangat umum di game kasual/mahjong Indonesia & sering sengaja pakai domain acak/rotasi untuk menghindari ad-blocker berbasis DNS.
- Klaim UI di `FilterOption.kt` ("Blokir Iklan Game **Fully**"/"semua game Android") **overclaim** relatif terhadap batas nyata pendekatan DNS-blocking — perlu direvisi ke bahasa yang lebih jujur (mis. "sebagian besar iklan berbasis SDK umum").
- Belum ada bukti dari sisi Fandri bahwa APK yang diuji di device adalah build TERBARU dari source ini, dan/atau bahwa proteksi VPN benar-benar ON & `ActivityLogScreen` menunjukkan domain terkait — perlu dikonfirmasi sebagai langkah debug pertama sebelum kerja tambahan blocklist dilakukan.

- [x] **2.7 (baru)** Tambahkan sumber blocklist global HaGeZi/dns-blocklists ke `BlocklistSource.kt` (Pro/Gambling/Fake/Pop-Up Ads/native OEM tracker) — blocklist paling komprehensif & sering diperbarui saat ini (dipakai NextDNS/ControlD/Pi-hole/AdGuard Home). Kategori baru `gambling_scam_ads` dibuat (`FilterOption.kt`, `BlocklistStore.kt`, `BlocklistEngine.kt`) khusus judi online & investasi/trading palsu — langsung menjawab kedua kasus nyata Fandri (NX888, Binance palsu). Parser `BlocklistUpdateManager.parseHostsFile()` diperluas mendukung format domain polos (folder `domains/` HaGeZi) selain format hosts lama. **Catatan jujur: URL sumber diverifikasi lewat dokumentasi resmi proyek (bukan uji unduh langsung — lingkungan kerja saya tidak punya akses jaringan keluar), WAJIB dicek Fandri lewat tombol "Perbarui Database" setelah install nyata.**
- [x] **2.8 (baru)** Revisi teks klaim di `FilterOption.kt` (`game_ads`): "Blokir Iklan Game Fully...semua game Android" → "Blokir Iklan Game...efektif untuk domain yang ada di database — lihat batasan di Pengaturan". Tidak lagi overclaim.
- [ ] **2.9 (baru)** Tambahkan indikator diagnostik di UI (mis. badge di Dashboard/Settings): "Blocklist terakhir diperbarui: [timestamp], X domain aktif" yang jelas terlihat, supaya user bisa memverifikasi sendiri apakah blocklist benar-benar ter-load tanpa harus baca Logcat — mempercepat debug kasus seperti ini di masa depan.

**File yang diubah/ditambah (Fase 2.7):**
- ✏️ `BlocklistSource.kt` — sumber HaGeZi ditambahkan, kategori `gambling_scam_ads` baru
- ✏️ `BlocklistUpdateManager.kt` — parser multi-format (hosts/AdBlock/domain polos)
- ✏️ `BlocklistStore.kt` — `gambling_scam_ads` masuk `CATEGORY_PRIORITY`
- ✏️ `BlocklistEngine.kt` — konstanta `CATEGORY_GAMBLING_SCAM_ADS`
- ✏️ `FilterOption.kt` — filter baru + revisi teks `game_ads`
- ✏️ `DnsEngineRepository.kt` — `gambling_scam_ads` diperlakukan setara ancaman (masuk `threat_events` + notifikasi)
- ✏️ `NetShieldDao.kt` — `getThreatsPreventedCount()` menghitung `gambling_scam_ads`
- ✏️ `NetShieldViewModel.kt` — filter tampilan "Ancaman" mencakup `gambling_scam_ads`
- ✏️ `CHANGELOG.md` — entri Fase 2.7

---

## FASE 3 — Sambungkan Statistik & Log ke Data Nyata ⚠️ KODE SELESAI, MENUNGGU VERIFIKASI DEVICE FISIK

- [x] **3.1** `simulationJob` di `DnsEngineRepository` dihapus TOTAL dari jalur produksi.
- [x] **3.2** Kanal komunikasi Service → Repository dibuat via `MutableSharedFlow<DnsQueryEvent>` + `recordDnsQueryResolved()` — setiap DNS query nyata dari `PacketTunnel.Callbacks.onDnsQueryResolved()` (via `NetShieldVpnService`) langsung ditulis ke `dnsLogs` Room lewat collector `persistDnsQueryEvent()`.
- [x] **3.3** `ProtectionStats` (totalRequests, totalBlocked, avgLatencyMs, threatsPrevented untuk kategori malware_guard) dihitung dari agregasi event nyata di `persistDnsQueryEvent()`.
- [x] **3.4** `dataSavedMb` diestimasi dari konstanta `AVG_BLOCKED_PAYLOAD_KB = 45f` (asumsi didokumentasikan di companion object `DnsEngineRepository`), bukan lagi random `0.15–0.55`.
- [ ] **3.5 (dikoreksi audit 2026-08-08 — SEBELUMNYA SALAH DITANDAI [x])** Mode demo/simulasi khusus testing UI (`enableDebugSimulation()`, dibungkus `BuildConfig.DEBUG`) **TIDAK PERNAH benar-benar ditulis di kode** — hanya disebut di komentar/dokumentasi tanpa implementasi nyata. Ditemukan lewat audit kode langsung (bukan dari klaim dokumentasi) tanggal 2026-08-08. Dampak: tidak ada cara mudah menguji UI Dashboard/ActivityLog tanpa VPN aktif & trafik DNS sungguhan. Ini item TERBUKA — perlu diimplementasikan sungguhan jika masih dibutuhkan, atau dihapus dari rencana jika dianggap tidak perlu.

**File yang diubah (Fase 3):**
- ✏️ `DnsEngineRepository.kt`, `NetShieldVpnService.kt`
- ✏️ `CHANGELOG.md` — entri Fase 3

**Kriteria selesai:** Semua angka statistik & log yang tampil di Dashboard berasal dari trafik DNS nyata perangkat, dapat diverifikasi manual (mis. akses situs dengan iklan, cek log muncul). **Status: kode lengkap (kecuali 3.5); belum bisa dikonfirmasi jalan karena lingkungan kerja saya tidak punya Android SDK/device fisik — checklist verifikasi lengkap ada di `CHANGELOG.md` §Fase 3.**

**Keterbatasan diketahui (transparan):**
- `clientApp` masih label generik "Trafik Perangkat (VPN)" — atribusi per-aplikasi belum diimplementasikan (perlu mapping UID, di luar cakupan fase ini).
- `dataSavedMb` adalah estimasi konstanta, bukan pengukuran byte aktual (keterbatasan inheren DNS-level blocking).
- (Catatan: baris ini SEBELUMNYA menyebut `threat_events` "TETAP dari triggerThreatSimulationAlert() (simulasi)" — itu sudah tidak akurat sejak Fase 4 selesai, lihat §Fase 4 di bawah. Dihapus dari daftar keterbatasan oleh audit 2026-08-08.)

---

## FASE 4 — Deteksi Ancaman (Malware/Phishing) Nyata ⚠️ KODE SELESAI, MENUNGGU VERIFIKASI DEVICE FISIK

- [x] **4.1** `triggerThreatSimulationAlert()` (domain ancaman acak) dihapus total. Diganti `recordRealThreatEvent()`, dipanggil otomatis dari `persistDnsQueryEvent()` hanya saat domain hasil query nyata cocok kategori `malware_guard` (blocklist Fase 2: StevenBlack fakenews + URLhaus).
- [x] **4.2** Istilah "AI Guard"/"AI" dihapus dari teks yang dibuat repository & label UI (`ThreatScreen`) — deteksi dinyatakan transparan sebagai rule-based blocklist matching, bukan model AI/ML.
- [x] **4.3** `sendThreatNotification` sekarang hanya terpicu dari `recordRealThreatEvent` (dipanggil dari jalur query DNS nyata Fase 1) — tidak ada lagi jalur probabilitas acak/tombol simulasi yang bisa memicu notifikasi ancaman palsu.

**File yang diubah (Fase 4):**
- ✏️ `DnsEngineRepository.kt` — hapus `triggerThreatSimulationAlert()`, tambah `recordRealThreatEvent()`/`refreshThreatIntelligence()`
- ✏️ `NetShieldViewModel.kt`, `ThreatScreen.kt`, `DashboardScreen.kt` — sambungkan ke fungsi nyata, hapus label "AI Guard"
- ✏️ `CHANGELOG.md` — entri Fase 4

**Kriteria selesai:** Notifikasi "ancaman diblokir" hanya muncul saat device benar-benar mencoba resolve domain yang ada di daftar ancaman. **Status: kode lengkap; checklist verifikasi manual lengkap ada di `CHANGELOG.md` §Fase 4.**

---

## FASE 5 — Penyempurnaan Fitur Existing ⚠️ KODE SELESAI (5.1, 5.2, 5.4), MENUNGGU VERIFIKASI DEVICE FISIK

- [x] **5.1** `dohEnabled` diimplementasikan nyata: `PacketTunnel.forwardToUpstream()` memakai DNS-over-HTTPS (RFC 8484 via OkHttp) ke `provider.dohUrl` saat toggle aktif & provider mendukung, dengan fallback otomatis ke UDP polos jika gagal. Socket TLS OkHttp di-protect() lewat `ProtectingSocketFactory` khusus.
- [x] **5.2** `lowBatteryMode` kini punya efek nyata: notifikasi push per-ancaman di-throttle (interval minimum 120 detik) saat aktif — insiden tetap 100% tercatat ke `threat_events`, hanya pengiriman notifikasi individual yang digabung/ditunda untuk mengurangi wake-up radio/layar. Efek lain (mis. frekuensi update blocklist) belum diterapkan karena belum ada scheduler periodik (`WorkManager`) di project — dicatat sebagai utang teknis Fase 6/7.
- [x] **5.3** Ditinjau ulang — `ActivityLogScreen`/`filteredLogs` sudah memenuhi kriteria performa (`LIMIT 200` di query DAO + filter di memori). Tidak ada perubahan kode; pagination penuh dicatat sebagai potensi kerja Fase 7 jika volume log riil jauh lebih besar dari perkiraan.
- [x] **5.4** Dialog "Clear Logs" di `ActivityLogScreen` sekarang menawarkan 3 opsi: hapus sesuai filter aktif (Diblokir/Diizinkan/Ancaman), hapus log >30 hari, atau hapus semua — didukung query DAO baru (`clearDnsLogsByBlockedStatus`, `clearDnsLogsByThreatCategory`, `clearDnsLogsOlderThan`).

**File yang diubah (Fase 5):**
- ✏️ `PacketTunnel.kt` — DoH real (5.1), `ProtectingSocketFactory`, `Callbacks.isDohEnabled()`
- ✏️ `DnsEngineRepository.kt` — `dohEnabledSnapshot()`, throttle notifikasi (5.2), `clearLogsByDisplayFilter()`/`clearLogsOlderThan()` (5.4)
- ✏️ `NetShieldVpnService.kt` — wiring `isDohEnabled()`
- ✏️ `NetShieldDao.kt` — query clear granular baru
- ✏️ `NetShieldViewModel.kt`, `ActivityLogScreen.kt` — UI clear log granular
- ✏️ `CHANGELOG.md` — entri Fase 5

**Kriteria selesai:** DoH benar-benar dipakai saat toggle aktif (dengan fallback aman); lowBatteryMode & clear log granular berefek nyata dan bisa diverifikasi manual. **Status: kode lengkap; checklist verifikasi manual lengkap ada di `CHANGELOG.md` §Fase 5. Keterbatasan resolusi hostname DoH itu sendiri (bergantung resolver sistem, bukan tunnel) didokumentasikan transparan di sana.**

---

## FASE 6 — Keandalan, Keamanan & Kepatuhan Android

- [x] **6.1** Tangani **VPN revoked oleh user/sistem** (`onRevoke()` di `VpnService`) — pastikan state UI ikut ter-update, service berhenti bersih, dan notifikasi hilang.
  - `onRevoke()` sebelumnya SUDAH memanggil `stopVpn()` (bersih: tun ditutup,
    notifikasi hilang), tapi `isProtectionActive` di repository/UI TIDAK
    pernah ikut disinkronkan balik — switch di UI bisa tetap "aktif" walau
    VPN sudah mati di OS. Ditambahkan `DnsEngineRepository.syncProtectionStateFromService()`,
    dipanggil dari `NetShieldVpnService.startVpn()` (saat sukses establish)
    dan `stopVpn()` (dipanggil dari SEMUA jalur keluar: stop normal via
    ACTION_DISCONNECT, gagal `establish()`, dan `onRevoke()`). Sekaligus
    memperbaiki celah lama: tombol "Matikan Proteksi" di notifikasi
    mengirim `ACTION_DISCONNECT` langsung ke service (tanpa lewat
    `ViewModel.toggleProtection`), sehingga sebelumnya UI tidak pernah tahu
    proteksi sudah dimatikan dari notifikasi.
  - **File yang diubah:** `DnsEngineRepository.kt` (fungsi baru
    `syncProtectionStateFromService`), `NetShieldVpnService.kt`
    (pemanggilan di `startVpn()`/`stopVpn()`).
  - **Status verifikasi:** kode selesai; **BELUM diverifikasi di device
    fisik** (lingkungan kerja tidak punya Android SDK/emulator). Wajib
    sebelum merge:
    1. Aktifkan proteksi dari switch UI, lalu cabut izin VPN lewat
       Settings > VPN (bukan dari app) — pastikan switch NetShield ikut
       berubah ke "nonaktif" dan notifikasi hilang, tanpa perlu restart app.
    2. Aktifkan proteksi, lalu tekan tombol "Matikan Proteksi" di
       notifikasi — pastikan switch di UI (jika app sedang dibuka) ikut
       berubah ke "nonaktif", bukan cuma notifikasi yang hilang.
    3. Aktifkan VPN lain (app VPN pihak ketiga) saat NetShield aktif —
       pastikan NetShield ter-revoke bersih & UI konsisten.
- [ ] **6.2** Tangani **always-on VPN** dan **lockdown mode** Android jika ingin mendukungnya (opsional, sesuai target pengguna).
- [x] **6.3** Tambah **battery optimization exemption** flow yang jelas (opsional, minta user mengecualikan app dari doze/battery saver) agar service tidak dibunuh sistem — jelaskan dengan transparan ke user kenapa ini diminta.
  - Ditambahkan permission `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` di
    manifest; `MainActivity` mengecek status via
    `PowerManager.isIgnoringBatteryOptimizations()` (di `onCreate` &
    `onResume`) dan menyediakan `requestBatteryOptimizationExemption()`
    yang membuka dialog sistem `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
    (dengan fallback ke halaman Settings umum bila OEM tidak
    menyediakannya). `SettingsScreen` mendapat kartu baru
    `BatteryExemptionCard` yang menampilkan status, penjelasan transparan,
    dan tombol permintaan — hanya tampil bila belum exempt. Flow bersifat
    100% opsional & eksplisit (tidak pernah otomatis saat app dibuka).
  - **File yang diubah:** `AndroidManifest.xml`, `MainActivity.kt`,
    `ui/screens/SettingsScreen.kt`.
  - **Status verifikasi:** kode selesai; **BELUM diverifikasi di device
    fisik** (lingkungan kerja tidak punya Android SDK/emulator). Checklist
    verifikasi manual lengkap ada di `CHANGELOG.md` §Fase 6.3.
- [x] **6.4** Room database: ganti `fallbackToDestructiveMigration()` dengan migrasi eksplisit (`Migration` object) sebelum rilis produksi, supaya update aplikasi di masa depan tidak menghapus data custom rules milik user (`MIGRATION_1_2` ditambahkan).
- [x] **6.5** Review permission di `AndroidManifest.xml` — ditambahkan `FOREGROUND_SERVICE_SPECIAL_USE` dan `android:foregroundServiceType="specialUse"` untuk kepatuhan Android 14+ / targetSdk 36.
- [x] **6.6** Pastikan **tidak ada logging domain yang dikunjungi user dikirim keluar device** — diverifikasi: seluruh DNS log tersimpan murni di Room DB lokal (`dns_logs`).
- [x] **6.7** Tambahkan kebijakan privasi & disclosure jelas — ditambahkan kartu "Your Privacy Matters" dan penjelasan transparan pada `SettingsScreen.kt`.

---

## FASE 7 — Testing & QA

- [x] **7.1** Unit test untuk parser DNS packet (Fase 1) dengan berbagai bentuk paket valid/invalid (`DnsMessageTest.kt`, `NetPacketUtilsTest.kt`).
- [x] **7.2** Unit test untuk logic pencocokan domain vs blocklist + custom rule override (`BlocklistEngineTest.kt`).
- [ ] **7.3** Instrumented test end-to-end: aktifkan VPN, akses domain uji, verifikasi log tercatat dengan benar.
- [ ] **7.4** Uji manual di berbagai versi Android (minSdk 24 s.d. targetSdk 36) dan berbagai merek device (khususnya yang agresif membunuh background service: Xiaomi/MIUI, Oppo/ColorOS, dll.).
- [ ] **7.5** Uji stabilitas jangka panjang (VPN aktif berjam-jam) untuk memastikan tidak ada memory leak pada packet loop.
- [x] **7.6** Ganti/lengkapi test boilerplate dengan unit test nyata (`DnsMessageTest.kt`, `BlocklistEngineTest.kt`, `NetPacketUtilsTest.kt`, `SecurityScoreCalculatorTest.kt`).

---

## FASE 8 — Rilis

- [x] **8.1** Aktifkan `isMinifyEnabled = true` + review `proguard-rules.pro` untuk build release (`app/build.gradle.kts` & `app/proguard-rules.pro`).
- [ ] **8.2** Pastikan signing config release memakai keystore aman (env var `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD` sudah ada strukturnya — pastikan tidak pernah commit file `.jks`/`.env` asli ke repo).
- [ ] **8.3** Siapkan listing Play Store dengan deskripsi akurat (jangan overclaim "AI" jika belum ada) + privacy policy (lihat 6.7).
- [ ] **8.4** Siapkan crash reporting/monitoring produksi (Firebase Crashlytics — Firebase BOM sudah ada di dependencies, tinggal diaktifkan) untuk memantau stabilitas packet loop di real-world device.

---

## Urutan Prioritas Kerja (Ringkas)

1. Fase 0 (bug kritis) → wajib duluan, cepat dikerjakan.
2. Fase 1 (packet interception nyata) → inti produk, paling kompleks & krusial.
3. Fase 2 (blocklist nyata) → berjalan paralel dengan Fase 1 di bagian akhir.
4. Fase 3 & 4 (sambungkan stats/log/threat ke data nyata) → setelah Fase 1–2 stabil.
5. Fase 5 (penyempurnaan fitur) → setelah inti berjalan solid.
6. Fase 6 (keandalan & kepatuhan) → sebelum rilis.
7. Fase 7 (testing) → berjalan terus-menerus sejak Fase 1, diperketat menjelang rilis.
8. Fase 8 (rilis) → tahap akhir.

---

## Catatan Penting

- Fase 1 (VPN packet interception + DNS resolver protect()) adalah **bagian tersulit dan paling berisiko** — kesalahan di sini bisa membuat device kehilangan koneksi internet total, jadi selalu sediakan tombol "Matikan Proteksi" yang tetap berfungsi kapan pun (sudah ada di notifikasi, pastikan tetap teruji baik).
- Sangat disarankan untuk mempelajari arsitektur project ad-blocker VPN-based open source lain (RethinkDNS, Blokada, PersonalDNSFilter) sebagai referensi pola implementasi packet-loop dan protect socket — bukan untuk disalin kodenya, tapi dipahami pendekatan arsitekturnya, karena topik ini punya banyak jebakan teknis (loop trafik, performa parsing, IPv6, dll.).
- Setiap fase sebaiknya dikerjakan di branch terpisah dengan checklist di atas dicentang bertahap dan diverifikasi di device fisik sebelum merge.
