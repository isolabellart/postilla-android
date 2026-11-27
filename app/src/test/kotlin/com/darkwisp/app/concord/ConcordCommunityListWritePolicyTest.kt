package com.darkwisp.app.concord

import com.vitorpamplona.quartz.concord.cord02Community.ConcordListFragmentSet
import com.vitorpamplona.quartz.concord.cord02Community.ConcordListFragments
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcordCommunityListWritePolicyTest {
    @Test
    fun readQuorumScalesToTwoThirdsWithoutRequiringEveryRelay() {
        assertEquals(20, ConcordCommunityListWritePolicy.requiredReadRelayResponses(30))
        assertEquals(17, ConcordCommunityListWritePolicy.requiredReadRelayResponses(25))
        assertEquals(2, ConcordCommunityListWritePolicy.requiredReadRelayResponses(3))
        assertEquals(2, ConcordCommunityListWritePolicy.requiredReadRelayResponses(2))
        assertEquals(1, ConcordCommunityListWritePolicy.requiredReadRelayResponses(1))
    }

    @Test
    fun completeReadAllowsWriteWhenListIsEmpty() {
        assertTrue(
            ConcordCommunityListWritePolicy.mayWrite(
                allReadRelaysResponded = true,
                readQuorumReached = true,
                hasWritableFragment = false,
            )
        )
    }

    @Test
    fun partialReadAllowsScopedWriteWhenAReadableFragmentExists() {
        assertTrue(
            ConcordCommunityListWritePolicy.mayWrite(
                allReadRelaysResponded = false,
                readQuorumReached = true,
                hasWritableFragment = true,
            )
        )
    }

    @Test
    fun partialReadBelowQuorumCannotAuthorizeAWrite() {
        assertFalse(
            ConcordCommunityListWritePolicy.mayWrite(
                allReadRelaysResponded = false,
                readQuorumReached = false,
                hasWritableFragment = true,
            )
        )
    }

    @Test
    fun partialEmptyOrUnreadableReadNeverAuthorizesAWrite() {
        assertFalse(
            ConcordCommunityListWritePolicy.mayWrite(
                allReadRelaysResponded = false,
                readQuorumReached = true,
                hasWritableFragment = false,
            )
        )
    }

    private fun material(name: String) = buildJsonObject {
        put("owner", "1".repeat(64))
        put("owner_salt", "2".repeat(64))
        put("community_root", "3".repeat(64))
        put("root_epoch", 0)
        put("control_pk", "4".repeat(64))
        put("channels", buildJsonArray { })
        put("relays", buildJsonArray { })
        put("name", name)
    }

    private fun membership(id: String, name: String) = buildJsonObject {
        put("community_id", id)
        put("seed", material(name))
        put("current", material(name))
        put("added_at", 1L)
    }

    private fun document(entries: List<JsonObject>) = buildJsonObject {
        put("entries", JsonArray(entries))
        put("tombstones", JsonArray(emptyList()))
    }

    @Test
    fun partialReadScopesNewMembershipToHeldFragmentWithoutRepacking() {
        val existing = membership("a".repeat(64), "Existing")
        val added = membership("b".repeat(64), "New")
        val wireEntry = ConcordListFragments.entryToWire(existing)
        val originalPlaintext = ConcordListFragments.serializeFragment(1, listOf(wireEntry), emptyList())
        val set = ConcordListFragmentSet.of(
            listOf(ConcordListFragmentSet.Copy(0, 100, "event-0", originalPlaintext))
        )
        assertTrue(set.complete)

        val next = ConcordListFragments.mergeDocs(set.doc, document(listOf(added)))
        val writes = ConcordCommunityListWritePolicy.planScopedWrites(set, next, now = 50)

        assertEquals(listOf(0), writes.map { it.index })
        assertEquals(101L, writes.single().createdAt)
        val decoded = ConcordListFragments.decodeFragment(writes.single().plaintext)
        assertEquals(1, decoded.frags)
        assertEquals(2, decoded.doc["entries"]!!.let { (it as JsonArray).size })
    }

    @Test
    fun scopedWriteDoesNotInventAFragmentWhenNothingReadableWasFetched() {
        val empty = ConcordListFragmentSet.EMPTY
        val added = membership("c".repeat(64), "New")
        val next = document(listOf(added))
        assertTrue(
            runCatching {
                ConcordCommunityListWritePolicy.planScopedWrites(empty, next, now = 100)
            }.isFailure
        )
    }
}
