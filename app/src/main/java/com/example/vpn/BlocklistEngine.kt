package com.example.vpn

import com.example.data.local.CustomRuleEntity
import com.example.model.FilterOption

/**
 * Mesin keputusan "apakah domain ini diblokir?" yang dipakai packet loop
 * VpnService secara sinkron (harus cepat, dipanggil per-query DNS).
 *
 * STATUS (Fase 2): sumber utama sekarang [BlocklistStore] (hash-set
 * in-memory, ratusan ribu domain dari sumber nyata — lihat
 * [BlocklistSource] & [BlocklistUpdateManager]). [SEED_BLOCKED_DOMAINS]
 * TETAP dipertahankan sebagai fallback kecil HANYA untuk kondisi
 * BlocklistStore masih kosong (instalasi pertama, belum pernah online
 * sama sekali & belum ada cache disk) — supaya app tidak 100% tanpa
 * proteksi sama sekali sebelum unduhan pertama selesai.
 *
 * === CHANGELOG ===
 * [Fase 1 - 2026-08-07] Baru dibuat sebagai jembatan sementara supaya
 * Fase 1 (packet interception) bisa diverifikasi end-to-end tanpa
 * menunggu Fase 2 (sumber blocklist nyata) selesai.
 * [Fase 2 - 2026-08-07] evaluate() kini mengecek [BlocklistStore] (hasil
 * unduhan blocklist nyata) terlebih dahulu; SEED_BLOCKED_DOMAINS
 * diturunkan statusnya jadi fallback offline-pertama-kali saja, bukan
 * lagi sumber utama. Lihat RENCANA_PRODUKSI_NETSHIELD.md §Fase 2.
 */
object BlocklistEngine {

    // Fallback offline pertama kali saja (lihat dokumentasi kelas di atas).
    // BUKAN lagi sumber utama sejak Fase 2 — sumber utama: BlocklistStore.
    private val SEED_BLOCKED_DOMAINS: Map<String, String> = mapOf(
        "ads.doubleclick.net" to "game_ads",
        "googleads.g.doubleclick.net" to "game_ads",
        "unityads.unity3d.com" to "game_ads",
        "advertisement.applovin.com" to "game_ads",
        "shopee-promo-ads.net" to "marketplace_ads",
        "tokopedia-tracker.info" to "marketplace_ads",
        "analytics.facebook.com" to "trackers",
        "telemetry.microsoft.com" to "trackers",
        "graph.facebook.com" to "trackers",
        "app-measurement.com" to "trackers",
        "phish-login-bank-id.online" to "malware_guard",
        "trojan-downloader-apk.net" to "malware_guard",
        "phishing-bank-login.xyz" to "malware_guard",
        "crypto-drainer-scam.net" to "malware_guard",
        "malware-payload-installer.info" to "malware_guard",
        "ransomware-c2-server.top" to "malware_guard"
    )

    /** Kategori (filter id) yang termasuk indikasi ancaman keamanan — dipakai untuk notifikasi & log ancaman. */
    const val CATEGORY_MALWARE_GUARD = "malware_guard"
    const val CATEGORY_PHISHING_GUARD = "phishing_guard"

    data class Decision(val isBlocked: Boolean, val category: String)

    /**
     * Evaluasi keputusan blokir untuk [domain].
     * Urutan prioritas (sesuai dokumentasi, Fase 2.4):
     * 1. Custom rule user (blacklist/whitelist manual) SELALU menang atas
     *    apa pun — whitelist manual harus tetap tembus walau ada di
     *    blocklist umum, blacklist manual harus tetap diblokir walau
     *    kategori bawaannya nonaktif.
     * 2. BlocklistStore (blocklist nyata hasil unduhan, Fase 2.2-2.3).
     * 3. SEED_BLOCKED_DOMAINS (fallback offline-pertama-kali, lihat dok kelas).
     */
    fun evaluate(
        domain: String,
        customRules: List<CustomRuleEntity>,
        filterOptions: List<FilterOption>
    ): Decision {
        val normalized = domain.lowercase().removeSuffix(".")

        val customMatch = customRules.firstOrNull {
            it.isEnabled && it.domain.equals(normalized, ignoreCase = true)
        }
        if (customMatch != null) {
            return Decision(customMatch.isBlocked, customMatch.category)
        }

        val storeCategory = BlocklistStore.categoryFor(normalized)
        if (storeCategory != null) {
            val enabled = filterOptions.firstOrNull { it.id == storeCategory }?.isEnabled ?: true
            return Decision(enabled, storeCategory)
        }

        // Fallback hanya dipakai saat BlocklistStore benar-benar masih kosong.
        if (BlocklistStore.isEmpty()) {
            val seedCategory = SEED_BLOCKED_DOMAINS[normalized]
            if (seedCategory != null) {
                val enabled = filterOptions.firstOrNull { it.id == seedCategory }?.isEnabled ?: true
                return Decision(enabled, seedCategory)
            }
        }

        return Decision(isBlocked = false, category = "Normal")
    }
}
