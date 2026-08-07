package com.example.util

import com.example.model.FilterOption
import com.example.model.ProtectionStats

/**
 * Algoritma kalkulasi Security Score dinamis berdasarkan:
 * 1. Kondisi perangkat & Status Proteksi VPN
 * 2. Aktivitas VPN & Lalu lintas terproses
 * 3. Proteksi DNS Leak (DNS-over-HTTPS aktif)
 * 4. Malware & Phishing Filter yang diaktifkan
 * 5. Jumlah Ancaman/Malware yang berhasil diblokir
 */
object SecurityScoreCalculator {

    data class ScoreBreakdown(
        val totalScore: Int,
        val vpnActivePoints: Int,
        val dohPoints: Int,
        val malwareFilterPoints: Int,
        val trackerFilterPoints: Int,
        val vpnActivityPoints: Int,
        val threatMitigationPoints: Int
    )

    fun calculateScore(
        isProtectionActive: Boolean,
        dohEnabled: Boolean,
        filterOptions: List<FilterOption>,
        stats: ProtectionStats
    ): ScoreBreakdown {
        var vpnPts = 0
        var dohPts = 0
        var malwarePts = 0
        var trackerPts = 0
        var activityPts = 0
        var threatPts = 0

        // 1. Kondisi perangkat & Status Proteksi VPN (Maks 25 poin)
        if (isProtectionActive) {
            vpnPts = 25
        }

        // 2. Proteksi Anti-DNS Leak via HTTPS DNS (DoH) (Maks 20 poin)
        if (dohEnabled) {
            dohPts = 20
        }

        // 3. Malware, Phishing & Fingerprinting Guard Enabled (Maks 20 poin)
        val malwareEnabled = filterOptions.any { it.id == "malware_guard" && it.isEnabled }
        val phishingEnabled = filterOptions.any { it.id == "phishing_guard" && it.isEnabled }
        val fingerprintEnabled = filterOptions.any { it.id == "fingerprint_guard" && it.isEnabled }
        if (malwareEnabled) malwarePts += 7
        if (phishingEnabled) malwarePts += 7
        if (fingerprintEnabled) malwarePts += 6

        // 4. Tracker & Telemetri Filter Enabled (Maks 15 poin)
        val trackerEnabled = filterOptions.any { it.id == "trackers" && it.isEnabled }
        if (trackerEnabled) trackerPts = 15

        // 5. Aktivitas VPN (Maks 10 poin)
        if (isProtectionActive && (stats.totalRequests > 0 || stats.activeRulesCount > 0)) {
            activityPts = 10
        }

        // 6. Malware Blocked & Threat Mitigation (Maks 10 poin)
        if (stats.threatsPrevented > 0 || stats.totalBlocked > 0) {
            threatPts = 10
        } else if (isProtectionActive) {
            threatPts = 5
        }

        val total = (vpnPts + dohPts + malwarePts + trackerPts + activityPts + threatPts).coerceIn(0, 100)

        return ScoreBreakdown(
            totalScore = total,
            vpnActivePoints = vpnPts,
            dohPoints = dohPts,
            malwareFilterPoints = malwarePts,
            trackerFilterPoints = trackerPts,
            vpnActivityPoints = activityPts,
            threatMitigationPoints = threatPts
        )
    }
}
