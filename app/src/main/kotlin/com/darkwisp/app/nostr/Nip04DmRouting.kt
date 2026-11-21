package com.darkwisp.app.nostr

/**
 * Shared NIP-04 routing rules for incoming and outgoing kind-4 events.
 * A received event is addressed to the local account; a sent event is authored
 * by the local account and its p-tag identifies the remote participant.
 */
object Nip04DmRouting {
    fun peerPubkey(ownerPubkey: String, eventAuthor: String, taggedPeerPubkey: String?): String? {
        if (ownerPubkey.isBlank() || taggedPeerPubkey.isNullOrBlank()) return null
        val outgoing = eventAuthor == ownerPubkey
        val peer = if (outgoing) taggedPeerPubkey else eventAuthor
        if (peer == ownerPubkey) return null
        if (!outgoing && taggedPeerPubkey != ownerPubkey) return null
        return peer
    }

    /** NIP-04 needs both received (#p=me) and sent (authors=me) history filters. */
    fun subscriptionFilters(ownerPubkey: String, includeNip04: Boolean): List<Filter> = buildList {
        val kinds = if (includeNip04) listOf(1059, 4) else listOf(1059)
        add(Filter(kinds = kinds, pTags = listOf(ownerPubkey)))
        if (includeNip04) add(Filter(kinds = listOf(4), authors = listOf(ownerPubkey)))
    }
}
