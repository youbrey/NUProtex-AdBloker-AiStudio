# NetShield - Android Privacy & DNS Protection Engine

NetShield adalah aplikasi Android modern berbasis Kotlin & Jetpack Compose dengan custom VPN Packet Tunneling engine untuk perlindungan privasi, pencegahan malware/phishing, dan penyaringan DNS secara lokal tanpa server pihak ketiga.

---

## 🌟 Fitur Utama

- **Real-Time DNS Filtering**: Pencegahan otomatis iklan, malware, phishing, dan tracker pada tingkat paket.
- **Custom VPN Engine**: Custom packet reader/writer di atas Linux TUN interface dengan manajemen NAT TCP/IPv4/IPv6 & UDP.
- **Room Persistence**: Penyimpanan log DNS, aturan kustom, dan indikator ancaman secara lokal dan efisien.
- **Custom Security Dial & Analytics**: Visualisasi statistik lalu lintas dan indikator skor keamanan secara waktu nyata.

---

## 🛠️ Alur Build (CI/CD GitHub Actions)

Proyek ini telah dilengkapi dengan workflow CI/CD otomatis untuk membangun file APK secara otomatis saat kode didorong ke GitHub.

### Flowchart Build APK

```mermaid
graph TD
    A[Push / Pull Request / Manual Dispatch] --> B[Trigger GitHub Actions Workflow]
    B --> C[Setup Environment: Ubuntu Latest + JDK 17]
    C --> D[Cache Gradle Dependencies]
    D --> E[Build Debug APK via Gradle]
    E --> F{Tag Push v*?}
    F -- Ya --> G[Publikasikan GitHub Release & Lampirkan APK]
    F -- Tidak --> H[Upload Artifact APK (NetShield-Debug-APK)]
    G --> I[Selesai]
    H --> I[Selesai]
```

---

## 🚀 Cara Menjalankan Lokal

1. **Clone Repository**:
   ```bash
   git clone https://github.com/username/netshield.git
   cd netshield
   ```

2. **Build via Gradle**:
   ```bash
   gradle assembleDebug
   ```

3. **Install APK**:
   File APK hasil build dapat ditemukan di:
   `app/build/outputs/apk/debug/app-debug.apk`
