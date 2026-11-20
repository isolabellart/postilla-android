package com.darkwisp.app.repo

import com.darkwisp.app.nostr.ProfileData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSearchTest {
    private fun profile(
        pubkey: String,
        name: String?,
        displayName: String? = null,
        nip05: String? = null,
        about: String? = null,
        updatedAt: Long = 1L
    ) = ProfileData(
        pubkey = pubkey,
        name = name,
        displayName = displayName,
        about = about,
        picture = null,
        banner = null,
        nip05 = nip05,
        lud16 = null,
        updatedAt = updatedAt
    )

    @Test
    fun `prioritizes exact and prefix name matches over biography matches`() {
        val exact = profile("a", "alice")
        val prefix = profile("b", "alice-art")
        val partial = profile("c", "the-alice-gallery")
        val bio = profile("d", "artist", about = "I paint with Alice")

        val ranked = rankProfileSearchResults(listOf(bio, partial, prefix, exact), "alice")

        assertEquals(listOf("a", "b", "c", "d"), ranked.map { it.pubkey })
    }

    @Test
    fun `matches display name NIP05 and public key`() {
        assertTrue(profileSearchScore(profile("alice-key", "artist", displayName = "Alice"), "alice") > 0)
        assertTrue(profileSearchScore(profile("b", "artist", nip05 = "alice@example.com"), "alice") > 0)
        assertTrue(profileSearchScore(profile("alice0123", "artist"), "alice") > 0)
    }

    @Test
    fun `deduplicates profiles and keeps the newest metadata event`() {
        val old = profile("same", "alice-old", updatedAt = 10L)
        val newest = profile("same", "alice", updatedAt = 20L)
        val other = profile("other", "alice-friend", updatedAt = 15L)

        val ranked = rankProfileSearchResults(listOf(old, other, newest), "alice")

        assertEquals(2, ranked.size)
        assertEquals("alice", ranked.first().name)
        assertEquals(20L, ranked.first().updatedAt)
    }
}
