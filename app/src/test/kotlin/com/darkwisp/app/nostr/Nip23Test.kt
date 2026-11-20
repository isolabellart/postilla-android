package com.darkwisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Nip23Test {
    @Test
    fun articleTagsContainRequiredAddressAndMetadata() {
        val tags = Nip23.buildArticleTags(
            dTag = "my-first-article", title = "A long-form title",
            summary = "An optional summary", image = "https://cdn.example/cover.jpg",
            publishedAt = 1_800_000_000L, hashtags = listOf("#Nostr", "Art", "nostr")
        )
        assertEquals(listOf("d", "my-first-article"), tags.first())
        assertTrue(tags.contains(listOf("title", "A long-form title")))
        assertTrue(tags.contains(listOf("summary", "An optional summary")))
        assertTrue(tags.contains(listOf("image", "https://cdn.example/cover.jpg")))
        assertTrue(tags.contains(listOf("published_at", "1800000000")))
        assertEquals(1, tags.count { it == listOf("t", "nostr") })
        assertTrue(tags.contains(listOf("t", "art")))
        assertTrue(tags.contains(listOf("client", "Postilla")))
    }

    @Test
    fun optionalFieldsAndClientTagCanBeOmitted() {
        val tags = Nip23.buildArticleTags("article-2", "Title", publishedAt = 1L, includeClientTag = false)
        assertFalse(tags.any { it.firstOrNull() == "summary" || it.firstOrNull() == "image" || it.firstOrNull() == "client" })
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankDTagIsRejected() {
        Nip23.buildArticleTags("", "Title", publishedAt = 1L)
    }
}
