package com.darkwisp.app.ui.screen

import com.darkwisp.app.nostr.NostrEvent
import com.darkwisp.app.viewmodel.thread.ThreadItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketplaceCommentThreadsTest {
    private fun event(
        id: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>> = emptyList()
    ) = NostrEvent(id, "author", createdAt, kind, tags, "", "")

    private val listing = event("listing", 1L, 30402, listOf(listOf("d", "painting")))

    @Test
    fun rootCommentGetsPostillaClientTagWhenEnabled() {
        val tags = buildMarketplaceCommentTags(listing, null, includeClientTag = true)
        assertTrue(tags.contains(listOf("client", "Postilla")))
        assertTrue(tags.any { it.firstOrNull() == "A" })
    }

    @Test
    fun clientTagRespectsTheExistingInterfacePreference() {
        val tags = buildMarketplaceCommentTags(listing, null, includeClientTag = false)
        assertFalse(tags.contains(listOf("client", "Postilla")))
    }

    @Test
    fun replyRetainsNip22ParentAndClientReferences() {
        val parent = event("parent", 2L, 1111)
        val tags = buildMarketplaceCommentTags(listing, parent, includeClientTag = true)
        assertTrue(tags.contains(listOf("e", "parent", "", "author")))
        assertTrue(tags.contains(listOf("k", "1111")))
        assertTrue(tags.contains(listOf("client", "Postilla")))
    }

    @Test
    fun reactionFiltersIncludeCommentIdsInBoundedBatches() {
        val ids = (1..101).map { "comment-$it" }
        val filters = buildMarketplaceCommentReactionFilters("listing-id", "30402:seller:painting", ids)
        assertEquals(4, filters.size)
        assertEquals(listOf("listing-id"), filters[0].eTags)
        assertEquals(listOf("30402:seller:painting"), filters[1].aTags)
        assertEquals(100, filters[2].eTags?.size)
        assertEquals(listOf("comment-101"), filters[3].eTags)
    }

    @Test
    fun repliesAreFlattenedUnderTheirNip22ParentWithThreadDepth() {
        val rootComment = event("root-comment", 10L, 1111, listOf(listOf("A", "30402:seller:painting")))
        val reply = event(
            "reply", 11L, 1111,
            listOf(
                listOf("A", "30402:seller:painting"),
                listOf("e", "root-comment", "", "author"),
                listOf("k", "1111")
            )
        )
        val posts = buildMarketplaceCommentThread("listing-id", listOf(rootComment, reply))
            .filterIsInstance<ThreadItem.Post>()
        assertEquals(listOf("root-comment", "reply"), posts.map { it.event.id })
        assertEquals(listOf(0, 1), posts.map { it.depth })
        assertTrue(posts.last().connectorStartsMidAir)
    }

    @Test
    fun replyWithUnavailableParentRemainsVisibleAsTopLevelComment() {
        val reply = event(
            "orphan", 11L, 1111,
            listOf(
                listOf("A", "30402:seller:painting"),
                listOf("e", "not-loaded", "", "author"),
                listOf("k", "1111")
            )
        )
        val posts = buildMarketplaceCommentThread("listing-id", listOf(reply))
            .filterIsInstance<ThreadItem.Post>()
        assertEquals(listOf("orphan"), posts.map { it.event.id })
        assertEquals(0, posts.single().depth)
    }
}
