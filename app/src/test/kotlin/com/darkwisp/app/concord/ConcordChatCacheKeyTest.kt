package com.darkwisp.app.concord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import com.vitorpamplona.quartz.concord.crypto.GroupKey

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
