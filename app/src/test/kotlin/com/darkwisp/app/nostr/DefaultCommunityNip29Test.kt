package com.darkwisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test

class DefaultCommunityNip29Test {
    @Test
    fun defaultRelayIsThePostillaNostrfyRelayButGroupIdWaitsForCreation() {
        assertEquals("wss://nostr.isolabellart.it.com", Nip29.DEFAULT_COMMUNITY_RELAY)
        assertEquals(listOf(Nip29.DEFAULT_COMMUNITY_RELAY), Nip29.DEFAULT_GROUP_RELAYS)
        assertTrue(Nip29.DEFAULT_COMMUNITY_GROUP_ID.isBlank())
        assertFalse(Nip29.hasConfiguredDefaultCommunityGroup)
    }

    @Test
    fun legacyGroupInviteStillParses() {
        val parsed = Nip29.parseInviteLink("wss://groups.hzrd149.com'2f28ff?code=legacy123")

        assertNotNull(parsed)
        assertEquals("wss://groups.hzrd149.com", parsed!!.first)
        assertEquals("2f28ff", parsed.second)
        assertEquals("legacy123", parsed.third)
    }

    @Test
    fun canonicalNaddrInviteParsesNip29AddressAndInviteParameter() {
        val self = "11".repeat(32)
        val relay = "wss://groups.example.com"
        val address = Nip19.naddrEncode("postilla-room", self, Nip29.KIND_GROUP_METADATA, listOf(relay))

        val parsed = Nip29.parseInviteLink("$address?invite=join%20code")

        assertNotNull(parsed)
        assertEquals(relay, parsed!!.first)
        assertEquals("postilla-room", parsed.second)
        assertEquals("join code", parsed.third)
    }

    @Test
    fun naddrForNonGroupMetadataIsRejected() {
        val self = "22".repeat(32)
        val address = Nip19.naddrEncode("not-a-group", self, 1, listOf("wss://groups.example.com"))

        assertNull(Nip29.parseGroupIdentifier(address))
    }

    @Test
    fun editMetadataUsesOnlyPositiveRestrictionTags() {
        val tags = Nip29.buildEditMetadataTags(
            groupId = "room",
            name = "Community",
            isPrivate = false,
            isClosed = false,
            isRestricted = true,
            isHidden = false
        )

        assertEquals(listOf(listOf("h", "room"), listOf("name", "Community"), listOf("restricted")), tags)
        assertFalse(tags.any { it.firstOrNull() in setOf("public", "open", "unrestricted", "visible") })
    }

    @Test
    fun previousTagContainsOnlyRealHexEventPrefixes() {
        val first = "a".repeat(64)
        val second = "b".repeat(64)

        assertEquals(
            listOf("previous", "aaaaaaaa", "bbbbbbbb"),
            Nip29.buildPreviousTag(listOf(first, "not-an-event-id", second))
        )
        assertNull(Nip29.buildPreviousTag(listOf("short", "invalid")))
    }

    @Test
    fun onlyPrivateOrRestrictedMetadataRequiresMembershipAdmission() {
        val open = Nip29.GroupMetadata(
            groupId = "room",
            name = null, picture = null, about = null,
            isPrivate = false, isClosed = false, isRestricted = false, isHidden = false
        )

        assertFalse(Nip29.requiresJoinRequest(open))
        assertTrue(Nip29.requiresJoinRequest(open.copy(isPrivate = true)))
        assertTrue(Nip29.requiresJoinRequest(open.copy(isRestricted = true)))
    }
}
