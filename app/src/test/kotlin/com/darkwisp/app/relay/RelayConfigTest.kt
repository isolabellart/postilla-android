package com.darkwisp.app.relay

import org.junit.Assert.assertEquals
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
}
