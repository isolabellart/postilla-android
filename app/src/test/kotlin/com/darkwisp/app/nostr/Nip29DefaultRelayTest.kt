package com.darkwisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Test

class Nip29DefaultRelayTest {
    @Test
    fun newGroupsUsePostillaCommunityRelayByDefault() {
        assertEquals(listOf("wss://groups.hzrd149.com"), Nip29.DEFAULT_GROUP_RELAYS)
    }
}
