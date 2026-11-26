package com.darkwisp.app.relay

import org.junit.Assert.assertEquals
import org.junit.Test

class EventBroadcastStatusTest {
    @Test
    fun acceptedRelayIsConfirmed() {
        assertEquals(
            EventBroadcastStatus.ACCEPTED,
            classifyEventBroadcastStatus(
                sent = true,
                result = PublishResult("wss://relay.example", "event-id", true, "stored")
            )
        )
    }

    @Test
    fun duplicateRelayIsReportedAsAlreadyPresent() {
        assertEquals(
            EventBroadcastStatus.ALREADY_PRESENT,
            classifyEventBroadcastStatus(
                sent = true,
                result = PublishResult("wss://relay.example", "event-id", false, "duplicate: already have this event")
            )
        )
    }

    @Test
    fun rejectedRelayIsNotCountedAsConfirmed() {
        assertEquals(
            EventBroadcastStatus.REJECTED,
            classifyEventBroadcastStatus(
                sent = true,
                result = PublishResult("wss://relay.example", "event-id", false, "blocked: event rejected")
            )
        )
    }

    @Test
    fun unavailableRelayIsNotQueuedForLaterBroadcast() {
        assertEquals(
            EventBroadcastStatus.NOT_SENT,
            classifyEventBroadcastStatus(sent = false, result = null)
        )
    }

    @Test
    fun missingAcknowledgementIsReportedSeparately() {
        assertEquals(
            EventBroadcastStatus.NO_ACK,
            classifyEventBroadcastStatus(sent = true, result = null)
        )
    }
}
