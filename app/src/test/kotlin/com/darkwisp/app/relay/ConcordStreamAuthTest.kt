package com.darkwisp.app.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcordStreamAuthTest {
    @Test
    fun nip42PayloadBindsTheExactRelayAndChallenge() {
        val tags = concordStreamAuthTags(
            relayUrl = "wss://concord.example",
            challenge = "relay-challenge-123",
        )

        assertEquals(
            listOf(
                listOf("relay", "wss://concord.example"),
                listOf("challenge", "relay-challenge-123"),
            ),
            tags,
        )
    }

    @Test
    fun nip42PayloadRejectsMissingRelayOrChallenge() {
        val missingRelay = runCatching { concordStreamAuthTags("", "challenge") }.exceptionOrNull()
        val missingChallenge = runCatching { concordStreamAuthTags("wss://concord.example", "") }.exceptionOrNull()

        assertTrue(missingRelay is IllegalArgumentException)
        assertTrue(missingChallenge is IllegalArgumentException)
    }
}
