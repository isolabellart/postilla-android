package com.darkwisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Test

class Nip29DefaultRelayTest {
    @Test
    fun newGroupsAndDiscoveryUseThePostillaRelayByDefault() {
        assertEquals(listOf("wss://nostr.isolabellart.it.com"), Nip29.DEFAULT_GROUP_RELAYS)
    }

    @Test
    fun externalRelaysRemainValidCreationTargets() {
        assertEquals("wss://groups.example.com", Nip29.normalizeRelayUrl("groups.example.com"))
        assertEquals("ws://localhost:7777", Nip29.normalizeRelayUrl("ws://localhost:7777"))
    }
}
