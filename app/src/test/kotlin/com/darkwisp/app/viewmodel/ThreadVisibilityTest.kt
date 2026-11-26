package com.darkwisp.app.viewmodel

import com.darkwisp.app.nostr.NostrEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadVisibilityTest {
    private fun event(id: String) =
        NostrEvent(id, "author", 100L, 1, emptyList(), "thread reply", "")

    @Test
    fun threadEventsUseTheSharedVisibilityPolicy() {
        val visible = event("trusted")
        val hidden = event("outside-wot")
        val result = filterVisibleThreadEvents(listOf(visible, hidden)) {
            it.id == visible.id
        }
        assertEquals(listOf(visible.id), result.map { it.id })
    }

    @Test
    fun threadVisibilityCanBeRecomputedWhenPolicyChanges() {
        val note = event("recomputed")
        assertEquals(emptyList<String>(), filterVisibleThreadEvents(listOf(note)) { false }.map { it.id })
        assertEquals(listOf(note.id), filterVisibleThreadEvents(listOf(note)) { true }.map { it.id })
    }
}
