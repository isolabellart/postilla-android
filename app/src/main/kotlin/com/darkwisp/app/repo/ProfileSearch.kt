package com.darkwisp.app.repo

import com.darkwisp.app.nostr.ProfileData

/**
 * Relevance score for a locally cached kind-0 profile.
 * Names and NIP-05 identifiers are prioritized over free-form biography matches.
 */
internal fun profileSearchScore(profile: ProfileData, query: String): Int {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return 0

    fun score(value: String?, exact: Int, prefix: Int, contains: Int): Int {
        val candidate = value?.trim()?.lowercase().orEmpty()
        return when {
            candidate.isEmpty() -> 0
            candidate == needle -> exact
            candidate.startsWith(needle) -> prefix
            candidate.contains(needle) -> contains
            else -> 0
        }
    }

    val displayNameScore = maxOf(
        score(profile.displayName, 1000, 820, 620),
        score(profile.name, 980, 800, 600)
    )
    val nip05Score = score(profile.nip05, 900, 760, 540)
    val pubkeyScore = score(profile.pubkey, 850, 700, 500)
    val aboutScore = score(profile.about, 0, 0, 120)
    return maxOf(displayNameScore, nip05Score, pubkeyScore, aboutScore)
}

/** Deduplicate by pubkey, keep the newest metadata, and sort by text relevance. */
internal fun rankProfileSearchResults(
    profiles: Iterable<ProfileData>,
    query: String
): List<ProfileData> {
    val newestByPubkey = linkedMapOf<String, ProfileData>()
    profiles.forEach { candidate ->
        val previous = newestByPubkey[candidate.pubkey]
        if (previous == null || candidate.updatedAt > previous.updatedAt) {
            newestByPubkey[candidate.pubkey] = candidate
        }
    }
    return newestByPubkey.values.sortedWith(
        compareByDescending<ProfileData> { profileSearchScore(it, query) }
            .thenByDescending { it.updatedAt }
    )
}
