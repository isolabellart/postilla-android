package com.darkwisp.app.repo

import com.darkwisp.app.nostr.Filter

/** NIP-50 sorting modes documented by the public Ditto relay. */
internal enum class DittoTrendingSort(val queryToken: String) {
    HOT("sort:hot protocol:nostr"),
    RISING("sort:rising protocol:nostr"),
    ZAPS("sort:zaps protocol:nostr")
}

/** Builds bounded, time-scoped NIP-50 filters for Ditto's public trends index. */
internal object DittoTrendingQuery {
    fun buildFilter(since: Long, sort: DittoTrendingSort, limit: Int = 200): Filter {
        require(since > 0L) { "since must be a positive Unix timestamp" }
        require(limit in 1..200) { "limit must be between 1 and 200" }
        return Filter(
            kinds = listOf(1),
            since = since,
            search = sort.queryToken,
            limit = limit
        )
    }
}
