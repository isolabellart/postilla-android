package com.darkwisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlossomServerRecommendationsTest {
    @Test
    fun recommendedFreeServersAreHttpsAndHaveUniqueUrls() {
        val servers = Blossom.RECOMMENDED_FREE_SERVERS

        assertEquals(
            listOf(
                "https://blossom.band",
                "https://blossom.azzamo.media"
            ),
            servers.map { it.url }
        )
        assertTrue(servers.all { it.url.startsWith("https://") })
        assertEquals(servers.size, servers.map { it.url.lowercase().trimEnd('/') }.distinct().size)
        assertEquals(servers.map { it.url }, Blossom.DEFAULT_SERVERS)
    }
}
