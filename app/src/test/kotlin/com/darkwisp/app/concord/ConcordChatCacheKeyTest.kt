package com.darkwisp.app.concord

import com.vitorpamplona.quartz.concord.cord03Channels.ChannelChat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import com.vitorpamplona.quartz.concord.crypto.GroupKey
import com.vitorpamplona.quartz.concord.cord04Roles.ConcordPermissions

class ConcordChatCacheKeyTest {
    @Test
    fun sameEventInDifferentRoomsGetsIndependentCacheKeys() {
        val eventId = "ab".repeat(32)

        val firstRoom = concordChatCacheRecordKey(
            roomKey = "concord|owner-one|community|channel",
            eventId = eventId,
        )
        val secondRoom = concordChatCacheRecordKey(
            roomKey = "concord|owner-two|community|channel",
            eventId = eventId,
        )

        assertNotEquals(firstRoom, secondRoom)
    }

    @Test
    fun sameEventInSameRoomKeepsAnIdempotentCacheKey() {
        val roomKey = "concord|owner|community|channel"
        val eventId = "CD".repeat(32)

        assertEquals(
            concordChatCacheRecordKey(roomKey, eventId),
            concordChatCacheRecordKey(roomKey, eventId.lowercase()),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankRoomKeyIsRejected() {
        concordChatCacheRecordKey("", "ab".repeat(32))
    }

    @Test
    fun historySignatureIsStableForTheSamePlaneSetInAnyOrder() {
        val first = readPlane(1, epoch = 0)
        val second = readPlane(2, epoch = 1)

        assertEquals(
            concordChatHistoryStreamSignature(listOf(first, second)),
            concordChatHistoryStreamSignature(listOf(second, first, first)),
        )
    }

    @Test
    fun addingAWriterOrHistoricalPlaneStartsANewHistoryCursor() {
        val readPlane = readPlane(1, epoch = 0)
        val newlyDiscoveredWriterPlane = readPlane(2, epoch = 0)

        assertNotEquals(
            concordChatHistoryStreamSignature(listOf(readPlane)),
            concordChatHistoryStreamSignature(listOf(readPlane, newlyDiscoveredWriterPlane)),
        )
    }

    @Test
    fun aChangedChannelEpochStartsANewHistoryCursorEvenWhenAddressIsUnchanged() {
        val epochZero = readPlane(7, epoch = 0)
        val epochOne = readPlane(7, epoch = 1)

        assertNotEquals(
            concordChatHistoryStreamSignature(listOf(epochZero)),
            concordChatHistoryStreamSignature(listOf(epochOne)),
        )
    }

    @Test
    fun restrictedAndLockedChannelsRouteEventsToTheCorrectPlanes() {
        assertTrue(concordEventUsesWriterPlane(kind = 9, locked = false))
        assertTrue(concordEventUsesWriterPlane(kind = 1111, locked = false))
        assertFalse(concordEventUsesWriterPlane(kind = 7, locked = false))
        assertFalse(concordEventUsesWriterPlane(kind = 5, locked = false))

        assertTrue(concordEventUsesWriterPlane(kind = 9, locked = true))
        assertTrue(concordEventUsesWriterPlane(kind = 7, locked = true))
        assertTrue(concordEventUsesWriterPlane(kind = 5, locked = true))
    }

    @Test
    fun scopedWriterCertificatesCoverOnlyTheirDeclaredProtocolRights() {
        val reactions = 1uL shl ConcordPermissions.ADD_REACTIONS
        val connect = 1uL shl ConcordPermissions.CONNECT

        assertTrue(concordScopedWriterRightsCoverKind(7, reactions))
        assertTrue(concordScopedWriterRightsCoverKind(ChannelChat.KIND_TYPING, connect))
        assertTrue(concordScopedWriterRightsCoverKind(5, 0uL))
        assertFalse(concordScopedWriterRightsCoverKind(9, reactions))
        assertFalse(concordScopedWriterRightsCoverKind(7, connect))
        assertFalse(concordScopedWriterRightsCoverKind(7, reactions or (1uL shl 63)))
    }

    @Test
    fun scopedWriterCertificateDigestMatchesVectorPublishedTestVector() {
        val digest = concordScopedWriterCertificateDigest(
            communityId = ByteArray(32) { 0x01 },
            channelId = ByteArray(32) { 0x02 },
            epoch = 3,
            writerPubkey = ByteArray(32) { 0x04 },
            rights = 1uL shl ConcordPermissions.ADD_REACTIONS,
        )
        val hex = digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        assertEquals(
            "14129a75435ce645cd6c76d170f4ad61e88f54c0168a2deed48c9213b5eba10c",
            hex,
        )
    }

    private fun readPlane(addressByte: Int, epoch: Long): ConcordChatReadPlane =
        ConcordChatReadPlane(
            stream = GroupKey(
                secretKey = ByteArray(32) { 1 },
                publicKey = ByteArray(32) { addressByte.toByte() },
                conversationKey = ByteArray(32) { 2 },
            ),
            channelEpoch = epoch,
        )
}
