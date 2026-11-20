package com.darkwisp.app.viewmodel

import java.util.concurrent.ConcurrentHashMap

/** Counts EOSE only for relays that actually accepted this subscription. */
internal class MarketplaceRelayEoseTracker(targetRelayUrls: Collection<String>) {
    private val targets = targetRelayUrls.filter { it.isNotBlank() }.toSet()
    private val sent = ConcurrentHashMap.newKeySet<String>()
    private val completed = ConcurrentHashMap.newKeySet<String>()

    fun markSent(relayUrl: String) {
        if (relayUrl in targets) sent.add(relayUrl)
    }

    fun markNotSent(relayUrl: String) {
        sent.remove(relayUrl)
        completed.remove(relayUrl)
    }

    fun wasSent(relayUrl: String): Boolean = relayUrl in sent

    fun markCompleted(relayUrl: String) {
        if (relayUrl in sent) completed.add(relayUrl)
    }

    fun targetCount(): Int = sent.size
    fun completedCount(): Int = completed.size
    fun hasCompleted(relayUrl: String): Boolean = relayUrl in completed
    fun minimumResponseCount(): Int = minOf(4, sent.size)
    fun allTargetsCompleted(): Boolean = completed.containsAll(sent)
}
