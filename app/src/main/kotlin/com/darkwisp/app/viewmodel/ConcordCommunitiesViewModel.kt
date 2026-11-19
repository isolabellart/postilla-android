package com.darkwisp.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darkwisp.app.nostr.NostrSigner
import com.darkwisp.app.repo.ConcordChatMessage
import com.darkwisp.app.repo.ConcordCommunitySummary
import com.darkwisp.app.repo.ConcordCreatedCommunity
import com.darkwisp.app.repo.ConcordRepository
import com.darkwisp.app.repo.ConcordChannelSummary
import com.darkwisp.app.relay.RelayPool
import com.darkwisp.app.relay.SubscriptionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ConcordCommunitiesUiState(
    val loading: Boolean = false,
    val working: Boolean = false,
    val communities: List<ConcordCommunitySummary> = emptyList(),
    val error: String? = null,
    val info: String? = null,
    val selectedCommunityId: String? = null,
    val selectedChannel: ConcordChannelSummary? = null,
    val messages: List<ConcordChatMessage> = emptyList(),
    val inviteToShare: String? = null,
)

class ConcordCommunitiesViewModel : ViewModel() {
    private var repository: ConcordRepository? = null
    private val _state = MutableStateFlow(ConcordCommunitiesUiState())
    val state: StateFlow<ConcordCommunitiesUiState> = _state.asStateFlow()

    fun bind(relayPool: RelayPool, subscriptions: SubscriptionManager) {
        if (repository == null) {
            repository = ConcordRepository(relayPool, subscriptions)
        }
    }

    fun refresh(signer: NostrSigner?) {
        val repo = repository ?: return
        if (signer == null) {
            _state.value = _state.value.copy(
                loading = false,
                error = "Accedi con un signer Nostr per entrare e gestire le Community Concord.",
            )
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null, info = null)
            try {
                // First visit to Communities automatically redeems the official Postilla invite.
                runCatching { repo.ensureOfficialPostillaCommunity(signer) }
                    .onFailure { _state.value = _state.value.copy(info = "Community Postilla: \${it.message ?: "accesso non completato"}") }
                val joined = repo.loadJoinedCommunities(signer)
                _state.value = _state.value.copy(
                    loading = false,
                    communities = joined,
                    error = null,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "Impossibile caricare le Community.",
                )
            }
        }
    }

    fun joinInvite(inviteUrl: String, signer: NostrSigner?) {
        val repo = repository ?: return
        if (signer == null) {
            _state.value = _state.value.copy(error = "Serve un signer Nostr per unirsi a una Community.")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(working = true, error = null, info = null)
            try {
                val joined = repo.joinInvite(inviteUrl, signer)
                val updated = (listOf(joined) + _state.value.communities.filterNot { it.id == joined.id })
                _state.value = _state.value.copy(
                    working = false,
                    communities = updated,
                    info = "Sei entrato in \${joined.name}.",
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    working = false,
                    error = e.message ?: "Impossibile aprire l'invito Concord.",
                )
            }
        }
    }

    fun createCommunity(
        signer: NostrSigner?,
        name: String,
        description: String,
        relays: List<String>,
    ) {
        val repo = repository ?: return
        if (signer == null) {
            _state.value = _state.value.copy(error = "Serve un signer Nostr per creare una Community.")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(working = true, error = null, info = null, inviteToShare = null)
            try {
                val created: ConcordCreatedCommunity = repo.createCommunity(signer, name, description, relays)
                val updated = listOf(created.community) + _state.value.communities.filterNot { it.id == created.community.id }
                _state.value = _state.value.copy(
                    working = false,
                    communities = updated,
                    info = "Community creata. Copia e conserva il link d'invito.",
                    inviteToShare = created.inviteUrl,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    working = false,
                    error = e.message ?: "Impossibile creare la Community.",
                )
            }
        }
    }

    fun updateMetadata(
        signer: NostrSigner?,
        communityId: String,
        name: String,
        description: String,
        relays: List<String>,
        timerSeconds: Long?,
    ) {
        val repo = repository ?: return
        if (signer == null) {
            _state.value = _state.value.copy(error = "Serve un signer Nostr per modificare la Community.")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(working = true, error = null, info = null)
            try {
                val updated = repo.updateMetadata(
                    communityId = communityId,
                    signer = signer,
                    name = name,
                    description = description,
                    relays = relays,
                    messageExpirationSecs = timerSeconds,
                )
                _state.value = _state.value.copy(
                    working = false,
                    communities = _state.value.communities.map { if (it.id == updated.id) updated else it },
                    info = "Metadati della Community aggiornati.",
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    working = false,
                    error = e.message ?: "Impossibile aggiornare i metadati.",
                )
            }
        }
    }

    fun openChannel(
        communityId: String,
        channel: ConcordChannelSummary,
    ) {
        val repo = repository ?: return
        if (!channel.canRead) {
            _state.value = _state.value.copy(error = "Non possiedi la chiave per questo canale privato.")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(
                working = true,
                error = null,
                selectedCommunityId = communityId,
                selectedChannel = channel,
                messages = emptyList(),
            )
            try {
                val messages = repo.loadChannelMessages(communityId, channel)
                _state.value = _state.value.copy(working = false, messages = messages)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    working = false,
                    error = e.message ?: "Impossibile caricare il canale.",
                )
            }
        }
    }

    fun sendMessage(signer: NostrSigner?, text: String) {
        val repo = repository ?: return
        val communityId = _state.value.selectedCommunityId ?: return
        val channel = _state.value.selectedChannel ?: return
        if (signer == null) {
            _state.value = _state.value.copy(error = "Serve un signer Nostr per scrivere nel canale.")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(working = true, error = null)
            try {
                repo.sendChannelMessage(communityId, channel, text, signer)
                val messages = repo.loadChannelMessages(communityId, channel)
                _state.value = _state.value.copy(working = false, messages = messages)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    working = false,
                    error = e.message ?: "Impossibile inviare il messaggio.",
                )
            }
        }
    }

    fun closeChannel() {
        _state.value = _state.value.copy(
            selectedCommunityId = null,
            selectedChannel = null,
            messages = emptyList(),
        )
    }

    fun clearMessages() {
        _state.value = _state.value.copy(error = null, info = null, inviteToShare = null)
    }
}
