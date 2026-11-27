package com.darkwisp.app.concord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

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
}
