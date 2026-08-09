package com.example

import android.app.Application
import com.example.repository.DnsEngineRepository
import com.example.vpn.BlocklistUpdateWorker

/**
 * Custom Application class.
 *
 * Sebelumnya [DnsEngineRepository] dibuat langsung di dalam
 * NetShieldViewModel (`DnsEngineRepository(application)`), yang berarti
 * setiap kali ViewModel baru dibuat, sebuah CoroutineScope + coroutine job
 * simulasi/uptime baru ikut dibuat tanpa pernah dibatalkan (leak).
 *
 * Dengan menaruh repository di sini sebagai singleton yang mengikuti
 * lifecycle proses aplikasi, kita menjamin hanya ada SATU instance
 * repository, SATU CoroutineScope, dan SATU set job aktif selama aplikasi
 * berjalan.
 *
 * === CHANGELOG ===
 * [Fase 0 - 2026-08-07] Dibuat baru. Lihat CHANGELOG.md root project &
 * RENCANA_PRODUKSI_NETSHIELD.md §Fase 0 untuk detail rasional.
 * [Audit-9 - 2026-08-09] Ditambahkan `onCreate()` yang memanggil
 * `BlocklistUpdateWorker.schedulePeriodicUpdate()` — blocklist HaGeZi/
 * StevenBlack/URLhaus sekarang otomatis diperbarui setiap 24 jam di
 * background (WorkManager), tidak lagi HANYA lewat tombol manual
 * "Perbarui Database". Lihat CHANGELOG-v2.md §Audit-9 & dokumentasi
 * lengkap di BlocklistUpdateWorker.kt.
 */
class NetShieldApplication : Application() {

    val dnsEngineRepository: DnsEngineRepository by lazy {
        DnsEngineRepository(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
        // Audit-9: idempoten (ExistingPeriodicWorkPolicy.KEEP) — aman
        // dipanggil setiap kali proses app dimulai ulang oleh sistem.
        BlocklistUpdateWorker.schedulePeriodicUpdate(applicationContext)
    }
}
