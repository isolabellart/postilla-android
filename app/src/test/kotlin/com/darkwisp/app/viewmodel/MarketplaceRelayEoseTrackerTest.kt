package com.darkwisp.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketplaceRelayEoseTrackerTest {
    @Test fun `only sent relays count toward EOSE`() {
        val tracker = MarketplaceRelayEoseTracker(listOf("wss://one.example", "wss://two.example"))
        tracker.markSent("wss://one.example")
        tracker.markCompleted("wss://two.example")
        tracker.markCompleted("wss://one.example")
        assertEquals(1, tracker.completedCount())
        assertEquals(1, tracker.targetCount())
        assertTrue(tracker.hasCompleted("wss://one.example"))
        assertFalse(tracker.hasCompleted("wss://two.example"))
    }

    @Test fun `duplicate EOSE counts once`() {
        val tracker = MarketplaceRelayEoseTracker(listOf("wss://one.example", "wss://two.example"))
        tracker.markSent("wss://one.example")
        tracker.markSent("wss://two.example")
        tracker.markCompleted("wss://one.example")
        tracker.markCompleted("wss://one.example")
        assertEquals(1, tracker.completedCount())
        assertFalse(tracker.allTargetsCompleted())
    }

    @Test fun `unknown relay cannot satisfy threshold`() {
        val tracker = MarketplaceRelayEoseTracker(listOf("wss://expected.example"))
        tracker.markSent("wss://expected.example")
        tracker.markCompleted("wss://other.example")
        assertEquals(0, tracker.completedCount())
        assertEquals(1, tracker.minimumResponseCount())
    }

    @Test fun `seller relay completion can be checked independently`() {
        val tracker = MarketplaceRelayEoseTracker(listOf("wss://indexer.example", "wss://seller.example"))
        tracker.markSent("wss://indexer.example")
        tracker.markSent("wss://seller.example")
        tracker.markCompleted("wss://indexer.example")
        assertFalse(tracker.hasCompleted("wss://seller.example"))
        tracker.markCompleted("wss://seller.example")
        assertTrue(tracker.hasCompleted("wss://seller.example"))
    }

    @Test fun `all sent targets can complete`() {
        val tracker = MarketplaceRelayEoseTracker(listOf("wss://one.example", "wss://two.example"))
        tracker.markSent("wss://one.example")
        tracker.markSent("wss://two.example")
        tracker.markCompleted("wss://one.example")
        tracker.markCompleted("wss://two.example")
        assertEquals(2, tracker.completedCount())
        assertTrue(tracker.allTargetsCompleted())
    }

    @Test fun `empty sent set is complete`() {
        val tracker = MarketplaceRelayEoseTracker(listOf("wss://not-sent.example"))
        assertEquals(0, tracker.minimumResponseCount())
        assertTrue(tracker.allTargetsCompleted())
    }

    @Test fun `failed send is removed from expected set`() {
        val tracker = MarketplaceRelayEoseTracker(listOf("wss://sent.example", "wss://skipped.example"))
        tracker.markSent("wss://sent.example")
        tracker.markSent("wss://skipped.example")
        tracker.markNotSent("wss://skipped.example")
        tracker.markCompleted("wss://sent.example")
        assertEquals(1, tracker.targetCount())
        assertTrue(tracker.allTargetsCompleted())
    }
}
