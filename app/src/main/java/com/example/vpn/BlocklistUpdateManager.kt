package com.example.vpn

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Mekanisme update blocklist BERKALA & NYATA (Fase 2.3), menggantikan
 * `DnsEngineRepository.updateThreatDatabase()` lama yang cuma
 * `delay(2200)` + increment angka statis.
 *
 * Alur:
 * 1. Unduh setiap [BlocklistSource.ALL_SOURCES] via OkHttp (sudah ada di
 *    dependencies project — lihat app/build.gradle.kts).
 * 2. Hitung checksum SHA-256 konten yang diunduh.
 * 3. Bandingkan dengan checksum tersimpan (SharedPreferences) dari unduhan
 *    sebelumnya — jika sama, skip parse ulang (hemat CPU/baterai).
 * 4. Jika berbeda (atau belum pernah ada), parse format hosts, simpan
 *    salinan mentah ke cache file lokal (`filesDir/blocklist_cache/`), dan
 *    catat checksum + timestamp baru.
 * 5. Gabungkan hasil parsing seluruh sumber ke [BlocklistStore] (per
 *    kategori) via [BlocklistStore.replaceAll] — atomik, aman dibaca
 *    packet loop yang sedang berjalan.
 *
 * Saat app start (sebelum sempat unduh dari internet, mis. mode pesawat),
 * [loadFromDiskCacheIfAvailable] memuat salinan cache lokal terakhir agar
 * blocklist tetap aktif walau offline.
 *
 * === CHANGELOG ===
 * [Fase 2 - 2026-08-07] Baru dibuat. Implementasi Fase 2.1-2.3.
 * Menggantikan `updateThreatDatabase()` versi delay() palsu di
 * DnsEngineRepository — lihat pemanggilan baru di sana.
 * [Fase 2.7 - 2026-08-08] `parseHostsFile()` diperluas jadi multi-format
 * (hosts / AdBlock `||domain^` / domain polos) — lihat CHANGELOG.md &
 * RENCANA_PRODUKSI_NETSHIELD.md §Fase 2.7. Menambahkan dukungan sumber
 * HaGeZi (folder `domains/`), blocklist global paling komprehensif saat
 * ini yang mencakup gambling/scam/fake secara eksplisit — lihat
 * BlocklistSource.kt.
 */
class BlocklistUpdateManager(context: Context) {

    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.filesDir, "blocklist_cache").apply { mkdirs() }
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class UpdateResult(
        val success: Boolean,
        val totalDomains: Int,
        val sourcesUpdated: Int,
        val sourcesUnchanged: Int,
        val sourcesFailed: Int,
        val versionLabel: String,
        val errorMessage: String? = null
    )

    /**
     * Muat blocklist dari cache disk (tanpa jaringan) — dipanggil saat
     * repository/VpnService start, supaya proteksi tetap aktif dari
     * unduhan sebelumnya walau device sedang offline.
     * Jika belum ada cache sama sekali (instalasi pertama, belum pernah
     * online), [BlocklistStore] tetap kosong — [BlocklistEngine] akan
     * fallback ke seed kecil (lihat dokumentasi BlocklistEngine).
     */
    suspend fun loadFromDiskCacheIfAvailable() = withContext(Dispatchers.IO) {
        val byCategory = mutableMapOf<String, MutableSet<String>>()
        var anyLoaded = false
        for (source in BlocklistSource.ALL_SOURCES) {
            val file = cacheFile(source)
            if (!file.exists()) continue
            try {
                val domains = parseHostsFile(file.readText())
                byCategory.getOrPut(source.categoryId) { mutableSetOf() }.addAll(domains)
                anyLoaded = true
            } catch (e: IOException) {
                Log.w(TAG, "Gagal baca cache lokal ${source.cacheKey}: ${e.message}")
            }
        }
        if (anyLoaded) {
            val versionLabel = prefs.getString(KEY_LAST_VERSION_LABEL, "cache lokal") ?: "cache lokal"
            BlocklistStore.replaceAll(byCategory.mapValues { it.value.toSet() }, versionLabel)
            Log.d(TAG, "Blocklist dimuat dari cache disk: ${BlocklistStore.totalDomainCount()} domain.")
        }
    }

    /**
     * Unduh & perbarui seluruh sumber blocklist dari internet (Fase 2.3).
     * Dipanggil dari `DnsEngineRepository.updateThreatDatabase()` (dipicu
     * user lewat tombol "Perbarui Database" di UI) DAN dari
     * `BlocklistUpdateWorker` (Audit-9) yang menjadwalkannya otomatis
     * setiap 24 jam di background via WorkManager.
     *
     * @param enabledCategoryIds Audit-10: kategori (filter id) yang SEDANG
     * aktif di [com.example.model.FilterOption]. Sumber yang kategorinya
     * TIDAK ada di sini di-skip total (tidak diunduh, tidak diparsing,
     * tidak disimpan) — sebelumnya SEMUA source di [BlocklistSource.ALL_SOURCES]
     * diunduh & disimpan ke RAM tanpa syarat, termasuk kategori yang user
     * matikan (mis. `doh_bypass_guard`, default OFF, 17 ribu domain
     * tersimpan sia-sia 24/7). Kontributor bloat memori yang jadi salah
     * satu root cause laporan "internet lambat saat nonton Reels" — lihat
     * CHANGELOG-v2.md §Audit-10. `null` (default) = perilaku lama, unduh
     * semua (dipakai test/pemanggilan tanpa akses ke filter state).
     */
    suspend fun updateAll(enabledCategoryIds: Set<String>? = null): UpdateResult = withContext(Dispatchers.IO) {
        var updated = 0
        var unchanged = 0
        var failed = 0

        val byCategory = mutableMapOf<String, MutableSet<String>>()
        val sourcesToFetch = if (enabledCategoryIds == null) {
            BlocklistSource.ALL_SOURCES
        } else {
            BlocklistSource.ALL_SOURCES.filter { it.categoryId in enabledCategoryIds }
        }
        val skippedCount = BlocklistSource.ALL_SOURCES.size - sourcesToFetch.size
        if (skippedCount > 0) {
            Log.d(TAG, "Audit-10: $skippedCount sumber di-skip (kategori nonaktif) — hemat bandwidth & memori.")
        }

        try {
            coroutineScope {
                val deferreds = sourcesToFetch.map { source ->
                    async { source to fetchAndCacheSource(source) }
                }
                val results = deferreds.awaitAll()
                for ((source, outcome) in results) {
                    when (outcome) {
                        is FetchOutcome.Updated -> {
                            updated++
                            byCategory.getOrPut(source.categoryId) { mutableSetOf() }.addAll(outcome.domains)
                        }
                        is FetchOutcome.Unchanged -> {
                            unchanged++
                            byCategory.getOrPut(source.categoryId) { mutableSetOf() }.addAll(outcome.domains)
                        }
                        is FetchOutcome.Failed -> {
                            failed++
                            Log.w(TAG, "Sumber ${source.cacheKey} gagal diunduh: ${outcome.reason}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            return@withContext UpdateResult(
                success = false, totalDomains = 0, sourcesUpdated = updated,
                sourcesUnchanged = unchanged, sourcesFailed = failed,
                versionLabel = BlocklistStore.versionLabel(),
                errorMessage = e.message
            )
        }

        // Jika SEMUA sumber gagal (mis. tidak ada internet) dan store sudah
        // punya isi dari cache sebelumnya, jangan timpa dengan peta kosong.
        if (byCategory.isEmpty()) {
            return@withContext UpdateResult(
                success = false, totalDomains = BlocklistStore.totalDomainCount(),
                sourcesUpdated = 0, sourcesUnchanged = 0, sourcesFailed = failed,
                versionLabel = BlocklistStore.versionLabel(),
                errorMessage = "Semua sumber gagal diunduh, blocklist lama (jika ada) dipertahankan."
            )
        }

        val versionLabel = buildVersionLabel()
        prefs.edit().putString(KEY_LAST_VERSION_LABEL, versionLabel).apply()

        val finalMap = byCategory.mapValues { it.value.toSet() }
        BlocklistStore.replaceAll(finalMap, versionLabel)

        UpdateResult(
            success = true,
            totalDomains = BlocklistStore.totalDomainCount(),
            sourcesUpdated = updated,
            sourcesUnchanged = unchanged,
            sourcesFailed = failed,
            versionLabel = versionLabel
        )
    }

    private sealed class FetchOutcome {
        data class Updated(val domains: Set<String>) : FetchOutcome()
        data class Unchanged(val domains: Set<String>) : FetchOutcome()
        data class Failed(val reason: String) : FetchOutcome()
    }

    private fun fetchAndCacheSource(source: BlocklistSource.Source): FetchOutcome {
        return try {
            val request = Request.Builder().url(source.url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return FetchOutcome.Failed("HTTP ${response.code}")
                }
                val body = response.body?.string() ?: return FetchOutcome.Failed("Body kosong")
                val newChecksum = sha256(body)
                val checksumKey = KEY_CHECKSUM_PREFIX + source.cacheKey
                val previousChecksum = prefs.getString(checksumKey, null)

                val file = cacheFile(source)
                if (newChecksum == previousChecksum && file.exists()) {
                    // Konten tidak berubah sejak unduhan terakhir — tidak perlu tulis ulang file.
                    return FetchOutcome.Unchanged(parseHostsFile(file.readText()))
                }

                file.writeText(body)
                prefs.edit()
                    .putString(checksumKey, newChecksum)
                    .putLong(KEY_TIMESTAMP_PREFIX + source.cacheKey, System.currentTimeMillis())
                    .apply()

                FetchOutcome.Updated(parseHostsFile(body))
            }
        } catch (e: IOException) {
            FetchOutcome.Failed(e.message ?: "IOException")
        } catch (e: Exception) {
            FetchOutcome.Failed(e.message ?: "Exception tak dikenal")
        }
    }

    /**
     * Parser multi-format untuk sumber blocklist:
     *  1. Format hosts standar: `0.0.0.0 domain.tld` / `127.0.0.1 domain.tld`
     *  2. Format AdBlock Plus: `||domain.tld^` (dipakai sebagian sumber HaGeZi)
     *  3. Format domain polos: satu domain per baris tanpa prefix apa pun
     *     (dipakai folder `domains/` HaGeZi & beberapa daftar publik lain) —
     *     ditambahkan Fase 2.7 untuk memperluas cakupan sumber blocklist
     *     global tanpa terbatas hanya pada format hosts-file lama.
     * Mengabaikan baris kosong, komentar (`#`/`!`), dan entri localhost
     * bawaan (localhost, broadcasthost, dst.) yang lazim ada di awal file
     * hosts.
     *
     * === CHANGELOG ===
     * [Fase 2.7 - 2026-08-08] Diperluas dari hanya format hosts menjadi
     * multi-format (AdBlock & domain polos), supaya sumber HaGeZi
     * (folder `domains/`) — blocklist global paling komprehensif saat ini,
     * mencakup gambling/scam/fake secara eksplisit — bisa dipakai tanpa
     * perlu parser terpisah. Lihat RENCANA_PRODUKSI_NETSHIELD.md §Fase 2.7.
     */
    private fun parseHostsFile(content: String): Set<String> {
        val result = HashSet<String>(content.length / 20) // estimasi kapasitas awal
        for (rawLine in content.lineSequence()) {
            var line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue
            line = line.substringBefore('#').trim()
            if (line.isEmpty()) continue

            val domain: String? = when {
                // Format hosts: "0.0.0.0 domain" / "127.0.0.1 domain"
                line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ") -> {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 2) parts[1].lowercase().trim() else null
                }
                // Format AdBlock Plus: "||domain.tld^" (abaikan modifier setelah '$' jika ada)
                line.startsWith("||") -> {
                    line.removePrefix("||")
                        .substringBefore('^')
                        .substringBefore('$')
                        .lowercase()
                        .trim()
                }
                // Format domain polos (folder domains/ HaGeZi, OISD, dll.):
                // satu baris = satu domain, tanpa spasi, minimal 1 titik.
                !line.contains(' ') && line.contains('.') && DOMAIN_REGEX.matches(line) -> {
                    line.lowercase().trim()
                }
                else -> null
            }

            if (domain != null && domain.isNotEmpty() && domain !in IGNORED_HOSTNAMES) {
                result.add(domain)
            }
        }
        return result
    }

    private fun cacheFile(source: BlocklistSource.Source): File = File(cacheDir, "${source.cacheKey}.txt")

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun buildVersionLabel(): String {
        val formatter = java.text.SimpleDateFormat("yyyy.MM.dd-HHmm", java.util.Locale("id", "ID"))
        return "${formatter.format(java.util.Date())} (${BlocklistSource.ALL_SOURCES.size} sumber)"
    }

    companion object {
        private const val TAG = "BlocklistUpdateManager"
        private const val PREFS_NAME = "netshield_blocklist_prefs"
        private const val KEY_CHECKSUM_PREFIX = "checksum_"
        private const val KEY_TIMESTAMP_PREFIX = "timestamp_"
        private const val KEY_LAST_VERSION_LABEL = "last_version_label"
        private val IGNORED_HOSTNAMES = setOf(
            "localhost", "localhost.localdomain", "local", "broadcasthost",
            "ip6-localhost", "ip6-loopback", "ip6-localnet", "ip6-mcastprefix",
            "ip6-allnodes", "ip6-allrouters", "ip6-allhosts", "0.0.0.0"
        )
        // Validasi longgar untuk baris "domain polos" (Fase 2.7): label
        // alfanumerik+strip dipisah titik, TLD minimal 2 huruf. Cukup untuk
        // menyaring baris yang jelas bukan domain (mis. IPv6 literal, teks
        // metadata) tanpa perlu validasi RFC 1035 penuh.
        private val DOMAIN_REGEX = Regex(
            "^(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}$"
        )
    }
}
