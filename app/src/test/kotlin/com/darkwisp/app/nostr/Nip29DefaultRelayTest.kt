package com.darkwisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Test

class Nip29DefaultRelayTest {
    @Test
    fun newGroupsUsePyramidRelayByDefault() {
        assertEquals(listOf("wss://pyramid.fiatjaf.com"), Nip29.DEFAULT_GROUP_RELAYS)
    }
}
