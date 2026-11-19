package com.darkwisp.app.concord

import com.darkwisp.app.repo.forceScopedConcordListWritesWhenUnconfirmed
import com.vitorpamplona.quartz.concord.cord02Community.ConcordListFragmentSet
import com.vitorpamplona.quartz.concord.cord02Community.ConcordListFragments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcordPartialRelaySafetyTest {
    @Test
    fun unconfirmedCompleteSnapshotIsDowngradedToScopedWriteMode() {
        val plaintext = ConcordListFragments.serializeFragment(
            frags = 1,
            entries = emptyList(),
            tombstones = emptyList(),
        )
        val complete = ConcordListFragmentSet.of(
            listOf(
                ConcordListFragmentSet.Copy(
                    index = 0,
                    createdAt = 100L,
                    id = "known-fragment",
                    plaintext = plaintext,
                ),
            ),
        )
        assertTrue(complete.complete)

        val guarded = forceScopedConcordListWritesWhenUnconfirmed(
            fragments = complete,
            relaysConfirmed = false,
        )

        assertFalse(guarded.complete)
        assertFalse(guarded.isEmpty)
        assertEquals(complete.declared, guarded.declared)
        assertEquals(complete.held.keys, guarded.held.keys)
    }

    @Test
    fun confirmedSnapshotRemainsEligibleForNormalRepacking() {
        val plaintext = ConcordListFragments.serializeFragment(
            frags = 1,
            entries = emptyList(),
            tombstones = emptyList(),
        )
        val complete = ConcordListFragmentSet.of(
            listOf(
                ConcordListFragmentSet.Copy(0, 100L, "known-fragment", plaintext),
            ),
        )

        val unchanged = forceScopedConcordListWritesWhenUnconfirmed(
            fragments = complete,
            relaysConfirmed = true,
        )

        assertTrue(unchanged.complete)
    }

    @Test
    fun emptyUnconfirmedSnapshotIsNotInventedIntoAWritableList() {
        val empty = ConcordListFragmentSet.EMPTY
        val unchanged = forceScopedConcordListWritesWhenUnconfirmed(
            fragments = empty,
            relaysConfirmed = false,
        )

        assertTrue(unchanged.isEmpty)
        assertTrue(unchanged.complete)
    }
}
