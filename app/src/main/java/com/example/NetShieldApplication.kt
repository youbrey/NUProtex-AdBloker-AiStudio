package com.example

import android.app.Application
import com.example.repository.DnsEngineRepository

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
 */
class NetShieldApplication : Application() {

    val dnsEngineRepository: DnsEngineRepository by lazy {
        DnsEngineRepository(applicationContext)
    }
}
