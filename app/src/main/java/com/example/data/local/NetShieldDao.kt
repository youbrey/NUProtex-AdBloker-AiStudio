package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * === CHANGELOG ===
 * [Fase 2.7 - 2026-08-08] `getThreatsPreventedCount()` ditambah kondisi
 * `category = 'gambling_scam_ads'` — kategori baru judi online/investasi
 * palsu diperlakukan setara ancaman keamanan. Lihat CHANGELOG.md &
 * RENCANA_PRODUKSI_NETSHIELD.md §Fase 2.7.
 */

@Dao
interface NetShieldDao {

    // DNS Logs
    @Query("SELECT * FROM dns_logs ORDER BY timestamp DESC LIMIT 200")
    fun getAllDnsLogs(): Flow<List<DnsLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDnsLog(log: DnsLogEntity)

    @Query("DELETE FROM dns_logs")
    suspend fun clearDnsLogs()

    // Fase 5.4: export/clear log lebih granular — per kategori tampilan
    // (sesuai persis logika filter di NetShieldViewModel.filteredLogs) &
    // rentang tanggal.
    @Query("DELETE FROM dns_logs WHERE isBlocked = :isBlocked")
    suspend fun clearDnsLogsByBlockedStatus(isBlocked: Boolean)

    @Query("DELETE FROM dns_logs WHERE category = :category OR threatLevel != 'NONE'")
    suspend fun clearDnsLogsByThreatCategory(category: String)

    @Query("DELETE FROM dns_logs WHERE timestamp < :beforeTimestamp")
    suspend fun clearDnsLogsOlderThan(beforeTimestamp: Long)

    @Query("SELECT * FROM dns_logs ORDER BY timestamp DESC LIMIT 200")
    suspend fun getAllDnsLogsForExport(): List<DnsLogEntity>

    @Query("SELECT COUNT(*) FROM dns_logs WHERE isBlocked = 1")
    fun getBlockedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM dns_logs")
    fun getTotalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM dns_logs")
    suspend fun getTotalRequestsCount(): Long

    @Query("SELECT COUNT(*) FROM dns_logs WHERE isBlocked = 1")
    suspend fun getBlockedRequestsCount(): Long

    @Query("SELECT COUNT(*) FROM dns_logs WHERE threatLevel != 'NONE' OR category = 'malware_guard' OR category = 'phishing_guard' OR category = 'gambling_scam_ads'")
    suspend fun getThreatsPreventedCount(): Long

    @Query("SELECT AVG(latencyMs) FROM dns_logs WHERE latencyMs > 0")
    suspend fun getAverageLatencyMs(): Double?

    // Custom Rules
    @Query("SELECT * FROM custom_rules ORDER BY createdAt DESC")
    fun getAllCustomRules(): Flow<List<CustomRuleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomRule(rule: CustomRuleEntity)

    @Delete
    suspend fun deleteCustomRule(rule: CustomRuleEntity)

    @Query("DELETE FROM custom_rules WHERE domain = :domain")
    suspend fun deleteCustomRuleByDomain(domain: String)

    // Threat Events
    @Query("SELECT * FROM threat_events ORDER BY timestamp DESC LIMIT 100")
    fun getAllThreatEvents(): Flow<List<ThreatEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertThreatEvent(threat: ThreatEventEntity)

    @Query("DELETE FROM threat_events")
    suspend fun clearThreatEvents()
}
