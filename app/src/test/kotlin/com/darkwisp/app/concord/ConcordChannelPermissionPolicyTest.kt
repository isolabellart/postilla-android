package com.darkwisp.app.concord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcordChannelPermissionPolicyTest {
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
