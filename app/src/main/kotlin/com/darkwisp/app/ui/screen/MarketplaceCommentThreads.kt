package com.darkwisp.app.ui.screen

import com.darkwisp.app.nostr.Filter
import com.darkwisp.app.nostr.Nip22
import com.darkwisp.app.nostr.NostrClientTag
import com.darkwisp.app.nostr.NostrEvent
import com.darkwisp.app.viewmodel.thread.ThreadFlattener
import com.darkwisp.app.viewmodel.thread.ThreadItem

internal fun buildMarketplaceCommentTags(
    listing: NostrEvent,
    parentComment: NostrEvent?,
    includeClientTag: Boolean
): List<List<String>> {
    val tags = (parentComment?.let { Nip22.buildAddressableCommentReplyTags(listing, it) }
        ?: Nip22.buildAddressableRootCommentTags(listing)).toMutableList()
    return NostrClientTag.apply(tags, includeClientTag)
}

/** Listing reactions use the listing address; discussion reactions use the comment event ID. */
internal fun buildMarketplaceCommentReactionFilters(
    listingEventId: String,
    listingAddress: String,
    commentIds: Collection<String>
): List<Filter> {
    val filters = mutableListOf(
        Filter(kinds = listOf(7), eTags = listOf(listingEventId), limit = 500),
        Filter(kinds = listOf(7), aTags = listOf(listingAddress), limit = 1000)
    )
    commentIds.asSequence().filter { it.isNotBlank() }.distinct().toList()
        .chunked(100)
        .forEach { ids -> filters += Filter(kinds = listOf(7), eTags = ids, limit = 500) }
    return filters
}

/** Build the NIP-22 parent tree, then reuse the main feed's depth cap and connector flags. */
internal fun buildMarketplaceCommentThread(
    listingEventId: String,
    comments: List<NostrEvent>,
    expandedIds: Set<String> = emptySet()
): List<ThreadItem> {
    val knownIds = comments.mapTo(mutableSetOf()) { it.id }
    val parentToChildren = mutableMapOf<String, MutableList<NostrEvent>>()
    comments.forEach { comment ->
        val parentId = Nip22.getParentCommentId(comment)?.takeIf { it in knownIds } ?: listingEventId
        parentToChildren.getOrPut(parentId) { mutableListOf() }.add(comment)
    }
    val ordered = parentToChildren.mapValues { (_, children) -> children.sortedBy { it.created_at } }
    return ThreadFlattener.flatten(
        rootId = listingEventId,
        rootEvent = null,
        parentToChildren = ordered,
        expandedIds = expandedIds,
        maxSiblingsInline = Int.MAX_VALUE
    )
}
