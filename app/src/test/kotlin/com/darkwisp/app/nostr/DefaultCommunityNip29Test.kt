package com.darkwisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultCommunityNip29Test {
    @Test
    fun defaultGroupRelayPointsToPostillaCommunity() {
        assertEquals("wss://groups.hzrd149.com", Nip29.DEFAULT_GROUP_RELAYS.first())
    }

    @Test
    fun defaultCommunityInviteParsesWithoutAnInviteCode() {
        val parsed = Nip29.parseInviteLink(Nip29.DEFAULT_COMMUNITY_INVITE)

        assertNotNull(parsed)
        assertEquals(Nip29.DEFAULT_COMMUNITY_RELAY, parsed!!.first)
        assertEquals(Nip29.DEFAULT_COMMUNITY_GROUP_ID, parsed.second)
        assertNull(parsed.third)
    }

    @Test
    fun onlyPrivateOrRestrictedMetadataRequiresMembershipAdmission() {
        val open = Nip29.GroupMetadata(
            groupId = Nip29.DEFAULT_COMMUNITY_GROUP_ID,
            name = null, picture = null, about = null,
            isPrivate = false, isClosed = false, isRestricted = false, isHidden = false
        )

        assertFalse(Nip29.requiresJoinRequest(open))
        assertTrue(Nip29.requiresJoinRequest(open.copy(isPrivate = true)))
        assertTrue(Nip29.requiresJoinRequest(open.copy(isRestricted = true)))
    }
}
