package com.darkwisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class NostrClientTagTest {
    @Test
    fun clientTagIdentifiesPostilla() {
        assertEquals(listOf("client", "Postilla"), NostrClientTag.tag)
    }

    @Test
    fun enabledClientTagReplacesLegacyAndDuplicateValues() {
        val source = listOf(
            listOf("client", "Dark Wisp"),
            listOf("e", "reply-target"),
            listOf("client", "Another client")
        )

        val result = NostrClientTag.apply(source, enabled = true)

        assertEquals(listOf(listOf("client", "Postilla")), result.filter { it.firstOrNull() == "client" })
        assertEquals(listOf("e", "reply-target"), result.first())
    }

    @Test
    fun disabledClientTagRemovesEveryClientValue() {
        val result = NostrClientTag.apply(
            listOf(listOf("client", "Dark Wisp"), listOf("e", "reply-target")),
            enabled = false
        )

        assertFalse(result.any { it.firstOrNull() == "client" })
        assertEquals(listOf("e", "reply-target"), result.single())
    }

    @Test
    fun remoteSignerCannotReturnAnEventWithAChangedLegacyClientTag() {
        val requested = NostrEvent.createUnsigned(
            pubkeyHex = "11".repeat(32),
            createdAt = 1L,
            kind = 1111,
            tags = NostrClientTag.apply(emptyList(), enabled = true),
            content = "A comment"
        )
        val altered = requested.copy(
            tags = requested.tags + listOf(listOf("client", "Dark Wisp"))
        )

        assertThrows(SignerRejectedException::class.java) {
            validateRemoteSignedEvent(requested, altered)
        }
    }

    @Test
    fun unsignedCommentEventContainsOnlyTheCanonicalClientTag() {
        val tags = NostrClientTag.apply(
            listOf(listOf("client", "Dark Wisp"), listOf("e", "reply-target")),
            enabled = true
        )
        val unsigned = NostrEvent.createUnsigned(
            pubkeyHex = "11".repeat(32),
            createdAt = 1L,
            kind = 1111,
            tags = tags,
            content = "A comment"
        )

        val clientTags = unsigned.tags.filter { it.firstOrNull() == "client" }
        assertEquals(listOf(listOf("client", "Postilla")), clientTags)
        assertTrue(unsigned.tags.any { it == listOf("e", "reply-target") })
    }
}
