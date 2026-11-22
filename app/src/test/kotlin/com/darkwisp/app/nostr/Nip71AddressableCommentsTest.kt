package com.darkwisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Nip71AddressableCommentsTest {
    private val author = "e2ccf7cf20403f3f2a4a55b328f0de3be38558a7d5f33632fdaaefc726c1c8eb"

    private fun event(id: String, pubkey: String, kind: Int, tags: List<List<String>>) = NostrEvent(
        id = id, pubkey = pubkey, created_at = 1_800_000_000L, kind = kind,
        tags = tags, content = "Video discussion", sig = "test"
    )

    @Test
    fun videoRootCommentsUseTheAddressableCoordinate() {
        val root = event("video-event", author, Nip71.KIND_VIDEO_ADDRESSABLE_VERTICAL,
            listOf(listOf("d", "portrait-1"), listOf("title", "Portrait video")))
        val coordinate = "${Nip71.KIND_VIDEO_ADDRESSABLE_VERTICAL}:$author:portrait-1"
        val tags = Nip22.buildAddressableRootCommentTags(root)
        val comment = event("comment-1", "comment-author", Nip22.KIND_COMMENT, tags)
        assertTrue(tags.contains(listOf("A", coordinate)))
        assertEquals(coordinate, Nip22.addressableRootCoordinate(comment))
        assertEquals(coordinate, Nip22.getRootScopeId(comment))
        assertTrue(Nip22.referencesRoot(comment, coordinate))
    }

    @Test
    fun nestedVideoCommentKeepsTheVideoRootAndTargetsItsParent() {
        val coordinate = "${Nip71.KIND_VIDEO_ADDRESSABLE_HORIZONTAL}:$author:clip-1"
        val parent = event("parent-comment", "parent-author", Nip22.KIND_COMMENT,
            listOf(listOf("A", coordinate),
                listOf("K", Nip71.KIND_VIDEO_ADDRESSABLE_HORIZONTAL.toString()),
                listOf("P", author)))
        val nestedTags = Nip22.buildAddressableCommentReplyTags(parent)
        val nested = event("nested-comment", "nested-author", Nip22.KIND_COMMENT, nestedTags)
        assertTrue(nestedTags.contains(listOf("A", coordinate)))
        assertTrue(nestedTags.contains(listOf("e", parent.id, "", parent.pubkey)))
        assertEquals(coordinate, Nip22.getRootScopeId(nested))
        assertEquals(parent.id, Nip10.getReplyTarget(nested))
    }
}
