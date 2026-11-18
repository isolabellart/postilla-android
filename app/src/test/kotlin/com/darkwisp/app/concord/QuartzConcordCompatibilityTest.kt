package com.darkwisp.app.concord

import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityListFragmentEvent
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteBundle
import com.vitorpamplona.quartz.concord.envelope.ConcordStreamEnvelope
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Build/runtime compatibility gate for the pinned Quartz Concord engine.
 *
 * These assertions intentionally touch the published protocol classes rather than
 * testing local placeholders: the staging integration must use the same wire kinds
 * as other Concord clients.
 */
class QuartzConcordCompatibilityTest {
    @Test
    fun concordWireKindsAreAvailable() {
        assertEquals(1059, ConcordStreamEnvelope.KIND_WRAP)
        assertEquals(33301, ConcordInviteBundle.KIND)
        assertEquals(33302, ConcordCommunityListFragmentEvent.KIND)
    }
}
