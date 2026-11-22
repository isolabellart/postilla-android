package com.darkwisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Nip71Test {
    private fun event(kind: Int, tags: List<List<String>>) = NostrEvent(
        id = "event-id",
        pubkey = "author",
        created_at = 1_700_000_000L,
        kind = kind,
        tags = tags,
        content = "A video description",
        sig = "signature"
    )

    @Test
    fun addressableVideoBuilderEmitsRequiredCoordinateAndMetadata() {
        val tags = Nip71.buildVideoTags(
            title = "A short film",
            media = listOf(
                Nip71.VideoMeta(
                    url = "https://media.example/video.mp4",
                    mimeType = "video/mp4",
                    dim = "1080x1920",
                    duration = 42
                )
            ),
            dTag = "short-film-1",
            publishedAt = 1_700_000_000L
        )
        assertEquals(listOf("d", "short-film-1"), tags.first { it.firstOrNull() == "d" })
        assertEquals(listOf("title", "A short film"), tags.first { it.firstOrNull() == "title" })
        assertEquals(listOf("published_at", "1700000000"), tags.first { it.firstOrNull() == "published_at" })
        assertEquals(listOf("duration", "42"), tags.first { it.firstOrNull() == "duration" })
        assertTrue(tags.any { it.firstOrNull() == "imeta" && it.any { part -> part == "url https://media.example/video.mp4" } })
    }

    @Test
    fun addressableVideoBuilderRejectsMissingTitleOrTimestamp() {
        try {
            Nip71.buildVideoTags(
                title = " ",
                media = listOf(Nip71.VideoMeta("https://media.example/video.mp4")),
                dTag = "video",
                publishedAt = 1_700_000_000L
            )
            throw AssertionError("Expected missing title to be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            Nip71.buildVideoTags(
                title = "Video",
                media = listOf(Nip71.VideoMeta("https://media.example/video.mp4")),
                dTag = "video",
                publishedAt = null
            )
            throw AssertionError("Expected missing published_at to be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun parsesTopLevelDurationAndAddressableMetadata() {
        val video = event(
            Nip71.KIND_VIDEO_ADDRESSABLE_VERTICAL,
            listOf(
                listOf("d", "portrait-video"),
                listOf("title", "Portrait"),
                listOf("published_at", "1700000000"),
                listOf("duration", "37"),
                listOf("imeta", "url https://media.example/portrait.mp4", "m video/mp4", "dim 1080x1920")
            )
        )
        val media = Nip71.parseVideoMeta(video).single()
        assertEquals(37, media.duration)
        assertEquals("https://media.example/portrait.mp4", media.url)
        assertEquals("portrait-video", Nip71.getDTag(video))
        assertEquals(1_700_000_000L, Nip71.getPublishedAt(video))
        assertTrue(Nip71.isAddressableVideo(video))
    }

    @Test
    fun detectsBothLegacyAndAddressableVideoKinds() {
        assertTrue(Nip71.isVideo(event(Nip71.KIND_VIDEO_HORIZONTAL, emptyList())))
        assertTrue(Nip71.isVideo(event(Nip71.KIND_VIDEO_VERTICAL, emptyList())))
        assertTrue(Nip71.isVideo(event(Nip71.KIND_VIDEO_ADDRESSABLE_HORIZONTAL, emptyList())))
        assertTrue(Nip71.isVideo(event(Nip71.KIND_VIDEO_ADDRESSABLE_VERTICAL, emptyList())))
        assertFalse(Nip71.isAddressableVideo(event(Nip71.KIND_VIDEO_HORIZONTAL, emptyList())))
    }
}
