package com.darkwisp.app.concord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcordChannelPermissionPolicyTest {
    @Test
    fun writerPublicationsReachBothCurrentAndBootstrapRelays() {
        assertEquals(
            listOf("wss://current.example", "wss://bootstrap.example"),
            concordCommunityRelayTargets(
                metadataRelays = listOf("wss://current.example/"),
                fallbackRelays = listOf("wss://bootstrap.example"),
                controlReadHasQuorum = true,
            ),
        )
    }

    @Test
    fun writerPublicationsDoNotAdoptUnverifiedMetadataRelays() {
        assertEquals(
            listOf("wss://bootstrap.example"),
            concordCommunityRelayTargets(
                metadataRelays = listOf("wss://unverified.example"),
                fallbackRelays = listOf("wss://bootstrap.example"),
                controlReadHasQuorum = false,
            ),
        )
    }

    @Test
    fun authenticatedPartialControlSnapshotCanAdoptNewMetadataRelays() {
        // A valid encrypted/folded metadata edition is useful even while some relays
        // have not sent EOSE; otherwise clients can remain stranded on stale bootstrap URLs.
        assertEquals(
            listOf("wss://new-relay.example"),
            concordEffectiveCommunityRelays(
                metadataRelays = listOf("wss://new-relay.example/"),
                fallbackRelays = listOf("wss://bootstrap.example"),
                controlReadHasQuorum = false,
            ),
        )
        assertEquals(
            listOf("wss://new-relay.example"),
            concordEffectiveCommunityRelays(
                metadataRelays = listOf("wss://new-relay.example/"),
                fallbackRelays = listOf("wss://bootstrap.example"),
                controlReadHasQuorum = true,
            ),
        )
    }

    @Test
    fun invalidMetadataRelaysFallBackToExistingBootstrapHints() {
        assertEquals(
            listOf("wss://bootstrap.example"),
            concordEffectiveCommunityRelays(
                metadataRelays = listOf("not a relay"),
                fallbackRelays = listOf("wss://bootstrap.example"),
                controlReadHasQuorum = true,
            ),
        )
    }

    @Test
    fun writerPlaneIsRequiredWhenEveryoneCannotSendEvenIfOtherRightsRemain() {
        assertTrue(
            concordChannelRequiresWriterPlane(
                rolesV2 = true,
                everyoneCanSend = false,
                hasLiveWriter = false,
            ),
        )
    }

    @Test
    fun openChannelsDoNotRequireAWriterPlaneWithoutALiveLedger() {
        assertFalse(
            concordChannelRequiresWriterPlane(
                rolesV2 = true,
                everyoneCanSend = true,
                hasLiveWriter = false,
            ),
        )
    }

    @Test
    fun existingLiveWriterLedgerKeepsItsWriterPlane() {
        assertTrue(
            concordChannelRequiresWriterPlane(
                rolesV2 = false,
                everyoneCanSend = true,
                hasLiveWriter = true,
            ),
        )
    }

    @Test
    fun communityOwnerRetainsOrdinaryChannelPermissions() {
        assertTrue(concordRolePermissionAllowsChannelAction(rolesV2 = true, isOwner = true, rolePermissionGranted = false))
    }

    @Test
    fun nonOwnerMustHaveTheRequestedRolesV2Permission() {
        assertTrue(concordRolePermissionAllowsChannelAction(rolesV2 = true, isOwner = false, rolePermissionGranted = true))
        assertFalse(concordRolePermissionAllowsChannelAction(rolesV2 = true, isOwner = false, rolePermissionGranted = false))
    }

    @Test
    fun legacyChannelsKeepTheirExistingPermissionBehaviour() {
        assertTrue(concordRolePermissionAllowsChannelAction(rolesV2 = false, isOwner = false, rolePermissionGranted = false))
    }
}

class ConcordPrivilegedRosterPolicyTest {
    @Test
    fun rosterAlwaysIncludesOwnerAndRoleHoldersAndNormalizesDuplicates() {
        val candidates = concordPrivilegedRosterCandidates(
            ownerPubkey = "OWNER",
            roleHolders = listOf("ADMIN", "MODERATOR", "admin", ""),
        )
        assertEquals(setOf("owner", "admin", "moderator"), candidates)
    }
}
