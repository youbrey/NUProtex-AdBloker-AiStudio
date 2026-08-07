package com.example.model

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
                title = "Blokir Iklan Game Fully",
                description = "Hentikan popup, interstitial, & reward video ads di semua game Android",
                isEnabled = true,
                iconRes = "sports_esports",
                ruleCount = 0,
                badgeColorHex = "#FF4081"
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
