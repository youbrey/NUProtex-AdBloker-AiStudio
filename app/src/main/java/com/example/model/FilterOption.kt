package com.example.model

/**
 * === CHANGELOG ===
 * [Fase 2.7 - 2026-08-08]
 *  - Ditambahkan kategori `gambling_scam_ads` ("Blokir Iklan Judi & Investasi
 *    Palsu") — respons langsung atas laporan Fandri: iklan judi (NX888) &
 *    iklan trading kripto palsu (meniru UI Binance) lolos dari filter lama.
 *  - Teks `game_ads` direvisi (2.8): "Fully"/"semua game Android" dihapus
 *    karena overclaim relatif terhadap batas nyata DNS-blocking (hanya
 *    efektif untuk domain yang ada di database blocklist).
 *  Lihat CHANGELOG.md & RENCANA_PRODUKSI_NETSHIELD.md §Fase 2.7-2.8.
 */

data class FilterOption(
    val id: String,
    val title: String,
    val description: String,
    val isEnabled: Boolean,
    val iconRes: String,
    val ruleCount: Int,
    val badgeColorHex: String = "#00E5FF"
) {
    companion object {
        val DEFAULT_FILTERS = listOf(
            FilterOption(
                id = "malware_guard",
                title = "Proteksi Malware & Ransomware",
                description = "Deteksi & blokir domain malware, ransomware, & C2 botnet secara real-time",
                isEnabled = true,
                iconRes = "security",
                ruleCount = 0,
                badgeColorHex = "#76FF03"
            ),
            FilterOption(
                id = "phishing_guard",
                title = "Anti-Phishing & Penipuan",
                description = "Blokir situs phishing bank, scam kripto, & pencurian identitas",
                isEnabled = true,
                iconRes = "security",
                ruleCount = 0,
                badgeColorHex = "#FF1744"
            ),
            FilterOption(
                id = "game_ads",
                title = "Blokir Iklan Game",
                description = "Kurangi popup, interstitial, & reward video ads di game Android (efektif untuk domain yang ada di database — lihat batasan di Pengaturan)",
                isEnabled = true,
                iconRes = "sports_esports",
                ruleCount = 0,
                badgeColorHex = "#FF4081"
            ),
            FilterOption(
                id = "gambling_scam_ads",
                title = "Blokir Iklan Judi & Investasi Palsu",
                description = "Blokir domain judi online & iklan trading/kripto palsu yang meniru platform resmi (mis. Binance palsu)",
                isEnabled = true,
                iconRes = "money_off",
                ruleCount = 0,
                badgeColorHex = "#FF3D00"
            ),
            FilterOption(
                id = "marketplace_ads",
                title = "Blokir Iklan Marketplace",
                description = "Blokir pelacak promosi & pop-up Shopee, Tokopedia, Lazada, dll.",
                isEnabled = true,
                iconRes = "shopping_bag",
                ruleCount = 0,
                badgeColorHex = "#FF9100"
            ),
            FilterOption(
                id = "trackers",
                title = "Anti-Tracker & Telemetri",
                description = "Cegah aplikasi mengumpulkan lokasi, identitas perangkat, & aktivitas",
                isEnabled = true,
                iconRes = "radar",
                ruleCount = 0,
                badgeColorHex = "#00E5FF"
            ),
            FilterOption(
                id = "fingerprint_guard",
                title = "Blokir Fingerprinting & Profiling",
                description = "Mencegah situs atau aplikasi melakukan device fingerprinting & profiling identitas HP",
                isEnabled = true,
                iconRes = "fingerprint",
                ruleCount = 0,
                badgeColorHex = "#AA00FF"
            ),
            FilterOption(
                id = "adult_content",
                title = "Filter Konten Dewasa & SafeSearch",
                description = "Paksa SafeSearch di peramban & blokir situs tidak pantas",
                isEnabled = false,
                iconRes = "family_restroom",
                ruleCount = 0,
                badgeColorHex = "#E040FB"
            ),
            FilterOption(
                id = "social_ads",
                title = "Blokir Pelacak Media Sosial",
                description = "Blokir pixel tracker Facebook, TikTok, Instagram, & Twitter",
                isEnabled = true,
                iconRes = "public",
                ruleCount = 0,
                badgeColorHex = "#00B0FF"
            )
        )
    }
}
