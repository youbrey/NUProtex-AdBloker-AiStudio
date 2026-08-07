package com.example

import com.example.data.local.CustomRuleEntity
import com.example.model.FilterOption
import com.example.vpn.BlocklistEngine
import com.example.vpn.BlocklistStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * === CHANGELOG ===
 * [Fase 7 - 2026-08-07] Unit test untuk [BlocklistEngine].
 * Memastikan evaluasi domain memprioritaskan custom rules (whitelist > blacklist)
 * dan mematuhi opsi filter aktif & blocklist store.
 */
class BlocklistEngineTest {

    @Before
    fun setUp() {
        // Set up in-memory blocklist store
        BlocklistStore.replaceAll(
            mapOf(
                "ads_guard" to setOf("doubleclick.net", "adservice.google.com"),
                "malware_guard" to setOf("malicious-domain.com")
            ),
            "v1.0"
        )
    }

    @Test
    fun evaluate_customWhitelistOverride_alwaysAllowsDomain() {
        val customRules = listOf(
            CustomRuleEntity(
                id = 1,
                domain = "doubleclick.net",
                isBlocked = false, // Whitelist
                isEnabled = true,
                category = "Kustom"
            )
        )

        val filterOptions = listOf(
            FilterOption("ads_guard", "Ads", "Block ads", isEnabled = true, iconRes = "security", ruleCount = 10)
        )

        val result = BlocklistEngine.evaluate("doubleclick.net", customRules, filterOptions)

        assertFalse(result.isBlocked)
        assertEquals("Kustom", result.category)
    }

    @Test
    fun evaluate_customBlacklistOverride_alwaysBlocksDomain() {
        val customRules = listOf(
            CustomRuleEntity(
                id = 2,
                domain = "safe-site.com",
                isBlocked = true, // Blacklist
                isEnabled = true,
                category = "Kustom"
            )
        )

        val filterOptions = emptyList<FilterOption>()

        val result = BlocklistEngine.evaluate("safe-site.com", customRules, filterOptions)

        assertTrue(result.isBlocked)
        assertEquals("Kustom", result.category)
    }

    @Test
    fun evaluate_domainInBlocklistStore_blocksWhenFilterEnabled() {
        val filterOptions = listOf(
            FilterOption("ads_guard", "Ads", "Block ads", isEnabled = true, iconRes = "security", ruleCount = 10)
        )

        val result = BlocklistEngine.evaluate("doubleclick.net", emptyList(), filterOptions)

        assertTrue(result.isBlocked)
        assertEquals("ads_guard", result.category)
    }

    @Test
    fun evaluate_domainInBlocklistStore_allowsWhenFilterDisabled() {
        val filterOptions = listOf(
            FilterOption("ads_guard", "Ads", "Block ads", isEnabled = false, iconRes = "security", ruleCount = 10)
        )

        val result = BlocklistEngine.evaluate("doubleclick.net", emptyList(), filterOptions)

        assertFalse(result.isBlocked)
        assertEquals("ads_guard", result.category)
    }

    @Test
    fun evaluate_unknownDomain_allowsDomain() {
        val filterOptions = listOf(
            FilterOption("ads_guard", "Ads", "Block ads", isEnabled = true, iconRes = "security", ruleCount = 10)
        )

        val result = BlocklistEngine.evaluate("github.com", emptyList(), filterOptions)

        assertFalse(result.isBlocked)
        assertEquals("Normal", result.category)
    }
}
