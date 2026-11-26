package com.darkwisp.app.concord

import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityList
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityListEntry
import com.vitorpamplona.quartz.concord.cord02Community.ConcordListFragments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class ConcordCommunityListEncodingTest {
    @Test
    fun newMembershipIsPackedAsAFragmentAndBinarySecretsAreBase64UrlEncoded() {
        val communityId = "11".repeat(32)
        val owner = "22".repeat(32)
        val ownerSalt = "33".repeat(32)
        val root = "44".repeat(32)
        val controlRoot = "55".repeat(32)
        val entry = ConcordCommunityListEntry(
            id = communityId,
            owner = owner,
            ownerSalt = ownerSalt,
            root = root,
            rootEpoch = 0,
            controlPk = "66".repeat(32),
            controlRoot = controlRoot,
            relays = listOf("wss://relay.example"),
            name = "Postilla Concord Test",
            addedAt = 1_795_728_000_000L,
        )

        val internalDocument = ConcordCommunityList.encode(listOf(entry))
        val fragments = ConcordListFragments.pack(
            kotlinx.serialization.json.Json.parseToJsonElement(internalDocument).jsonObject
        )

        assertEquals(1, fragments.size)
        assertTrue(fragments.single().contains("\"frags\":1"))
        assertTrue(fragments.single().contains("Postilla Concord Test"))
        // The fragment wire format encodes 32-byte key material as unpadded base64url.
        assertFalse(fragments.single().contains(communityId))
        assertFalse(fragments.single().contains(ownerSalt))
        assertFalse(fragments.single().contains(root))
        assertFalse(fragments.single().contains(controlRoot))
    }
}
