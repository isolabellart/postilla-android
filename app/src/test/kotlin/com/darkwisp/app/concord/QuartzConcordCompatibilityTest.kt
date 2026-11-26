package com.darkwisp.app.concord

import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityListFragmentEvent
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteBundle
import com.vitorpamplona.quartz.concord.envelope.ConcordStreamEnvelope
import com.darkwisp.app.nostr.LocalSigner
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import org.junit.Assert.assertEquals
import org.junit.Test

/** Guards the published Quartz artifact's Concord wire identifiers. */
class QuartzConcordCompatibilityTest {
    @Test
    fun concordWireKindsAreAvailable() {
        assertEquals(1059, ConcordStreamEnvelope.KIND_WRAP)
        assertEquals(33301, ConcordInviteBundle.KIND)
        assertEquals(33302, ConcordCommunityListFragmentEvent.KIND)
    }

    @Test
    fun localQuartzKeyDerivationMatchesQuartzReferenceSigner() = kotlinx.coroutines.runBlocking {
        val privateKey = ByteArray(32).also { it[31] = 7 }
        val keyPair = KeyPair(privKey = privateKey)
        val nonce = ByteArray(32).also { it[0] = 42 }.toHexKey()
        val expected = NostrSignerSync(keyPair).deriveKey(nonce)
        val actual = LocalSigner(privateKey, keyPair.pubKey).deriveKey(nonce)
        assertEquals(expected, actual)
    }
}
