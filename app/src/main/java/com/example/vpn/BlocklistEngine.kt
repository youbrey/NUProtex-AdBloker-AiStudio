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
        // Mobile Game Ads SDKs & In-App Ad Networks (game_ads)
        "doubleclick.net" to "game_ads",
        "googleads.g.doubleclick.net" to "game_ads",
        "pagead2.googlesyndication.com" to "game_ads",
        "adservice.google.com" to "game_ads",
        "unity3d.com" to "game_ads",
        "unityads.unity3d.com" to "game_ads",
        "config.unityads.unity3d.com" to "game_ads",
        "auction.unityads.unity3d.com" to "game_ads",
        "applovin.com" to "game_ads",
        "applvin.com" to "game_ads",
        "advertisement.applovin.com" to "game_ads",
        "a.applovin.com" to "game_ads",
        "sdk.applovin.com" to "game_ads",
        "ms.applovin.com" to "game_ads",
        "vungle.com" to "game_ads",
        "ads.vungle.com" to "game_ads",
        "init.vungle.com" to "game_ads",
        "ironsrc.com" to "game_ads",
        "is.com" to "game_ads",
        "supersonicads.com" to "game_ads",
        "init.supersonicads.com" to "game_ads",
        "outcome-ssp.supersonicads.com" to "game_ads",
        "inmobi.com" to "game_ads",
        "config.inmobi.com" to "game_ads",
        "sdkm.inmobi.com" to "game_ads",
        "chartboost.com" to "game_ads",
        "live.chartboost.com" to "game_ads",
        "mbridge.io" to "game_ads",
        "mintegral.com" to "game_ads",
        "cdn-adn.mbridge.io" to "game_ads",
        "tapjoy.com" to "game_ads",
        "rpc.tapjoy.com" to "game_ads",
        "ads.tapjoy.com" to "game_ads",
        "fyber.com" to "game_ads",
        "inner-active.mobi" to "game_ads",
        "liftoff.io" to "game_ads",
        "pangle.io" to "game_ads",
        "pangle-ads.com" to "game_ads",
        "i18n-pglstatp.pangle.io" to "game_ads",
        "aniview.com" to "game_ads",
        "adcolony.com" to "game_ads",
        "mopub.com" to "game_ads",
        "smaato.net" to "game_ads",
        "admob.com" to "game_ads",
        "criteo.com" to "game_ads",
        "rubiconproject.com" to "game_ads",
        "openx.net" to "game_ads",
        "pubmatic.com" to "game_ads",
        "taboola.com" to "game_ads",
        "outbrain.com" to "game_ads",

        // Marketplace & Promo Ads (marketplace_ads)
        "shopee-promo-ads.net" to "marketplace_ads",
        "tokopedia-tracker.info" to "marketplace_ads",
        "lazada-tracker.com" to "marketplace_ads",
        "s.amazon-adsystem.com" to "marketplace_ads",
        "amazon-adsystem.com" to "marketplace_ads",

        // Anti-Tracking, Telemetry & Analytics (trackers)
        "analytics.facebook.com" to "trackers",
        "telemetry.microsoft.com" to "trackers",
        "graph.facebook.com" to "trackers",
        "an.facebook.com" to "trackers",
        "pixel.facebook.com" to "trackers",
        "app-measurement.com" to "trackers",
        "appsflyer.com" to "trackers",
        "t.appsflyer.com" to "trackers",
        "events.appsflyer.com" to "trackers",
        "adjust.com" to "trackers",
        "app.adjust.com" to "trackers",
        "app.adjust.io" to "trackers",
        "branch.io" to "trackers",
        "api2.branch.io" to "trackers",
        "kochava.com" to "trackers",
        "singular.net" to "trackers",
        "gameanalytics.com" to "trackers",
        "mixpanel.com" to "trackers",
        "amplitude.com" to "trackers",
        "flurry.com" to "trackers",
        "segment.io" to "trackers",
        "crashlytics.com" to "trackers",

        // Fingerprinting & Profiling (fingerprint_guard)
        "fingerprintjs.com" to "fingerprint_guard",
        "fpjs.io" to "fingerprint_guard",
        "api.fpjs.io" to "fingerprint_guard",
        "device-id.com" to "fingerprint_guard",
        "threatmetrix.com" to "fingerprint_guard",
        "iovation.com" to "fingerprint_guard",
        "seon.io" to "fingerprint_guard",
        "siftscience.com" to "fingerprint_guard",
        "sift.com" to "fingerprint_guard",
        "scorecardresearch.com" to "fingerprint_guard",
        "quantserve.com" to "fingerprint_guard",

        // Social Trackers (social_ads)
        "analytics.tiktok.com" to "social_ads",
        "log.tiktokv.com" to "social_ads",
        "connect.facebook.net" to "social_ads",

        // Malware & Phishing Guard (malware_guard / phishing_guard)
        "phish-login-bank-id.online" to "phishing_guard",
        "trojan-downloader-apk.net" to "malware_guard",
        "phishing-bank-login.xyz" to "phishing_guard",
        "crypto-drainer-scam.net" to "phishing_guard",
        "malware-payload-installer.info" to "malware_guard",
        "ransomware-c2-server.top" to "malware_guard"
    )

    /** Kategori (filter id) yang termasuk indikasi ancaman keamanan — dipakai untuk notifikasi & log ancaman. */
    const val CATEGORY_MALWARE_GUARD = "malware_guard"
    const val CATEGORY_PHISHING_GUARD = "phishing_guard"
    const val CATEGORY_FINGERPRINT_GUARD = "fingerprint_guard"

    data class Decision(val isBlocked: Boolean, val category: String)

    /**
     * Evaluasi keputusan blokir untuk [domain].
     * Urutan prioritas (sesuai dokumentasi, Fase 2.4):
     * 1. Custom rule user (blacklist/whitelist manual) SELALU menang atas apa pun.
     * 2. BlocklistStore (blocklist nyata hasil unduhan, Fase 2.2-2.3).
     * 3. SEED_BLOCKED_DOMAINS (fallback offline-pertama-kali).
     */
    fun evaluate(
        domain: String,
        customRules: List<CustomRuleEntity>,
        filterOptions: List<FilterOption>
    ): Decision {
        val normalized = domain.lowercase().trim().removeSuffix(".")

        // 1. Custom Rules check (exact or subdomain match)
        val customMatch = customRules.firstOrNull { rule ->
            if (!rule.isEnabled) return@firstOrNull false
            val rDomain = rule.domain.lowercase().trim().removeSuffix(".")
            normalized == rDomain || normalized.endsWith(".$rDomain")
        }
        if (customMatch != null) {
            return Decision(customMatch.isBlocked, customMatch.category)
        }

        // 2. BlocklistStore (check domain and parent domain hierarchy)
        val storeCategory = BlocklistStore.categoryFor(normalized)
        if (storeCategory != null) {
            val enabled = filterOptions.firstOrNull { it.id == storeCategory }?.isEnabled ?: true
            return Decision(enabled, storeCategory)
        }

        // 3. Fallback SEED_BLOCKED_DOMAINS (check domain and parent domain hierarchy)
        var curr = normalized
        while (curr.isNotEmpty()) {
            val seedCategory = SEED_BLOCKED_DOMAINS[curr]
            if (seedCategory != null) {
                val enabled = filterOptions.firstOrNull { it.id == seedCategory }?.isEnabled ?: true
                return Decision(enabled, seedCategory)
            }
            val dotPos = curr.indexOf('.')
            if (dotPos == -1 || dotPos == curr.lastIndexOf('.')) {
                break
            }
            curr = curr.substring(dotPos + 1)
        }

        return Decision(isBlocked = false, category = "Normal")
    }
}
