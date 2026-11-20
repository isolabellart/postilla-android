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


    @Test
    fun latestRevisionUsesStableAuthorAndDTagAndIsIndependentOfArrivalOrder() {
        val author = "author-a"
        val otherAuthor = "author-b"
        fun article(id: String, pubkey: String, dTag: String, createdAt: Long) = NostrEvent(
            id = id,
            pubkey = pubkey,
            created_at = createdAt,
            kind = Nip23.KIND_LONG_FORM,
            tags = listOf(listOf("d", dTag)),
            content = id,
            sig = "test"
        )

        val first = article("revision-1", author, "same-article", 100L)
        val latest = article("revision-2", author, "same-article", 200L)
        val anotherArticle = article("other-d", author, "other-article", 300L)
        val anotherAuthorArticle = article("other-author", otherAuthor, "same-article", 400L)

        assertEquals(
            latest.id,
            Nip23.latestRevision(
                listOf(anotherAuthorArticle, anotherArticle, latest, first),
                author,
                "same-article"
            )?.id
        )
        assertEquals(
            latest.id,
            Nip23.latestRevision(listOf(first, latest, anotherArticle), author, "same-article")?.id
        )
        assertEquals(null, Nip23.latestRevision(listOf(first, latest), author, "missing"))
    }

    @Test
    fun latestRevisionUsesNip01TieBreakForEqualTimestamps() {
        fun article(id: String) = NostrEvent(
            id = id,
            pubkey = "author",
            created_at = 100L,
            kind = Nip23.KIND_LONG_FORM,
            tags = listOf(listOf("d", "article")),
            content = id,
            sig = "test"
        )
        assertEquals("a-id", Nip23.latestRevision(listOf(article("z-id"), article("a-id")), "author", "article")?.id)
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankDTagIsRejected() {
        Nip23.buildArticleTags("", "Title", publishedAt = 1L)
    }
}
