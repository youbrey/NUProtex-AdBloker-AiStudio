package com.example

import com.example.model.FilterOption
import com.example.model.ProtectionStats
import com.example.util.SecurityScoreCalculator
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * === CHANGELOG ===
 * [Fase 7 - 2026-08-07] Unit test untuk [SecurityScoreCalculator].
 * Memastikan algoritma skor keamanan 0-100 memberikan nilai yang akurat sesuai kondisi.
 */
class SecurityScoreCalculatorTest {

    @Test
    fun calculate_protectionInactive_givesLowScore() {
        val stats = ProtectionStats(
            totalRequests = 100L,
            totalBlocked = 20L,
            avgLatencyMs = 25,
            dataSavedMb = 1.5f,
            threatsPrevented = 2L
        )

        val score = SecurityScoreCalculator.calculateScore(
            isProtectionActive = false,
            dohEnabled = false,
            filterOptions = emptyList(),
            stats = stats
        )

        assertTrue(score.totalScore < 50)
    }

    @Test
    fun calculate_fullProtection_givesHighScore() {
        val stats = ProtectionStats(
            totalRequests = 500L,
            totalBlocked = 120L,
            avgLatencyMs = 18,
            dataSavedMb = 5.0f,
            threatsPrevented = 15L
        )

        val filterOptions = listOf(
            FilterOption("malware_guard", "Malware", "Desc", isEnabled = true, iconRes = "security", ruleCount = 100),
            FilterOption("phishing_guard", "Phishing", "Desc", isEnabled = true, iconRes = "security", ruleCount = 50),
            FilterOption("trackers", "Trackers", "Desc", isEnabled = true, iconRes = "radar", ruleCount = 200)
        )

        val score = SecurityScoreCalculator.calculateScore(
            isProtectionActive = true,
            dohEnabled = true,
            filterOptions = filterOptions,
            stats = stats
        )

        assertTrue(score.totalScore >= 90)
        assertTrue(score.totalScore <= 100)
    }
}
