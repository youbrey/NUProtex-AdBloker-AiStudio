package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.data.local.CustomRuleEntity
import com.example.data.local.DnsLogEntity
import com.example.model.DnsProvider
import com.example.repository.DnsEngineRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * [repository] WAJIB diinjeksikan dari luar (lihat [Factory]) dan berasal
 * dari instance singleton di [com.example.NetShieldApplication].
 * JANGAN buat DnsEngineRepository baru di sini — itu akan menduplikasi
 * CoroutineScope & job background (lihat catatan di DnsEngineRepository).
 *
 * === CHANGELOG ===
 * [Fase 0 - 2026-08-07]
 *  - AndroidViewModel(application) yang membuat repository sendiri ->
 *    ViewModel(repository) yang menerima instance singleton dari luar.
 *  - Ditambahkan companion object Factory untuk konstruksi via
 *    NetShieldApplication.dnsEngineRepository.
 *  Lihat CHANGELOG.md & RENCANA_PRODUKSI_NETSHIELD.md §Fase 0.
 */
class NetShieldViewModel(private val repository: DnsEngineRepository) : ViewModel() {

    val isProtectionActive = repository.isProtectionActive
    val stats = repository.stats
    val selectedProvider = repository.selectedProvider
    val filterOptions = repository.filterOptions
    val isUpdatingDb = repository.isUpdatingDb
    val dohEnabled = repository.dohEnabled
    val lowBatteryMode = repository.lowBatteryMode

    val customRules = repository.customRules
    val threatEvents = repository.threatEvents

    val securityScore: StateFlow<Int> = combine(
        isProtectionActive,
        dohEnabled,
        filterOptions,
        stats
    ) { active, doh, filters, currentStats ->
        com.example.util.SecurityScoreCalculator.calculateScore(active, doh, filters, currentStats).totalScore
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Log Search and Filter state
    private val _logSearchQuery = MutableStateFlow("")
    val logSearchQuery: StateFlow<String> = _logSearchQuery.asStateFlow()

    private val _logFilterCategory = MutableStateFlow("Semua") // "Semua", "Diblokir", "Diizinkan", "Ancaman"
    val logFilterCategory: StateFlow<String> = _logFilterCategory.asStateFlow()

    val filteredLogs: StateFlow<List<DnsLogEntity>> = combine(
        repository.dnsLogs,
        _logSearchQuery,
        _logFilterCategory
    ) { logs, query, filter ->
        logs.filter { log ->
            val matchesQuery = query.isEmpty() || log.domain.contains(query, ignoreCase = true) || log.clientApp.contains(query, ignoreCase = true)
            val matchesFilter = when (filter) {
                "Diblokir" -> log.isBlocked
                "Diizinkan" -> !log.isBlocked
                "Ancaman" -> log.category == "malware_guard" || log.category == "phishing_guard" || log.threatLevel != "NONE"
                else -> true
            }
            matchesQuery && matchesFilter
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleProtection(active: Boolean) {
        repository.toggleProtection(active)
    }

    fun setLogSearchQuery(query: String) {
        _logSearchQuery.value = query
    }

    fun setLogFilterCategory(category: String) {
        _logFilterCategory.value = category
    }

    fun addCustomRule(domain: String, isBlocked: Boolean, category: String = "Situs Kustom", note: String = "") {
        repository.addCustomRule(domain, isBlocked, category, note)
    }

    fun removeCustomRule(rule: CustomRuleEntity) {
        repository.removeCustomRule(rule)
    }

    fun setDnsProvider(provider: DnsProvider) {
        repository.setDnsProvider(provider)
    }

    fun toggleFilter(filterId: String, enabled: Boolean) {
        repository.toggleFilter(filterId, enabled)
    }

    fun toggleDoh(enabled: Boolean) {
        repository.toggleDoh(enabled)
    }

    fun toggleLowBatteryMode(enabled: Boolean) {
        repository.toggleLowBatteryMode(enabled)
    }

    fun updateThreatDatabase() {
        repository.updateThreatDatabase()
    }

    /**
     * Fase 4.1: menggantikan `triggerThreatSimulationAlert()` lama (domain
     * ancaman acak). Ancaman nyata sekarang terdeteksi & tercatat otomatis
     * dari trafik DNS sungguhan (lihat DnsEngineRepository.recordRealThreatEvent).
     * Tombol di ThreatScreen sekarang memicu pemindaian ulang database
     * ancaman yang nyata lewat fungsi ini.
     */
    fun refreshThreatIntelligence() {
        repository.refreshThreatIntelligence()
    }

    fun clearAllLogs() {
        repository.clearAllLogs()
    }

    /** Fase 5.4: hapus log hanya untuk kategori filter yang sedang aktif ("Diblokir"/"Diizinkan"/"Ancaman"/"Semua"). */
    fun clearLogsByCurrentFilter() {
        repository.clearLogsByDisplayFilter(_logFilterCategory.value)
    }

    /** Fase 5.4: hapus log yang lebih lama dari [days] hari. */
    fun clearLogsOlderThan(days: Int) {
        repository.clearLogsOlderThan(days)
    }

    companion object {
        /**
         * Factory yang mengambil DnsEngineRepository singleton dari
         * NetShieldApplication, sehingga tidak ada instance repository
         * ganda dibuat setiap kali ViewModel baru diminta.
         */
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as com.example.NetShieldApplication
                @Suppress("UNCHECKED_CAST")
                return NetShieldViewModel(app.dnsEngineRepository) as T
            }
        }
    }
}
