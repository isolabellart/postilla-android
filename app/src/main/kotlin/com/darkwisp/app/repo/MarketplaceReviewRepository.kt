package com.darkwisp.app.repo

import com.darkwisp.app.nostr.ClientMessage
import com.darkwisp.app.nostr.Filter
import com.darkwisp.app.nostr.MarketplaceRelays
import com.darkwisp.app.nostr.Nip99
import com.darkwisp.app.nostr.NostrEvent
import com.darkwisp.app.nostr.ShopstrReview
import com.darkwisp.app.nostr.SellerReviewSummary
import com.darkwisp.app.relay.RelayConfig
import com.darkwisp.app.relay.RelayPool
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Reads explicit Shopstr kind 31555 reviews and separates no response from zero reviews. */
class MarketplaceReviewRepository(
    private val relayPool: RelayPool,
    private val eventRepository: EventRepository
) {
    companion object {
        private val sequence = AtomicLong()
        private const val REVIEW_LIMIT = 500
        private const val LOOKUP_TIMEOUT_MS = 12_000L
        private const val EOSE_GRACE_MS = 1_000L
        private const val TAG_CHUNK_SIZE = 100
    }

    suspend fun fetchSellerReviews(
        sellerPubkey: String,
        sellerListings: Collection<NostrEvent>
    ): SellerReviewSummary? = coroutineScope {
        if (sellerPubkey.isBlank()) return@coroutineScope null
        val addresses = sellerListings.asSequence()
            .filter { it.kind == Nip99.KIND_LISTING }
            .map { Nip99.addressTag(it) }
            .filter { it.startsWith("${Nip99.KIND_LISTING}:$sellerPubkey:") }
            .distinct().toList()
        val filters = mutableListOf(
            Filter(kinds = listOf(ShopstrReview.KIND), pTags = listOf(sellerPubkey), limit = REVIEW_LIMIT)
        )
        addresses.flatMap { listOf("a:$it", it) }.distinct().chunked(TAG_CHUNK_SIZE).forEach { chunk ->
            filters += Filter(kinds = listOf(ShopstrReview.KIND), dTags = chunk, limit = REVIEW_LIMIT)
        }
        addresses.chunked(TAG_CHUNK_SIZE).forEach { chunk ->
            filters += Filter(kinds = listOf(ShopstrReview.KIND), aTags = chunk, limit = REVIEW_LIMIT)
        }

        val targets = (relayPool.getReadRelayUrls() + RelayConfig.DEFAULT_INDEXER_RELAYS + MarketplaceRelays.READ)
            .filter { RelayConfig.isValidUrl(it) }.distinct()
        if (targets.isEmpty()) return@coroutineScope null

        val subId = "marketplace-reviews-${sequence.incrementAndGet()}"
        val request = ClientMessage.req(subId, filters)
        val closeMessage = ClientMessage.close(subId)
        val found = linkedMapOf<String, NostrEvent>()
        val sentTargets = ConcurrentHashMap.newKeySet<String>()
        val completedTargets = ConcurrentHashMap.newKeySet<String>()

        val eventCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            relayPool.relayEvents.filter { it.subscriptionId == subId }.collect { frame ->
                val event = frame.event
                if (ShopstrReview.referencesSeller(event, sellerPubkey, addresses)) {
                    found[event.id] = event
                    eventRepository.addEvent(event)
                }
            }
        }
        val eoseCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            relayPool.relayEoseSignals.filter { it.subscriptionId == subId }
                .collect { signal -> completedTargets.add(signal.relayUrl) }
        }

        try {
            targets.forEach { url ->
                val sent = relayPool.sendToRelayOrEphemeral(
                    url, request, skipBadCheck = true,
                    autoReconnect = url in RelayConfig.DEFAULT_INDEXER_RELAYS || url in MarketplaceRelays.READ
                )
                if (sent) sentTargets.add(url)
            }
            if (sentTargets.isEmpty()) return@coroutineScope null

            val responseThresholdReached = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
                val required = minOf(3, sentTargets.size)
                while (completedTargets.count { it in sentTargets } < required) delay(100L)
                delay(EOSE_GRACE_MS)
                true
            } == true
            eventCollector.cancelAndJoin()
            eoseCollector.cancelAndJoin()
            // A confirmed empty result requires a relay quorum. Real review events may still
            // be displayed when a slow relay times out, but no-response is never "no reviews".
            if (!responseThresholdReached && found.isEmpty()) null
            else ShopstrReview.summarize(found.values, sellerPubkey, addresses)
        } finally {
            sentTargets.forEach { url ->
                relayPool.sendToRelayOrEphemeral(url, closeMessage, skipBadCheck = true)
            }
            eventCollector.cancelAndJoin()
            eoseCollector.cancelAndJoin()
        }
    }
}
