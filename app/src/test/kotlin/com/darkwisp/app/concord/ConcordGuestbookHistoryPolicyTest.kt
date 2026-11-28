package com.darkwisp.app.concord

import org.junit.Assert.assertEquals
import org.junit.Test

class ConcordGuestbookHistoryPolicyTest {
    @Test
    fun currentAndHeldRootEpochsAreReadWithoutDuplicates() {
        assertEquals(
            listOf(1L, 2L, 4L),
            concordGuestbookEpochs(currentEpoch = 4L, heldEpochs = listOf(1L, 2L, 2L)),
        )
    }

    @Test
    fun currentEpochIsIncludedWhenThereAreNoHeldRoots() {
        assertEquals(listOf(7L), concordGuestbookEpochs(currentEpoch = 7L, heldEpochs = emptyList()))
    }
}
