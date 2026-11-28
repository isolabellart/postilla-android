package com.darkwisp.app.relay

import com.vitorpamplona.quartz.concord.crypto.ConcordKeyDerivation
import com.vitorpamplona.quartz.concord.crypto.GroupKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcordStreamAuthTest {
    @Test
    fun signsNip42AsTheConcordStreamAddressAndBindsRelayChallenge() = runBlocking {
        val key = ConcordKeyDerivation.guestbookPlaneKey(
            communityRoot = ByteArray(32) { 0x11 },
            communityId = ByteArray(32) { 0x22 },
            epoch = 7L,
        )
        val relay = "wss://concord.example"
        val challenge = "relay-challenge-123"

        val event = signConcordStreamAuthEvent(key, relay, challenge, createdAtSeconds = 1_800_000_000L)

        assertEquals(22242, event.kind)
        assertEquals(key.publicKeyHex, event.pubkey)
        assertEquals(listOf("relay", relay), event.tags[0])
        assertEquals(listOf("challenge", challenge), event.tags[1])
        assertEquals("", event.content)
        assertTrue(event.verifySignature())
    }

    @Test
    fun refusesASecretThatDoesNotMatchTheStreamAddress() = runBlocking {
        val actual = ConcordKeyDerivation.guestbookPlaneKey(
            communityRoot = ByteArray(32) { 0x31 },
            communityId = ByteArray(32) { 0x42 },
            epoch = 1L,
        )
        val other = ConcordKeyDerivation.guestbookPlaneKey(
            communityRoot = ByteArray(32) { 0x51 },
            communityId = ByteArray(32) { 0x62 },
            epoch = 1L,
        )
        val forgedAddress = GroupKey(
            secretKey = other.secretKey,
            publicKey = actual.publicKey,
            conversationKey = actual.conversationKey,
        )

        val error = runCatching {
            signConcordStreamAuthEvent(forgedAddress, "wss://concord.example", "challenge")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }
}
