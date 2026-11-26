package com.darkwisp.app.concord

import com.darkwisp.app.nostr.LocalSigner
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityListFragmentEvent
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteBundle
import com.vitorpamplona.quartz.concord.envelope.ConcordStreamEnvelope
import org.junit.Assert.assertEquals
import org.junit.Test

/** Guards Concord wire identifiers and a Quartz 2.15.1 derivation vector without loading native secp256k1. */
class QuartzConcordCompatibilityTest {
    @Test
    fun concordWireKindsAreAvailable() {
        assertEquals(1059, ConcordStreamEnvelope.KIND_WRAP)
        assertEquals(33301, ConcordInviteBundle.KIND)
        assertEquals(33302, ConcordCommunityListFragmentEvent.KIND)
    }

    @Test
    fun localQuartzKeyDerivationMatchesQuartzReferenceVector() = kotlinx.coroutines.runBlocking {
        val privateKey = ByteArray(32).also { it[31] = 7 }
        val publicKey = ByteArray(32)
        val nonce = ByteArray(32).also { it[0] = 42 }.joinToString("") { "%02x".format(it) }

        // Fixed vector computed from Quartz 2.15.1 EncryptionKeyDerivation:
        // SHA-256(HKDF-Extract(salt = UTF8("nip44kd") || nonce, IKM = privateKey)).
        val expected = "f14cc4d5a083aa3971978f0cfb260413b3600fd03893e0d2577f14576eaf4f64"
        val actual = LocalSigner(privateKey, publicKey).deriveKey(nonce)
        assertEquals(expected, actual)
    }
}
