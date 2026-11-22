package com.darkwisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class Nip51Test {
    @Test
    fun marketplaceWatchlistRoundTripsPublicListingCoordinates() {
        val dTag = "dark-wisp-marketplace-watchlist"
        val coordinates = setOf(
            "30402:" + "ab".repeat(32) + ":painting-01",
            "30402:" + "ef".repeat(32) + ":painting-02",
            "30402:" + "12".repeat(32) + ":painting-03"
        )
        val tags = Nip51.buildBookmarkSetTags(
            dTag = dTag,
            eventIds = emptySet(),
            coordinates = coordinates,
            title = "Marketplace Watchlist"
        )
        val event = NostrEvent(
            id = "bookmark-set-id",
            pubkey = "cd".repeat(32),
            created_at = 1_800_000_000L,
            kind = Nip51.KIND_BOOKMARK_SET,
            tags = tags,
            content = "",
            sig = "signature"
        )

        val parsed = Nip51.parseBookmarkSet(event)

        assertNotNull(parsed)
        assertEquals(dTag, parsed?.dTag)
        assertEquals(coordinates, parsed?.coordinates)
    }
    @Test
    fun publicCurationSetParsesAddressReferencesAndRelayHints() {
        val author = "ab".repeat(32)
        val event = NostrEvent(
            id = "curation-id", pubkey = "cd".repeat(32), created_at = 1_800_000_000L,
            kind = Nip51.KIND_CURATION_SET,
            tags = listOf(
                listOf("d", "my-reading-list"), listOf("title", "Reading list"),
                listOf("description", "Articles selected by the author"),
                listOf("image", "https://example.com/cover.jpg"),
                listOf("a", "30023:" + author + ":essay-one", "wss://relay.example.com"),
                listOf("e", "ef".repeat(32), "wss://notes.example.com")
            ), content = "", sig = "signature"
        )
        val parsed = Nip51.parseCurationSet(event)
        assertNotNull(parsed)
        assertEquals("Reading list", parsed?.title)
        assertEquals("Articles selected by the author", parsed?.description)
        assertEquals("https://example.com/cover.jpg", parsed?.image)
        assertEquals(2, parsed?.entries?.size)
        assertEquals(30023, parsed?.entries?.first()?.kind)
        assertEquals(author, parsed?.entries?.first()?.author)
        assertEquals("essay-one", parsed?.entries?.first()?.dTag)
        assertEquals("wss://relay.example.com", parsed?.entries?.first()?.relayHint)
        assertEquals(1, parsed?.entries?.last()?.kind)
        assertEquals("ef".repeat(32), parsed?.entries?.last()?.eventId)
        assertEquals("wss://notes.example.com", parsed?.entries?.last()?.relayHint)
    }
}
