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

    /**
     * Cari kategori pertama yang memuat [domain] persis (exact match,
     * hosts-file style — bukan wildcard/subdomain matching di fase ini).
     * Dipanggil di jalur kritis packet loop, harus cepat & non-blocking.
     */
    fun categoryFor(domain: String): String? {
        val snapshot = domainsByCategory
        for ((category, set) in snapshot) {
            if (set.contains(domain)) return category
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
