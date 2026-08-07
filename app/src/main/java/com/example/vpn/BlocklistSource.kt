package com.example.vpn

/**
 * Sumber blocklist domain NYATA per kategori filter (Fase 2.1).
 *
 * Semua URL di bawah menunjuk ke daftar publik dalam format "hosts file"
 * standar (baris `0.0.0.0 domain.tld` atau `127.0.0.1 domain.tld`),
 * di-parse oleh [BlocklistUpdateManager].
 *
 * KETERBATASAN YANG DIDOKUMENTASIKAN SECARA TRANSPARAN (sesuai prinsip
 * kerja §5 RENCANA_PRODUKSI_NETSHIELD.md):
 * Tidak ada sumber publik gratis yang memecah domain iklan per SDK app
 * (mis. "iklan khusus game" vs "iklan khusus marketplace"). Karena itu:
 *  - `game_ads` & `marketplace_ads` & `trackers` sama-sama memakai daftar
 *    gabungan ads+tracking StevenBlack (kategori UI berbeda, tapi sumber
 *    domainnya sama — ini pemecahan kategori di level UI/UX, bukan klaim
 *    bahwa sumbernya benar-benar terpisah per app).
 *  - `social_ads` memakai ekstensi "social" resmi dari StevenBlack (domain
 *    tracker/pixel media sosial - Facebook, TikTok, dll.).
 *  - `adult_content` memakai ekstensi "porn" resmi dari StevenBlack.
 *  - `malware_guard` menggabungkan ekstensi "fakenews" StevenBlack (dipakai
 *    di sini murni sebagai sumber domain berkualitas rendah/berisiko
 *    tambahan) dengan URLhaus (abuse.ch) — daftar host malware/C2 yang
 *    benar-benar dikurasi dari data ancaman nyata, bukan iklan.
 *
 * Lisensi: StevenBlack/hosts dirilis di bawah MIT License (cek repo untuk
 * detail terbaru). URLhaus (abuse.ch) hostfile disediakan gratis untuk
 * penggunaan non-komersial/keamanan — cek https://urlhaus.abuse.ch/api/
 * untuk syarat penggunaan sebelum dipakai di build release komersial.
 *
 * === CHANGELOG ===
 * [Fase 2 - 2026-08-07] Baru dibuat. Menggantikan
 * BlocklistEngine.SEED_BLOCKED_DOMAINS hardcoded (Fase 1) dengan definisi
 * sumber blocklist nyata yang bisa diunduh & diperbarui berkala
 * (lihat BlocklistUpdateManager, §Fase 2.3).
 */
object BlocklistSource {

    data class Source(
        val categoryId: String,
        val url: String,
        /** Nama singkat untuk logging/penyimpanan cache lokal, mis. "stevenblack_base". */
        val cacheKey: String
    )

    // StevenBlack/hosts — daftar gabungan ads + tracking + malware dasar.
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

    /** Semua sumber yang perlu diunduh. Satu kategori bisa punya >1 sumber (digabung saat load). */
    val ALL_SOURCES: List<Source> = listOf(
        Source("malware_guard", URLHAUS_HOSTFILE, "urlhaus_hostfile"),
        Source("malware_guard", STEVENBLACK_FAKENEWS, "stevenblack_fakenews"),
        Source("phishing_guard", PHISHING_DATABASE, "phishing_database"),
        Source("fingerprint_guard", STEVENBLACK_BASE, "stevenblack_base"),
        Source("trackers", DISCONNECT_TRACKING, "disconnect_tracking"),
        Source("trackers", STEVENBLACK_BASE, "stevenblack_base"),
        Source("game_ads", ADAWAY_OFFICIAL, "adaway_official"),
        Source("game_ads", DAN_POLLOCK_HOSTS, "dan_pollock_hosts"),
        Source("game_ads", STEVENBLACK_BASE, "stevenblack_base"),
        Source("marketplace_ads", DISCONNECT_AD, "disconnect_ad"),
        Source("social_ads", STEVENBLACK_SOCIAL, "stevenblack_social"),
        Source("adult_content", STEVENBLACK_PORN, "stevenblack_porn")
    )

    /** Kategori unik yang dikenal — dipakai untuk inisialisasi struktur kosong sebelum load pertama. */
    val ALL_CATEGORY_IDS: List<String> = ALL_SOURCES.map { it.categoryId }.distinct()
}
