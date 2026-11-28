package com.darkwisp.app.concord

import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityListEntry
import com.vitorpamplona.quartz.concord.cord02Community.PrivateChannelKey
import com.vitorpamplona.quartz.concord.cord05Invites.CommunityInvite
import com.vitorpamplona.quartz.concord.cord05Invites.InviteChannel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcordDirectInviteKeyCatchUpTest {
    private val communityId = "11".repeat(32)
    private val root = "22".repeat(32)
    private val controlPk = "33".repeat(32)
    private val heldChannel = "a1".repeat(32)
    private val addedChannel = "b2".repeat(32)
    private val heldKey = "ca".repeat(32)
    private val newKey = "db".repeat(32)

    private val held = ConcordCommunityListEntry(
        id = communityId,
        owner = "44".repeat(32),
        ownerSalt = "55".repeat(32),
        root = root,
        rootEpoch = 3,
        controlPk = controlPk,
        privateChannels = listOf(PrivateChannelKey(heldChannel, heldKey, 1, "moderators")),
        relays = listOf("wss://relay.example"),
        name = "Test community",
        inviteRef = "naddr1test",
    )

    private fun invite(
        root: String = this.root,
        epoch: Long = 3,
        controlPk: String? = this.controlPk,
        channels: List<InviteChannel>,
    ) = CommunityInvite(
        communityId = communityId,
        owner = held.owner,
        ownerSalt = held.ownerSalt,
        communityRoot = root,
        rootEpoch = epoch,
        controlPk = controlPk,
        channels = channels,
        name = "Test community",
    )

    @Test
    fun inboxShowsNewPrivateKeyForTheSameMembershipBase() {
        val direct = invite(
            channels = listOf(
                InviteChannel(heldChannel, heldKey, 1, "moderators"),
                InviteChannel(addedChannel, newKey, 0, "private"),
            ),
        )
        assertTrue(concordHasDirectInviteKeyCatchUp(held, direct))
    }

    @Test
    fun inboxHidesOrdinaryDuplicateInviteWithoutNewKeys() {
        val duplicate = invite(
            channels = listOf(InviteChannel(heldChannel, heldKey, 1, "moderators")),
        )
        assertFalse(concordHasDirectInviteKeyCatchUp(held, duplicate))
    }

    @Test
    fun inboxHidesInviteThatChangesTheCommunityBase() {
        val changedBase = invite(
            root = "99".repeat(32),
            channels = listOf(InviteChannel(addedChannel, newKey, 0, "private")),
        )
        assertFalse(concordHasDirectInviteKeyCatchUp(held, changedBase))
    }

    @Test
    fun inboxHidesKeylessOrLowerEpochDelivery() {
        val keyless = invite(channels = listOf(InviteChannel(addedChannel, "", 0, "private")))
        val stale = invite(channels = listOf(InviteChannel(heldChannel, "ee".repeat(32), 1, "moderators")))
        assertFalse(concordHasDirectInviteKeyCatchUp(held, keyless))
        assertFalse(concordHasDirectInviteKeyCatchUp(held, stale))
    }
}
