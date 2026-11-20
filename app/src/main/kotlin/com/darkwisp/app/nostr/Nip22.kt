package com.darkwisp.app.nostr

/**
 * NIP-22 comments (kind 1111).
 *
 * A comment is scoped to a root by uppercase `E`/`A`/`I` tags, with
 * lowercase tags naming the immediate parent. NIP-23 article comments are
 * published as kind 1111 and retain the article's addressable root coordinate.
 */
object Nip22 {
    const val KIND_COMMENT = 1111

    fun isComment(event: NostrEvent): Boolean = event.kind == KIND_COMMENT

    fun addressableRootTag(root: NostrEvent): String = Nip99.addressTag(root)

    /** Uppercase NIP-22 A scope, used to open comments on addressable roots. */
    fun getAddressableRootTag(event: NostrEvent): String? =
        event.tags.firstOrNull { it.size >= 2 && it[0] == "A" }?.get(1)

    /** NIP-22 root scope (A/K/P) and direct parent (a/k/p) for a listing comment. */
    fun buildAddressableRootCommentTags(root: NostrEvent): List<List<String>> {
        val address = addressableRootTag(root)
        val kind = root.kind.toString()
        return listOf(listOf("A", address), listOf("K", kind), listOf("P", root.pubkey),
            listOf("a", address), listOf("k", kind), listOf("p", root.pubkey))
    }

    fun referencesAddressableRoot(event: NostrEvent, addressTag: String): Boolean =
        event.kind == KIND_COMMENT && event.tags.any { it.size >= 2 && it[0] == "A" && it[1] == addressTag }

    /** Root coordinate for a comment on an addressable long-form article. */
    fun longFormRootCoordinate(event: NostrEvent): String? =
        event.tags.firstOrNull { it.size >= 2 && it[0] == "A" && it[1].startsWith("${Nip23.KIND_LONG_FORM}:") }?.get(1)
            ?: event.tags.firstOrNull { it.size >= 2 && it[0] == "a" && it[1].startsWith("${Nip23.KIND_LONG_FORM}:") }?.get(1)

    /** Build a kind-1111 reply while retaining the NIP-23 article scope. */
    fun buildAddressableCommentReplyTags(parentComment: NostrEvent): List<List<String>> {
        val coordinate = longFormRootCoordinate(parentComment)
            ?: throw IllegalArgumentException("Parent comment is not scoped to a NIP-23 article")
        val parts = coordinate.split(":", limit = 3)
        require(parts.size == 3) { "Invalid article coordinate" }
        val rootPubkey = parentComment.tags.firstOrNull { it.size >= 2 && it[0] == "P" }?.get(1)
            ?: parts[1]
        return listOf(
            listOf("A", coordinate),
            listOf("K", parts[0]),
            listOf("P", rootPubkey),
            listOf("e", parentComment.id, "", parentComment.pubkey),
            listOf("k", parentComment.kind.toString()),
            listOf("p", parentComment.pubkey)
        )
    }

    /** Build a kind-1111 reply to a comment while retaining the listing as the thread root. */
    fun buildAddressableCommentReplyTags(root: NostrEvent, parentComment: NostrEvent): List<List<String>> {
        require(parentComment.kind == KIND_COMMENT) { "NIP-22 replies must target another kind-1111 comment" }
        val address = addressableRootTag(root)
        return listOf(
            listOf("A", address),
            listOf("K", root.kind.toString()),
            listOf("P", root.pubkey),
            listOf("e", parentComment.id, "", parentComment.pubkey),
            listOf("k", KIND_COMMENT.toString()),
            listOf("p", parentComment.pubkey)
        )
    }

    /** Immediate parent comment ID for replies in an addressable NIP-22 thread. */
    fun getParentCommentId(event: NostrEvent): String? {
        if (event.kind != KIND_COMMENT) return null
        val parentKind = event.tags.firstOrNull { it.size >= 2 && it[0] == "k" }?.get(1)
        if (parentKind != KIND_COMMENT.toString()) return null
        return event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
    }


    /**
     * Root scope of a comment: the uppercase `E` tag per NIP-22. Lowercase `e`
     * names only the immediate parent, so for a reply-to-a-comment this is the
     * only pointer to the thread root.
     */
    fun getRootScopeId(event: NostrEvent): String? =
        event.tags.firstOrNull { it.size >= 2 && it[0] == "E" }?.get(1)

    /**
     * True if the event belongs to the thread rooted at [rootId], either as a
     * direct reply (lowercase `e`) or via NIP-22 root scope (uppercase `E`).
     */
    fun referencesRoot(event: NostrEvent, rootId: String): Boolean =
        event.tags.any { it.size >= 2 && (it[0] == "e" || it[0] == "E") && it[1] == rootId }
}
