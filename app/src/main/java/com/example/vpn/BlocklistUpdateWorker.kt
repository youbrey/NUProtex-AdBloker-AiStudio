package com.example.vpn

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import com.example.NetShieldApplication
import java.util.concurrent.TimeUnit

/**
 * Audit-9: penjadwal update blocklist HaGeZi/StevenBlack/URLhaus OTOMATIS
 * di background, menjawab permintaan user: "aplikasi selalu up to date,
 * tinggal klik tombol update, host updatan HaGeZi bisa otomatis masuk
 * database blocklist".
 *
 * Sebelum ini, `BlocklistUpdateManager.updateAll()` HANYA terpanggil lewat
 * tombol manual "Perbarui Database" di UI (`DnsEngineRepository.updateThreatDatabase()`)
 * — tidak ada mekanisme berkala otomatis, persis seperti dicatat sebagai
 * kerja lanjutan di dokumentasi kelas itu ("bisa dijadwalkan berkala, mis.
 * WorkManager, di fase produksi selanjutnya").
 *
 * Kenapa WorkManager (bukan sekadar `Handler.postDelayed`/coroutine timer
 * biasa di dalam service): WorkManager dijamin sistem Android tetap
 * berjalan walau app di-kill/di-swipe dari recent apps ATAU device reboot
 * (lewat `WorkManager.getInstance(context)` yang otomatis re-schedule
 * setelah `BOOT_COMPLETED`, tidak perlu broadcast receiver manual
 * terpisah) — cocok untuk tugas berkala jangka panjang seperti ini,
 * berbeda dari `NetShieldVpnService` yang HARUS jalan terus selama
 * proteksi aktif (makanya itu tetap Foreground Service biasa, bukan Work).
 *
 * Interval dipilih 24 jam — cocok dengan header `! Expires: 1 day` yang
 * konsisten muncul di semua file HaGeZi terbaru (lihat lampiran user),
 * jadi update lebih sering dari itu tidak akan mendapat data yang lebih
 * baru, hanya boros kuota & baterai user secara sia-sia.
 *
 * Constraint `NetworkType.UNMETERED` TIDAK dipakai (sengaja) — proteksi
 * DNS ini termasuk fitur keamanan, blocklist yang basi (mis. domain
 * malware/phishing baru belum masuk) punya risiko lebih besar daripada
 * memakai beberapa ratus KB kuota seluler user per hari (ukuran unduhan
 * total seluruh source, dilihat dari cek `wc -l`/`du -h` lampiran user,
 * sekitar 15-16 MB TANPA kompresi gzip — OkHttp otomatis minta gzip by
 * default, jadi realistanya jauh lebih kecil di kuota nyata).
 */
class BlocklistUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val manager = BlocklistUpdateManager(applicationContext)
            // Audit-10: samakan dengan tombol manual — hanya unduh kategori
            // yang sedang aktif, bukan seluruh ALL_SOURCES tanpa syarat.
            val enabledIds = (applicationContext as? NetShieldApplication)
                ?.dnsEngineRepository
                ?.filterOptionsSnapshot()
                ?.filter { it.isEnabled }
                ?.map { it.id }
                ?.toSet()
            val result = manager.updateAll(enabledIds)
            if (result.success) {
                Log.d(TAG, "Auto-update blocklist berhasil: ${result.totalDomains} domain, ${result.sourcesUpdated} sumber diperbarui, ${result.sourcesUnchanged} tidak berubah, ${result.sourcesFailed} gagal.")
                Result.success()
            } else {
                Log.w(TAG, "Auto-update blocklist gagal total: ${result.errorMessage}")
                // Result.retry() BUKAN Result.failure(): kegagalan di sini
                // hampir selalu sementara (tidak ada internet saat itu),
                // bukan bug permanen — WorkManager akan coba lagi dengan
                // backoff policy default (exponential, mulai ~30 detik)
                // sebelum kembali menunggu jadwal periodik berikutnya.
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Auto-update blocklist exception: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "BlocklistUpdateWorker"
        private const val UNIQUE_WORK_NAME = "netshield_blocklist_auto_update"

        /**
         * Jadwalkan (atau pastikan sudah terjadwal) update berkala 24 jam.
         * Dipanggil sekali dari `NetShieldApplication.onCreate()` — aman
         * dipanggil berkali-kali (mis. tiap kali app di-restart sistem)
         * berkat `ExistingPeriodicWorkPolicy.KEEP`: jadwal yang SUDAH ADA
         * tidak akan direset/diulang dari awal, hanya dibuat sekali saja
         * secara efektif di seluruh siklus hidup instalasi app.
         */
        fun schedulePeriodicUpdate(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<BlocklistUpdateWorker>(
                24, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
