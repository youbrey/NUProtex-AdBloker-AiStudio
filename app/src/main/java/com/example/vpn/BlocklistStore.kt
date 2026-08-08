package com.example.vpn

/**
 * Struktur in-memory untuk lookup blocklist per-query DNS (Fase 2.2).
 *
 * Kenapa HashSet, bukan Room row-per-domain:
 * Blocklist gabungan bisa berisi ratusan ribu domain. Query Room per DNS
 * request (yang bisa terjadi puluhan kali/detik saat browsing) akan jauh
 * lebih lambat (I/O disk + parsing cursor) dibanding lookup HashSet
 * in-memory (O(1) rata-rata, jauh di bawah target <5ms yang disyaratkan
 * RENCANA_PRODUKSI_NETSHIELD.md §Fase 2.2).
 *
 * Domain disimpan per kategori (`Map<kategori, HashSet<domain>>`) supaya
 * [BlocklistEngine] bisa tahu kategori mana yang match dan mengecek toggle
 * on/off filter kategori tersebut ([com.example.model.FilterOption]).
 *
 * Thread-safety: referensi peta di-swap secara atomik (@Volatile) setiap
 * kali [replaceAll] dipanggil (biasanya dari [BlocklistUpdateManager]
 * setelah selesai parsing sumber baru). Pembaca (packet loop di
 * [PacketTunnel]) selalu melihat snapshot peta yang konsisten — tidak
 * pernah membaca peta yang sedang ditulis separuh jalan.
 *
 * === CHANGELOG ===
 * [Fase 2 - 2026-08-07] Baru dibuat, menggantikan
 * `Map<String,String>` hardcoded kecil di BlocklistEngine (Fase 1 seed).
 * [Fase 2.7 - 2026-08-08] Tambah kategori `gambling_scam_ads` ke
 * CATEGORY_PRIORITY (prioritas tinggi, setelah malware/phishing) —
 * lihat CHANGELOG.md & RENCANA_PRODUKSI_NETSHIELD.md §Fase 2.7.
 */
object BlocklistStore {

    @Volatile
    private var domainsByCategory: Map<String, Set<String>> = emptyMap()

    @Volatile
    private var lastUpdatedAtMillis: Long = 0L

    @Volatile
    private var loadedVersionLabel: String = "belum pernah diperbarui"

    /**
     * Ganti seluruh isi store dengan hasil parsing baru. Dipanggil setelah
     * [BlocklistUpdateManager] selesai mengunduh & mem-parsing semua sumber.
     * Operasi ini atomik dari sudut pandang pembaca (single volatile write).
     */
    fun replaceAll(newDomainsByCategory: Map<String, Set<String>>, versionLabel: String) {
        domainsByCategory = newDomainsByCategory
        lastUpdatedAtMillis = System.currentTimeMillis()
        loadedVersionLabel = versionLabel
    }

    // Urutan prioritas kategori saat pencocokan domain:
    // Keamanan (Malware/Phishing/Fingerprinting) + Judi&Scam diperiksa LEBIH
    // DULU sebelum kategori iklan umum, agar domain berisiko finansial/
    // penipuan tercatat & terdeteksi dengan tepat (Fase 2.7).
    private val CATEGORY_PRIORITY = listOf(
        "malware_guard",
        "phishing_guard",
        "gambling_scam_ads",
        "fingerprint_guard",
        "trackers",
        "social_ads",
        "adult_content",
        "marketplace_ads",
        "game_ads"
    )

    /**
     * Cari kategori yang memuat [domain] atau subdomain utamanya
     * (hierarchy matching: mis. `sdk.applovin.com` -> `applovin.com`).
     * Menggunakan urutan prioritas [CATEGORY_PRIORITY] agar ancaman keamanan
     * dan pelacak tidak tertimpa oleh kategori iklan generik.
     * Dipanggil di jalur kritis packet loop, cepat & non-blocking.
     */
    fun categoryFor(domain: String): String? {
        val snapshot = domainsByCategory
        if (snapshot.isEmpty()) return null

        var curr = domain.lowercase().trim().removeSuffix(".")
        while (curr.isNotEmpty()) {
            for (cat in CATEGORY_PRIORITY) {
                val set = snapshot[cat]
                if (set != null && set.contains(curr)) {
                    return cat
                }
            }
            for ((cat, set) in snapshot) {
                if (!CATEGORY_PRIORITY.contains(cat) && set.contains(curr)) {
                    return cat
                }
            }

            val dotPos = curr.indexOf('.')
            if (dotPos == -1 || dotPos == curr.lastIndexOf('.')) {
                break
            }
            curr = curr.substring(dotPos + 1)
        }
        return null
    }

    /** Jumlah domain unik pada satu kategori — dipakai FilterOption.ruleCount (Fase 2.6). */
    fun countForCategory(categoryId: String): Int = domainsByCategory[categoryId]?.size ?: 0

    /** Total domain unik di seluruh kategori (bisa dobel-hitung jika satu domain ada di >1 kategori). */
    fun totalDomainCount(): Int = domainsByCategory.values.sumOf { it.size }

    fun isEmpty(): Boolean = domainsByCategory.isEmpty()

    fun lastUpdatedAt(): Long = lastUpdatedAtMillis

    fun versionLabel(): String = loadedVersionLabel
}
