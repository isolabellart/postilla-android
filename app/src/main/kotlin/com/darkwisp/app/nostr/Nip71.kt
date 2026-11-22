package com.darkwisp.app.nostr

object Nip71 {
    const val KIND_VIDEO_HORIZONTAL = 21
    const val KIND_VIDEO_VERTICAL = 22
    const val KIND_VIDEO_ADDRESSABLE_HORIZONTAL = 34235
    const val KIND_VIDEO_ADDRESSABLE_VERTICAL = 34236

    /** All NIP-71 video event kinds supported by Postilla. */
    val VIDEO_KINDS: List<Int> = listOf(
        KIND_VIDEO_HORIZONTAL,
        KIND_VIDEO_VERTICAL,
        KIND_VIDEO_ADDRESSABLE_HORIZONTAL,
        KIND_VIDEO_ADDRESSABLE_VERTICAL
    )

    /** Choose a familiar default from the uploaded video's shape; users can change it. */
    fun defaultVideoKind(width: Int?, height: Int?): Int =
        if (width != null && height != null && width > 0 && height > width) {
            KIND_VIDEO_VERTICAL
        } else {
            KIND_VIDEO_HORIZONTAL
        }

    data class VideoMeta(
        val url: String,
        val mimeType: String? = null,
        val dim: String? = null,
        val thumbnailUrl: String? = null,
        val duration: Int? = null,
        val thumbhash: String? = null,
        val blurhash: String? = null,
        val hash: String? = null,
        val fallback: List<String> = emptyList()
    )

    fun parseVideoMeta(event: NostrEvent): List<VideoMeta> {
        return event.tags.filter { it.firstOrNull() == "imeta" && it.size > 1 }
            .mapNotNull { tag ->
                val fields = mutableMapOf<String, String>()
                val fallbacks = mutableListOf<String>()
                for (i in 1 until tag.size) {
                    val part = tag[i]
                    val spaceIdx = part.indexOf(' ')
                    if (spaceIdx > 0) {
                        val key = part.substring(0, spaceIdx)
                        val value = part.substring(spaceIdx + 1)
                        if (key == "fallback") fallbacks.add(value)
                        else fields[key] = value
                    }
                }
                val url = fields["url"] ?: return@mapNotNull null
                val eventDuration = event.tags.firstOrNull {
                    it.firstOrNull() == "duration" && it.size > 1
                }?.get(1)?.toIntOrNull()
                VideoMeta(
                    url = url,
                    mimeType = fields["m"],
                    dim = fields["dim"],
                    thumbnailUrl = fields["image"],
                    duration = fields["duration"]?.toIntOrNull() ?: eventDuration,
                    thumbhash = fields["thumbhash"],
                    blurhash = fields["blurhash"],
                    hash = fields["x"],
                    fallback = fallbacks
                )
            }
    }

    fun buildVideoTags(
        title: String?,
        media: List<VideoMeta>,
        hashtags: List<String> = emptyList(),
        contentWarning: String? = null,
        dTag: String? = null,
        publishedAt: Long? = null
    ): List<List<String>> {
        if (dTag != null) {
            require(dTag.isNotBlank()) { "NIP-71 addressable videos require a non-empty d tag" }
            require(!title.isNullOrBlank()) { "NIP-71 addressable videos require a title" }
            require(publishedAt != null && publishedAt > 0L) {
                "NIP-71 addressable videos require a positive published_at timestamp"
            }
        }
        val tags = mutableListOf<List<String>>()
        dTag?.let { tags.add(listOf("d", it)) }
        if (!title.isNullOrBlank()) {
            tags.add(listOf("title", title))
        }
        publishedAt?.let { tags.add(listOf("published_at", it.toString())) }
        for (entry in media) {
            val imetaParts = mutableListOf("imeta")
            imetaParts.add("url ${entry.url}")
            entry.mimeType?.let { imetaParts.add("m $it") }
            entry.dim?.let { imetaParts.add("dim $it") }
            entry.thumbnailUrl?.let { imetaParts.add("image $it") }
            entry.duration?.let { imetaParts.add("duration $it") }
            entry.thumbhash?.let { imetaParts.add("thumbhash $it") }
            entry.blurhash?.let { imetaParts.add("blurhash $it") }
            entry.hash?.let { imetaParts.add("x $it") }
            for (fb in entry.fallback) imetaParts.add("fallback $fb")
            tags.add(imetaParts)
        }
        media.firstOrNull { it.duration != null }?.duration?.let {
            tags.add(listOf("duration", it.toString()))
        }
        for (hashtag in hashtags) {
            tags.add(listOf("t", hashtag))
        }
        contentWarning?.let { tags.add(listOf("content-warning", it)) }
        return tags
    }

    fun getTitle(event: NostrEvent): String? =
        event.tags.firstOrNull { it.firstOrNull() == "title" && it.size > 1 }?.get(1)

    fun getDTag(event: NostrEvent): String? =
        event.tags.firstOrNull { it.firstOrNull() == "d" && it.size > 1 }?.get(1)

    fun getPublishedAt(event: NostrEvent): Long? =
        event.tags.firstOrNull { it.firstOrNull() == "published_at" && it.size > 1 }?.get(1)?.toLongOrNull()

    fun isVideoKind(kind: Int): Boolean = kind in VIDEO_KINDS

    fun isAddressableVideoKind(kind: Int): Boolean =
        kind == KIND_VIDEO_ADDRESSABLE_HORIZONTAL || kind == KIND_VIDEO_ADDRESSABLE_VERTICAL

    fun isAddressableVideo(event: NostrEvent): Boolean = isAddressableVideoKind(event.kind)

    fun isVideo(event: NostrEvent): Boolean = isVideoKind(event.kind)
}
