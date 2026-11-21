package com.darkwisp.app.repo

import org.junit.Assert.assertEquals
import org.junit.Test

class DittoTrendingQueryTest {
    @Test
    fun hotFilterIsTimeScopedAndUsesDittoNip50Sorting() {
        val since = 1_795_000_000L
        val filter = DittoTrendingQuery.buildFilter(since, DittoTrendingSort.HOT)

        assertEquals(listOf(1), filter.kinds)
        assertEquals(since, filter.since)
        assertEquals("sort:hot protocol:nostr", filter.search)
        assertEquals(200, filter.limit)
    }

    @Test
    fun risingAndZapSortModesUseDocumentedDittoExtensions() {
        assertEquals(
            "sort:rising protocol:nostr",
            DittoTrendingQuery.buildFilter(1_795_000_000L, DittoTrendingSort.RISING).search
        )
        assertEquals(
            "sort:zaps protocol:nostr",
            DittoTrendingQuery.buildFilter(1_795_000_000L, DittoTrendingSort.ZAPS).search
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedLimits() {
        DittoTrendingQuery.buildFilter(1_795_000_000L, DittoTrendingSort.HOT, 201)
    }
}
