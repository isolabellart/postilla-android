package com.darkwisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darkwisp.app.nostr.ClientMessage
import com.darkwisp.app.nostr.Filter
import com.darkwisp.app.nostr.Nip10
import com.darkwisp.app.nostr.Nip22
import com.darkwisp.app.nostr.NostrEvent
import com.darkwisp.app.relay.OutboxRouter
import com.darkwisp.app.relay.RelayPool
import com.darkwisp.app.relay.RelayScoreBoard
import com.darkwisp.app.repo.EventRepository
import com.darkwisp.app.repo.ExtendedNetworkCache
import com.darkwisp.app.repo.MuteRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal fun filterVisibleHashtagEvents(
    events: Iterable<NostrEvent>,
    isVisibleToUser: (NostrEvent) -> Boolean
): List<NostrEvent> =
    events.asSequence()
        .filter { it.kind == 1 && isVisibleToUser(it) }
        .sortedByDescending { it.created_at }
        .toList()

class HashtagFeedViewModel(app: Application) : AndroidViewModel(app) {
    private val _notes = MutableStateFlow<List<NostrEvent>>(emptyList())
    val notes: StateFlow<List<NostrEvent>> = _notes

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _hashtag = MutableStateFlow("")
    val hashtag: StateFlow<String> = _hashtag

    private val _setName = MutableStateFlow<String?>(null)
    val setName: StateFlow<String?> = _setName

    private var relayPoolRef: RelayPool? = null
    private var eventRepoRef: EventRepository? = null
    private var outboxRouterRef: OutboxRouter? = null
    private var relayScoreBoardRef: RelayScoreBoard? = null
    private var loadJob: Job? = null
    private var visibilityObserverJob: Job? = null
    private val activeSubIds = mutableListOf<String>()
    private val allNotes = mutableListOf<NostrEvent>()
    private var loadCounter = 0
    private var noteSub = "hashtag-notes-0"
    private var engagePrefix = "hashtag-engage-0"

    fun loadHashtag(
        tag: String,
        relayPool: RelayPool,
        eventRepo: EventRepository,
        muteRepo: MuteRepository? = null,
        outboxRouter: OutboxRouter? = null,
        relayScoreBoard: RelayScoreBoard? = null
    ) {
        _setName.value = null
        loadTags(listOf(tag), tag, relayPool, eventRepo, muteRepo, outboxRouter, relayScoreBoard)
    }

    fun loadHashtags(
        tags: List<String>,
        name: String,
        relayPool: RelayPool,
        eventRepo: EventRepository,
        muteRepo: MuteRepository? = null,
        outboxRouter: OutboxRouter? = null,
        relayScoreBoard: RelayScoreBoard? = null
    ) {
        _setName.value = name
        loadTags(tags, tags.firstOrNull() ?: "", relayPool, eventRepo, muteRepo, outboxRouter, relayScoreBoard)
    }

    private fun loadTags(
        tags: List<String>,
        displayTag: String,
        relayPool: RelayPool,
        eventRepo: EventRepository,
        muteRepo: MuteRepository? = null,
        outboxRouter: OutboxRouter? = null,
        relayScoreBoard: RelayScoreBoard? = null
    ) {
        if (tags.isEmpty()) return

        loadJob?.cancel()
        relayPoolRef?.let { closeAllSubs(it) }

        _hashtag.value = displayTag
        _notes.value = emptyList()
        allNotes.clear()
        _isLoading.value = true
        relayPoolRef = relayPool
        eventRepoRef = eventRepo
        outboxRouterRef = outboxRouter
        relayScoreBoardRef = relayScoreBoard

        visibilityObserverJob?.cancel()
        val effectiveMuteRepo = muteRepo ?: eventRepo.muteRepo
        val blockedPubkeys = effectiveMuteRepo?.blockedPubkeys ?: flowOf<Set<String>>(emptySet())
        val mutedWords = effectiveMuteRepo?.mutedWords ?: flowOf<Set<String>>(emptySet())
        val mutedThreads = effectiveMuteRepo?.mutedThreads ?: flowOf<Set<String>>(emptySet())
        val wotEnabled = eventRepo.safetyPrefs?.wotFilterEnabled ?: flowOf(false)
        val networkSnapshot = eventRepo.extendedNetworkRepo?.cachedNetwork
            ?: flowOf<ExtendedNetworkCache?>(null)
        visibilityObserverJob = combine(
            blockedPubkeys,
            mutedWords,
            mutedThreads,
            wotEnabled,
            networkSnapshot
        ) { _, _, _, _, _ ->
            publishVisibleNotes(eventRepo)
        }.launchIn(viewModelScope)

        loadCounter++
        noteSub = "hashtag-notes-$loadCounter"
        engagePrefix = "hashtag-engage-$loadCounter"

        val currentNoteSub = noteSub

        val seenIds = mutableSetOf<String>()

        loadJob = viewModelScope.launch {
            val eventJob = launch {
                relayPool.relayEvents.collect { relayEvent ->
                    if (relayEvent.subscriptionId == currentNoteSub) {
                        val event = relayEvent.event
                        if (event.kind == 1 && seenIds.add(event.id)) {
                            allNotes.add(event)
                            publishVisibleNotes(eventRepo)
                        }
                    }
                    if (relayEvent.subscriptionId.startsWith(engagePrefix)) {
                        val e = relayEvent.event
                        when (e.kind) {
                            6, 7, 1018, 9735 -> eventRepo.addEvent(e)
                            1, Nip22.KIND_COMMENT -> {
                                val parentId = Nip10.getReplyTarget(e)
                                if (parentId != null) eventRepo.addReplyCount(parentId, e.id)
                            }
                            5 -> eventRepo.addEvent(e)
                        }
                    }
                }
            }

            // Send REQs after collectors are active — use #t filter, not search query
            val noteFilter = Filter(kinds = listOf(1), tTags = tags, limit = 100)
            val noteReq = ClientMessage.req(currentNoteSub, noteFilter)
            activeSubIds.add(currentNoteSub)

            relayPool.sendToRelayOrEphemeral(SearchViewModel.DEFAULT_SEARCH_RELAY, noteReq)

            // Wait for EOSE or timeout, then subscribe engagement
            withTimeoutOrNull(5_000) {
                relayPool.eoseSignals.first { it == currentNoteSub }
            }
            _isLoading.value = false

            // Fetch profiles for all note authors
            for (note in _notes.value) {
                eventRepo.requestProfileIfMissing(note.pubkey)
            }

            subscribeEngagement(relayPool, eventRepo)

            // Keep collecting for another 10s then clean up
            delay(10_000)
            closeAllSubs(relayPool)
            eventJob.cancel()
        }
    }

    private fun publishVisibleNotes(eventRepo: EventRepository) {
        val visible = filterVisibleHashtagEvents(allNotes, eventRepo::isEventVisibleToUser)
        val previousIds = _notes.value.mapTo(HashSet()) { it.id }
        visible.filter { it.id !in previousIds }.forEach { event ->
            eventRepo.cacheEvent(event)
            eventRepo.requestProfileIfMissing(event.pubkey)
        }
        _notes.value = visible
    }

    private fun subscribeEngagement(relayPool: RelayPool, eventRepo: EventRepository) {
        val notes = _notes.value
        if (notes.isEmpty()) return

        val router = outboxRouterRef
        if (router != null) {
            val eventsByAuthor = mutableMapOf<String, MutableList<String>>()
            for (note in notes) {
                eventsByAuthor.getOrPut(note.pubkey) { mutableListOf() }.add(note.id)
            }
            val safetyNet = relayScoreBoardRef?.getScoredRelays()?.take(5)?.map { it.url } ?: emptyList()
            router.subscribeEngagementByAuthors(engagePrefix, eventsByAuthor, activeSubIds, safetyNet)
        } else {
            // Fallback: query search relay directly
            val eventIds = notes.map { it.id }.distinct()
            eventIds.chunked(50).forEachIndexed { index, batch ->
                val subId = if (index == 0) engagePrefix else "$engagePrefix-$index"
                activeSubIds.add(subId)
                val filter = Filter(kinds = listOf(1, Nip22.KIND_COMMENT, 5, 6, 7, 1018, 9735), eTags = batch, limit = 500)
                relayPool.sendToRelayOrEphemeral(SearchViewModel.DEFAULT_SEARCH_RELAY, ClientMessage.req(subId, filter))
            }
        }
    }

    private fun closeAllSubs(relayPool: RelayPool) {
        for (subId in activeSubIds) {
            relayPool.closeOnAllRelays(subId)
        }
        activeSubIds.clear()
    }

    override fun onCleared() {
        super.onCleared()
        loadJob?.cancel()
        visibilityObserverJob?.cancel()
        relayPoolRef?.let { closeAllSubs(it) }
    }
}
