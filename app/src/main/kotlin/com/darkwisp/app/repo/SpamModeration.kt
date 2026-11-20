package com.darkwisp.app.repo

/** Shared author-level spam decision used by feed threads and Marketplace discussions. */
internal const val SPAM_AUTHOR_SCORE_THRESHOLD = 0.7f

internal fun shouldHideSpamAuthor(
    pubkey: String,
    currentUserPubkey: String?,
    isFollowing: Boolean,
    isSafelisted: Boolean,
    spamFilterEnabled: Boolean,
    score: Float?
): Boolean =
    spamFilterEnabled &&
        pubkey != currentUserPubkey &&
        !isFollowing &&
        !isSafelisted &&
        score != null &&
        score >= SPAM_AUTHOR_SCORE_THRESHOLD
