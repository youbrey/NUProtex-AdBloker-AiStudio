# DOKUMENTASI — Fase 6.3: Battery Optimization Exemption Flow

## Ringkasan
Mengerjakan item **6.3** dari `RENCANA_PRODUKSI_NETSHIELD.md`: menambahkan
alur permintaan pengecualian dari battery optimization/doze Android, agar
`NetShieldVpnService` (layanan VPN yang menjalankan DNS shield) lebih tahan
terhadap kebiasaan sebagian OEM (Xiaomi/MIUI, Oppo/ColorOS, dll.) yang
agresif membunuh proses background demi hemat baterai.

Sebelum melakukan perubahan, dilakukan pengecekan terhadap:
- `RENCANA_PRODUKSI_NETSHIELD.md` — untuk memastikan status Fase 0–5
  (sudah kode-lengkap) dan cakupan Fase 6 (item 6.1 sudah selesai, 6.2 &
  6.4–6.7 masih tertunda).
- `CHANGELOG.md` — untuk memahami gaya dokumentasi & konvensi penamaan
  entri per fase yang sudah dipakai (Fase 0 s.d. Fase 6.1).

## File yang Diubah

### 1. `app/src/main/AndroidManifest.xml`
Menambahkan permission:
```xml
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```
Permission ini hanya mengizinkan app **menampilkan** dialog sistem untuk
meminta exemption — tidak memberi exemption otomatis. User tetap harus
menyetujui lewat dialog resmi Android.

### 2. `app/src/main/java/com/example/MainActivity.kt`
- State baru `isBatteryOptimizationExempt: MutableState<Boolean>`, diisi
  dari `PowerManager.isIgnoringBatteryOptimizations(packageName)`:
  - Dicek pertama kali di `onCreate()`.
  - Di-refresh ulang di `onResume()` — penting karena setelah user
    menutup dialog sistem/Settings baterai, `MainActivity` akan
    `onResume()` lagi, sehingga UI otomatis konsisten tanpa perlu restart
    app.
- Fungsi baru `requestBatteryOptimizationExemption()`:
  - Membuka `Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))`
    lewat `ActivityResultLauncher` (`batteryOptimizationLauncher`).
  - Bila device/OEM tidak menyediakan dialog per-app langsung
    (`ActivityNotFoundException`), fallback ke
    `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (halaman daftar
    battery optimization umum) supaya user tetap bisa mengatur manual.
  - Hanya dipanggil dari tombol eksplisit di `SettingsScreen` — **tidak
    pernah otomatis** saat app dibuka, sesuai instruksi rencana agar
    transparan ke user.
- `NetShieldMainApp()` diperluas menerima `isBatteryOptimizationExempt`
  dan `onRequestBatteryOptimizationExemption`, diteruskan ke
  `SettingsScreen`.

### 3. `app/src/main/java/com/example/ui/screens/SettingsScreen.kt`
- Composable baru `BatteryExemptionCard(isExempt, onRequestExemption)`:
  - Menampilkan ikon status (centang hijau bila sudah exempt, ikon
    baterai bila belum) beserta label singkat.
  - Bila belum exempt: menampilkan paragraf penjelasan (dalam Bahasa
    Indonesia, sesuai bahasa project) tentang **kenapa** app meminta ini —
    Android bisa mematikan VPN service di background sehingga proteksi
    berhenti tanpa disadari — dan tombol "Kecualikan dari Battery
    Optimization".
  - Bila sudah exempt: tombol disembunyikan, hanya status yang tampil.
- Kartu ini dipasang di section "Device Optimization", tepat setelah
  toggle "Auto-Start on Boot", agar berdekatan secara tematik dengan
  toggle "Ultra Low-Power Engine" yang sudah ada.

## Logika / Alasan Desain
1. **Selalu opsional & eksplisit** — sesuai kalimat rencana "jelaskan
   dengan transparan ke user kenapa ini diminta", flow ini tidak pernah
   dipicu otomatis. User harus menekan tombol di Settings, lalu tetap
   bebas menolak di dialog sistem Android.
2. **Status selalu dari OS, bukan disimpan lokal** — `PowerManager` adalah
   satu-satunya sumber kebenaran; tidak ada flag tersendiri di
   `DataStore`/DB yang bisa "berbohong" (mengikuti pola yang sama seperti
   perbaikan Fase 6.1 soal `isProtectionActive` — status UI harus selalu
   mencerminkan status OS yang sebenarnya).
3. **Fallback OEM** — beberapa custom ROM tidak mengimplementasikan
   `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dengan benar; fallback ke
   halaman Settings umum mencegah crash (`ActivityNotFoundException`)
   pada device tersebut.

## Keterbatasan yang Diketahui (Belum Diselesaikan Sesi Ini)
- Status hanya di-refresh di `onCreate()`/`onResume()` (bukan realtime via
  `BroadcastReceiver`). Cukup untuk alur pemakaian normal, tapi tidak
  ter-update bila optimization diubah dari app lain saat NetShield sedang
  foreground tanpa pernah ke background.
- Item Fase 6.2 (always-on VPN/lockdown mode), 6.4 (migrasi Room
  eksplisit), 6.5 (`foregroundServiceType`), 6.6 (audit no-logging), dan
  6.7 (privacy policy) **belum dikerjakan** — masih di rencana Fase 6.

## Status Verifikasi
**Kode lengkap, tapi BELUM diverifikasi di device fisik** — lingkungan
kerja tidak memiliki Android SDK/emulator/device fisik maupun akses
jaringan keluar untuk build & jalankan APK. Checklist verifikasi manual
lengkap (4 langkah) dicatat di `CHANGELOG.md` §Fase 6.3 dan
`RENCANA_PRODUKSI_NETSHIELD.md` §6.3 — wajib dijalankan Fandri di device
fisik sebelum merge, terutama pada device MIUI/ColorOS yang punya battery
manager tambahan di luar AOSP standar.

## Langkah Selanjutnya yang Disarankan
Setelah verifikasi device fisik untuk 6.1 dan 6.3 selesai, lanjutkan ke
item Fase 6 yang tersisa sesuai urutan di rencana: 6.4 (migrasi Room
eksplisit) adalah prioritas tinggi karena menyangkut kehilangan data user
saat update aplikasi di masa depan.
