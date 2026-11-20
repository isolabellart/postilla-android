package com.darkwisp.app.nostr

/** NIP-23 long-form articles (kind 30023). */
object Nip23 {
    const val KIND_LONG_FORM = 30023

    /** Select the current NIP-23 revision for one stable author + d-tag coordinate. */
    fun latestRevision(events: Iterable<NostrEvent>, authorPubkey: String, dTag: String): NostrEvent? {
        if (authorPubkey.isBlank() || dTag.isBlank()) return null
        var latest: NostrEvent? = null
        for (candidate in events) {
            if (candidate.kind != KIND_LONG_FORM || candidate.pubkey != authorPubkey || Nip23.dTag(candidate) != dTag) continue
            val current = latest
            if (current == null || Nip01EventOrder.isNewer(candidate, current)) latest = candidate
        }
        return latest
    }

    fun dTag(event: NostrEvent): String =
        event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1).orEmpty()

    fun title(event: NostrEvent): String =
        event.tags.firstOrNull { it.size >= 2 && it[0] == "title" }?.get(1).orEmpty()

    fun summary(event: NostrEvent): String =
        event.tags.firstOrNull { it.size >= 2 && it[0] == "summary" }?.get(1).orEmpty()

    fun image(event: NostrEvent): String =
        event.tags.firstOrNull { it.size >= 2 && it[0] == "image" }?.get(1).orEmpty()

    fun hashtags(event: NostrEvent): List<String> =
        event.tags.filter { it.size >= 2 && it[0] == "t" }.map { it[1] }

    fun buildArticleTags(
        dTag: String,
        title: String,
        summary: String = "",
        image: String = "",
        publishedAt: Long,
        hashtags: List<String> = emptyList(),
        includeClientTag: Boolean = true
    ): List<List<String>> {
        require(dTag.isNotBlank()) { "NIP-23 articles require a non-empty d-tag" }
        require(title.isNotBlank()) { "NIP-23 articles require a title" }
        return buildList {
            add(listOf("d", dTag.trim()))
            add(listOf("title", title.trim()))
            if (summary.isNotBlank()) add(listOf("summary", summary.trim()))
            if (image.isNotBlank()) add(listOf("image", image.trim()))
            add(listOf("published_at", publishedAt.coerceAtLeast(0L).toString()))
            hashtags.map { it.trim().removePrefix("#").lowercase() }
                .filter { it.isNotBlank() }.distinct().forEach { add(listOf("t", it)) }
            if (includeClientTag) add(listOf("client", "Postilla"))
        }
    }
}
