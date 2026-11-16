package com.darkwisp.app.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darkwisp.app.nostr.ClientMessage
import com.darkwisp.app.nostr.Filter
import com.darkwisp.app.nostr.Nip99
import com.darkwisp.app.nostr.Nip19
import com.darkwisp.app.nostr.Nip01EventOrder
import com.darkwisp.app.nostr.MarketplaceRelays
import com.darkwisp.app.nostr.NostrEvent
import com.darkwisp.app.relay.RelayConfig
import com.darkwisp.app.relay.RelayPool
import com.darkwisp.app.repo.EventRepository
import com.darkwisp.app.repo.PeerRelayListLookup
import com.darkwisp.app.repo.RelayListRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class MarketplaceScope { ALL, FOLLOWING, MINE }

sealed class MarketplaceNaddrLookupState {
    object Idle : MarketplaceNaddrLookupState()
    object Loading : MarketplaceNaddrLookupState()
    data class Found(val event: NostrEvent) : MarketplaceNaddrLookupState()
    object Invalid : MarketplaceNaddrLookupState()
    object Unsupported : MarketplaceNaddrLookupState()
    object NotFound : MarketplaceNaddrLookupState()
}

/** Accept a bare naddr or its NIP-21 nostr: URI form from the Marketplace search field. */
internal fun normalizeMarketplaceNaddrInput(query: String): String? {
    var value = query.trim()
    if (value.startsWith("nostr:", ignoreCase = true)) {
        value = value.substringAfter(':').trim()
    }
    return value
        .takeIf { it.startsWith("naddr1", ignoreCase = true) }
        ?.lowercase()
}

class MarketplaceViewModel(app: Application) : AndroidViewModel(app) {
    private val _listings = MutableStateFlow<List<NostrEvent>>(emptyList())
    val listings: StateFlow<List<NostrEvent>> = _listings

    private val _sellerInventory = MutableStateFlow<List<NostrEvent>>(emptyList())
    val sellerInventory: StateFlow<List<NostrEvent>> = _sellerInventory
    private val _sellerInventoryLoaded = MutableStateFlow(false)
    val sellerInventoryLoaded: StateFlow<Boolean> = _sellerInventoryLoaded

    private val _scope = MutableStateFlow(MarketplaceScope.ALL)
    val scope: StateFlow<MarketplaceScope> = _scope

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery
    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory
    private val _availableCategories = MutableStateFlow<List<String>>(emptyList())
    val availableCategories: StateFlow<List<String>> = _availableCategories

    private val _naddrLookupState = MutableStateFlow<MarketplaceNaddrLookupState>(
        MarketplaceNaddrLookupState.Idle
    )
    val naddrLookupState: StateFlow<MarketplaceNaddrLookupState> = _naddrLookupState

    private val naddrLookupGeneration = AtomicLong()
    private var naddrLookupJob: Job? = null
    private val subscriptionSequence = AtomicLong()
    private val cachedByAddress = linkedMapOf<String, NostrEvent>()
    private val cacheLock = Any()
    private var loadJob: Job? = null
    private var activeSubscriptionId: String? = null
    @Volatile private var activeSellerRelayUrls: List<String> = emptyList()
    private var currentAuthors: Set<String> = emptySet()
    private var currentUserPubkey: String? = null
    private var currentSellerPubkey: String? = null
    private var currentEventRepository: EventRepository? = null

    companion object {
        private const val LOAD_TIMEOUT_MS = 12_000L
        private const val EOSE_DRAIN_GRACE_MS = 3_000L
        private const val MINIMUM_EOSE_COUNT = 6
        private const val MAX_CACHED_LISTINGS = 5000
        private const val SELLER_PAGE_SIZE = 500
        private const val SELLER_HISTORY_MAX_EXTRA_PAGES = 8
        private const val SELLER_PAGE_EOSE_TIMEOUT_MS = 5_000L
        private const val NADDR_LOOKUP_DEBOUNCE_MS = 350L
        // MetadataFetcher performs an exact coordinate lookup and, when necessary,
        // a bounded paginated seller-history fallback (up to 25 seconds).
        private const val NADDR_LOOKUP_TIMEOUT_MS = 42_000L
        private const val NADDR_LOOKUP_SETTLE_MS = 1_200L
        private val HEX_PUBKEY_REGEX = Regex("^[0-9a-f]{64}$")

    }

    fun setScope(scope: MarketplaceScope) {
        _scope.value = scope
    }

    private fun matchesScope(event: NostrEvent, selectedScope: MarketplaceScope, authors: Set<String>, userPubkey: String?, sellerPubkey: String?): Boolean {
        if (sellerPubkey != null) return event.pubkey == sellerPubkey
        return when (selectedScope) {
            MarketplaceScope.ALL -> true
            MarketplaceScope.FOLLOWING -> event.pubkey in authors
            MarketplaceScope.MINE -> event.pubkey == userPubkey
        }
    }

    private fun publishVisibleCache(selectedScope: MarketplaceScope, authors: Set<String>, userPubkey: String?, sellerPubkey: String? = currentSellerPubkey) {
        val snapshot = synchronized(cacheLock) { cachedByAddress.values.toList() }
        val scoped = snapshot.filter { matchesScope(it, selectedScope, authors, userPubkey, sellerPubkey) }
        // Publish seller inventory progressively from local persistence and every relay
        // batch. The storefront header and review lookup must not wait for a global EOSE quorum.
        if (sellerPubkey != null) {
            _sellerInventory.value = scoped.asSequence()
                .filter { it.pubkey == sellerPubkey && Nip99.dTag(it).isNotBlank() }
                .filter { Nip99.status(it) != "hidden" && currentEventRepository?.isEventVisibleToUser(it) != false }
                .sortedByDescending { it.created_at }
                .take(MAX_CACHED_LISTINGS)
                .toList()
        }
        _availableCategories.value = scoped.flatMap { Nip99.categories(it) }.map { it.trim().lowercase() }
            .filter { it.isNotBlank() }.distinct().sorted()
        val query = _searchQuery.value.trim().lowercase()
        val category = _selectedCategory.value?.lowercase()
        _listings.value = scoped.asSequence()
            .filter { event -> category == null || Nip99.categories(event).any { it.trim().equals(category, ignoreCase = true) } }
            .filter { event ->
                if (query.isBlank()) true else {
                    val maker = currentEventRepository?.getProfileData(event.pubkey)?.displayString.orEmpty()
                    listOf(Nip99.title(event), Nip99.summary(event), Nip99.description(event), Nip99.location(event),
                        event.pubkey, maker, Nip99.categories(event).joinToString(" "))
                        .any { it.contains(query, ignoreCase = true) }
                }
            }
            .sortedByDescending { it.created_at }.take(MAX_CACHED_LISTINGS).toList()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        publishVisibleCache(_scope.value, currentAuthors, currentUserPubkey, currentSellerPubkey)
    }

    fun setSelectedCategory(category: String?) {
        _selectedCategory.value = category
        publishVisibleCache(_scope.value, currentAuthors, currentUserPubkey, currentSellerPubkey)
    }

    /**
     * Resolve a pasted NIP-19 address from the Marketplace search field.
     *
     * Relay routing stays in EventRepository/MetadataFetcher: naddr hints are tried
     * immediately, while the existing resolver also uses the seller's NIP-65 relays,
     * account/indexer relays and a paged exact-coordinate fallback when needed.
     */
    fun resolveMarketplaceNaddrQuery(query: String, eventRepository: EventRepository) {
        val naddr = normalizeMarketplaceNaddrInput(query)
        naddrLookupJob?.cancel()
        val generation = naddrLookupGeneration.incrementAndGet()

        if (naddr == null) {
            _naddrLookupState.value = MarketplaceNaddrLookupState.Idle
            return
        }

        _naddrLookupState.value = MarketplaceNaddrLookupState.Loading
        naddrLookupJob = viewModelScope.launch(Dispatchers.IO) {
            delay(NADDR_LOOKUP_DEBOUNCE_MS)
            if (naddrLookupGeneration.get() != generation) return@launch

            val address = try {
                Nip19.naddrDecode(naddr)
            } catch (_: Exception) {
                publishNaddrLookupState(generation, MarketplaceNaddrLookupState.Invalid)
                return@launch
            }

            val author = address.author
            val kind = address.kind
            if (
                author == null ||
                !HEX_PUBKEY_REGEX.matches(author) ||
                kind == null ||
                address.dTag.isBlank()
            ) {
                publishNaddrLookupState(generation, MarketplaceNaddrLookupState.Invalid)
                return@launch
            }

            if (!Nip99.isMarketplaceKind(kind)) {
                publishNaddrLookupState(generation, MarketplaceNaddrLookupState.Unsupported)
                return@launch
            }

            val updates = Channel<NostrEvent>(Channel.CONFLATED)
            val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                eventRepository.observeAddressableEvent(kind, author, address.dTag).collect { event ->
                    if (
                        event != null &&
                        isUsableMarketplaceAddressEvent(event, kind, author, address.dTag, eventRepository)
                    ) {
                        updates.trySend(event)
                    }
                }
            }

            try {
                var best = eventRepository.findAddressableEvent(kind, author, address.dTag)
                    ?.takeIf {
                        isUsableMarketplaceAddressEvent(it, kind, author, address.dTag, eventRepository)
                    }

                if (best == null) {
                    best = eventRepository.findPersistedMarketplaceListing(kind, author, address.dTag)
                        ?.takeIf {
                            isUsableMarketplaceAddressEvent(it, kind, author, address.dTag, eventRepository)
                        }
                    if (best != null) eventRepository.cacheEvent(best)
                }

                if (best != null) {
                    publishNaddrLookupState(generation, MarketplaceNaddrLookupState.Found(best))
                }

                // Start network resolution even when a cached revision is available:
                // the relay lookup may reveal a newer version of the same listing.
                eventRepository.requestAddressableEvent(kind, author, address.dTag, address.relays)
                eventRepository.requestProfileIfMissing(author)

                val lookupDeadline = System.currentTimeMillis() + NADDR_LOOKUP_TIMEOUT_MS
                var quietDeadline = if (best == null) {
                    lookupDeadline
                } else {
                    minOf(lookupDeadline, System.currentTimeMillis() + NADDR_LOOKUP_SETTLE_MS)
                }

                while (System.currentTimeMillis() < quietDeadline) {
                    val remaining = (quietDeadline - System.currentTimeMillis()).coerceAtLeast(1L)
                    val candidate = withTimeoutOrNull(remaining) { updates.receive() } ?: break
                    if (!isUsableMarketplaceAddressEvent(candidate, kind, author, address.dTag, eventRepository)) {
                        continue
                    }

                    val previous = best
                    if (previous == null || Nip01EventOrder.isNewer(candidate, previous)) {
                        best = candidate
                        publishNaddrLookupState(generation, MarketplaceNaddrLookupState.Found(candidate))
                        // Give additional relay responses a short window to provide a newer revision.
                        quietDeadline = minOf(
                            lookupDeadline,
                            System.currentTimeMillis() + NADDR_LOOKUP_SETTLE_MS
                        )
                    }
                }

                if (best == null) {
                    publishNaddrLookupState(generation, MarketplaceNaddrLookupState.NotFound)
                } else {
                    publishNaddrLookupState(generation, MarketplaceNaddrLookupState.Found(best))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("MarketplaceVM", "Could not resolve Marketplace naddr", error)
                publishNaddrLookupState(generation, MarketplaceNaddrLookupState.NotFound)
            } finally {
                observer.cancelAndJoin()
                updates.close()
            }
        }
    }

    private fun isUsableMarketplaceAddressEvent(
        event: NostrEvent,
        kind: Int,
        author: String,
        dTag: String,
        eventRepository: EventRepository
    ): Boolean =
        event.kind == kind &&
            event.pubkey == author &&
            Nip99.dTag(event) == dTag &&
            Nip99.status(event) != "hidden" &&
            eventRepository.isEventVisibleToUser(event)

    private fun publishNaddrLookupState(
        generation: Long,
        state: MarketplaceNaddrLookupState
    ) {
        if (naddrLookupGeneration.get() == generation) {
            _naddrLookupState.value = state
        }
    }

    fun load(
        selectedScope: MarketplaceScope,
        relayPool: RelayPool,
        eventRepository: EventRepository,
        followedPubkeys: List<String>,
        userPubkey: String?,
        relayListRepository: RelayListRepository,
        sellerPubkey: String? = null
    ) {
        _scope.value = selectedScope
        currentUserPubkey = userPubkey
        val previousSellerPubkey = currentSellerPubkey
        currentSellerPubkey = sellerPubkey
        currentEventRepository = eventRepository
        if (sellerPubkey == null) {
            _sellerInventory.value = emptyList()
            _sellerInventoryLoaded.value = true
        } else if (sellerPubkey != previousSellerPubkey) {
            _sellerInventory.value = emptyList()
            _sellerInventoryLoaded.value = false
        }
        val authors = when {
            sellerPubkey != null -> setOf(sellerPubkey)
            selectedScope == MarketplaceScope.ALL -> emptySet()
            selectedScope == MarketplaceScope.FOLLOWING -> followedPubkeys.toSet()
            else -> setOfNotNull(userPubkey)
        }
        currentAuthors = authors

        // Detach the previous request before cancelling it. Its finally block
        // must not reset the next request's loading indicator or error state.
        val oldSubId = activeSubscriptionId
        val oldSellerRelayUrls = activeSellerRelayUrls
        activeSubscriptionId = null
        activeSellerRelayUrls = emptyList()
        loadJob?.cancel()
        oldSubId?.let { closeSubscription(relayPool, it, oldSellerRelayUrls) }

        if (selectedScope != MarketplaceScope.ALL && authors.isEmpty()) {
            _listings.value = emptyList()
            _sellerInventory.value = emptyList()
            _sellerInventoryLoaded.value = true
            _loading.value = false
            _error.value = null
            return
        }

        // Keep known listings visible immediately while the relay query refreshes.
        publishVisibleCache(selectedScope, authors, userPubkey, sellerPubkey)
        _loading.value = true
        _error.value = null

        val subId = "marketplace-${subscriptionSequence.incrementAndGet()}"
        activeSubscriptionId = subId

        loadJob = viewModelScope.launch(Dispatchers.IO) {
            var sellerRelayTargets = emptyList<String>()
            try {
                if (sellerPubkey != null) {
                    // Paint the seller storefront from ObjectBox before network discovery;
                    // a previously seen catalogue must not appear empty while relays connect.
                    val storedListings = eventRepository.getCachedEventsByAuthorAndKinds(
                        sellerPubkey,
                        Nip99.MARKETPLACE_KINDS.toSet(),
                        limit = 5_000
                    ).filter {
                        Nip99.dTag(it).isNotBlank() &&
                            Nip99.status(it) != "hidden" &&
                            eventRepository.isEventVisibleToUser(it)
                    }
                    synchronized(cacheLock) {
                        storedListings.forEach { event ->
                            val key = Nip99.addressTag(event)
                            val current = cachedByAddress[key]
                            if (current == null || Nip01EventOrder.isNewer(event, current)) {
                                cachedByAddress[key] = event
                            }
                        }
                    }
                    if (activeSubscriptionId == subId) {
                        publishVisibleCache(selectedScope, authors, userPubkey, sellerPubkey)
                    }

                    // Refresh seller NIP-65 on every catalogue load. A stale non-empty
                    // outbox cache must not prevent discovery of newly added write relays.
                    val cachedSellerRelays = relayListRepository.getWriteRelays(sellerPubkey).orEmpty()
                    PeerRelayListLookup.fetch(
                        sellerPubkey,
                        relayPool,
                        relayListRepository,
                        extraRelayUrls = cachedSellerRelays,
                        requireMarketplaceRelayEose = true
                    )
                    sellerRelayTargets = relayListRepository.getWriteRelays(sellerPubkey).orEmpty().distinct()
                    if (activeSubscriptionId == subId) activeSellerRelayUrls = sellerRelayTargets
                }

                val filter = Filter(
                    kinds = Nip99.MARKETPLACE_KINDS,
                    authors = authors.takeIf { it.isNotEmpty() }?.toList(),
                    // Seller catalogues use bounded first pages and then paginate each
                    // saturated relay independently. Global discovery keeps its larger cap.
                    limit = if (sellerPubkey != null) SELLER_PAGE_SIZE else 5000
                )
                val request = ClientMessage.req(subId, filter)

                // Search the account's configured relays, public indexers, and
                // the public relay sets used by NIP-99 marketplaces. This avoids
                // relying on a single indexer that may not carry external listings.
                // Subscribe to the result stream before sending REQ. Some relays reply
                // immediately; collecting afterwards loses their EVENT/EOSE frames.
                val remoteListingCount = AtomicLong(0L)
                val sellerInitialEventsByRelay = ConcurrentHashMap<String, ConcurrentHashMap<String, NostrEvent>>()
                val eventCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                    relayPool.relayEvents
                        .filter { it.subscriptionId == subId }
                        .collect { relayEvent ->
                            val event = relayEvent.event
                            if (!Nip99.isMarketplaceKind(event.kind)) return@collect
                            if (sellerPubkey != null && event.pubkey != sellerPubkey) return@collect
                            if (sellerPubkey == null && selectedScope == MarketplaceScope.FOLLOWING && event.pubkey !in authors) return@collect
                            if (sellerPubkey == null && selectedScope == MarketplaceScope.MINE && event.pubkey != userPubkey) return@collect
                            val dTag = Nip99.dTag(event)
                            if (dTag.isBlank()) return@collect
                            // Track every matching relay result for pagination cursors, even
                            // when hidden/muted. Only visible listings enter the user cache.
                            if (sellerPubkey != null) {
                                sellerInitialEventsByRelay
                                    .computeIfAbsent(relayEvent.relayUrl) { ConcurrentHashMap() }[event.id] = event
                            }
                            if (!eventRepository.isEventVisibleToUser(event) || Nip99.status(event) == "hidden") return@collect

                            // NIP-99 recommends a title tag, but it is not a hard
                            // validity requirement. Do not hide otherwise valid listings.
                            eventRepository.addEvent(event)
                            eventRepository.requestProfileIfMissing(event.pubkey)

                            val address = Nip99.addressTag(event)
                            val updated = synchronized(cacheLock) {
                                val previous = cachedByAddress[address]
                                if (previous == null || Nip01EventOrder.isNewer(event, previous)) {
                                    cachedByAddress[address] = event
                                    while (cachedByAddress.size > MAX_CACHED_LISTINGS) {
                                        val oldestKey = cachedByAddress.minByOrNull { it.value.created_at }?.key ?: break
                                        cachedByAddress.remove(oldestKey)
                                    }
                                    true
                                } else false
                            }
                            if (updated) {
                                remoteListingCount.incrementAndGet()
                                if (activeSubscriptionId == subId) {
                                    publishVisibleCache(selectedScope, authors, userPubkey)
                                }
                            }
                        }
                }

                val targets = (relayPool.getRelayUrls() + RelayConfig.DEFAULT_INDEXER_RELAYS + MarketplaceRelays.READ + sellerRelayTargets).distinct()
                val eoseTracker = MarketplaceRelayEoseTracker(targets)
                val eoseCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                    relayPool.relayEoseSignals
                        .filter { it.subscriptionId == subId }
                        .collect { signal -> eoseTracker.markCompleted(signal.relayUrl) }
                }

                // Keep collecting after the first six distinct relays finish. A slower
                // indexer can hold listings that are absent from the faster relays.
                val reconnectableTargets = (
                    RelayConfig.DEFAULT_INDEXER_RELAYS +
                        MarketplaceRelays.READ +
                        sellerRelayTargets
                    ).toSet()
                targets.forEach { relay ->
                    eoseTracker.markSent(relay)
                    val sent = relayPool.sendToRelayOrEphemeral(
                        relay,
                        request,
                        skipBadCheck = true,
                        autoReconnect = relay in reconnectableTargets
                    )
                    if (!sent) eoseTracker.markNotSent(relay)
                }

                var drainStartedAt = 0L
                val completedWithinWindow = withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                    while (true) {
                        val completedCount = eoseTracker.completedCount()
                        val sentSellerRelayTargets = sellerRelayTargets.filter(eoseTracker::wasSent)
                        val sellerRelaysCompleted = sentSellerRelayTargets.all(eoseTracker::hasCompleted)
                        if (eoseTracker.targetCount() == 0) break
                        if (completedCount >= eoseTracker.minimumResponseCount() && sellerRelaysCompleted) {
                            if (drainStartedAt == 0L) {
                                drainStartedAt = System.currentTimeMillis()
                            }
                            val graceElapsed =
                                System.currentTimeMillis() - drainStartedAt >= EOSE_DRAIN_GRACE_MS
                            if (eoseTracker.allTargetsCompleted() || graceElapsed) break
                        }
                        delay(100)
                    }
                    true
                } == true

                var sellerHistoryComplete = true
                if (sellerPubkey != null) {
                    val saturatedRelayPages = sellerInitialEventsByRelay.entries
                        .filter { (relayUrl, events) ->
                            eoseTracker.hasCompleted(relayUrl) && events.size >= SELLER_PAGE_SIZE
                        }
                        .associate { it.key to it.value.values.toList() }
                    if (saturatedRelayPages.isNotEmpty()) {
                        sellerHistoryComplete = fetchSellerHistoryPages(
                            sellerPubkey,
                            saturatedRelayPages,
                            relayPool,
                            eventRepository
                        )
                    }
                }
                val sellerInventoryComplete =
                    completedWithinWindow && eoseTracker.completedCount() > 0 && sellerHistoryComplete
                if (sellerPubkey != null && sellerInventoryComplete) {
                    publishCompleteSellerInventory(sellerPubkey, eventRepository)
                    _sellerInventoryLoaded.value = true
                }

                Log.i(
                    "MarketplaceVM",
                    "Listing fetch finished sub=$subId seller=${sellerPubkey?.take(12) ?: "all"} " +
                        "relays=${eoseTracker.completedCount()}/${eoseTracker.targetCount()} " +
                        "sellerRelays=${sellerRelayTargets.size} sentRelays=${eoseTracker.targetCount()} " +
                        "remoteUpdates=${remoteListingCount.get()} timeout=${!completedWithinWindow}"
                )
                eoseCollector.cancel()
                eventCollector.cancel()
                eventCollector.join()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (activeSubscriptionId == subId) {
                    _error.value = error.localizedMessage ?: error.message
                }
            } finally {
                closeSubscription(relayPool, subId, sellerRelayTargets)
                if (activeSubscriptionId == subId) {
                    activeSubscriptionId = null
                    activeSellerRelayUrls = emptyList()
                    // A timeout must not turn a partial relay response into a complete
                    // seller inventory. Keep the last complete snapshot, if any.
                    _loading.value = false
                }
            }
        }
    }

    private data class SellerHistoryPage(
        val events: List<NostrEvent>,
        val rawEvents: List<NostrEvent>,
        val completed: Boolean
    )

    /**
     * Continue only relays whose first seller page was saturated. Each relay gets
     * its own timestamp cursor so one busy relay cannot skip another relay's history.
     * A repeated full page or an EOSE timeout is reported as incomplete, never as success.
     */
    private suspend fun fetchSellerHistoryPages(
        sellerPubkey: String,
        saturatedPages: Map<String, List<NostrEvent>>,
        relayPool: RelayPool,
        eventRepository: EventRepository
    ): Boolean = coroutineScope {
        saturatedPages.entries.map { (relayUrl, firstPage) ->
            async {
                var until = firstPage.minOfOrNull { it.created_at } ?: return@async true
                val seenIds = firstPage.mapTo(mutableSetOf()) { it.id }
                repeat(SELLER_HISTORY_MAX_EXTRA_PAGES) {
                    val page = fetchSellerHistoryPage(
                        relayUrl, sellerPubkey, until, relayPool, eventRepository
                    )
                    if (!page.completed) return@async false

                    val added = page.rawEvents.count { seenIds.add(it.id) }
                    page.events.forEach { event ->
                        eventRepository.addEvent(event)
                        val address = Nip99.addressTag(event)
                        synchronized(cacheLock) {
                            val previous = cachedByAddress[address]
                            if (previous == null || Nip01EventOrder.isNewer(event, previous)) {
                                cachedByAddress[address] = event
                            }
                        }
                    }
                    if (page.rawEvents.size < SELLER_PAGE_SIZE) {
                        publishVisibleCache(_scope.value, currentAuthors, currentUserPubkey, sellerPubkey)
                        return@async true
                    }
                    if (added == 0) return@async false

                    val nextUntil = page.rawEvents.minOfOrNull { it.created_at } ?: return@async true
                    if (nextUntil >= until) return@async false
                    until = nextUntil
                    publishVisibleCache(_scope.value, currentAuthors, currentUserPubkey, sellerPubkey)
                }
                // Reaching the safety page cap is not proof of a complete inventory.
                false
            }
        }.awaitAll().all { it }
    }

    private suspend fun fetchSellerHistoryPage(
        relayUrl: String,
        sellerPubkey: String,
        until: Long,
        relayPool: RelayPool,
        eventRepository: EventRepository
    ): SellerHistoryPage = coroutineScope {
        val subId = "marketplace-history-${subscriptionSequence.incrementAndGet()}"
        val request = ClientMessage.req(
            subId,
            Filter(
                kinds = Nip99.MARKETPLACE_KINDS,
                authors = listOf(sellerPubkey),
                until = until,
                limit = SELLER_PAGE_SIZE
            )
        )
        val pageEvents = ConcurrentHashMap<String, NostrEvent>()
        val rawPageEvents = ConcurrentHashMap<String, NostrEvent>()
        val eventCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            relayPool.relayEvents
                .filter { it.subscriptionId == subId && it.relayUrl == relayUrl }
                .collect { frame ->
                    val event = frame.event
                    if (
                        event.pubkey == sellerPubkey &&
                        Nip99.isMarketplaceKind(event.kind) &&
                        Nip99.dTag(event).isNotBlank()
                    ) {
                        rawPageEvents.putIfAbsent(event.id, event)
                        if (Nip99.status(event) != "hidden" && eventRepository.isEventVisibleToUser(event)) {
                            pageEvents.putIfAbsent(event.id, event)
                        }
                    }
                }
        }
        val eoseWaiter = async(start = CoroutineStart.UNDISPATCHED) {
            relayPool.relayEoseSignals.first {
                it.subscriptionId == subId && it.relayUrl == relayUrl
            }
        }
        try {
            val sent = relayPool.sendToRelayOrEphemeral(
                relayUrl, request, skipBadCheck = true, autoReconnect = true
            )
            if (!sent) return@coroutineScope SellerHistoryPage(emptyList(), emptyList(), false)
            val completed = withTimeoutOrNull(SELLER_PAGE_EOSE_TIMEOUT_MS) {
                eoseWaiter.await()
                true
            } == true
            val events = pageEvents.values.sortedByDescending { it.created_at }
            val rawEvents = rawPageEvents.values.sortedByDescending { it.created_at }
            if (completed) {
                events.forEach { event ->
                    eventRepository.addEvent(event)
                    val address = Nip99.addressTag(event)
                    synchronized(cacheLock) {
                        val previous = cachedByAddress[address]
                        if (previous == null || Nip01EventOrder.isNewer(event, previous)) {
                            cachedByAddress[address] = event
                        }
                    }
                }
                publishVisibleCache(_scope.value, currentAuthors, currentUserPubkey, sellerPubkey)
            }
            SellerHistoryPage(events, rawEvents, completed)
        } finally {
            eoseWaiter.cancelAndJoin()
            eventCollector.cancelAndJoin()
            relayPool.sendToRelayOrEphemeral(
                relayUrl, ClientMessage.close(subId), skipBadCheck = true, autoReconnect = true
            )
        }
    }

    private fun publishCompleteSellerInventory(
        sellerPubkey: String,
        eventRepository: EventRepository
    ) {
        val snapshot = synchronized(cacheLock) {
            cachedByAddress.values.asSequence()
                .filter { event ->
                    event.pubkey == sellerPubkey &&
                        Nip99.dTag(event).isNotBlank() &&
                        Nip99.status(event) != "hidden" &&
                        eventRepository.isEventVisibleToUser(event)
                }
                .sortedByDescending { it.created_at }
                .take(MAX_CACHED_LISTINGS)
                .toList()
        }
        _sellerInventory.value = snapshot
    }

    private fun closeSubscription(
        relayPool: RelayPool,
        subId: String,
        extraRelayUrls: List<String> = emptyList()
    ) {
        val close = ClientMessage.close(subId)
        relayPool.sendToAll(close)
        (RelayConfig.DEFAULT_INDEXER_RELAYS + MarketplaceRelays.READ + extraRelayUrls).distinct().forEach {
            relayPool.sendToRelayOrEphemeral(it, close, skipBadCheck = true)
        }
    }

    fun addOrUpdate(event: NostrEvent) {
        if (!Nip99.isMarketplaceKind(event.kind) || Nip99.dTag(event).isBlank()) return
        val key = Nip99.addressTag(event)
        synchronized(cacheLock) {
            val previous = cachedByAddress[key]
            if (previous == null || Nip01EventOrder.isNewer(event, previous)) {
                cachedByAddress[key] = event
            }
        }
        publishVisibleCache(_scope.value, currentAuthors, currentUserPubkey, currentSellerPubkey)
    }

    fun removeByAddress(pubkey: String, dTag: String) {
        if (dTag.isBlank()) return
        synchronized(cacheLock) {
            cachedByAddress.entries.removeAll { (_, event) -> event.pubkey == pubkey && Nip99.dTag(event) == dTag }
        }
        publishVisibleCache(_scope.value, currentAuthors, currentUserPubkey)
    }

    override fun onCleared() {
        activeSubscriptionId = null
        loadJob?.cancel()
        naddrLookupGeneration.incrementAndGet()
        naddrLookupJob?.cancel()
        super.onCleared()
    }
}
