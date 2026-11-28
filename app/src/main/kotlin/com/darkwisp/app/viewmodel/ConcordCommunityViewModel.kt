package com.darkwisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darkwisp.app.concord.ConcordChatMessage
import com.darkwisp.app.concord.ConcordChatRepository
import com.darkwisp.app.concord.ConcordHubRepository
import com.darkwisp.app.concord.VectorHubListing
import com.vitorpamplona.quartz.nip92IMeta.IMetaTag
import com.vitorpamplona.quartz.concord.cord02Community.ImagePointer
import com.darkwisp.app.concord.ConcordCommunityRepository
import com.darkwisp.app.concord.concordNormalizeRelayTargets
import com.darkwisp.app.concord.PostillaQuartzSigner
import com.darkwisp.app.nostr.NostrSigner
import com.darkwisp.app.relay.RelayPool
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityListEntry
import com.vitorpamplona.quartz.concord.cord05Invites.OpenedDirectInvite
import com.vitorpamplona.quartz.concord.cord04Roles.ConcordPermissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ConcordCommunityViewModel(app: Application) : AndroidViewModel(app) {
    data class HubDirectoryState(
        val isLoading: Boolean = false,
        val listings: List<VectorHubListing> = emptyList(),
        val error: String? = null,
    )

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

    data class InviteLinksState(
        val communityId: String? = null,
        val isLoading: Boolean = false,
        val links: List<ConcordCommunityRepository.CommunityInviteLink> = emptyList(),
        val revokingToken: String? = null,
        val error: String? = null,
    )

    data class MembersState(
        val communityId: String? = null,
        val isLoading: Boolean = false,
        val members: List<ConcordCommunityRepository.CommunityMember> = emptyList(),
        val error: String? = null,
    )

    data class ChannelSyncState(
        val isLoading: Boolean = false,
        val controlResponded: Int = 0,
        val controlRequested: Int = 0,
        /** A valid authenticated Control Plane snapshot was opened and folded. */
        val controlReady: Boolean = false,
        /** Relay EOSE completeness/quorum, retained for diagnostics and sensitive admin writes. */
        val controlComplete: Boolean = false,
        val writerAccessResponded: Int? = null,
        val writerAccessRequested: Int? = null,
        val writerAccessComplete: Boolean? = null,
        val error: String? = null,
        val lastSyncAtMs: Long? = null,
    )

    data class DirectInvitesState(
        val isLoading: Boolean = false,
        val invites: List<OpenedDirectInvite> = emptyList(),
        val error: String? = null,
    )

    data class RolesState(
        val isLoading: Boolean = false,
        val roles: List<ConcordCommunityRepository.CommunityRole> = emptyList(),
        val rolesV2: Boolean = false,
        val canManageRoles: Boolean = false,
        val delegablePermissionBits: List<Int> = emptyList(),
        val error: String? = null,
    )

    data class CommunityItem(
        val id: String,
        val name: String,
        val generalChannelId: String?,
        val rootEpoch: Long,
        val relays: List<String>,
        val description: String? = null,
    )

    private var repository: ConcordCommunityRepository? = null
    private var chatRepository: ConcordChatRepository? = null
    private val hubRepository = ConcordHubRepository()
    private var signerPubkey: String? = null
    private var activeSigner: NostrSigner? = null
    private var communityWarmupJob: Job? = null

    /**
     * The encrypted local membership is the bootstrap anchor; the current Control Plane
     * metadata is authoritative for the relay set and display name after a successful read.
     * ConcordCommunityListEntry is intentionally not a data class, so preserve every key
     * and extension explicitly when refreshing these hints in the in-memory session.
     */
    private fun communityRelayTargets(
        communityId: String,
        membership: ConcordCommunityListEntry,
    ): List<String> = concordNormalizeRelayTargets(
        _communities.value.firstOrNull { it.id == communityId }?.relays.orEmpty() + membership.relays,
    )

    private fun membershipWithCurrentMetadata(
        membership: ConcordCommunityListEntry,
        name: String?,
        relays: List<String>,
    ): ConcordCommunityListEntry = ConcordCommunityListEntry(
        id = membership.id,
        owner = membership.owner,
        ownerSalt = membership.ownerSalt,
        root = membership.root,
        rootEpoch = membership.rootEpoch,
        controlPk = membership.controlPk,
        controlRoot = membership.controlRoot,
        heldRoots = membership.heldRoots,
        privateChannels = membership.privateChannels,
        // Preserve the encrypted membership's original bootstrap relay hints.
        // Current metadata relays are kept separately in CommunityItem.
        relays = membership.relays.ifEmpty { relays },
        name = name?.takeIf { it.isNotBlank() } ?: membership.name,
        addedAt = membership.addedAt,
        inviteRef = membership.inviteRef,
        excludedAtEpoch = membership.excludedAtEpoch,
        residue = membership.residue,
    )
    private val pendingChannelRefresh = mutableSetOf<String>()
    private val memberships = linkedMapOf<String, ConcordCommunityListEntry>()

    private val _communities = MutableStateFlow<List<CommunityItem>>(emptyList())
    val communities: StateFlow<List<CommunityItem>> = _communities

    private val _hubDirectory = MutableStateFlow(HubDirectoryState())
    val hubDirectory: StateFlow<HubDirectoryState> = _hubDirectory

    private val _channels = MutableStateFlow<List<ConcordCommunityRepository.CommunityChannel>>(emptyList())
    val channels: StateFlow<List<ConcordCommunityRepository.CommunityChannel>> = _channels
    private val _channelsByCommunity =
        MutableStateFlow<Map<String, List<ConcordCommunityRepository.CommunityChannel>>>(emptyMap())
    val channelsByCommunity: StateFlow<Map<String, List<ConcordCommunityRepository.CommunityChannel>>> =
        _channelsByCommunity
    private val _channelSyncByCommunity = MutableStateFlow<Map<String, ChannelSyncState>>(emptyMap())
    val channelSyncByCommunity: StateFlow<Map<String, ChannelSyncState>> = _channelSyncByCommunity

    private val _communityIcons = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val communityIcons: StateFlow<Map<String, ByteArray>> = _communityIcons

    private val _creation = MutableStateFlow(CreationState())
    val creation: StateFlow<CreationState> = _creation

    private val _join = MutableStateFlow(JoinState())
    val join: StateFlow<JoinState> = _join

    private val _invite = MutableStateFlow(InviteState())
    val invite: StateFlow<InviteState> = _invite

    private val _inviteLinksByCommunity =
        MutableStateFlow<Map<String, InviteLinksState>>(emptyMap())
    val inviteLinksByCommunity: StateFlow<Map<String, InviteLinksState>> = _inviteLinksByCommunity

    private val _members = MutableStateFlow(MembersState())
    val members: StateFlow<MembersState> = _members

    private val _membersByCommunity =
        MutableStateFlow<Map<String, MembersState>>(emptyMap())
    val membersByCommunity: StateFlow<Map<String, MembersState>> = _membersByCommunity

    private val _directInvites = MutableStateFlow(DirectInvitesState())
    val directInvites: StateFlow<DirectInvitesState> = _directInvites

    private val _rolesByCommunity = MutableStateFlow<Map<String, RolesState>>(emptyMap())
    val rolesByCommunity: StateFlow<Map<String, RolesState>> = _rolesByCommunity

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _communityListWarning = MutableStateFlow<String?>(null)
    val communityListWarning: StateFlow<String?> = _communityListWarning

    private val _restoringGeneralChannel = MutableStateFlow<String?>(null)
    val restoringGeneralChannel: StateFlow<String?> = _restoringGeneralChannel

    private val _generalChannelError = MutableStateFlow<String?>(null)
    val generalChannelError: StateFlow<String?> = _generalChannelError

    fun init(relayPool: RelayPool, signer: NostrSigner?) {
        activeSigner = signer
        if (repository == null) {
            repository = ConcordCommunityRepository(getApplication(), relayPool)
            chatRepository = ConcordChatRepository(relayPool, getApplication())
        }
        if (signer?.pubkeyHex == signerPubkey) return
        communityWarmupJob?.cancel()
        communityWarmupJob = null
        synchronized(pendingChannelRefresh) { pendingChannelRefresh.clear() }
        signerPubkey = signer?.pubkeyHex
        memberships.clear()
        _channels.value = emptyList()
        _channelsByCommunity.value = emptyMap()
        _channelSyncByCommunity.value = emptyMap()
        _members.value = MembersState()
        _membersByCommunity.value = emptyMap()
        _directInvites.value = DirectInvitesState()
        _inviteLinksByCommunity.value = emptyMap()
        _rolesByCommunity.value = emptyMap()
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

    fun refreshHubDirectory() {
        val current = _hubDirectory.value
        if (current.isLoading) return
        _hubDirectory.value = current.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val listings = hubRepository.fetchListings()
                _hubDirectory.value = HubDirectoryState(listings = listings)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _hubDirectory.value = _hubDirectory.value.copy(
                    isLoading = false,
                    error = error.message ?: "Impossibile caricare il catalogo dei gruppi pubblici",
                )
            }
        }
    }

    fun refreshLocalCommunities(signer: NostrSigner) {
        val repo = repository ?: return
        val requestedOwner = signer.pubkeyHex.lowercase()
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val quartzSigner = PostillaQuartzSigner(signer)
                val entries = repo.loadLocalMemberships(quartzSigner)
                val warning = repo.getCommunityListReadWarning()
                if (signerPubkey?.lowercase() != requestedOwner) return@launch
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
                startCommunityWarmup(signer, entries)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (signerPubkey?.lowercase() != requestedOwner) return@launch
                _communityListWarning.value =
                    error.message ?: "Could not load the Concord Community List"
                // Keep the last known list visible; a failed refresh must not erase it.
            } finally {
                if (signerPubkey?.lowercase() == requestedOwner) _isLoading.value = false
            }
        }
    }

    /**
     * Armada-style first paint: fetch the Control Plane/channel catalogue for every
     * membership, then batch the newest activity across all channels so recent messages
     * and member presence are ready before the user opens a room.
     *
     * Work is account-scoped and bounded to two communities at once. The channel and
     * member snapshots stay local-first: a partial/failed refresh never clears a prior
     * successful snapshot.
     */
    private fun startCommunityWarmup(
        signer: NostrSigner,
        entries: List<ConcordCommunityListEntry>,
    ) {
        val repo = repository ?: return
        val chat = chatRepository ?: return
        val requestedOwner = signer.pubkeyHex.lowercase()
        communityWarmupJob?.cancel()
        communityWarmupJob = viewModelScope.launch {
            val permits = Semaphore(permits = 2)
            coroutineScope {
                entries.map { membership ->
                    async {
                        permits.withPermit {
                            if (signerPubkey?.lowercase() != requestedOwner) return@withPermit
                            val previousChannelState = _channelSyncByCommunity.value[membership.id]
                                ?: ChannelSyncState()
                            val previousMembers = _membersByCommunity.value[membership.id]
                                ?: MembersState(communityId = membership.id)
                            _channelSyncByCommunity.value = _channelSyncByCommunity.value + (
                                membership.id to previousChannelState.copy(isLoading = true, error = null)
                            )
                            setMembersState(
                                membership.id,
                                previousMembers.copy(communityId = membership.id, isLoading = true, error = null),
                            )

                            // Channel/control failures are separated from activity/member failures.
                            var snapshot = try {
                                repo.loadCommunityChannelSnapshot(membership, PostillaQuartzSigner(signer))
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                if (signerPubkey?.lowercase() == requestedOwner) {
                                    _channelSyncByCommunity.value = _channelSyncByCommunity.value + (
                                        membership.id to previousChannelState.copy(
                                            isLoading = false,
                                            controlComplete = false,
                                            error = error.message ?: "Could not synchronize community channels",
                                        )
                                    )
                                    setMembersState(
                                        membership.id,
                                        previousMembers.copy(
                                            communityId = membership.id,
                                            isLoading = false,
                                            error = error.message ?: "Could not synchronize community members",
                                        ),
                                    )
                                }
                                finishQueuedChannelRefresh(membership.id, requestedOwner)
                                return@withPermit
                            }

                            if (signerPubkey?.lowercase() != requestedOwner) return@withPermit
                            val sessionMembership = membershipWithCurrentMetadata(
                                membership,
                                snapshot.communityName,
                                snapshot.communityRelays,
                            )
                            memberships[membership.id] = sessionMembership
                            _channelsByCommunity.value = _channelsByCommunity.value + (membership.id to snapshot.channels)
                            _channels.value = snapshot.channels
                            if (snapshot.communityName != null || snapshot.communityRelays.isNotEmpty()) {
                                _communities.value = _communities.value.map {
                                    if (it.id == membership.id) {
                                        it.copy(
                                            name = sessionMembership.name,
                                            description = snapshot.communityDescription,
                                            relays = snapshot.communityRelays.ifEmpty { sessionMembership.relays },
                                        )
                                    } else it
                                }
                            }
                            val access = snapshot.writerAccessRead
                            _channelSyncByCommunity.value = _channelSyncByCommunity.value + (
                                membership.id to ChannelSyncState(
                                    isLoading = false,
                                    controlResponded = snapshot.controlRead.respondedCount,
                                    controlRequested = snapshot.controlRead.requestedCount,
                                    controlReady = true,
                                    controlComplete = snapshot.controlRead.hasQuorum,
                                    writerAccessResponded = access?.respondedCount,
                                    writerAccessRequested = access?.requestedCount,
                                    writerAccessComplete = access?.hasQuorum,
                                    lastSyncAtMs = System.currentTimeMillis(),
                                )
                            )
                            val general = snapshot.channels.firstOrNull { it.name.equals("general", ignoreCase = true) }
                            if (general != null) {
                                _communities.value = _communities.value.map {
                                    if (it.id == membership.id) it.copy(generalChannelId = general.id) else it
                                }
                            }

                            try {
                                val (writerSnapshot, writerError) = prepareWriterAccess(
                                    repo, membership, signer, snapshot,
                                )
                                snapshot = writerSnapshot
                                if (signerPubkey?.lowercase() != requestedOwner) return@withPermit
                                _channelsByCommunity.value = _channelsByCommunity.value + (membership.id to snapshot.channels)
                                _channels.value = snapshot.channels
                                val writerRead = snapshot.writerAccessRead
                                val latestSync = _channelSyncByCommunity.value[membership.id] ?: ChannelSyncState()
                                _channelSyncByCommunity.value = _channelSyncByCommunity.value + (
                                    membership.id to latestSync.copy(
                                        controlResponded = snapshot.controlRead.respondedCount,
                                        controlRequested = snapshot.controlRead.requestedCount,
                                        controlReady = true,
                                    controlComplete = snapshot.controlRead.hasQuorum,
                                        writerAccessResponded = writerRead?.respondedCount,
                                        writerAccessRequested = writerRead?.requestedCount,
                                        writerAccessComplete = writerRead?.hasQuorum,
                                        error = writerError,
                                        lastSyncAtMs = System.currentTimeMillis(),
                                    )
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                if (signerPubkey?.lowercase() == requestedOwner) {
                                    val latestSync = _channelSyncByCommunity.value[membership.id] ?: ChannelSyncState()
                                    _channelSyncByCommunity.value = _channelSyncByCommunity.value + (
                                        membership.id to latestSync.copy(
                                            error = error.message ?: "Could not initialize writer access",
                                        )
                                    )
                                }
                            }

                            try {
                                val observed = chat.loadObservedAuthorActivity(
                                    ownerPubkey = requestedOwner,
                                    communityIdHex = membership.id,
                                    channels = snapshot.channels,
                                    communityRelays = communityRelayTargets(membership.id, membership),
                                )
                                if (signerPubkey?.lowercase() != requestedOwner) return@withPermit
                                val loadedMembers = repo.loadMembers(PostillaQuartzSigner(signer), membership.id, observed)
                                if (signerPubkey?.lowercase() != requestedOwner) return@withPermit
                                setMembersState(
                                    membership.id,
                                    MembersState(communityId = membership.id, members = loadedMembers),
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                if (signerPubkey?.lowercase() == requestedOwner) {
                                    val latestMembers = _membersByCommunity.value[membership.id] ?: previousMembers
                                    setMembersState(
                                        membership.id,
                                        latestMembers.copy(
                                            communityId = membership.id,
                                            isLoading = false,
                                            error = error.message ?: "Could not synchronize community members",
                                        ),
                                    )
                                }
                            } finally {
                                finishQueuedChannelRefresh(membership.id, requestedOwner)
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private fun finishQueuedChannelRefresh(communityId: String, requestedOwner: String) {
        if (signerPubkey?.lowercase() != requestedOwner) {
            synchronized(pendingChannelRefresh) { pendingChannelRefresh.remove(communityId) }
            return
        }
        val rerun = synchronized(pendingChannelRefresh) { pendingChannelRefresh.remove(communityId) }
        if (rerun) refreshChannels(communityId)
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

    /**
     * Invalidates the cached image after a metadata edit and reloads the encrypted
     * pointer from the community Control Plane.
     */
    private fun refreshCommunityIcon(communityId: String) {
        _communityIcons.value = _communityIcons.value - communityId
        loadCommunityIcon(communityId)
    }

    private fun findChannel(communityId: String, channelId: String) =
        _channelsByCommunity.value[communityId]?.firstOrNull { it.id == channelId }

    private suspend fun prepareWriterAccess(
        repo: ConcordCommunityRepository,
        membership: ConcordCommunityListEntry,
        signer: NostrSigner,
        initial: ConcordCommunityRepository.CommunityChannelSnapshot,
    ): Pair<ConcordCommunityRepository.CommunityChannelSnapshot, String?> {
        var snapshot = initial
        var issue: String? = null
        val candidates = initial.channels.filter { channel ->
            (channel.isLocked || channel.writeRestricted) &&
                channel.writerCredential == null &&
                channel.canManageChannel
        }
        for (channel in candidates) {
            try {
                snapshot = repo.ensureWriterAccess(
                    signer = PostillaQuartzSigner(signer),
                    communityId = membership.id,
                    channelId = channel.id,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                issue = error.message ?: "Could not initialize Concord writer access"
                break
            }
        }
        return snapshot to issue
    }

    fun refreshChannels(communityId: String) {
        val repo = repository ?: return
        val membership = memberships[communityId] ?: return
        val signer = activeSigner
        val requestedOwner = signer?.pubkeyHex?.lowercase()
        val previous = _channelSyncByCommunity.value[communityId] ?: ChannelSyncState()
        if (previous.isLoading) {
            synchronized(pendingChannelRefresh) { pendingChannelRefresh.add(communityId) }
            return
        }
        _channelSyncByCommunity.value = _channelSyncByCommunity.value +
            (communityId to previous.copy(isLoading = true, error = null))

        viewModelScope.launch {
            try {
                var snapshot = repo.loadCommunityChannelSnapshot(
                    membership,
                    signer?.let(::PostillaQuartzSigner),
                )
                var writerError: String? = null
                if (signer != null) {
                    val prepared = prepareWriterAccess(repo, membership, signer, snapshot)
                    snapshot = prepared.first
                    writerError = prepared.second
                }
                if (signerPubkey?.lowercase() != requestedOwner) return@launch
                val sessionMembership = membershipWithCurrentMetadata(
                    membership,
                    snapshot.communityName,
                    snapshot.communityRelays,
                )
                memberships[communityId] = sessionMembership
                _channelsByCommunity.value = _channelsByCommunity.value + (communityId to snapshot.channels)
                _channels.value = snapshot.channels
                if (snapshot.communityName != null || snapshot.communityRelays.isNotEmpty()) {
                    _communities.value = _communities.value.map {
                        if (it.id == communityId) {
                            it.copy(
                                name = sessionMembership.name,
                                description = snapshot.communityDescription,
                                relays = snapshot.communityRelays.ifEmpty { sessionMembership.relays },
                            )
                        } else it
                    }
                }
                val access = snapshot.writerAccessRead
                _channelSyncByCommunity.value = _channelSyncByCommunity.value + (
                    communityId to ChannelSyncState(
                        isLoading = false,
                        controlResponded = snapshot.controlRead.respondedCount,
                        controlRequested = snapshot.controlRead.requestedCount,
                        controlReady = true,
                                    controlComplete = snapshot.controlRead.hasQuorum,
                        writerAccessResponded = access?.respondedCount,
                        writerAccessRequested = access?.requestedCount,
                        writerAccessComplete = access?.hasQuorum,
                        error = writerError,
                        lastSyncAtMs = System.currentTimeMillis(),
                    )
                )
                val general = snapshot.channels.firstOrNull { it.name.equals("general", ignoreCase = true) }
                if (general != null) {
                    _communities.value = _communities.value.map {
                        if (it.id == communityId) it.copy(generalChannelId = general.id) else it
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (signerPubkey?.lowercase() != requestedOwner) return@launch
                val latest = _channelSyncByCommunity.value[communityId] ?: previous
                _channelSyncByCommunity.value = _channelSyncByCommunity.value + (
                    communityId to latest.copy(
                        isLoading = false,
                        controlComplete = false,
                        error = error.message ?: "Could not synchronize the Concord channels",
                    )
                )
            } finally {
                if (signerPubkey?.lowercase() == requestedOwner) {
                    val rerun = synchronized(pendingChannelRefresh) {
                        pendingChannelRefresh.remove(communityId)
                    }
                    if (rerun) refreshChannels(communityId)
                } else {
                    synchronized(pendingChannelRefresh) { pendingChannelRefresh.remove(communityId) }
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
            communityRelays = communityRelayTargets(communityId, entry),
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
        val activeSigner = signer ?: return null
        val requestedOwner = activeSigner.pubkeyHex.lowercase()
        if (signerPubkey?.lowercase() != requestedOwner) {
            throw IllegalStateException("The active Nostr account changed. Refresh the community before sending.")
        }
        val entry = memberships[communityId] ?: return null
        val channelSync = _channelSyncByCommunity.value[communityId]
        if (channelSync?.controlReady != true) {
            val detail = channelSync?.let { "${it.controlResponded}/${it.controlRequested}" } ?: "not verified"
            throw IllegalStateException(
                "A valid Concord Control Plane snapshot is not available yet ($detail relays responded). Refresh channels before sending.",
            )
        }
        if (channelSync.controlComplete != true) {
            throw IllegalStateException(
                "The Control Plane has not reached a verified relay quorum " +
                    "(${channelSync.controlResponded}/${channelSync.controlRequested}). " +
                    "Postilla remains read-only until channel permissions are verified.",
            )
        }
        val channelInfo = findChannel(communityId, channelId)
            ?: throw IllegalStateException("Channel access is not loaded yet. Refresh the channel list before sending.")
        if (!channelInfo.canViewChannel) {
            throw IllegalStateException("This account does not have VIEW_CHANNEL permission.")
        }
        if (!channelInfo.canSendMessages) {
            throw IllegalStateException("This account does not have SEND_MESSAGES permission in this channel.")
        }
        if (imetas.isNotEmpty() && !channelInfo.canAttachFiles) {
            throw IllegalStateException("This account does not have ATTACH_FILES permission in this channel.")
        }
        if (channelInfo.isPrivate && channelInfo.privateKeyHex.isNullOrBlank()) {
            throw IllegalStateException("The private channel key is not available on this account.")
        }
        if (channelInfo.writeRestricted && channelInfo.writerCredential?.canSendMessages != true) {
            throw IllegalStateException(
                "This channel requires a current CORD-03 writer key and SEND_MESSAGES permission. Refresh channel access before sending.",
            )
        }
        val chat = chatRepository ?: return null
        return chat.sendMessage(
            signer = PostillaQuartzSigner(activeSigner),
            communityIdHex = entry.id,
            communityRootHex = entry.root,
            channelIdHex = channelId,
            epoch = entry.rootEpoch,
            text = text,
            privateChannelKeyHex = channelInfo.privateKeyHex,
            privateChannelEpoch = channelInfo.privateChannelEpoch,
            communityRelays = communityRelayTargets(communityId, entry),
            writerCredential = channelInfo.writerCredential,
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
            communityRelays = communityRelayTargets(communityId, entry),
        ).orEmpty()
    }

    suspend fun sendChannelReaction(
        communityId: String,
        channelId: String,
        target: ConcordChatMessage,
        reaction: String,
        signer: NostrSigner?,
    ): Boolean {
        val activeSigner = signer ?: return false
        if (signerPubkey?.lowercase() != activeSigner.pubkeyHex.lowercase()) return false
        val entry = memberships[communityId] ?: return false
        val channelSync = _channelSyncByCommunity.value[communityId] ?: return false
        if (!channelSync.controlReady || !channelSync.controlComplete) return false
        val channel = findChannel(communityId, channelId) ?: return false
        if (!channel.canViewChannel || !channel.canAddReactions) return false
        if (channel.isPrivate && channel.privateKeyHex.isNullOrBlank()) return false
        // Restricted-but-not-locked channels keep reactions on the shared read plane.
        // Locked channels require the delivered writer key and ADD_REACTIONS scope.
        if (channel.isLocked && channel.writerCredential?.canAddReactions != true) return false
        return chatRepository?.sendReaction(
            signer = PostillaQuartzSigner(activeSigner),
            communityIdHex = entry.id,
            communityRootHex = entry.root,
            channelIdHex = channelId,
            epoch = entry.rootEpoch,
            target = target,
            reaction = reaction,
            privateChannelKeyHex = channel.privateKeyHex,
            privateChannelEpoch = channel.privateChannelEpoch,
            communityRelays = communityRelayTargets(communityId, entry),
            writerCredential = channel.writerCredential,
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

    fun refreshInviteLinks(communityId: String, signer: NostrSigner?) {
        val repo = repository ?: return
        if (signer == null) {
            _inviteLinksByCommunity.value = _inviteLinksByCommunity.value +
                (communityId to InviteLinksState(communityId = communityId, error = "A writable Nostr signer is required"))
            return
        }
        val requestedOwner = signer.pubkeyHex.lowercase()
        val current = _inviteLinksByCommunity.value[communityId] ?: InviteLinksState(communityId = communityId)
        if (current.isLoading || current.revokingToken != null) return
        _inviteLinksByCommunity.value = _inviteLinksByCommunity.value + (
            communityId to current.copy(communityId = communityId, isLoading = true, error = null)
        )
        viewModelScope.launch {
            try {
                val links = repo.loadInviteLinks(PostillaQuartzSigner(signer), communityId)
                if (signerPubkey?.lowercase() != requestedOwner) return@launch
                _inviteLinksByCommunity.value = _inviteLinksByCommunity.value + (
                    communityId to InviteLinksState(communityId = communityId, links = links)
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (signerPubkey?.lowercase() != requestedOwner) return@launch
                val latest = _inviteLinksByCommunity.value[communityId] ?: current
                _inviteLinksByCommunity.value = _inviteLinksByCommunity.value + (
                    communityId to latest.copy(
                        communityId = communityId,
                        isLoading = false,
                        error = error.message ?: "Could not load public invite links",
                    )
                )
            }
        }
    }

    suspend fun revokeInviteLink(
        communityId: String,
        token: String,
        signer: NostrSigner?,
    ) {
        val repo = repository ?: throw IllegalStateException("Community service is not initialized")
        val active = signer ?: throw IllegalStateException("A writable Nostr signer is required")
        val requestedOwner = active.pubkeyHex.lowercase()
        val current = _inviteLinksByCommunity.value[communityId] ?: InviteLinksState(communityId = communityId)
        if (current.revokingToken != null) return
        _inviteLinksByCommunity.value = _inviteLinksByCommunity.value + (
            communityId to current.copy(revokingToken = token, error = null)
        )
        try {
            repo.revokeInviteLink(PostillaQuartzSigner(active), communityId, token)
            val links = repo.loadInviteLinks(PostillaQuartzSigner(active), communityId)
            if (signerPubkey?.lowercase() != requestedOwner) return
            _inviteLinksByCommunity.value = _inviteLinksByCommunity.value + (
                communityId to InviteLinksState(communityId = communityId, links = links)
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (signerPubkey?.lowercase() != requestedOwner) return
            val latest = _inviteLinksByCommunity.value[communityId] ?: current
            _inviteLinksByCommunity.value = _inviteLinksByCommunity.value + (
                communityId to latest.copy(
                    communityId = communityId,
                    revokingToken = null,
                    error = error.message ?: "Could not revoke public invite link",
                )
            )
        }
    }

    fun refreshDirectInvites(signer: NostrSigner?) {
        val repo = repository ?: return
        if (signer == null) {
            _directInvites.value = DirectInvitesState()
            return
        }
        val requestedOwner = signer.pubkeyHex.lowercase()
        if (_directInvites.value.isLoading) return
        _directInvites.value = _directInvites.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val invites = repo.fetchDirectInvites(PostillaQuartzSigner(signer))
                if (signerPubkey?.lowercase() != requestedOwner) return@launch
                _directInvites.value = DirectInvitesState(invites = invites)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (signerPubkey?.lowercase() != requestedOwner) return@launch
                _directInvites.value = _directInvites.value.copy(
                    error = error.message ?: "Could not load Direct Invites",
                )
            } finally {
                if (signerPubkey?.lowercase() == requestedOwner) {
                    _directInvites.value = _directInvites.value.copy(isLoading = false)
                }
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

    private fun setMembersState(communityId: String, state: MembersState) {
        _membersByCommunity.value = _membersByCommunity.value + (communityId to state)
        _members.value = state // Compatibility snapshot for callers that still observe one community at a time.
    }

    fun loadMembers(communityId: String, signer: NostrSigner?) {
        val repo = repository ?: return
        if (signer == null) {
            setMembersState(
                communityId,
                MembersState(communityId = communityId, error = "A writable Nostr signer is required"),
            )
            return
        }
        val requestedOwner = signer.pubkeyHex.lowercase()
        val current = _membersByCommunity.value[communityId] ?: MembersState(communityId = communityId)
        if (current.isLoading) return

        setMembersState(
            communityId,
            current.copy(communityId = communityId, isLoading = true, error = null),
        )
        viewModelScope.launch {
            try {
                val entry = memberships[communityId]
                    ?: throw IllegalArgumentException("Community membership is not available")
                val channelSnapshot = repo.loadCommunityChannelSnapshot(
                    entry,
                    PostillaQuartzSigner(signer),
                )
                val observed = chatRepository?.loadObservedAuthorActivity(
                    ownerPubkey = signer.pubkeyHex,
                    communityIdHex = entry.id,
                    channels = channelSnapshot.channels,
                    communityRelays = concordNormalizeRelayTargets(channelSnapshot.communityRelays + entry.relays),
                ).orEmpty()
                val loaded = repo.loadMembers(PostillaQuartzSigner(signer), communityId, observed)
                if (signerPubkey?.lowercase() != requestedOwner) return@launch
                setMembersState(communityId, MembersState(communityId = communityId, members = loaded))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (signerPubkey?.lowercase() != requestedOwner) return@launch
                val latest = _membersByCommunity.value[communityId]
                    ?: MembersState(communityId = communityId)
                setMembersState(
                    communityId,
                    latest.copy(
                        communityId = communityId,
                        isLoading = false,
                        error = error.message ?: "Could not load community members",
                    ),
                )
            }
        }
    }

    private fun setRolesState(communityId: String, state: RolesState) {
        _rolesByCommunity.value = _rolesByCommunity.value + (communityId to state)
    }

    private fun requireCurrentWritableSigner(signer: NostrSigner?): NostrSigner {
        val active = signer ?: throw IllegalStateException("A writable Nostr signer is required")
        check(signerPubkey?.equals(active.pubkeyHex, ignoreCase = true) == true) {
            "The active Nostr account changed. Refresh the community before managing roles."
        }
        return active
    }

    private suspend fun refreshRolesSnapshot(
        communityId: String,
        signer: NostrSigner,
        requestedOwner: String,
    ) {
        val repo = repository ?: throw IllegalStateException("Community service is not initialized")
        val snapshot = repo.loadCommunityRoles(PostillaQuartzSigner(signer), communityId)
        if (signerPubkey?.lowercase() != requestedOwner) return
        setRolesState(
            communityId,
            RolesState(
                roles = snapshot.roles,
                rolesV2 = snapshot.rolesV2,
                canManageRoles = snapshot.canManageRoles,
                delegablePermissionBits = snapshot.delegablePermissionBits,
            ),
        )
    }

    fun refreshRoles(communityId: String, signer: NostrSigner?) {
        val repo = repository ?: return
        if (signer == null) {
            setRolesState(communityId, RolesState(error = "A writable Nostr signer is required"))
            return
        }
        val requestedOwner = signer.pubkeyHex.lowercase()
        val current = _rolesByCommunity.value[communityId] ?: RolesState()
        if (current.isLoading) return
        setRolesState(communityId, current.copy(isLoading = true, error = null))
        viewModelScope.launch {
            try {
                refreshRolesSnapshot(communityId, signer, requestedOwner)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (signerPubkey?.lowercase() != requestedOwner) return@launch
                val latest = _rolesByCommunity.value[communityId] ?: current
                setRolesState(
                    communityId,
                    latest.copy(isLoading = false, error = error.message ?: "Could not load community roles"),
                )
            }
        }
    }

    suspend fun createRole(
        communityId: String,
        name: String,
        position: Long,
        permissions: ConcordPermissions,
        signer: NostrSigner?,
    ): String {
        val active = requireCurrentWritableSigner(signer)
        val repo = repository ?: throw IllegalStateException("Community service is not initialized")
        val owner = active.pubkeyHex.lowercase()
        val roleId = repo.createRole(PostillaQuartzSigner(active), communityId, name, position, permissions)
        refreshRolesSnapshot(communityId, active, owner)
        return roleId
    }

    suspend fun updateRole(
        communityId: String,
        roleId: String,
        name: String,
        position: Long,
        permissions: ConcordPermissions,
        signer: NostrSigner?,
    ): String {
        val active = requireCurrentWritableSigner(signer)
        val repo = repository ?: throw IllegalStateException("Community service is not initialized")
        val owner = active.pubkeyHex.lowercase()
        val eventId = repo.updateRole(PostillaQuartzSigner(active), communityId, roleId, name, position, permissions)
        refreshRolesSnapshot(communityId, active, owner)
        return eventId
    }

    suspend fun deleteRole(communityId: String, roleId: String, signer: NostrSigner?) {
        val active = requireCurrentWritableSigner(signer)
        val repo = repository ?: throw IllegalStateException("Community service is not initialized")
        val owner = active.pubkeyHex.lowercase()
        repo.deleteRole(PostillaQuartzSigner(active), communityId, roleId)
        refreshRolesSnapshot(communityId, active, owner)
        loadMembers(communityId, active)
    }

    suspend fun setMemberRoles(
        communityId: String,
        memberPubKey: String,
        roleIds: Collection<String>,
        signer: NostrSigner?,
    ) {
        val active = requireCurrentWritableSigner(signer)
        val repo = repository ?: throw IllegalStateException("Community service is not initialized")
        val owner = active.pubkeyHex.lowercase()
        repo.setMemberRoles(PostillaQuartzSigner(active), communityId, memberPubKey, roleIds)
        refreshRolesSnapshot(communityId, active, owner)
        loadMembers(communityId, active)
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

    suspend fun updateCommunityMetadata(
        communityId: String,
        name: String,
        description: String?,
        relays: List<String>,
        icon: ImagePointer? = null,
        banner: ImagePointer? = null,
        signer: NostrSigner?,
    ) {
        val active = requireCurrentWritableSigner(signer)
        val repo = repository ?: throw IllegalStateException("Community service is not initialized")
        repo.updateCommunityMetadata(
            signer = PostillaQuartzSigner(active),
            communityId = communityId,
            name = name,
            description = description,
            icon = icon,
            banner = banner,
            relays = relays,
        )
        _communities.value = _communities.value.map {
            if (it.id == communityId) it.copy(name = name.trim(), description = description, relays = relays) else it
        }
        refreshChannels(communityId)
        if (icon != null) refreshCommunityIcon(communityId)
    }

    suspend fun createPublicChannel(communityId: String, name: String, signer: NostrSigner?) {
        require(signer != null) { "A writable Nostr signer is required" }
        val repo = repository ?: throw IllegalStateException("Community service is not initialized")
        repo.createPublicChannel(PostillaQuartzSigner(signer), communityId, name)
        refreshChannels(communityId)
    }

    suspend fun createPrivateChannel(communityId: String, name: String, signer: NostrSigner?) {
        val active = requireCurrentWritableSigner(signer)
        val repo = repository ?: throw IllegalStateException("Community service is not initialized")
        repo.createPrivateChannel(PostillaQuartzSigner(active), communityId, name)
        refreshChannels(communityId)
        refreshRoles(communityId, active)
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
