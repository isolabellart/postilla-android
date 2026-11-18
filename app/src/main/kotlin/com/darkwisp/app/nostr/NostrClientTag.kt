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

    /**
     * Canonicalize the NIP-89 client tag before signing. This deliberately removes
     * every pre-existing client tag so a legacy "Dark Wisp" value cannot precede
     * Postilla and become the value displayed by clients that read the first tag.
     * When the preference is disabled, all client tags are omitted.
     */
    fun apply(tags: List<List<String>>, enabled: Boolean): List<List<String>> {
        val withoutClientTags = tags.filterNot { it.firstOrNull() == KEY }
        return if (enabled) withoutClientTags + listOf(tag) else withoutClientTags
    }
}
