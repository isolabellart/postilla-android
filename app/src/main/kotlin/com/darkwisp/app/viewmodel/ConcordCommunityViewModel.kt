package com.darkwisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darkwisp.app.concord.ConcordChatMessage
import com.darkwisp.app.concord.ConcordChatRepository
import com.vitorpamplona.quartz.nip92IMeta.IMetaTag
import com.vitorpamplona.quartz.concord.cord02Community.ImagePointer
import com.darkwisp.app.concord.ConcordCommunityRepository
import com.darkwisp.app.concord.PostillaQuartzSigner
import com.darkwisp.app.nostr.NostrSigner
import com.darkwisp.app.relay.RelayPool
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityListEntry
import com.vitorpamplona.quartz.concord.cord05Invites.OpenedDirectInvite
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

    data class MembersState(
        val communityId: String? = null,
        val isLoading: Boolean = false,
        val members: List<ConcordCommunityRepository.CommunityMember> = emptyList(),
        val error: String? = null,
    )

    data class DirectInvitesState(
        val isLoading: Boolean = false,
        val invites: List<OpenedDirectInvite> = emptyList(),
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

    private val _channels = MutableStateFlow<List<ConcordCommunityRepository.CommunityChannel>>(emptyList())
    val channels: StateFlow<List<ConcordCommunityRepository.CommunityChannel>> = _channels
    private val _channelsByCommunity =
        MutableStateFlow<Map<String, List<ConcordCommunityRepository.CommunityChannel>>>(emptyMap())
    val channelsByCommunity: StateFlow<Map<String, List<ConcordCommunityRepository.CommunityChannel>>> =
        _channelsByCommunity

    private val _communityIcons = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val communityIcons: StateFlow<Map<String, ByteArray>> = _communityIcons

    private val _creation = MutableStateFlow(CreationState())
    val creation: StateFlow<CreationState> = _creation

    private val _join = MutableStateFlow(JoinState())
    val join: StateFlow<JoinState> = _join

    private val _invite = MutableStateFlow(InviteState())
    val invite: StateFlow<InviteState> = _invite

    private val _members = MutableStateFlow(MembersState())
    val members: StateFlow<MembersState> = _members

    private val _directInvites = MutableStateFlow(DirectInvitesState())
    val directInvites: StateFlow<DirectInvitesState> = _directInvites

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _communityListWarning = MutableStateFlow<String?>(null)
    val communityListWarning: StateFlow<String?> = _communityListWarning

    private val _restoringGeneralChannel = MutableStateFlow<String?>(null)
    val restoringGeneralChannel: StateFlow<String?> = _restoringGeneralChannel

    private val _generalChannelError = MutableStateFlow<String?>(null)
    val generalChannelError: StateFlow<String?> = _generalChannelError

    fun init(relayPool: RelayPool, signer: NostrSigner?) {
        if (repository == null) {
            repository = ConcordCommunityRepository(getApplication(), relayPool)
            chatRepository = ConcordChatRepository(relayPool, getApplication())
        }
        if (signer?.pubkeyHex == signerPubkey) return
        signerPubkey = signer?.pubkeyHex
        memberships.clear()
        _channels.value = emptyList()
        _channelsByCommunity.value = emptyMap()
        _members.value = MembersState()
        _directInvites.value = DirectInvitesState()
        _communityIcons.value = emptyMap()
        if (signer != null) {
            refreshLocalCommunities(signer)
            refreshDirectInvites(signer)
        }
        else {
            _communities.value = emptyList()
            _communityListWarning.value = null
            _generalChannelError.value = null
        }
    }

    fun refreshLocalCommunities(signer: NostrSigner) {
        val repo = repository ?: return
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val quartzSigner = PostillaQuartzSigner(signer)
                val entries = repo.loadLocalMemberships(quartzSigner)
                val warning = repo.getCommunityListReadWarning()
                // An incomplete/empty remote snapshot is not proof that existing memberships
                // were removed. Keep the last known list visible until a usable snapshot arrives.
                if (entries.isEmpty() && memberships.isNotEmpty() && warning != null) {
                    _communityListWarning.value = warning
                    return@launch
                }
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
                _communityListWarning.value = warning
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _communityListWarning.value =
                    error.message ?: "Could not load the Concord Community List"
                // Keep the last known list visible; a failed refresh must not erase it.
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun createCommunity(
        signer: NostrSigner?,
        name: String,
        description: String? = null,
        icon: ImagePointer? = null,
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
                    icon = icon,
                )
                val entries = repo.loadLocalMemberships(quartzSigner)
                val warning = repo.getCommunityListReadWarning()
                // An incomplete/empty remote snapshot is not proof that existing memberships
                // were removed. Keep the last known list visible until a usable snapshot arrives.
                if (!(entries.isEmpty() && memberships.isNotEmpty() && warning != null)) {
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
                }
                _communityListWarning.value = warning
                _creation.value = CreationState(result = result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _creation.value = CreationState(error = error.message ?: "Community creation failed")
            }
        }
    }

    fun loadCommunityIcon(communityId: String) {
        val repo = repository ?: return
        val membership = memberships[communityId] ?: return
        if (_communityIcons.value.containsKey(communityId)) return
        viewModelScope.launch {
            runCatching { repo.loadCommunityIcon(membership) }
                .getOrNull()
                ?.let { bytes -> _communityIcons.value = _communityIcons.value + (communityId to bytes) }
        }
    }

    private fun findChannel(communityId: String, channelId: String) =
        _channelsByCommunity.value[communityId]?.firstOrNull { it.id == channelId }

    fun refreshChannels(communityId: String) {
        val repo = repository ?: return
        val membership = memberships[communityId] ?: return
        viewModelScope.launch {
            runCatching { repo.loadCommunityChannels(membership) }
                .onSuccess { loaded ->
                    _channelsByCommunity.value = _channelsByCommunity.value + (communityId to loaded)
                    _channels.value = loaded // Legacy observer; Concord operations use the community-scoped index.
                    val general = loaded.firstOrNull { it.name.equals("general", ignoreCase = true) }
                    if (general != null) {
                        val current = _communities.value
                        _communities.value = current.map {
                            if (it.id == communityId) it.copy(generalChannelId = general.id) else it
                        }
                    }
                }
        }
    }

    fun observeChannel(communityId: String, channelId: String): Flow<ConcordChatMessage> {
        val entry = memberships[communityId] ?: return emptyFlow()
        val channel = findChannel(communityId, channelId)
        if (channel?.isPrivate == true && channel.privateKeyHex.isNullOrBlank()) return emptyFlow()
        val ownerPubkey = signerPubkey ?: return emptyFlow()
        return chatRepository?.observeChannel(
            ownerPubkey = ownerPubkey,
            communityIdHex = entry.id,
            communityRootHex = entry.root,
            channelIdHex = channelId,
            epoch = entry.rootEpoch,
            communityRelays = entry.relays,
            privateChannelKeyHex = channel?.privateKeyHex,
            privateChannelEpoch = channel?.privateChannelEpoch,
            readPlanes = channel?.readPlanes.orEmpty(),
        ) ?: emptyFlow()
    }

    fun observeGeneralChannel(communityId: String): Flow<ConcordChatMessage> {
        val channelId = repository?.getLocalGeneralChannelId(communityId) ?: return emptyFlow()
        return observeChannel(communityId, channelId)
    }

    fun restoreGeneralChannel(communityId: String) {
        val repo = repository ?: return
        if (_restoringGeneralChannel.value == communityId) return

        val cachedChannelId = repo.getLocalGeneralChannelId(communityId)
        if (!cachedChannelId.isNullOrBlank()) {
            _communities.value = _communities.value.map { item ->
                if (item.id == communityId) item.copy(generalChannelId = cachedChannelId) else item
            }
            return
        }

        val membership = memberships[communityId]
        if (membership == null) {
            _generalChannelError.value = "The private membership for this community is not available on this device."
            return
        }

        _generalChannelError.value = null
        _restoringGeneralChannel.value = communityId
        viewModelScope.launch {
            try {
                val channelId = repo.restoreGeneralChannelId(membership)
                _communities.value = _communities.value.map { item ->
                    if (item.id == communityId) item.copy(generalChannelId = channelId) else item
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _generalChannelError.value =
                    error.message ?: "Could not restore the Concord #general channel"
            } finally {
                _restoringGeneralChannel.value = null
            }
        }
    }

    fun clearGeneralChannelError() {
        _generalChannelError.value = null
    }

    suspend fun sendChannelMessage(
        communityId: String,
        channelId: String,
        text: String,
        signer: NostrSigner?,
        extraTags: Array<Array<String>> = emptyArray(),
        imetas: List<IMetaTag> = emptyList(),
        replyTo: ConcordChatMessage? = null,
        inlineReply: Boolean = false,
    ): ConcordChatMessage? {
        val entry = memberships[communityId] ?: return null
        val channelInfo = findChannel(communityId, channelId)
        if (channelInfo?.isPrivate == true && channelInfo.privateKeyHex.isNullOrBlank()) return null
        if (channelInfo?.writeRestricted == true) {
            throw IllegalStateException(
                "This channel uses a restricted writer key. Postilla can read its messages, but cannot publish to it yet.",
            )
        }
        val chat = chatRepository ?: return null
        signer ?: return null
        return chat.sendMessage(
            signer = PostillaQuartzSigner(signer),
            communityIdHex = entry.id,
            communityRootHex = entry.root,
            channelIdHex = channelId,
            epoch = entry.rootEpoch,
            text = text,
            privateChannelKeyHex = channelInfo?.privateKeyHex,
            privateChannelEpoch = channelInfo?.privateChannelEpoch,
            communityRelays = entry.relays,
            extraTags = extraTags,
            imetas = imetas,
            replyTo = replyTo,
            inlineReply = inlineReply,
        )
    }

    suspend fun loadOlderChannelMessages(
        communityId: String,
        channelId: String,
    ): List<ConcordChatMessage> {
        val entry = memberships[communityId] ?: return emptyList()
        val ownerPubkey = signerPubkey ?: return emptyList()
        val channel = findChannel(communityId, channelId)
        if (channel?.isPrivate == true && channel.privateKeyHex.isNullOrBlank()) return emptyList()
        if (channel?.readPlanes.isNullOrEmpty()) return emptyList()
        return chatRepository?.loadOlder(
            ownerPubkey = ownerPubkey,
            communityIdHex = entry.id,
            channelIdHex = channelId,
            readPlanes = channel.readPlanes,
            communityRelays = entry.relays,
        ).orEmpty()
    }

    suspend fun sendChannelReaction(
        communityId: String,
        channelId: String,
        target: ConcordChatMessage,
        reaction: String,
        signer: NostrSigner?,
    ): Boolean {
        val entry = memberships[communityId] ?: return false
        val channel = findChannel(communityId, channelId)
        if (channel?.isPrivate == true && channel.privateKeyHex.isNullOrBlank()) return false
        if (channel?.writeRestricted == true) return false
        signer ?: return false
        return chatRepository?.sendReaction(
            signer = PostillaQuartzSigner(signer),
            communityIdHex = entry.id,
            communityRootHex = entry.root,
            channelIdHex = channelId,
            epoch = entry.rootEpoch,
            target = target,
            reaction = reaction,
            privateChannelKeyHex = channel?.privateKeyHex,
            privateChannelEpoch = channel?.privateChannelEpoch,
            communityRelays = entry.relays,
        ) ?: false
    }

    suspend fun sendGeneralMessage(
        communityId: String,
        text: String,
        signer: NostrSigner?,
        extraTags: Array<Array<String>> = emptyArray(),
        imetas: List<IMetaTag> = emptyList(),
    ): ConcordChatMessage? {
        val channelId = repository?.getLocalGeneralChannelId(communityId) ?: return null
        return sendChannelMessage(communityId, channelId, text, signer, extraTags, imetas)
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
                val warning = repo.getCommunityListReadWarning()
                // An incomplete/empty remote snapshot is not proof that existing memberships
                // were removed. Keep the last known list visible until a usable snapshot arrives.
                if (!(entries.isEmpty() && memberships.isNotEmpty() && warning != null)) {
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
                }
                _communityListWarning.value = warning
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

    fun refreshDirectInvites(signer: NostrSigner?) {
        val repo = repository ?: return
        if (signer == null) {
            _directInvites.value = DirectInvitesState()
            return
        }
        if (_directInvites.value.isLoading) return
        _directInvites.value = _directInvites.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val invites = repo.fetchDirectInvites(PostillaQuartzSigner(signer))
                _directInvites.value = DirectInvitesState(invites = invites)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _directInvites.value = _directInvites.value.copy(
                    error = error.message ?: "Could not load Direct Invites",
                )
            } finally {
                _directInvites.value = _directInvites.value.copy(isLoading = false)
            }
        }
    }

    fun declineDirectInvite(wrapId: String) {
        val owner = signerPubkey ?: return
        repository?.declineDirectInvite(wrapId, owner)
        _directInvites.value = _directInvites.value.copy(
            invites = _directInvites.value.invites.filterNot { it.wrapId.equals(wrapId, ignoreCase = true) },
        )
    }

    fun acceptDirectInvite(opened: OpenedDirectInvite, signer: NostrSigner?) {
        if (_join.value.isJoining) return
        if (signer == null) {
            _join.value = JoinState(error = "A writable Nostr signer is required")
            return
        }
        val repo = repository ?: run {
            _join.value = JoinState(error = "Community service is not initialized")
            return
        }
        _join.value = JoinState(isJoining = true)
        viewModelScope.launch {
            try {
                val quartzSigner = PostillaQuartzSigner(signer)
                val result = repo.joinCommunityFromDirectInvite(quartzSigner, opened)
                val entries = repo.loadLocalMemberships(quartzSigner)
                val warning = repo.getCommunityListReadWarning()
                if (!(entries.isEmpty() && memberships.isNotEmpty() && warning != null)) {
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
                }
                _communityListWarning.value = warning
                _directInvites.value = _directInvites.value.copy(
                    invites = _directInvites.value.invites.filterNot { it.wrapId == opened.wrapId },
                )
                _join.value = JoinState(result = result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _join.value = JoinState(error = error.message ?: "Could not accept Direct Invite")
            }
        }
    }

    fun loadMembers(communityId: String, signer: NostrSigner?) {
        val repo = repository ?: return
        if (signer == null) {
            _members.value = MembersState(communityId = communityId, error = "A writable Nostr signer is required")
            return
        }
        if (_members.value.communityId == communityId && _members.value.isLoading) return
        _members.value = _members.value.copy(communityId = communityId, isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val entry = memberships[communityId]
                    ?: throw IllegalArgumentException("Community membership is not available")
                val channels = repo.loadCommunityChannels(entry)
                val observed = chatRepository?.loadObservedAuthorActivity(
                    ownerPubkey = signer.pubkeyHex,
                    communityIdHex = entry.id,
                    channels = channels,
                    communityRelays = entry.relays,
                ).orEmpty()
                val loaded = repo.loadMembers(PostillaQuartzSigner(signer), communityId, observed)
                _members.value = MembersState(communityId = communityId, members = loaded)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _members.value = MembersState(
                    communityId = communityId,
                    members = _members.value.members,
                    error = error.message ?: "Could not load community members",
                )
            }
        }
    }

    suspend fun sendDirectInvite(communityId: String, recipient: String, signer: NostrSigner?) {
        require(signer != null) { "A writable Nostr signer is required" }
        val repo = repository ?: throw IllegalStateException("Community service is not initialized")
        repo.sendDirectInvite(PostillaQuartzSigner(signer), communityId, recipient)
    }

    suspend fun kickMember(communityId: String, targetPubkey: String, signer: NostrSigner?) {
        require(signer != null) { "A writable Nostr signer is required" }
        val repo = repository ?: throw IllegalStateException("Community service is not initialized")
        repo.kickAndRotate(PostillaQuartzSigner(signer), communityId, targetPubkey)
        refreshLocalCommunities(signer)
        loadMembers(communityId, signer)
    }

    suspend fun banMember(communityId: String, targetPubkey: String, signer: NostrSigner?) {
        require(signer != null) { "A writable Nostr signer is required" }
        val repo = repository ?: throw IllegalStateException("Community service is not initialized")
        repo.banAndRotate(PostillaQuartzSigner(signer), communityId, targetPubkey)
        refreshLocalCommunities(signer)
        loadMembers(communityId, signer)
    }

    suspend fun createPublicChannel(communityId: String, name: String, signer: NostrSigner?) {
        require(signer != null) { "A writable Nostr signer is required" }
        val repo = repository ?: throw IllegalStateException("Community service is not initialized")
        repo.createPublicChannel(PostillaQuartzSigner(signer), communityId, name)
        refreshChannels(communityId)
    }

    suspend fun renameChannel(communityId: String, channelId: String, name: String, signer: NostrSigner?) {
        require(signer != null) { "A writable Nostr signer is required" }
        val repo = repository ?: throw IllegalStateException("Community service is not initialized")
        repo.renameChannel(PostillaQuartzSigner(signer), communityId, channelId, name)
        refreshChannels(communityId)
    }

    suspend fun deleteChannel(communityId: String, channelId: String, signer: NostrSigner?) {
        require(signer != null) { "A writable Nostr signer is required" }
        val repo = repository ?: throw IllegalStateException("Community service is not initialized")
        repo.deleteChannel(PostillaQuartzSigner(signer), communityId, channelId)
        refreshChannels(communityId)
    }

    fun clearResult() {
        if (!_creation.value.isCreating) _creation.value = CreationState()
    }
}
