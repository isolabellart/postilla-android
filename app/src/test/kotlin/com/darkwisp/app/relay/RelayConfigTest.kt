package com.darkwisp.app.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayConfigTest {
    @Test
    fun defaultSearchRelaysMatchTheRecommendedSet() {
        assertEquals(
            listOf(
                "wss://nostr.wine",
                "wss://relay.noswhere.com",
                "wss://search.nos.today",
                "wss://antiprimal.net",
                "wss://relay.ditto.pub",
                "wss://search.brainstorm.world",
                "wss://scores.brainstorm.world"
            ),
            RelayConfig.DEFAULT_SEARCH_RELAYS
        )
    }

    @Test
    fun mergingDefaultsPreservesCustomRelaysAndAvoidsDuplicates() {
        val customRelay = "wss://my-custom-search.example"
        val existing = listOf(customRelay, RelayConfig.DEFAULT_SEARCH_RELAYS.first())

        val merged = RelayConfig.mergeDefaultSearchRelays(existing)

        assertEquals(customRelay, merged.first())
        assertEquals(1, merged.count { it == RelayConfig.DEFAULT_SEARCH_RELAYS.first() })
        assertEquals(
            existing.size + RelayConfig.DEFAULT_SEARCH_RELAYS.size - 1,
            merged.size
        )
        assertEquals(merged, RelayConfig.mergeDefaultSearchRelays(merged))
    }

    @Test
    fun mergingRecommendedGeneralRelaysPreservesCustomSettingsAndOrder() {
        val custom = RelayConfig("wss://custom.example", read = false, write = true, auth = false)
        val existingRecommended = RelayConfig.RECOMMENDED_GENERAL_RELAYS.first().copy(
            read = false,
            write = false,
            auth = false
        )
        val existing = listOf(custom, existingRecommended)

        val merged = RelayConfig.mergeRecommendedGeneralRelays(existing)

        assertEquals(custom, merged.first())
        assertEquals(existingRecommended, merged[1])
        assertEquals(
            1,
            merged.count { it.url == existingRecommended.url }
        )
        assertEquals(merged, RelayConfig.mergeRecommendedGeneralRelays(merged))
        assertEquals(4, merged.size)
    }

    @Test
    fun mergingRecommendedDmRelaysPreservesCustomEntriesAndNormalizesDuplicates() {
        val custom = "wss://my-dm-relay.example"
        val alreadyPresent = "wss://auth.nostr1.com/"
        val existing = listOf(custom, alreadyPresent)

        val merged = RelayConfig.mergeRecommendedDmRelays(existing)

        assertEquals(custom, merged.first())
        assertEquals(alreadyPresent, merged[1])
        assertEquals(1, merged.count { it.trimEnd('/') == "wss://auth.nostr1.com" })
        assertTrue("wss://inbox.azzamo.net" in merged)
        assertTrue("wss://inbox.nostr.wine" !in merged)
        assertTrue("wss://nostr.land" !in merged)
        assertEquals(merged, RelayConfig.mergeRecommendedDmRelays(merged))
    }

    @Test
    fun bulkDmRecommendationsDoNotExceedThreeRelays() {
        val existing = listOf(
            "wss://my-first-dm.example",
            "wss://my-second-dm.example"
        )

        val merged = RelayConfig.mergeRecommendedDmRelays(existing)

        assertEquals(existing, merged.take(2))
        assertEquals(3, merged.size)
        assertEquals("wss://auth.nostr1.com", merged.last())
    }

    @Test
    fun fullDmRelayListIsNotChangedByRecommendations() {
        val existing = listOf(
            "wss://one.example",
            "wss://two.example",
            "wss://three.example"
        )

        assertEquals(existing, RelayConfig.mergeRecommendedDmRelays(existing))
    }
}
