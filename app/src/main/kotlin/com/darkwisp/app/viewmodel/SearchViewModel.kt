package com.darkwisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.darkwisp.app.nostr.ClientMessage
import com.darkwisp.app.nostr.Filter
import com.darkwisp.app.nostr.NostrEvent
import com.darkwisp.app.nostr.ProfileData
import com.darkwisp.app.relay.RelayConfig
import com.darkwisp.app.relay.RelayPool
import com.darkwisp.app.repo.EventRepository
import com.darkwisp.app.repo.KeyRepository
import com.darkwisp.app.repo.MuteRepository
import com.darkwisp.app.repo.NostrWineApiClient
import com.darkwisp.app.repo.rankProfileSearchResults
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class SearchFilter { PEOPLE, NOTES }

enum class RelayOption {
    DEFAULT,
    ALL_RELAYS,
    INDIVIDUAL
}

class SearchViewModel(app: Application) : AndroidViewModel(app) {
    private val keyRepo = KeyRepository(app)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _filter = MutableStateFlow(SearchFilter.PEOPLE)
    val filter: StateFlow<SearchFilter> = _filter

    // Relay selection
    private val _selectedRelayOption = MutableStateFlow(RelayOption.DEFAULT)
    val selectedRelayOption: StateFlow<RelayOption> = _selectedRelayOption

    private val _selectedRelayUrl = MutableStateFlow<String?>(null)
    val selectedRelayUrl: StateFlow<String?> = _selectedRelayUrl

    // User's search relays
    private val _searchRelays = MutableStateFlow(keyRepo.getSearchRelays())
    val searchRelays: StateFlow<List<String>> = _searchRelays

    // Author filter (for note search)
    private val _authorFilter = MutableStateFlow<ProfileData?>(null)
    val authorFilter: StateFlow<ProfileData?> = _authorFilter

    private val _authorSearchResults = MutableStateFlow<List<ProfileData>>(emptyList())
    val authorSearchResults: StateFlow<List<ProfileData>> = _authorSearchResults

    private val _isAuthorSearching = MutableStateFlow(false)
    val isAuthorSearching: StateFlow<Boolean> = _isAuthorSearching

    // Results
    private val _users = MutableStateFlow<List<ProfileData>>(emptyList())
    val users: StateFlow<List<ProfileData>> = _users

    private val _notes = MutableStateFlow<List<NostrEvent>>(emptyList())
    val notes: StateFlow<List<NostrEvent>> = _notes

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching

    private var searchJob: Job? = null
    private var authorSearchJob: Job? = null
    private var relayPool: RelayPool? = null
    private var eventRepoRef: EventRepository? = null
    private var muteRepoRef: MuteRepository? = null
    private var autoSearchJob: Job? = null
    private var searchCounter = 0

    private var userSubId = "search-users-0"
    private var noteSubId = "search-notes-0"
    private var authorSubId = "search-author-0"
    private var metricsSubId = "engage-search-0"

    fun selectFilter(filter: SearchFilter) {
        _filter.value = filter
    }

    fun selectDefaultRelay() {
        _selectedRelayOption.value = RelayOption.DEFAULT
        _selectedRelayUrl.value = null
    }

    fun selectAllRelays() {
        _selectedRelayOption.value = RelayOption.ALL_RELAYS
        _selectedRelayUrl.value = null
    }

    fun selectRelay(url: String) {
        _selectedRelayOption.value = RelayOption.INDIVIDUAL
        _selectedRelayUrl.value = url
    }

    fun addSearchRelay(url: String): Boolean {
        val trimmed = url.trim().trimEnd('/')
        if (!RelayConfig.isValidUrl(trimmed)) return false
        if (trimmed in _searchRelays.value) return false
        val updated = _searchRelays.value + trimmed
        keyRepo.saveSearchRelays(updated)
        _searchRelays.value = updated
        return true
    }

    fun removeSearchRelay(url: String) {
        val updated = _searchRelays.value - url
        keyRepo.saveSearchRelays(updated)
        _searchRelays.value = updated
        if (_selectedRelayOption.value == RelayOption.INDIVIDUAL && _selectedRelayUrl.value == url) {
            selectDefaultRelay()
        }
    }

    fun initSearchRefs(relayPool: RelayPool, eventRepo: EventRepository, muteRepo: MuteRepository?) {
        if (this.relayPool == null) this.relayPool = relayPool
        if (this.eventRepoRef == null) this.eventRepoRef = eventRepo
        if (this.muteRepoRef == null) this.muteRepoRef = muteRepo
    }

    fun updateQuery(newQuery: String) {
        _query.value = newQuery
        // Debounced auto-search: trigger after 500ms of no typing
        autoSearchJob?.cancel()
        val pool = relayPool ?: return
        val repo = eventRepoRef ?: return
        val trimmed = newQuery.trim().removePrefix("nostr:")
        // Do not launch a search while the user has just entered a separator.
        // Searching on a trailing space can interrupt the IME composition and eat
        // the space before the next word is typed.
        if (newQuery.lastOrNull()?.isWhitespace() == true || trimmed.length < 2) {
            searchJob?.cancel()
            relayPool?.let { closeSubscriptions(it) }
            _users.value = emptyList()
            _notes.value = emptyList()
            _isSearching.value = false
            return
        }
        autoSearchJob = viewModelScope.launch {
            delay(500)
            search(newQuery, pool, repo, muteRepoRef)
        }
    }

    fun setAuthorFilter(profile: ProfileData) {
        _authorFilter.value = profile
        _authorSearchResults.value = emptyList()
    }

    fun clearAuthorFilter() {
        _authorFilter.value = null
    }

    fun prepareAuthorSearch(profile: ProfileData) {
        _filter.value = SearchFilter.NOTES
        _authorFilter.value = profile
        _authorSearchResults.value = emptyList()
    }

    private fun selectedSearchRelays(): List<String> {
        val defaults = RelayConfig.DEFAULT_SEARCH_RELAYS.distinct()
        return when (_selectedRelayOption.value) {
            RelayOption.DEFAULT -> defaults
            RelayOption.ALL_RELAYS -> _searchRelays.value.ifEmpty { defaults }.distinct()
            RelayOption.INDIVIDUAL -> listOfNotNull(_selectedRelayUrl.value)
        }
    }

    fun searchAuthors(query: String, relayPool: RelayPool, eventRepo: EventRepository) {
        val trimmed = query.trim().removePrefix("nostr:")
        if (trimmed.isEmpty()) {
            authorSearchJob?.cancel()
            relayPool.closeOnAllRelays(authorSubId)
            _authorSearchResults.value = emptyList()
            _isAuthorSearching.value = false
            return
        }

        authorSearchJob?.cancel()
        relayPool.closeOnAllRelays(authorSubId)
        searchCounter++
        authorSubId = "search-author-$searchCounter"
        val activeSubId = authorSubId
        val profilesByPubkey = linkedMapOf<String, ProfileData>()
        eventRepo.searchProfiles(trimmed, limit = 30).forEach { profilesByPubkey[it.pubkey] = it }
        _isAuthorSearching.value = true
        _authorSearchResults.value = rankProfileSearchResults(profilesByPubkey.values, trimmed)

        val authorRequest = ClientMessage.req(
            activeSubId,
            Filter(kinds = listOf(0), search = trimmed, limit = 30)
        )

        authorSearchJob = viewModelScope.launch {
            val eventJob = launch(start = CoroutineStart.UNDISPATCHED) {
                relayPool.relayEvents.collect { relayEvent ->
                    if (relayEvent.subscriptionId != activeSubId) return@collect
                    val event = relayEvent.event
                    if (event.kind != 0) return@collect
                    val profile = ProfileData.fromEvent(event) ?: return@collect
                    eventRepo.cacheEvent(event)
                    val previous = profilesByPubkey[event.pubkey]
                    if (previous == null || profile.updatedAt > previous.updatedAt) {
                        profilesByPubkey[event.pubkey] = profile
                        _authorSearchResults.value = rankProfileSearchResults(profilesByPubkey.values, trimmed)
                    }
                }
            }

            val eoseJob = launch(start = CoroutineStart.UNDISPATCHED) {
                relayPool.eoseSignals.collect { subId ->
                    if (subId == activeSubId) _isAuthorSearching.value = false
                }
            }

            try {
                selectedSearchRelays().forEach { url ->
                    runCatching { relayPool.sendToRelayOrEphemeral(url, authorRequest) }
                }
                delay(6000L)
            } finally {
                _isAuthorSearching.value = false
                relayPool.closeOnAllRelays(activeSubId)
                eventJob.cancel()
                eoseJob.cancel()
            }
        }
    }



    fun search(query: String, relayPool: RelayPool, eventRepo: EventRepository, muteRepo: MuteRepository? = null) {
        val trimmed = query.trim().removePrefix("nostr:")
        if (trimmed.isEmpty()) {
            clear()
            return
        }

        searchJob?.cancel()
        autoSearchJob?.cancel()
        this.relayPool = relayPool
        this.eventRepoRef = eventRepo
        this.muteRepoRef = muteRepo

        closeSubscriptions(relayPool)

        searchCounter++
        userSubId = "search-users-$searchCounter"
        noteSubId = "search-notes-$searchCounter"
        metricsSubId = "engage-search-$searchCounter"

        // Keep _query exactly as typed: trimming here erases a trailing space from the live TextField.
        _isSearching.value = true
        _users.value = emptyList()
        _notes.value = emptyList()

        val activeFilter = _filter.value
        val relaysToQuery = selectedSearchRelays()
        val activeSubId = when (activeFilter) {
            SearchFilter.PEOPLE -> userSubId
            SearchFilter.NOTES -> noteSubId
        }
        val userRequest = ClientMessage.req(
            userSubId,
            Filter(kinds = listOf(0), search = trimmed, limit = 40)
        )
        val authorPubkey = _authorFilter.value?.pubkey
        val noteRequest = ClientMessage.req(
            noteSubId,
            Filter(
                kinds = listOf(1),
                authors = authorPubkey?.let { listOf(it) },
                search = trimmed,
                limit = 50
            )
        )

        val profilesByPubkey = linkedMapOf<String, ProfileData>()
        if (activeFilter == SearchFilter.PEOPLE) {
            eventRepo.searchProfiles(trimmed, limit = 50)
                .filter { muteRepo?.isBlocked(it.pubkey) != true }
                .forEach { profilesByPubkey[it.pubkey] = it }
            _users.value = rankProfileSearchResults(profilesByPubkey.values, trimmed)
        }

        val seenNoteIds = mutableSetOf<String>()
        var metricsRequested = false

        searchJob = viewModelScope.launch {
            // Start both collectors before sending REQ: fast relays may answer immediately.
            val eventJob = launch(start = CoroutineStart.UNDISPATCHED) {
                relayPool.relayEvents.collect { relayEvent ->
                    if (relayEvent.subscriptionId != activeSubId) return@collect
                    when (activeFilter) {
                        SearchFilter.PEOPLE -> {
                            val event = relayEvent.event
                            if (event.kind != 0 || muteRepo?.isBlocked(event.pubkey) == true) return@collect
                            val profile = ProfileData.fromEvent(event) ?: return@collect
                            eventRepo.cacheEvent(event)
                            val previous = profilesByPubkey[event.pubkey]
                            if (previous == null || profile.updatedAt > previous.updatedAt) {
                                profilesByPubkey[event.pubkey] = profile
                                _users.value = rankProfileSearchResults(profilesByPubkey.values, trimmed)
                            }
                        }
                        SearchFilter.NOTES -> {
                            val event = relayEvent.event
                            if (event.kind == 1 && event.id !in seenNoteIds) {
                                if (muteRepo?.isBlocked(event.pubkey) == true) return@collect
                                if (muteRepo?.isEventMuted(event) == true) return@collect
                                seenNoteIds.add(event.id)
                                _notes.value = _notes.value + event
                                eventRepo.cacheEvent(event)
                            }
                        }
                    }
                }
            }

            val eoseJob = launch(start = CoroutineStart.UNDISPATCHED) {
                relayPool.eoseSignals.collect { subId ->
                    if (subId != activeSubId) return@collect
                    _isSearching.value = false
                    // Request engagement only once; the same subscription can EOSE on many relays.
                    if (activeFilter == SearchFilter.NOTES && !metricsRequested) {
                        metricsRequested = true
                        val noteIds = seenNoteIds.toList()
                        if (noteIds.isNotEmpty()) {
                            noteIds.chunked(100).forEachIndexed { i, chunk ->
                                val chunkSubId = if (i == 0) metricsSubId else "$metricsSubId-$i"
                                val engageFilter = Filter(
                                    kinds = listOf(7, 6, 9735),
                                    eTags = chunk,
                                    limit = 500
                                )
                                relayPool.sendToReadRelays(ClientMessage.req(chunkSubId, engageFilter))
                            }
                        }
                    }
                }
            }

            try {
            launch {
                try {
                    val apiEvents = NostrWineApiClient.search(
                        query = trimmed,
                        kind = if (activeFilter == SearchFilter.PEOPLE) 0 else 1,
                        pubkey = if (activeFilter == SearchFilter.NOTES) authorPubkey else null,
                        limit = 50
                    )
                    val verified = withContext(Dispatchers.Default) {
                        apiEvents.filter { runCatching { it.verifySignature() }.getOrDefault(false) }
                    }
                    verified.forEach { event ->
                        when (activeFilter) {
                            SearchFilter.PEOPLE -> {
                                if (event.kind != 0 || muteRepo?.isBlocked(event.pubkey) == true) return@forEach
                                val profile = ProfileData.fromEvent(event) ?: return@forEach
                                eventRepo.cacheEvent(event)
                                val previous = profilesByPubkey[event.pubkey]
                                if (previous == null || profile.updatedAt > previous.updatedAt) {
                                    profilesByPubkey[event.pubkey] = profile
                                    _users.value = rankProfileSearchResults(profilesByPubkey.values, trimmed)
                                }
                            }
                            SearchFilter.NOTES -> {
                                if (event.kind != 1 || event.id in seenNoteIds) return@forEach
                                if (muteRepo?.isBlocked(event.pubkey) == true || muteRepo?.isEventMuted(event) == true) return@forEach
                                seenNoteIds.add(event.id)
                                eventRepo.cacheEvent(event)
                                _notes.value = _notes.value + event
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w("SearchVM", "Nostr.wine REST unavailable; retaining NIP-50 results", error)
                }
            }
                relaysToQuery.forEach { url ->
                    runCatching {
                        relayPool.sendToRelayOrEphemeral(
                            url,
                            if (activeFilter == SearchFilter.PEOPLE) userRequest else noteRequest
                        )
                    }
                }
                delay(7000L)
            } finally {
                _isSearching.value = false
                relayPool.closeOnAllRelays(activeSubId)
                relayPool.closeOnAllRelays(metricsSubId)
                eventJob.cancel()
                eoseJob.cancel()
            }
        }
    }



    fun clear() {
        autoSearchJob?.cancel()
        autoSearchJob = null
        searchJob?.cancel()
        authorSearchJob?.cancel()
        relayPool?.let { closeSubscriptions(it) }
        _query.value = ""
        _users.value = emptyList()
        _notes.value = emptyList()
        _isSearching.value = false
        _authorFilter.value = null
        _authorSearchResults.value = emptyList()
        _isAuthorSearching.value = false
    }

    companion object {
        const val DEFAULT_SEARCH_RELAY = RelayConfig.DITTO_RELAY_URL
    }

    private fun closeSubscriptions(relayPool: RelayPool) {
        relayPool.closeOnAllRelays(userSubId)
        relayPool.closeOnAllRelays(noteSubId)
        relayPool.closeOnAllRelays(authorSubId)
        relayPool.closeOnAllRelays(metricsSubId)
    }
}
