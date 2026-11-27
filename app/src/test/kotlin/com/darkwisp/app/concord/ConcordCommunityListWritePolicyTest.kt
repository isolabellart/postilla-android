package com.darkwisp.app.concord

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcordCommunityListWritePolicyTest {
    @Test
    fun completeReadAllowsWriteWhenListIsEmpty() {
        assertTrue(
            ConcordCommunityListWritePolicy.mayWrite(
                allReadRelaysResponded = true,
                hasObservedFragment = false,
                observedSetIsComplete = true,
            )
        )
    }

    @Test
    fun partialReadAllowsScopedWriteOnlyWhenObservedFragmentsAreIncomplete() {
        assertTrue(
            ConcordCommunityListWritePolicy.mayWrite(
                allReadRelaysResponded = false,
                hasObservedFragment = true,
                observedSetIsComplete = false,
            )
        )
    }

    @Test
    fun partialReadThatLooksCompleteCannotAuthorizeARepack() {
        assertFalse(
            ConcordCommunityListWritePolicy.mayWrite(
                allReadRelaysResponded = false,
                hasObservedFragment = true,
                observedSetIsComplete = true,
            )
        )
    }

    @Test
    fun partialEmptyReadNeverAuthorizesAWrite() {
        assertFalse(
            ConcordCommunityListWritePolicy.mayWrite(
                allReadRelaysResponded = false,
                hasObservedFragment = false,
                observedSetIsComplete = true,
            )
        )
    }
}
