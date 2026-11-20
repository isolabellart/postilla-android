package com.darkwisp.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darkwisp.app.nostr.ClientMessage
import com.darkwisp.app.nostr.CustomNip
import com.darkwisp.app.nostr.Nip10
import com.darkwisp.app.nostr.Nip22
import com.darkwisp.app.nostr.Nip57
import com.darkwisp.app.nostr.Filter
import com.darkwisp.app.nostr.NostrEvent
import com.darkwisp.app.relay.OutboxRouter
import com.darkwisp.app.relay.RelayPool
import com.darkwisp.app.relay.SubscriptionManager
import com.darkwisp.app.repo.EventRepository
import com.darkwisp.app.repo.MetadataFetcher
import com.darkwisp.app.repo.RelayHintStore
import com.darkwisp.app.repo.RelayListRepository
import com.darkwisp.app.viewmodel.thread.ThreadFlattener
import com.darkwisp.app.viewmodel.thread.ThreadItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ArticleViewModel : ViewModel() {
    private val _article = MutableStateFlow<NostrEvent?>(null)
    val article: StateFlow<NostrEvent?> = _article

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _title = MutableStateFlow<String?>(null)
    val title: StateFlow<String?> = _title

    private val _coverImage = MutableStateFlow<String?>(null)
    val coverImage: StateFlow<String?> = _coverImage

    private val _publishedAt = MutableStateFlow<Long?>(null)
    val publishedAt: StateFlow<Long?> = _publishedAt

    private val _hashtags = MutableStateFlow<List<String>>(emptyList())
    val hashtags: StateFlow<List<String>> = _hashtags

    // Custom NIP (kind 30817) defined-kind chips, from `k` tags. Empty for ordinary articles.
    private val _definedKinds = MutableStateFlow<List<CustomNip.DefinedKind>>(emptyList())
    val definedKinds: StateFlow<List<CustomNip.DefinedKind>> = _definedKinds

    // Comments
    private val _comments = MutableStateFlow<List<ThreadItem>>(emptyList())
    val comments: StateFlow<List<ThreadItem>> = _comments

    private val _isCommentsLoading = MutableStateFlow(false)
    val isCommentsLoading: StateFlow<Boolean> = _isCommentsLoading

    private val commentEvents = mutableMapOf<String, NostrEvent>()
    private var currentArticleEventId: String? = null
    /** Anchors whose folded comment subtree the user expanded inline. */
    private val expandedIds = mutableSetOf<String>()
    private var collectorJob: Job? = null
    private var engagementCollectorJob: Job? = null
    private var rebuildJob: Job? = null
    private var loadJob: Job? = null
    private var metadataBatchJob: Job? = null
    private var relayPoolRef: RelayPool? = null
    private var topRelayUrls: List<String> = emptyList()
    private var relayListRepoRef: RelayListRepository? = null
    private var relayHintStoreRef: RelayHintStore? = null
    private val activeSubIds = mutableListOf<String>()

    // Incremental metadata tracking
    private val metadataSubscribedIds = mutableSetOf<String>()
    private val pendingMetadataIds = mutableSetOf<String>()
    private var metadataBatchIndex = 0

    fun loadArticle(kind: Int, author: String, dTag: String, eventRepo: EventRepository) {
        viewModelScope.launch {
            val cached = eventRepo.findAddressableEvent(kind, author, dTag)
            if (cached != null) {
                parseAndEmit(cached)
                return@launch
            }

            eventRepo.requestAddressableEvent(kind, author, dTag)

            // Poll for arrival with timeout
            var elapsed = 0L
            while (elapsed < 8000) {
                delay(200)
                elapsed += 200
                val event = eventRepo.findAddressableEvent(kind, author, dTag)
                if (event != null) {
                    parseAndEmit(event)
                    return@launch
                }
            }
            _isLoading.value = false
        }
    }

    fun loadComments(
        kind: Int,
        author: String,
        dTag: String,
        articleEventId: String?,
        eventRepo: EventRepository,
        relayPool: RelayPool,
        outboxRouter: OutboxRouter,
        subManager: SubscriptionManager,
        metadataFetcher: MetadataFetcher,
        topRelayUrls: List<String>,
        relayListRepo: RelayListRepository? = null,
        relayHintStore: RelayHintStore? = null
    ) {
        this.relayPoolRef = relayPool
        this.topRelayUrls = topRelayUrls
        this.relayListRepoRef = relayListRepo
        this.relayHintStoreRef = relayHintStore
        this.currentArticleEventId = articleEventId
        _isCommentsLoading.value = true

        // Close any subscriptions from a previous article before starting a new scope.
        activeSubIds.toList().forEach(relayPool::closeOnAllRelays)
        activeSubIds.clear()

        val coordinate = "$kind:$author:$dTag"
        val commentFilters = Nip22.addressableRootCommentFilters(coordinate, articleEventId)
        val commentSubIds = commentFilters.mapIndexed { index, _ ->
            when (index) {
                0 -> "article-comments-A"
                1 -> "article-comments-a"
                else -> "article-comments-e-$index"
            }
        }
        activeSubIds.addAll(commentSubIds)

        // Reset transient state and hydrate comments already known locally before
        // waiting for network subscriptions to answer.
        commentEvents.clear()
        expandedIds.clear()
        metadataSubscribedIds.clear()
        synchronized(pendingMetadataIds) { pendingMetadataIds.clear() }
        metadataBatchIndex = 0
        for (cached in eventRepo.getRecentCommentsForAddressableRoot(coordinate)) {
            commentEvents[cached.id] = cached
            if (eventRepo.getProfileData(cached.pubkey) == null) {
                metadataFetcher.addToPendingProfiles(cached.pubkey)
            }
            synchronized(pendingMetadataIds) { pendingMetadataIds.add(cached.id) }
        }
        _comments.value = emptyList()
        if (commentEvents.isNotEmpty()) rebuildTree(articleEventId)
        _isCommentsLoading.value = true

        // Listen to every independent query. A comment is accepted only when it
        // explicitly points to this addressable root or to the article event ID.
        collectorJob?.cancel()
        collectorJob = viewModelScope.launch {
            relayPool.relayEvents.collect { (event, relayUrl, subId) ->
                if (subId in commentSubIds) {
                    if (event.kind != 1 && event.kind != Nip22.KIND_COMMENT) return@collect
                    val referencesCoordinate = event.tags.any {
                        it.size >= 2 && it[0] in setOf("A", "a") && it[1] == coordinate
                    }
                    val referencesArticleEvent = articleEventId != null &&
                        Nip22.referencesRoot(event, articleEventId)
                    if (!referencesCoordinate && !referencesArticleEvent) return@collect

                    val isNew = event.id !in commentEvents
                    if (isNew) {
                        commentEvents[event.id] = event
                        eventRepo.addEvent(event)
                        eventRepo.addEventRelay(event.id, relayUrl)
                        if (eventRepo.getProfileData(event.pubkey) == null) {
                            metadataFetcher.addToPendingProfiles(event.pubkey)
                        }
                        synchronized(pendingMetadataIds) {
                            pendingMetadataIds.add(event.id)
                        }
                        scheduleRebuild(articleEventId)
                    }
                }
            }
        }

        // Collect engagement events (reactions, zaps, reposts).
        engagementCollectorJob?.cancel()
        engagementCollectorJob = viewModelScope.launch {
            relayPool.relayEvents.collect { (event, _, subId) ->
                if (!subId.startsWith("article-engage")) return@collect
                when (event.kind) {
                    7, 6 -> eventRepo.addEvent(event)
                    9735 -> {
                        eventRepo.addEvent(event)
                        val zapperPubkey = eventRepo.resolveZapSender(event).first
                        if (zapperPubkey != null && eventRepo.getProfileData(zapperPubkey) == null) {
                            metadataFetcher.addToPendingProfiles(zapperPubkey)
                        }
                    }
                }
            }
        }

        // #A, #a and legacy #e must be separate REQs: different tag filters in
        // a single Nostr filter are combined with AND and hide nested replies.
        loadJob?.cancel()
        metadataBatchJob?.cancel()
        loadJob = viewModelScope.launch {
            commentFilters.forEachIndexed { index, commentFilter ->
                val subId = commentSubIds[index]
                outboxRouter.subscribeToUserReadRelays(subId, author, commentFilter)
                val message = ClientMessage.req(subId, commentFilter)
                for (url in topRelayUrls) {
                    relayPool.sendToRelayOrEphemeral(url, message)
                }
            }

            commentSubIds.forEach { subId ->
                subManager.awaitEoseWithTimeout(subId, 3_000)
            }
            _isCommentsLoading.value = false
            metadataFetcher.sweepMissingProfiles()

            // Seed reply counts and subscribe to engagement data.
            subscribeEngagement(relayPool, articleEventId, coordinate, author, eventRepo, subManager)
            startMetadataBatching(relayPool, author)
        }
    }

    /**
     * Subscribe for engagement data (reactions, zaps, reposts) on the article event
     * itself and all comment events. Seeds reply counts upfront from loaded comments.
     * Awaits EOSE for article engagement to ensure reliable counts.
     */
    private suspend fun subscribeEngagement(
        relayPool: RelayPool,
        articleEventId: String?,
        coordinate: String,
        authorPubkey: String,
        eventRepo: EventRepository,
        subManager: SubscriptionManager
    ) {
        // Seed reply counts from already-loaded comments (before engagement subscriptions)
        for (event in commentEvents.values) {
            val parentId = Nip10.getReplyTarget(event) ?: articleEventId ?: continue
            eventRepo.addReplyCount(parentId, event.id)
        }

        // Collect all event IDs we need engagement for: article + comments
        val allEventIds = mutableListOf<String>()
        if (articleEventId != null) allEventIds.add(articleEventId)
        allEventIds.addAll(commentEvents.keys)
        if (allEventIds.isEmpty() && articleEventId == null) return

        // Track these as already subscribed for incremental batching
        metadataSubscribedIds.addAll(allEventIds)

        // Phase 1a: Article engagement via e-tag (high priority) — await EOSE
        if (articleEventId != null) {
            val eTagSubId = "article-engage"
            activeSubIds.add(eTagSubId)
            val eTagFilter = Filter(kinds = listOf(7, 6, 9735), eTags = listOf(articleEventId))
            sendToEngagementRelays(relayPool, eTagSubId, eTagFilter, authorPubkey)
            subManager.awaitEoseWithTimeout(eTagSubId, 3_500)
        }

        // Phase 1b: Article engagement via a-tag coordinate — many clients reference
        // addressable events by coordinate ("30023:<pubkey>:<dtag>") instead of event ID
        val aTagSubId = "article-engage-a"
        activeSubIds.add(aTagSubId)
        val aTagFilter = Filter(kinds = listOf(7, 6, 9735), aTags = listOf(coordinate))
        sendToEngagementRelays(relayPool, aTagSubId, aTagFilter, authorPubkey)
        subManager.awaitEoseWithTimeout(aTagSubId, 3_500)

        // Phase 2: Comment engagement (lower priority) — chunked, fire-and-forget
        val commentIds = commentEvents.keys.toList()
        if (commentIds.isNotEmpty()) {
            commentIds.chunked(50).forEachIndexed { index, batch ->
                val subId = "article-engage-${index + 1}"
                activeSubIds.add(subId)
                val filter = Filter(kinds = listOf(7, 6, 9735), eTags = batch)
                sendToEngagementRelays(relayPool, subId, filter, authorPubkey)
            }
        }
    }

    /**
     * Batch pending event IDs every 500ms into new engagement subscriptions.
     * Late-arriving comments get their engagement data fetched incrementally.
     */
    private fun startMetadataBatching(relayPool: RelayPool, authorPubkey: String) {
        metadataBatchJob = viewModelScope.launch {
            while (true) {
                delay(500)
                val batch = synchronized(pendingMetadataIds) {
                    val newIds = pendingMetadataIds.filter { it !in metadataSubscribedIds }
                    pendingMetadataIds.clear()
                    if (newIds.isEmpty()) null
                    else {
                        metadataSubscribedIds.addAll(newIds)
                        newIds.toList()
                    }
                } ?: continue
                metadataBatchIndex++
                val subId = "article-engage-b$metadataBatchIndex"
                activeSubIds.add(subId)
                val filter = Filter(kinds = listOf(7, 6, 9735), eTags = batch)
                sendToEngagementRelays(relayPool, subId, filter, authorPubkey)
            }
        }
    }

    /**
     * Send subscription to author's read relays + top scored relays.
     * Mirrors ThreadViewModel.sendToEngagementRelays().
     */
    private fun sendToEngagementRelays(
        relayPool: RelayPool, subId: String, filter: Filter, authorPubkey: String
    ) {
        val msg = ClientMessage.req(subId, filter)
        val sent = mutableSetOf<String>()
        for (url in getAuthorRelays(authorPubkey)) {
            if (relayPool.sendToRelayOrEphemeral(url, msg)) sent.add(url)
        }
        for (url in topRelayUrls) {
            if (url !in sent) relayPool.sendToRelayOrEphemeral(url, msg)
        }
    }

    private fun getAuthorRelays(pubkey: String): List<String> {
        val nip65 = relayListRepoRef?.getReadRelays(pubkey)
        if (!nip65.isNullOrEmpty()) return nip65
        val hints = relayHintStoreRef?.getHints(pubkey)
        if (!hints.isNullOrEmpty()) return hints.toList()
        return emptyList()
    }

    private fun scheduleRebuild(articleEventId: String?) {
        rebuildJob?.cancel()
        rebuildJob = viewModelScope.launch {
            delay(100)
            rebuildTree(articleEventId)
        }
    }

    private fun rebuildTree(articleEventId: String?) {
        val parentToChildren = mutableMapOf<String, MutableList<NostrEvent>>()

        for (event in commentEvents.values) {
            val replyTarget = Nip10.getReplyTarget(event)
            val parentId = when {
                replyTarget != null && replyTarget in commentEvents -> replyTarget
                replyTarget != null && replyTarget == articleEventId -> "root"
                else -> "root"
            }
            parentToChildren.getOrPut(parentId) { mutableListOf() }.add(event)
        }

        for (children in parentToChildren.values) {
            children.sortBy { it.created_at }
        }

        // Article comments share note threads' progressive disclosure: deeper-than-cap
        // subtrees fold behind an inline "Show N more replies" affordance (expandBranch).
        _comments.value = ThreadFlattener.flatten(
            rootId = "root",
            rootEvent = null,
            parentToChildren = parentToChildren,
            expandedIds = expandedIds,
            // Fan-out ("show more replies") cap stays disabled for article comments.
            maxSiblingsInline = Int.MAX_VALUE
        )
    }

    /** Expand a folded comment subtree inline. */
    fun expandBranch(anchorId: String) {
        if (expandedIds.add(anchorId)) rebuildTree(currentArticleEventId)
    }

    fun collapseBranch(anchorId: String) {
        if (expandedIds.remove(anchorId)) rebuildTree(currentArticleEventId)
    }

    private fun parseAndEmit(event: NostrEvent) {
        _article.value = event
        _title.value = event.tags.firstOrNull { it.size >= 2 && it[0] == "title" }?.get(1)
        _coverImage.value = event.tags.firstOrNull { it.size >= 2 && it[0] == "image" }?.get(1)
        _publishedAt.value = event.tags.firstOrNull { it.size >= 2 && it[0] == "published_at" }?.get(1)?.toLongOrNull()
        _hashtags.value = event.tags.filter { it.size >= 2 && it[0] == "t" }.map { it[1] }
        _definedKinds.value =
            if (event.kind == CustomNip.KIND) CustomNip.parseDefinedKinds(event) else emptyList()
        _isLoading.value = false
    }

    override fun onCleared() {
        super.onCleared()
        collectorJob?.cancel()
        engagementCollectorJob?.cancel()
        rebuildJob?.cancel()
        loadJob?.cancel()
        metadataBatchJob?.cancel()
        relayPoolRef?.let { pool ->
            for (subId in activeSubIds) pool.closeOnAllRelays(subId)
        }
        activeSubIds.clear()
    }
}
