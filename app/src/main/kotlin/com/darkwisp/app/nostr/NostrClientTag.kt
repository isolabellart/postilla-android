package com.darkwisp.app.nostr

/**
 * Canonical NIP-89 client tag used by all events created by this application.
 * Keep the application identity in one place to prevent legacy fork names
 * from leaking into newly signed events.
 */
object NostrClientTag {
    const val NAME = "Postilla"
    const val KEY = "client"

    val tag: List<String> = listOf(KEY, NAME)
}
