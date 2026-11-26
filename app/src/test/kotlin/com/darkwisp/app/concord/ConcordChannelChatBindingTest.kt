package com.darkwisp.app.concord

import com.vitorpamplona.quartz.concord.cord03Channels.ChannelChat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcordChannelChatBindingTest {
    @Test
    fun messagesAreBoundToTheExactChannelAndEpoch() {
        val channelId = "33".repeat(32)
        val author = "22".repeat(32)
        val rumor = ChannelChat.message(
            authorPubKey = author,
            channelId = channelId,
            epoch = 4L,
            text = "A Concord test message",
            createdAt = 1_795_728_000L,
        )

        assertEquals(9, rumor.kind)
        assertTrue(ChannelChat.isBoundTo(rumor, channelId, 4L))
        assertFalse(ChannelChat.isBoundTo(rumor, channelId, 5L))
        assertFalse(ChannelChat.isBoundTo(rumor, "44".repeat(32), 4L))
    }
}
