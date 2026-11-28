package com.darkwisp.app.concord

import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteLink
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcordHubRepositoryTest {
    @Test
    fun parsesCuratedListingsAndDropsMalformedOrDuplicateInvites() {
        val invite = ConcordInviteLink.buildUrl(
            base = "https://vectorapp.io",
            linkSignerPubKey = "11".repeat(32),
            token = ByteArray(16) { 7 },
        )
        val body = JsonArray(
            listOf(
                buildJsonObject {
                    put("id", JsonPrimitive(42))
                    put("invite_url", JsonPrimitive(invite))
                    put("category", JsonPrimitive("Art"))
                    put("member_count", JsonPrimitive(120))
                    put("official", JsonPrimitive(true))
                    put("created_at", JsonPrimitive(1_800_000_000))
                },
                buildJsonObject {
                    put("id", JsonPrimitive(43))
                    put("invite_url", JsonPrimitive(invite))
                },
                buildJsonObject {
                    put("invite_url", JsonPrimitive("https://example.invalid/not-an-invite"))
                },
            ),
        )

        val listings = ConcordHubRepository.parseListings(body.toString())

        assertEquals(1, listings.size)
        assertEquals("Art", listings.single().category)
        assertEquals(120, listings.single().memberCount)
        assertTrue(listings.single().official)
        assertEquals(0, listings.single().rank)
    }

    @Test
    fun rejectsNonArrayDirectoryResponses() {
        val error = runCatching { ConcordHubRepository.parseListings("""{"listings":[]}""") }.exceptionOrNull()
        assertTrue(error is java.io.IOException)
    }
}
