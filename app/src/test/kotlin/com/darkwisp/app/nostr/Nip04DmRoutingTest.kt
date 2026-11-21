package com.darkwisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Nip04DmRoutingTest {
    private val me = "a".repeat(64)
    private val peer = "b".repeat(64)

    @Test
    fun subscriptionIncludesIncomingAndOutgoingNip04History() {
        val filters = Nip04DmRouting.subscriptionFilters(me, includeNip04 = true)

        assertEquals(2, filters.size)
        assertEquals(listOf(1059, 4), filters[0].kinds)
        assertEquals(listOf(me), filters[0].pTags)
        assertEquals(listOf(4), filters[1].kinds)
        assertEquals(listOf(me), filters[1].authors)
        assertNull(filters[1].pTags)
    }

    @Test
    fun disabledNip04KeepsOnlyGiftWrapSubscription() {
        val filters = Nip04DmRouting.subscriptionFilters(me, includeNip04 = false)

        assertEquals(1, filters.size)
        assertEquals(listOf(1059), filters.single().kinds)
        assertEquals(listOf(me), filters.single().pTags)
    }

    @Test
    fun resolvesIncomingAndOutgoingPeers() {
        assertEquals(peer, Nip04DmRouting.peerPubkey(me, peer, me))
        assertEquals(peer, Nip04DmRouting.peerPubkey(me, me, peer))
    }

    @Test
    fun rejectsMisaddressedAndSelfMessages() {
        assertNull(Nip04DmRouting.peerPubkey(me, peer, peer))
        assertNull(Nip04DmRouting.peerPubkey(me, me, me))
        assertNull(Nip04DmRouting.peerPubkey(me, peer, null))
    }
}
