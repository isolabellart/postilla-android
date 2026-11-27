package com.darkwisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darkwisp.app.concord.ConcordChatMessage
import com.darkwisp.app.concord.ConcordChatRepository
import com.darkwisp.app.concord.ConcordCommunityRepository
import com.darkwisp.app.concord.PostillaQuartzSigner
import com.darkwisp.app.nostr.NostrSigner
import com.darkwisp.app.relay.RelayPool
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityListEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

class ConcordCommunityViewModel(app: Application) : AndroidViewModel(app) {
    data class CreationState(
        val isCreating: Boolean = false,
        val result: ConcordCommunityRepository.CreationResult? = null,
        val error: String? = null,
    )

    data class JoinState(
        val isJoining: Boolean = false,
        val result: ConcordCommunityRepository.JoinResult? = null,
        val error: String? = null,
    )

    data class InviteState(
        val isCreating: Boolean = false,
        val url: String? = null,
        val error: String? = null,
    )

    data class CommunityItem(
        val id: String,
        val name: String,
        val generalChannelId: String?,
        val rootEpoch: Long,
        val relays: List<String>,
    )

    private var repository: ConcordCommunityRepository? = null
    private var chatRepository: ConcordChatRepository? = null
    private var signerPubkey: String? = null
    private val memberships = linkedMapOf<String, ConcordCommunityListEntry>()

    private val _communities = MutableStateFlow<List<CommunityItem>>(emptyList())
    val communities: StateFlow<List<CommunityItem>> = _communities

    private val _creation = MutableStateFlow(CreationState())
    val creation: StateFlow<CreationState> = _creation

    private val _join = MutableStateFlow(JoinState())
    val join: StateFlow<JoinState> = _join

    private val _invite = MutableStateFlow(InviteState())
    val invite: StateFlow<InviteState> = _invite

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    fun init(relayPool: RelayPool, signer: NostrSigner?) {
        if (repository == null) {
            repository = ConcordCommunityRepository(getApplication(), relayPool)
            chatRepository = ConcordChatRepository(relayPool)
        }
        if (signer?.pubkeyHex == signerPubkey) return
        signerPubkey = signer?.pubkeyHex
        if (signer != null) refreshLocalCommunities(signer)
        else {
            memberships.clear()
            _communities.value = emptyList()
        }
    }

    fun refreshLocalCommunities(signer: NostrSigner) {
        val repo = repository ?: return
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val quartzSigner = PostillaQuartzSigner(signer)
                val entries = repo.loadLocalMemberships(quartzSigner)
                memberships.clear()
                entries.forEach { memberships[it.id] = it }
                _communities.value = entries.map { entry ->
                    CommunityItem(
                        id = entry.id,
                        name = entry.name.ifBlank { entry.id.take(12) },
                        generalChannelId = repo.getLocalGeneralChannelId(entry.id),
                        rootEpoch = entry.rootEpoch,
                        relays = entry.relays,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _communities.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun createCommunity(
        signer: NostrSigner?,
        name: String,
        description: String? = null,
    ) {
        if (_creation.value.isCreating) return
        if (signer == null) {
            _creation.value = CreationState(error = "A writable Nostr signer is required")
            return
        }
        val repo = repository
        if (repo == null) {
            _creation.value = CreationState(error = "Community service is not initialized")
            return
        }

        _creation.value = CreationState(isCreating = true)
        viewModelScope.launch {
            try {
                val quartzSigner = PostillaQuartzSigner(signer)
                val result = repo.createCommunity(
                    signer = quartzSigner,
                    name = name,
                    description = description,
                )
                val entries = repo.loadLocalMemberships(quartzSigner)
                memberships.clear()
                entries.forEach { memberships[it.id] = it }
                _communities.value = entries.map { entry ->
                    CommunityItem(
                        id = entry.id,
                        name = entry.name.ifBlank { entry.id.take(12) },
                        generalChannelId = repo.getLocalGeneralChannelId(entry.id),
                        rootEpoch = entry.rootEpoch,
                        relays = entry.relays,
                    )
                }
                _creation.value = CreationState(result = result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _creation.value = CreationState(error = error.message ?: "Community creation failed")
            }
        }
    }

    fun observeGeneralChannel(communityId: String): Flow<ConcordChatMessage> {
        val entry = memberships[communityId] ?: return emptyFlow()
        val channelId = repository?.getLocalGeneralChannelId(communityId) ?: return emptyFlow()
        return chatRepository?.observeChannel(
            communityRootHex = entry.root,
            channelIdHex = channelId,
            epoch = entry.rootEpoch,
            communityRelays = entry.relays,
        ) ?: emptyFlow()
    }

    suspend fun sendGeneralMessage(
        communityId: String,
        text: String,
        signer: NostrSigner?,
    ): ConcordChatMessage? {
        val entry = memberships[communityId] ?: return null
        val channelId = repository?.getLocalGeneralChannelId(communityId) ?: return null
        val chat = chatRepository ?: return null
        signer ?: return null
        return chat.sendMessage(
            signer = PostillaQuartzSigner(signer),
            communityRootHex = entry.root,
            channelIdHex = channelId,
            epoch = entry.rootEpoch,
            text = text,
            communityRelays = entry.relays,
        )
    }



    fun joinCommunityFromInvite(inviteUrl: String, signer: NostrSigner?) {
        if (_join.value.isJoining) return
        if (signer == null) {
            _join.value = JoinState(error = "A writable Nostr signer is required")
            return
        }
        val repo = repository
        if (repo == null) {
            _join.value = JoinState(error = "Community service is not initialized")
            return
        }
        _join.value = JoinState(isJoining = true)
        viewModelScope.launch {
            try {
                val quartzSigner = PostillaQuartzSigner(signer)
                val result = repo.joinCommunityFromInvite(quartzSigner, inviteUrl)
                val entries = repo.loadLocalMemberships(quartzSigner)
                memberships.clear()
                entries.forEach { memberships[it.id] = it }
                _communities.value = entries.map { entry ->
                    CommunityItem(
                        id = entry.id,
                        name = entry.name.ifBlank { entry.id.take(12) },
                        generalChannelId = repo.getLocalGeneralChannelId(entry.id),
                        rootEpoch = entry.rootEpoch,
                        relays = entry.relays,
                    )
                }
                _join.value = JoinState(result = result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _join.value = JoinState(error = error.message ?: "Community join failed")
            }
        }
    }

    fun clearJoinResult() {
        if (!_join.value.isJoining) _join.value = JoinState()
    }

    fun createInviteLink(communityId: String, signer: NostrSigner?) {
        if (_invite.value.isCreating) return
        if (signer == null) {
            _invite.value = InviteState(error = "A writable Nostr signer is required")
            return
        }
        val repo = repository
        if (repo == null) {
            _invite.value = InviteState(error = "Community service is not initialized")
            return
        }
        _invite.value = InviteState(isCreating = true)
        viewModelScope.launch {
            try {
                val url = repo.createInviteLink(PostillaQuartzSigner(signer), communityId)
                _invite.value = InviteState(url = url)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _invite.value = InviteState(error = error.message ?: "Invite creation failed")
            }
        }
    }

    fun clearInviteResult() {
        if (!_invite.value.isCreating) _invite.value = InviteState()
    }

    fun clearResult() {
        if (!_creation.value.isCreating) _creation.value = CreationState()
    }
}
