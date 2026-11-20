package com.darkwisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Test

class Nip09Test {
    @Test
    fun `addressable deletion includes coordinate kind and specific event id when available`() {
        assertEquals(
            listOf(
                listOf("e", "event-id"),
                listOf("a", "30018:author-pubkey:legacy-dtag"),
                listOf("k", "30018")
            ),
            Nip09.buildAddressableDeletionTags(
                kind = 30018,
                pubkey = "author-pubkey",
                dTag = "legacy-dtag",
                eventId = "event-id"
            )
        )
    }

    @Test
    fun `addressable deletion remains compatible when event id is unavailable`() {
        assertEquals(
            listOf(
                listOf("a", "30402:author-pubkey:listing-dtag"),
                listOf("k", "30402")
            ),
            Nip09.buildAddressableDeletionTags(
                kind = 30402,
                pubkey = "author-pubkey",
                dTag = "listing-dtag"
            )
        )
    }
}
