package com.darkwisp.app.concord

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcordWriterDutyReadinessTest {
    @Test
    fun twoThirdsSentAndEoseRelaysAreEnoughForWriterDuty() {
        val requested = (1..30).map { "wss://relay$it.example" }
        assertTrue(concordRelayReadHasQuorum(requested, requested.take(24), requested.take(21)))
    }

    @Test
    fun writerDutyRejectsAReadBelowTwoThirds() {
        val requested = (1..30).map { "wss://relay$it.example" }
        assertFalse(concordRelayReadHasQuorum(requested, requested, requested.take(19)))
    }

    @Test
    fun writerDutyDoesNotCountEoseFromRelaysThatNeverAcceptedReq() {
        val requested = (1..9).map { "wss://relay$it.example" }
        assertFalse(concordRelayReadHasQuorum(requested, requested.take(8), requested.take(6)))
    }
}
