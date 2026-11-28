package com.darkwisp.app.concord

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcordRelayReadCompletenessTest {
    @Test
    fun completeWhenEveryRequestedRelayWasSentAndReachedEose() {
        val requested = listOf("wss://one.example", "wss://two.example")
        assertTrue(
            concordRelayReadIsComplete(
                requestedRelays = requested,
                sentRelays = requested,
                eoseRelays = requested,
            ),
        )
    }

    @Test
    fun incompleteWhenARequestedRelayNeverAcceptedTheSubscription() {
        val requested = listOf("wss://one.example", "wss://two.example", "wss://three.example")
        val sent = listOf("wss://one.example", "wss://two.example")
        assertFalse(
            concordRelayReadIsComplete(
                requestedRelays = requested,
                sentRelays = sent,
                eoseRelays = sent,
            ),
        )
    }

    @Test
    fun incompleteWhenOneRequestedRelayDidNotReachEose() {
        val requested = listOf("wss://one.example", "wss://two.example")
        assertFalse(
            concordRelayReadIsComplete(
                requestedRelays = requested,
                sentRelays = requested,
                eoseRelays = listOf("wss://one.example"),
            ),
        )
    }

    @Test
    fun emptyRelaySetIsNeverConsideredComplete() {
        assertFalse(concordRelayReadIsComplete(emptyList(), emptyList(), emptyList()))
        assertFalse(concordRelayReadHasQuorum(emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun twoThirdsEoseQuorumMakesChatReadReadyWithoutRelaxingFullCompleteness() {
        val requested = (1..30).map { "wss://relay$it.example" }
        val responders = requested.take(21)
        assertTrue(concordRelayReadHasQuorum(requested, responders, responders))
        assertFalse(concordRelayReadIsComplete(requested, responders, responders))
    }

    @Test
    fun fewerThanTwoThirdsConfirmedRelaysDoesNotMakeChatReadReady() {
        val requested = (1..30).map { "wss://relay$it.example" }
        val responders = requested.take(19)
        assertFalse(concordRelayReadHasQuorum(requested, responders, responders))
    }

    @Test
    fun aRelayMustBeBothSentAndEoseConfirmedToCountTowardQuorum() {
        val requested = (1..6).map { "wss://relay$it.example" }
        val sent = requested.take(5)
        val eose = requested.take(4)
        assertFalse(concordRelayReadHasQuorum(requested, sent, eose))
    }
}
