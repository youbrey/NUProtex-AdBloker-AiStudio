package com.example.vpn

/**
 * Sumber blocklist domain NYATA per kategori filter (Fase 2.1, diperluas
 * Fase 2.7).
 *
 * Setiap [Source] menunjuk ke daftar publik yang di-parse
 * [BlocklistUpdateManager.parseHostsFile] — mendukung format hosts
 * (`0.0.0.0 domain`), AdBlock (`||domain^`), maupun domain polos satu
 * baris (lihat Fase 2.7).
 *
 * KETERBATASAN YANG DIDOKUMENTASIKAN SECARA TRANSPARAN (sesuai prinsip
 * kerja §5 RENCANA_PRODUKSI_NETSHIELD.md):
 *  - Tidak ada sumber publik gratis yang memecah domain iklan persis per
 *    SDK/app secara sempurna. `game_ads`/`marketplace_ads`/`trackers`
 *    tetap banyak beririsan sumbernya — pemecahan kategori sebagian besar
 *    di level UI/UX, bukan klaim domain-nya benar-benar eksklusif.
 *  - URL sumber HaGeZi (di bawah) diverifikasi via dokumentasi resmi
 *    proyek per 2026-08-08 (pola URL & keberadaan folder `hosts/`/`domains/`
 *    dikonfirmasi lewat README & DeepWiki proyek), TAPI tidak diuji
 *    unduh langsung dari lingkungan kerja ini (tidak ada akses jaringan
 *    keluar di sandbox). `BlocklistUpdateManager` sudah menangani
 *    kegagalan per-sumber secara graceful (satu sumber gagal/404 tidak
 *    menggagalkan sumber lain) — tetap WAJIB diverifikasi Fandri lewat
 *    tombol "Perbarui Database" setelah build & install nyata.
 *  - `malware_guard` menggabungkan HaGeZi Pro + URLhaus (abuse.ch) +
 *    StevenBlack fakenews — daftar host malware/C2 yang benar-benar
 *    dikurasi dari data ancaman nyata.
 *  - `gambling_scam_ads` (BARU, Fase 2.7) memakai HaGeZi Gambling +
 *    HaGeZi Fake/Fraud — inilah kategori yang secara spesifik mencakup
 *    kasus dunia nyata Fandir: iklan judi online (mis. "NX888") & iklan
 *    investasi/trading palsu yang meniru UI Binance dkk.
 *
 * Lisensi: StevenBlack/hosts (MIT). HaGeZi/dns-blocklists (lihat
 * https://github.com/hagezi/dns-blocklists/blob/main/LICENSE — gratis,
 * open source, cek syarat penggunaan komersial sebelum rilis Play Store).
 * URLhaus (abuse.ch) gratis untuk non-komersial/keamanan — cek
 * https://urlhaus.abuse.ch/api/ untuk syarat penggunaan komersial.
 *
 * === CHANGELOG ===
 * [Fase 2 - 2026-08-07] Baru dibuat. Menggantikan
 * BlocklistEngine.SEED_BLOCKED_DOMAINS hardcoded (Fase 1) dengan definisi
 * sumber blocklist nyata yang bisa diunduh & diperbarui berkala.
 * [Fase 2.7 - 2026-08-08] Ditambahkan sumber HaGeZi/dns-blocklists —
 * blocklist global paling komprehensif & paling sering diperbarui saat
 * ini (dipakai NextDNS, ControlD, Pi-hole, AdGuard Home, dll.), mencakup
 * gambling, scam/fake, pop-up ads, & native tracker OEM Android secara
 * eksplisit. Kategori baru `gambling_scam_ads` ditambahkan (lihat
 * FilterOption.kt, BlocklistStore.kt, BlocklistEngine.kt,
 * DnsEngineRepository.kt untuk perubahan terkait). Dipicu laporan
 * langsung Fandri: iklan judi (NX888) & iklan trading kripto palsu
 * (meniru UI Binance) lolos dari blocklist lama. Lihat CHANGELOG.md &
 * RENCANA_PRODUKSI_NETSHIELD.md §Fase 2.7.
 */
object BlocklistSource {

    data class Source(
        val categoryId: String,
        val url: String,
        /** Nama singkat untuk logging/penyimpanan cache lokal, mis. "stevenblack_base". */
        val cacheKey: String
    )

    // ---- Sumber lama (Fase 2, dipertahankan) ----
    private const val STEVENBLACK_BASE =
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    private const val STEVENBLACK_SOCIAL =
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/social-only/hosts"
    private const val STEVENBLACK_PORN =
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts"
    private const val STEVENBLACK_FAKENEWS =
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/fakenews-only/hosts"
    private const val URLHAUS_HOSTFILE =
        "https://urlhaus.abuse.ch/downloads/hostfile/"
    private const val ADAWAY_OFFICIAL =
        "https://adaway.org/hosts.txt"
    private const val DAN_POLLOCK_HOSTS =
        "https://someonewhocares.org/hosts/hosts"
    private const val DISCONNECT_TRACKING =
        "https://s3.amazonaws.com/lists.disconnect.me/simple_tracking.txt"
    private const val DISCONNECT_AD =
        "https://s3.amazonaws.com/lists.disconnect.me/simple_ad.txt"
    private const val PHISHING_DATABASE =
        "https://raw.githubusercontent.com/mitchellkrogza/Phishing.Database/master/phishing-domains_ACTIVE.txt"

    // ---- Sumber BARU Fase 2.7: HaGeZi/dns-blocklists (format domains/, global) ----
    // Pola URL resmi: https://raw.githubusercontent.com/hagezi/dns-blocklists/main/{format}/{listname}.txt
    private const val HAGEZI_PRO =
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/pro.txt"
    private const val HAGEZI_GAMBLING =
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/gambling.txt"
    private const val HAGEZI_FAKE =
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/fake.txt"
    private const val HAGEZI_POPUPADS =
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/popupads.txt"
    private const val HAGEZI_NATIVE_OPPO_REALME =
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/native.oppo-realme.txt"
    private const val HAGEZI_NATIVE_SAMSUNG =
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/native.samsung.txt"
    private const val HAGEZI_NATIVE_TIKTOK =
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/native.tiktok.extended.txt"

    // ---- Sumber BARU Audit-8 (diminta user, dari lampiran repo HaGeZi) ----
    // "social.txt" SENGAJA TIDAK didaftarkan di sini — isinya memblokir
    // SELURUH platform sosial (Facebook/Instagram/TikTok/dst.), bukan
    // sekadar tracker-nya. Bertentangan langsung dengan instruksi user
    // ("jangan memblokir API utama Facebook/WhatsApp/Instagram/TikTok").
    // "spam-tlds.txt" JUGA TIDAK didaftarkan — memakai sintaks wildcard TLD
    // (`||*.tld^$denyallow=...`) yang tidak didukung parseHostsFile() saat
    // ini (akan tersimpan sebagai literal "*.tld" yang tidak pernah cocok
    // domain nyata apa pun — no-op senyap). Perlu implementasi matcher
    // wildcard+denyallow terpisah sebelum bisa diaktifkan, dicatat sebagai
    // kerja lanjutan di CHANGELOG-v2.md §Audit-8.
    private const val HAGEZI_ANTI_PIRACY =
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/anti.piracy.txt"
    private const val HAGEZI_ULTIMATE =
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/ultimate.txt"
    private const val HAGEZI_MULTI =
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/multi.txt"
    private const val HAGEZI_GAMBLING_MEDIUM =
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/gambling.medium.txt"
    private const val HAGEZI_NATIVE_XIAOMI =
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/native.xiaomi.txt"
    private const val HAGEZI_URLSHORTENER =
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/urlshortener.txt"
    private const val HAGEZI_DOH_VPN_PROXY_BYPASS =
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/doh-vpn-proxy-bypass.txt"

    /** Semua sumber yang perlu diunduh. Satu kategori bisa punya >1 sumber (digabung saat load). */
    val ALL_SOURCES: List<Source> = listOf(
        Source("malware_guard", URLHAUS_HOSTFILE, "urlhaus_hostfile"),
        Source("malware_guard", STEVENBLACK_FAKENEWS, "stevenblack_fakenews"),
        Source("malware_guard", HAGEZI_PRO, "hagezi_pro"),

        Source("phishing_guard", PHISHING_DATABASE, "phishing_database"),
        Source("phishing_guard", HAGEZI_FAKE, "hagezi_fake"),

        Source("fingerprint_guard", STEVENBLACK_BASE, "stevenblack_base"),

        Source("trackers", DISCONNECT_TRACKING, "disconnect_tracking"),
        Source("trackers", STEVENBLACK_BASE, "stevenblack_base"),
        Source("trackers", HAGEZI_NATIVE_OPPO_REALME, "hagezi_native_oppo_realme"),
        Source("trackers", HAGEZI_NATIVE_SAMSUNG, "hagezi_native_samsung"),

        Source("game_ads", ADAWAY_OFFICIAL, "adaway_official"),
        Source("game_ads", DAN_POLLOCK_HOSTS, "dan_pollock_hosts"),
        Source("game_ads", STEVENBLACK_BASE, "stevenblack_base"),
        Source("game_ads", HAGEZI_PRO, "hagezi_pro"),
        Source("game_ads", HAGEZI_POPUPADS, "hagezi_popupads"),

        Source("marketplace_ads", DISCONNECT_AD, "disconnect_ad"),
        Source("marketplace_ads", HAGEZI_PRO, "hagezi_pro"),

        Source("social_ads", STEVENBLACK_SOCIAL, "stevenblack_social"),
        Source("social_ads", HAGEZI_NATIVE_TIKTOK, "hagezi_native_tiktok"),

        Source("adult_content", STEVENBLACK_PORN, "stevenblack_porn"),

        // BARU Fase 2.7 — langsung menjawab kasus nyata Fandri (iklan judi
        // NX888 & iklan trading kripto palsu ala Binance).
        Source("gambling_scam_ads", HAGEZI_GAMBLING, "hagezi_gambling"),
        Source("gambling_scam_ads", HAGEZI_FAKE, "hagezi_fake"),
        // BARU Audit-8 — varian "medium" gambling.txt, cakupan lebih luas.
        Source("gambling_scam_ads", HAGEZI_GAMBLING_MEDIUM, "hagezi_gambling_medium"),

        // BARU Audit-8 (lihat komentar HAGEZI_ANTI_PIRACY dkk. di atas
        // untuk daftar yang SENGAJA tidak didaftarkan & alasannya).
        Source("anti_piracy", HAGEZI_ANTI_PIRACY, "hagezi_anti_piracy"),
        Source("url_shortener_guard", HAGEZI_URLSHORTENER, "hagezi_urlshortener"),
        // multi.txt & ultimate.txt: list umum HaGeZi paling komprehensif,
        // otomatis dilindungi ESSENTIAL_ALLOWLIST di BlocklistEngine
        // (keduanya terverifikasi berisi graph.facebook.com/graph.instagram.com/
        // graph.whatsapp.com/gateway.instagram.com — lihat CHANGELOG-v2.md §Audit-8).
        Source("trackers", HAGEZI_MULTI, "hagezi_multi"),
        Source("trackers", HAGEZI_ULTIMATE, "hagezi_ultimate"),
        Source("trackers", HAGEZI_NATIVE_XIAOMI, "hagezi_native_xiaomi"),
        // Default filter OFF (lihat FilterOption.kt) — user harus aktifkan manual.
        Source("doh_bypass_guard", HAGEZI_DOH_VPN_PROXY_BYPASS, "hagezi_doh_vpn_proxy_bypass")
    )

    /** Kategori unik yang dikenal — dipakai untuk inisialisasi struktur kosong sebelum load pertama. */
    val ALL_CATEGORY_IDS: List<String> = ALL_SOURCES.map { it.categoryId }.distinct()
}
