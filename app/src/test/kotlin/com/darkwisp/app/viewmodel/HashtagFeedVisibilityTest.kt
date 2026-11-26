package com.darkwisp.app.viewmodel

import com.darkwisp.app.nostr.NostrEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HashtagFeedVisibilityTest {
    private fun event(id: String, createdAt: Long, kind: Int = 1) =
        NostrEvent(id, "author", createdAt, kind, emptyList(), "hashtag post", "")

    @Test
    fun sharedModerationPolicyControlsHashtagVisibility() {
        val visible = event("followed", 20L)
        val outsideWot = event("outside-wot", 10L)
        val result = filterVisibleHashtagEvents(listOf(visible, outsideWot)) {
            it.pubkey == "author" && it.id == visible.id
        }
        assertEquals(listOf(visible.id), result.map { it.id })
    }

    @Test
    fun hashtagFeedKeepsNewestVisibleEventsFirst() {
        val older = event("older", 10L)
        val newer = event("newer", 20L)
        val result = filterVisibleHashtagEvents(listOf(older, newer)) { true }
        assertEquals(listOf(newer.id, older.id), result.map { it.id })
    }

    @Test
    fun nonTextEventsAreNeverInsertedIntoHashtagFeed() {
        val note = event("note", 20L)
        val reaction = event("reaction", 30L, kind = 7)
        val result = filterVisibleHashtagEvents(listOf(note, reaction)) { true }
        assertEquals(listOf(note.id), result.map { it.id })
    }

    @Test
    fun hiddenEventDoesNotAppearEvenWhenItMatchesTheHashtag() {
        val muted = event("muted", 20L)
        assertTrue(filterVisibleHashtagEvents(listOf(muted)) { false }.isEmpty())
    }
}
