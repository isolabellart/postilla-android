package com.darkwisp.app.relay

import kotlinx.serialization.Serializable

enum class LocalRelayWritePolicy { OWN_NOTES, TAGGED, ALL_NOTES }

@Serializable
data class LocalRelayConfig(
    val url: String,
    val enabled: Boolean = true,
    val writePolicy: LocalRelayWritePolicy = LocalRelayWritePolicy.OWN_NOTES,
    val kinds: Set<Int> = setOf(1, 1059, 9735)
)

enum class RelaySetType(val displayName: String, val eventKind: Int) {
    GENERAL("General", 10002),
    DM("DM", 10050),
    SEARCH("Search", 10007),
    BLOCKED("Blocked", 10006),
    LOCAL("Local", 0)
}

@Serializable
data class RelayConfig(
    val url: String,
    val read: Boolean = true,
    val write: Boolean = true,
    val auth: Boolean = true
) {
    companion object {
        val DEFAULTS = listOf(
            RelayConfig("wss://relay.damus.io", read = true, write = true),
            RelayConfig("wss://relay.primal.net", read = true, write = true),
            RelayConfig("wss://indexer.coracle.social", read = true, write = false),
            RelayConfig("wss://relay.nos.social", read = true, write = false)
        )

        /** Default DM relays applied when a user has no DM relay set (kind 10050). */
        val DEFAULT_DM_RELAYS = listOf(
            "wss://auth.nostr1.com"
        )

        // Free public relays only: the paid nostr.wine WebSocket relay is intentionally excluded.
        val DEFAULT_SEARCH_RELAYS = listOf(
            "wss://relay.noswhere.com",
            "wss://search.nos.today",
            "wss://antiprimal.net",
            "wss://relay.ditto.pub",
            "wss://search.brainstorm.world"
        )

        val DEFAULT_INDEXER_RELAYS = listOf(
            "wss://purplepag.es",
            "wss://indexer.coracle.social",
            "wss://user.kindpag.es",
            "wss://directory.yabu.me",
            "wss://profiles.nostr1.com",
            "wss://search.brainstorm.world"
        )

        const val DITTO_RELAY_URL = "wss://relay.ditto.pub"
        const val DITTO_STATS_PUBKEY = "5f68e85ee174102ca8978eef302129f081f03456c884185d5ec1c1224ab633ea"

        private val IP_HOST_REGEX = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")

        /**
         * Structural URL validation — can this URL be stored in a relay list?
         * Rejects: non-wss schemes, localhost, IP addresses, URLs with ports.
         */
        fun isValidUrl(url: String): Boolean {
            if (!url.startsWith("wss://")) return false
            val afterScheme = url.removePrefix("wss://")
            val hostPort = afterScheme.split("/", limit = 2)[0]
            if (":" in hostPort) return false // has a port
            val host = hostPort.lowercase()
            if (host == "localhost" || host.endsWith(".localhost")) return false
            if (IP_HOST_REGEX.matches(host)) return false
            return true
        }

        private val LOCAL_HOST_REGEX = Regex(
            "^ws://(" +
                "localhost|" +
                "127\\.0\\.0\\.1|" +
                "10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|" +
                "192\\.168\\.\\d{1,3}\\.\\d{1,3}|" +
                "172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}|" +
                "[\\w.-]+:\\d+" +    // any host with explicit port
            ")(:\\d+)?(/.*)?$"
        )

        /** Returns true if the URL is a local/private relay (ws:// with localhost, loopback, or private IP). */
        fun isLocalRelayUrl(url: String): Boolean = LOCAL_HOST_REGEX.matches(url)
    }
}
