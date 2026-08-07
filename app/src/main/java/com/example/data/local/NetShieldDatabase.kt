package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * === CHANGELOG ===
 * [Fase 2 - 2026-08-07] version 1 -> 2 karena CustomRuleEntity kini punya
 * unique index pada `domain` (Fase 2.5).
 * [Fase 6.4 - 2026-08-07] Diganti dengan Migration eksplisit (MIGRATION_1_2)
 * menggantikan fallbackToDestructiveMigration(), sehingga data custom rules
 * milik user aman saat pembaruan aplikasi. Lihat RENCANA_PRODUKSI_NETSHIELD.md §Fase 6.4.
 */
@Database(
    entities = [DnsLogEntity::class, CustomRuleEntity::class, ThreatEventEntity::class],
    version = 2,
    exportSchema = false
)
abstract class NetShieldDatabase : RoomDatabase() {

    abstract fun dao(): NetShieldDao

    companion object {
        @Volatile
        private var INSTANCE: NetShieldDatabase? = null

        /**
         * Migrasi dari DB v1 ke v2:
         * Menambahkan unique index pada kolom `domain` di tabel `custom_rules`.
         * Hapus duplikat terlebih dahulu jika ada agar CREATE UNIQUE INDEX tidak fail.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM custom_rules WHERE id NOT IN (SELECT MIN(id) FROM custom_rules GROUP BY domain)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_custom_rules_domain` ON `custom_rules` (`domain`)")
            }
        }

        fun getDatabase(context: Context): NetShieldDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NetShieldDatabase::class.java,
                    "netshield_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

