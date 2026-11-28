package com.darkwisp.app.concord

import com.vitorpamplona.quartz.concord.cord03Channels.ConcordWritersLedger
import com.vitorpamplona.quartz.concord.crypto.ConcordKeyDerivation
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcordWriterLedgerContentTest {
    private val channel = "00".repeat(32)
    private val community = "11".repeat(32)
    private val generator = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"

    @Test
    fun firstWriterEntryIsQuartzParseable() {
        val content = concordAppendWriterLedgerContent(
            previousContent = null, channelIdHex = channel, atMs = 1_800_000_000_000L,
            epoch = 1L, writerPubKey = generator, rootEpoch = 0L,
            minterPubKey = generator, certifierPubKey = generator,
        )
        val entity = ConcordKeyDerivation.writersCoordinate(community.hexToByteArray(), channel.hexToByteArray()).toHexKey()
        assertNotNull(ConcordWritersLedger.parse(content, community.hexToByteArray(), entity))
    }

    @Test
    fun appendingPreservesRetiredEntriesAndUnknownFields() {
        val previous = """{"channel":"$channel","ledger":[],"retired":[],"future":{"revision":7}}"""
        val content = concordAppendWriterLedgerContent(
            previousContent = previous, channelIdHex = channel, atMs = 1_800_000_000_000L,
            epoch = 1L, writerPubKey = generator, rootEpoch = 2L,
            minterPubKey = generator, certifierPubKey = generator,
        )
        assertTrue(content.contains("\"future\""))
        assertTrue(content.contains("\"retired\""))
        val entity = ConcordKeyDerivation.writersCoordinate(community.hexToByteArray(), channel.hexToByteArray()).toHexKey()
        assertNotNull(ConcordWritersLedger.parse(content, community.hexToByteArray(), entity))
    }

    @Test
    fun epochAndTimestampMustAdvance() {
        val previous = """{"channel":"$channel","ledger":[{"at":100,"epoch":2,"writer":"$generator","root":0,"by":"$generator","certifier":"$generator"}]}"""
        assertTrue(
            runCatching {
                concordAppendWriterLedgerContent(previous, channel, 101, 2, generator, 0, generator, generator)
            }.isFailure,
        )
    }
}
