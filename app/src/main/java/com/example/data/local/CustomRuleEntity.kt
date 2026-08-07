package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * === CHANGELOG ===
 * [Fase 2 - 2026-08-07] Ditambahkan unique index pada kolom `domain`
 * (Fase 2.5) supaya `OnConflictStrategy.REPLACE` di
 * NetShieldDao.insertCustomRule() benar-benar mengganti (bukan
 * menduplikasi) rule untuk domain yang sama. Menaikkan versi Room DB
 * dari 1 -> 2 (lihat NetShieldDatabase.kt) — migrasi memakai
 * fallbackToDestructiveMigration() untuk sementara (didokumentasikan
 * sebagai utang teknis yang WAJIB diganti Migration eksplisit sebelum
 * rilis, lihat RENCANA_PRODUKSI_NETSHIELD.md §Fase 6.4).
 */
@Entity(tableName = "custom_rules", indices = [Index(value = ["domain"], unique = true)])
data class CustomRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val domain: String,
    val isBlocked: Boolean, // true = Blacklist (Blokir), false = Whitelist (Izinkan)
    val isEnabled: Boolean = true,
    val category: String = "Kustom",
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
