package com.darkwisp.app.repo

import com.darkwisp.app.nostr.NostrEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayFeedVisibilityTest {
    private fun event(createdAt: Long) =
        NostrEvent("id", "author", createdAt, 1, emptyList(), "relay feed post", "")

    @Test
    fun appliesSharedModerationVisibilityPolicy() {
        assertTrue(passesRelayFeedVisibility(event(100L), 100L) { true })
        assertFalse(passesRelayFeedVisibility(event(100L), 100L) { false })
    }

    @Test
    fun rejectsFutureDatedEventsOutsideExistingClockSkewWindow() {
        assertTrue(passesRelayFeedVisibility(event(130L), 100L) { true })
        assertFalse(passesRelayFeedVisibility(event(131L), 100L) { true })
    }
}
