package com.darkwisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darkwisp.app.concord.PostillaQuartzSigner
import com.darkwisp.app.nostr.ClientMessage
import com.darkwisp.app.nostr.Filter
import com.darkwisp.app.nostr.NostrEvent
import com.darkwisp.app.nostr.NostrSigner
import com.darkwisp.app.relay.RelayPool
import com.vitorpamplona.crypto.random.RandomInstance
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityFactory
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityList
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityListEntry
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityListFragmentEvent
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityState
import com.vitorpamplona.quartz.concord.cord02Community.ConcordListFragmentSet
import com.vitorpamplona.quartz.concord.cord02Community.PrivateChannelKey
import com.vitorpamplona.quartz.concord.cord03Channels.ChannelChat
import com.vitorpamplona.quartz.concord.cord03Channels.ConcordChannelKeys
import com.vitorpamplona.quartz.concord.cord04Roles.ChannelEntity
import com.vitorpamplona.quartz.concord.cord04Roles.ConcordJson
import com.vitorpamplona.quartz.concord.cord04Roles.ControlEdition
import com.vitorpamplona.quartz.concord.cord04Roles.ControlEditionBuilder
import com.vitorpamplona.quartz.concord.cord04Roles.ControlEntityKind
import com.vitorpamplona.quartz.concord.crypto.ControlPlaneKeys
import com.vitorpamplona.quartz.concord.crypto.GroupKey
import com.vitorpamplona.quartz.concord.cord05Invites.CommunityInvite
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteBundle
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteLink
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteList
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteListDocument
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteListEntry
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteListEvent
import com.vitorpamplona.quartz.concord.cord05Invites.InviteBundleStatus
import com.vitorpamplona.quartz.concord.cord05Invites.InviteChannel
import com.vitorpamplona.quartz.concord.envelope.ConcordStreamEnvelope
import com.vitorpamplona.quartz.nip01Core.core.Event as QuartzEvent
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.encodeToString
import java.util.UUID
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

data class ConcordCommunityUi(
    val id: String,
    val name: String,
    val description: String,
    val owner: String,
    val relays: List<String>,
    val channels: List<ConcordChannelUi>,
    val isOwner: Boolean,
)

data class ConcordChannelUi(
    val id: String,
    val name: String,
    val isPrivate: Boolean,
    val epoch: Long,
)

data class ConcordChatMessageUi(
    val id: String,
    val author: String,
    val content: String,
    val createdAt: Long,
    val isMine: Boolean,
)

/**
 * Concord integration for Postilla. User keys stay behind the existing Postilla
 * signer abstraction, including NIP-55 remote signers. The joined-community and
 * invite lists are encrypted to self by Quartz and are never stored as plaintext.
 */
class ConcordCommunitiesViewModel(app: Application) : AndroidViewModel(app) {
    private val _communities = MutableStateFlow<List<ConcordCommunityUi>>(emptyList())
    val communities: StateFlow<List<ConcordCommunityUi>> = _communities.asStateFlow()

    private val _activeCommunityId = MutableStateFlow<String?>(null)
    val activeCommunityId: StateFlow<String?> = _activeCommunityId.asStateFlow()

    private val _activeChannelId = MutableStateFlow<String?>(null)
    val activeChannelId: StateFlow<String?> = _activeChannelId.asStateFlow()

    private val _messages = MutableStateFlow<List<ConcordChatMessageUi>>(emptyList())
    val messages: StateFlow<List<ConcordChatMessageUi>> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _inviteLink = MutableStateFlow<String?>(null)
    val inviteLink: StateFlow<String?> = _inviteLink.asStateFlow()

    private var signer: NostrSigner? = null
    private var quartzSigner: PostillaQuartzSigner? = null
    private var relayPool: RelayPool? = null
    private var accountPubkey: String? = null
    private var accountJob: Job? = null
    private var channelJob: Job? = null
    private var activeSubscriptionId: String? = null
    private var activeChannelKey: GroupKey? = null
    private var activeChannelEpoch: Long = 0
    private var activeChannelRelays: List<String> = emptyList()
    private var entries: List<ConcordCommunityListEntry> = emptyList()
    private val writeMutex = kotlinx.coroutines.sync.Mutex()

    companion object {
        // Filled only after the owner creates and publishes the official Postilla
        // community from a real account. Empty deliberately disables synthetic auto-join.
        private const val DEFAULT_POSTILLA_COMMUNITY_INVITE = ""
        private const val EOSE_TIMEOUT_MS = 15_000L
    }

    fun initialize(activeSigner: NostrSigner?, pool: RelayPool) {
        val newPubkey = activeSigner?.pubkeyHex?.lowercase()
        if (newPubkey == accountPubkey && relayPool === pool) return

        accountJob?.cancel()
        channelJob?.cancel()
        activeSubscriptionId = null
        signer = activeSigner
        quartzSigner = activeSigner?.let(::PostillaQuartzSigner)
        relayPool = pool
        accountPubkey = newPubkey
        entries = emptyList()
        _communities.value = emptyList()
        _activeCommunityId.value = null
        _activeChannelId.value = null
        _messages.value = emptyList()
        _error.value = null
        _status.value = null
        _inviteLink.value = null

        if (activeSigner == null) {
            _status.value = "Accedi con il tuo account Nostr per usare Concord."
            return
        }

        accountJob = viewModelScope.launch {
            refreshInternal()
            // The official Postilla invite is inserted only after the owner has
            // created the real community from a signed-in staging installation.
            val defaultInvite = DEFAULT_POSTILLA_COMMUNITY_INVITE
            if (defaultInvite.isNotBlank()) {
                joinFromInvite(defaultInvite)
            }
        }
    }

    fun refresh() {
        if (signer == null || relayPool == null) return
        viewModelScope.launch { refreshInternal() }
    }

    private suspend fun refreshInternal() {
        _busy.value = true
        _error.value = null
        try {
            loadJoinedCommunities()
            _status.value = if (_communities.value.isEmpty()) {
                "Non sei ancora iscritto a una community Concord."
            } else {
                null
            }
        } catch (e: Exception) {
            _error.value = e.message ?: "Impossibile aggiornare le community."
        } finally {
            _busy.value = false
        }
    }

    fun createPostillaCommunity() {
        createCommunity("Postilla", "Official Postilla Community")
    }

    fun createCommunity(name: String, description: String) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) {
            _error.value = "Inserisci il nome della community."
            return
        }
        if (cleanName.encodeToByteArray().size > 64) {
            _error.value = "Il nome non può superare 64 byte UTF-8."
            return
        }
        viewModelScope.launch {
            writeMutex.lock()
            _busy.value = true
            _error.value = null
            _status.value = "Creazione della community e dei canali…"
            try {
                val quartz = requireNotNull(quartzSigner) { "Signer Nostr non disponibile." }
                val pool = requireNotNull(relayPool) { "Relay non disponibili." }
                val writeRelays = pool.getWriteRelayUrls().distinct().take(5)
                require(writeRelays.isNotEmpty()) { "Configura almeno un relay Nostr scrivibile." }

                val now = System.currentTimeMillis() / 1000L
                // Keep signing on the caller coroutine: NIP-55 signers may need
                // an Android foreground interaction and must not be hidden on Dispatchers.Default.
                val community = ConcordCommunityFactory.create(
                    ownerSigner = quartz,
                    name = cleanName,
                    createdAt = now,
                    description = description.trim().takeIf { it.isNotEmpty() },
                    relays = writeRelays,
                )

                val controlWraps = community.genesisWraps.toMutableList()
                val additionalChannels = mutableListOf<Pair<String, String>>()
                for (channelName in listOf("supporto", "sviluppo")) {
                    val channelId = RandomInstance.bytes(32)
                    val json = ConcordJson.instance.encodeToString(
                        ChannelEntity.serializer(),
                        ChannelEntity(name = channelName, private = false),
                    )
                    val rumor = ControlEditionBuilder.rumor(
                        authorPubKey = quartz.pubKey,
                        entityKind = ControlEntityKind.CHANNEL,
                        entityId = channelId,
                        version = 1L,
                        prevHash = null,
                        content = json,
                        createdAt = now,
                    )
                    controlWraps += ConcordStreamEnvelope.wrap(
                        rumor = rumor,
                        keys = community.controlPlane,
                        authorSigner = quartz,
                        encrypted = false,
                        createdAt = now,
                    )
                    additionalChannels += channelId.toHexKey() to channelName
                }

                // A community is exposed only after a relay has accepted every genesis edition.
                for (wrap in controlWraps) publishConfirmed(wrap)

                val inviteChannels = buildList {
                    add(InviteChannel(id = community.generalChannelIdHex, epoch = 0L, name = "general"))
                    additionalChannels.forEach { (id, channelName) ->
                        add(InviteChannel(id = id, epoch = 0L, name = channelName))
                    }
                }
                val invite = CommunityInvite(
                    communityId = community.communityIdHex,
                    owner = community.ownerPubKey,
                    ownerSalt = community.ownerSalt.toHexKey(),
                    communityRoot = community.communityRoot.toHexKey(),
                    rootEpoch = community.rootEpoch,
                    controlPk = community.controlPkHex,
                    channels = inviteChannels,
                    relays = writeRelays,
                    name = cleanName,
                )
                val minted = ConcordInviteBundle.mintLink(
                    base = "https://vector.chat",
                    invite = invite,
                    createdAt = now,
                    relays = writeRelays.take(3),
                )
                val bareInvite = requireNotNull(ConcordInviteLink.bareForm(minted.url)) {
                    "Il link Concord generato non è valido."
                }

                val entry = ConcordCommunityListEntry(
                    id = community.communityIdHex,
                    owner = community.ownerPubKey,
                    ownerSalt = community.ownerSalt.toHexKey(),
                    root = community.communityRoot.toHexKey(),
                    rootEpoch = community.rootEpoch,
                    controlPk = community.controlPkHex,
                    controlRoot = community.controlRoot.toHexKey(),
                    relays = writeRelays,
                    name = cleanName,
                    addedAt = System.currentTimeMillis(),
                    inviteRef = bareInvite,
                )
                saveCommunityEntry(entry)

                // Persist the creator's encrypted invite secret before publishing the public bundle.
                saveMintedInvite(minted, community.communityIdHex, bareInvite, now)
                publishConfirmed(minted.bundleEvent)

                _inviteLink.value = minted.url
                _status.value = "Community creata e invito pubblicato."
                loadJoinedCommunities()
                _activeCommunityId.value = community.communityIdHex
            } catch (e: Exception) {
                _error.value = e.message ?: "Creazione della community non riuscita."
            } finally {
                _busy.value = false
                writeMutex.unlock()
            }
        }
    }

    fun joinFromInvite(link: String) {
        val cleanLink = link.trim()
        if (cleanLink.isEmpty()) {
            _error.value = "Inserisci un invito Concord."
            return
        }
        viewModelScope.launch {
            writeMutex.lock()
            _busy.value = true
            _error.value = null
            _status.value = "Verifica dell'invito…"
            try {
                val quartz = requireNotNull(quartzSigner) { "Signer Nostr non disponibile." }
                val parsed = requireNotNull(ConcordInviteLink.parseUrl(cleanLink)) {
                    "Il link non è un invito Concord valido."
                }
                val relays = (parsed.fragment.relays + readRelays()).distinct().take(5)
                require(relays.isNotEmpty()) { "Nessun relay disponibile per recuperare l'invito." }
                val events = queryEvents(
                    filter = Filter(
                        kinds = listOf(ConcordInviteBundle.KIND),
                        authors = listOf(parsed.linkSignerPubKey),
                        dTags = listOf(""),
                    ),
                    relays = relays,
                )
                val bundleStatus = ConcordInviteBundle.classify(
                    wraps = events.map { QuartzEvent.fromJson(it.toJson()) },
                    linkSignerPubKey = parsed.linkSignerPubKey,
                    token = parsed.fragment.token,
                    nowMs = System.currentTimeMillis(),
                )
                val invite = when (bundleStatus) {
                    is InviteBundleStatus.Live -> bundleStatus.invite
                    is InviteBundleStatus.Expired -> throw IllegalStateException("Questo invito Concord è scaduto.")
                    InviteBundleStatus.Revoked -> throw IllegalStateException("Questo invito Concord è stato revocato.")
                    InviteBundleStatus.Absent -> throw IllegalStateException("Invito non trovato sui relay indicati.")
                    InviteBundleStatus.Unreadable -> throw IllegalStateException("Invito non leggibile: token errato o bundle non supportato.")
                }

                val entry = ConcordCommunityListEntry(
                    id = invite.communityId,
                    owner = invite.owner,
                    ownerSalt = invite.ownerSalt,
                    root = invite.communityRoot,
                    rootEpoch = invite.rootEpoch,
                    controlPk = invite.controlPk,
                    controlRoot = null,
                    privateChannels = invite.channels.filter { it.key.isNotBlank() }.map {
                        PrivateChannelKey(channelId = it.id, key = it.key, epoch = it.epoch, name = it.name)
                    },
                    relays = invite.relays.distinct().take(5),
                    name = invite.name,
                    addedAt = System.currentTimeMillis(),
                    inviteRef = requireNotNull(ConcordInviteLink.bareForm(cleanLink)),
                )
                saveCommunityEntry(entry)
                _status.value = "Iscrizione completata."
                loadJoinedCommunities()
                _activeCommunityId.value = entry.id
            } catch (e: Exception) {
                _error.value = e.message ?: "Iscrizione alla community non riuscita."
            } finally {
                _busy.value = false
                writeMutex.unlock()
            }
        }
    }

    fun openCommunity(communityId: String) {
        _activeCommunityId.value = communityId
        _activeChannelId.value = null
        _messages.value = emptyList()
        stopChannelSubscription()
    }

    fun openChannel(communityId: String, channelId: String) {
        val community = _communities.value.firstOrNull { it.id == communityId } ?: return
        val channel = community.channels.firstOrNull { it.id == channelId } ?: return
        val entry = entries.firstOrNull { it.id == communityId } ?: return
        val pool = relayPool ?: return

        stopChannelSubscription()
        _activeCommunityId.value = communityId
        _activeChannelId.value = channelId
        _messages.value = emptyList()
        _error.value = null

        try {
            val root = entry.root.hexToByteArray()
            val idBytes = entry.id.hexToByteArray()
            val key = if (channel.isPrivate) {
                val privateKey = entry.privateChannels.firstOrNull { it.channelId == channelId }
                    ?: throw IllegalStateException("Non possiedi la chiave di questo canale privato.")
                ConcordChannelKeys.privateChannel(
                    channelKey = privateKey.key.hexToByteArray(),
                    channelId = idBytes,
                    channelEpoch = privateKey.epoch,
                )
            } else {
                ConcordChannelKeys.publicChannel(root, channelId.hexToByteArray(), entry.rootEpoch)
            }
            activeChannelKey = key
            activeChannelEpoch = channel.epoch
            activeChannelRelays = (entry.relays + readRelays()).distinct().take(5)
            val subId = "concord-chat-" + UUID.randomUUID().toString().replace("-", "").take(16)
            activeSubscriptionId = subId

            channelJob = viewModelScope.launch {
                pool.relayEvents
                    .filter { it.subscriptionId == subId }
                    .collect { relayEvent ->
                        val opened = try {
                            ConcordStreamEnvelope.openOrNull(QuartzEvent.fromJson(relayEvent.event.toJson()), key)
                        } catch (_: Exception) {
                            null
                        } ?: return@collect
                        val rumor = ChannelChat.acceptOpened(opened, channelId, channel.epoch) ?: return@collect
                        val message = ConcordChatMessageUi(
                            id = rumor.id,
                            author = rumor.pubKey,
                            content = rumor.content,
                            createdAt = rumor.createdAt,
                            isMine = rumor.pubKey.equals(accountPubkey, ignoreCase = true),
                        )
                        if (_messages.value.none { it.id == message.id }) {
                            _messages.value = (_messages.value + message).sortedBy { it.createdAt }
                        }
                    }
            }

            for (url in activeChannelRelays) {
                pool.ensureGroupRelay(url)
                pool.sendToRelayOrEphemeral(
                    url = url,
                    message = ClientMessage.req(
                        subId,
                        Filter(kinds = listOf(ConcordStreamEnvelope.KIND_WRAP), authors = listOf(key.publicKeyHex), limit = 100),
                    ),
                    skipBadCheck = true,
                    autoReconnect = true,
                )
            }
        } catch (e: Exception) {
            _error.value = e.message ?: "Impossibile aprire il canale."
        }
    }

    fun sendMessage(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val channelId = _activeChannelId.value ?: return
        val community = _communities.value.firstOrNull { it.id == _activeCommunityId.value } ?: return
        val key = activeChannelKey ?: return
        val quartz = quartzSigner ?: return
        val channel = community.channels.firstOrNull { it.id == channelId } ?: return

        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            try {
                val now = System.currentTimeMillis() / 1000L
                val rumor = ChannelChat.message(
                    authorPubKey = quartz.pubKey,
                    channelId = channelId,
                    epoch = activeChannelEpoch,
                    text = clean,
                    createdAt = now,
                )
                val wrap = ConcordStreamEnvelope.wrap(
                    rumor = rumor,
                    stream = key,
                    authorSigner = quartz,
                    encrypted = true,
                    createdAt = now,
                )
                publishConfirmed(wrap)
                val message = ConcordChatMessageUi(rumor.id, quartz.pubKey, clean, now, true)
                if (_messages.value.none { it.id == message.id }) {
                    _messages.value = (_messages.value + message).sortedBy { it.createdAt }
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Invio del messaggio non confermato dai relay."
            } finally {
                _busy.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun clearInviteLink() {
        _inviteLink.value = null
    }

    private fun stopChannelSubscription() {
        channelJob?.cancel()
        channelJob = null
        activeSubscriptionId?.let { subId ->
            activeChannelRelays.forEach {
                relayPool?.sendToRelayOrEphemeral(it, ClientMessage.close(subId), skipBadCheck = true)
            }
        }
        activeSubscriptionId = null
        activeChannelKey = null
        activeChannelRelays = emptyList()
    }

    private suspend fun loadJoinedCommunities() {
        val quartz = requireNotNull(quartzSigner)
        val relays = readRelays()
        require(relays.isNotEmpty()) { "Configura almeno un relay di lettura." }

        val ownEvents = queryEvents(
            filter = Filter(kinds = listOf(ConcordCommunityListFragmentEvent.KIND), authors = listOf(quartz.pubKey)),
            relays = relays,
        )
        val fragments = ownEvents.map {
            ConcordCommunityListFragmentEvent(
                id = it.id,
                pubKey = it.pubkey,
                createdAt = it.created_at,
                tags = it.tags.map { tag -> tag.toTypedArray() }.toTypedArray(),
                content = it.content,
                sig = it.sig,
            )
        }
        val set = ConcordListFragmentSet.resolve(fragments, quartz)
        require(set.complete) {
            "La lista personale Concord è incompleta o non decifrabile. Non verrà sovrascritta."
        }
        val document = ConcordCommunityList.decodeDocument(set.doc)
        entries = document.entries

        val summaries = mutableListOf<ConcordCommunityUi>()
        for (entry in document.entries) {
            val summary = runCatching { loadCommunityState(entry, relays) }.getOrNull()
            summaries += summary ?: ConcordCommunityUi(
                id = entry.id,
                name = entry.name.ifBlank { "Community Concord" },
                description = "Metadati temporaneamente non disponibili",
                owner = entry.owner,
                relays = entry.relays,
                channels = emptyList(),
                isOwner = entry.owner.equals(quartz.pubKey, ignoreCase = true),
            )
        }
        _communities.value = summaries
    }

    private suspend fun loadCommunityState(
        entry: ConcordCommunityListEntry,
        fallbackRelays: List<String>,
    ): ConcordCommunityUi {
        val quartz = requireNotNull(quartzSigner)
        val root = entry.root.hexToByteArray()
        val communityIdBytes = entry.id.hexToByteArray()
        val control = ControlPlaneKeys.of(
            communityRoot = root,
            communityId = communityIdBytes,
            epoch = entry.rootEpoch,
            controlPk = entry.controlPk,
            controlRoot = entry.controlRoot,
        )
        val relays = (entry.relays + fallbackRelays).distinct().take(5)
        val wraps = queryEvents(
            filter = Filter(kinds = listOf(ConcordStreamEnvelope.KIND_WRAP), authors = listOf(control.address), limit = 300),
            relays = relays,
        )
        val editions = wraps.mapNotNull { event ->
            runCatching {
                val opened = ConcordStreamEnvelope.openOrNull(QuartzEvent.fromJson(event.toJson()), control)
                    ?: return@runCatching null
                ControlEdition.fromOpened(opened)
            }.getOrNull()
        }
        val state = ConcordCommunityState.fold(editions, communityIdBytes, entry.owner)
        val channels = state.channels.values.map { channel ->
            val privateKey = entry.privateChannels.firstOrNull { it.channelId == channel.channelIdHex }
            ConcordChannelUi(
                id = channel.channelIdHex,
                name = channel.definition.name,
                isPrivate = channel.definition.private,
                epoch = privateKey?.epoch ?: entry.rootEpoch,
            )
        }.sortedBy { it.name.lowercase() }
        return ConcordCommunityUi(
            id = entry.id,
            name = state.metadata?.name ?: entry.name.ifBlank { "Community Concord" },
            description = state.metadata?.description.orEmpty(),
            owner = entry.owner,
            relays = entry.relays,
            channels = channels,
            isOwner = entry.owner.equals(quartz.pubKey, ignoreCase = true),
        )
    }

    private suspend fun saveCommunityEntry(entry: ConcordCommunityListEntry) {
        val quartz = requireNotNull(quartzSigner)
        val current = readCommunityList(quartz)
        val merged = ConcordCommunityList.merge(current.document.entries, listOf(entry))
        val newDoc = ConcordCommunityList.encodeInternal(merged, current.document.residue)
        val writes = current.fragments.planWrites(newDoc, System.currentTimeMillis() / 1000L)
        for (write in writes) {
            val event = ConcordCommunityListFragmentEvent.create(
                signer = quartz,
                index = write.index,
                plaintext = write.plaintext,
                createdAt = write.createdAt,
            )
            publishConfirmed(event)
        }
    }

    private data class CommunityListSnapshot(
        val fragments: ConcordListFragmentSet,
        val document: com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityListDocument,
    )

    private suspend fun readCommunityList(quartz: PostillaQuartzSigner): CommunityListSnapshot {
        val events = queryEvents(
            filter = Filter(kinds = listOf(ConcordCommunityListFragmentEvent.KIND), authors = listOf(quartz.pubKey)),
            relays = readRelays(),
        )
        val fragments = events.map {
            ConcordCommunityListFragmentEvent(
                id = it.id,
                pubKey = it.pubkey,
                createdAt = it.created_at,
                tags = it.tags.map { tag -> tag.toTypedArray() }.toTypedArray(),
                content = it.content,
                sig = it.sig,
            )
        }
        val set = ConcordListFragmentSet.resolve(fragments, quartz)
        require(set.complete) {
            "Lista Concord incompleta: operazione annullata per proteggere le iscrizioni già salvate."
        }
        return CommunityListSnapshot(set, ConcordCommunityList.decodeDocument(set.doc))
    }

    private suspend fun saveMintedInvite(
        minted: com.vitorpamplona.quartz.concord.cord05Invites.MintedInviteLink,
        communityId: String,
        bareUrl: String,
        now: Long,
    ) {
        val quartz = requireNotNull(quartzSigner)
        val existingEvents = queryEvents(
            filter = Filter(kinds = listOf(ConcordInviteListEvent.KIND), authors = listOf(quartz.pubKey), limit = 20),
            relays = readRelays(),
        )
        val latestEvent = existingEvents.maxByOrNull { it.created_at }
        val previous = if (latestEvent == null) {
            ConcordInviteListDocument.EMPTY
        } else {
            val typed = ConcordInviteListEvent(
                id = latestEvent.id,
                pubKey = latestEvent.pubkey,
                createdAt = latestEvent.created_at,
                tags = latestEvent.tags.map { tag -> tag.toTypedArray() }.toTypedArray(),
                content = latestEvent.content,
                sig = latestEvent.sig,
            )
            requireNotNull(typed.decrypt(quartz)) {
                "La lista privata degli inviti non è leggibile: non sovrascrivo le chiavi dei link esistenti."
            }
        }
        val newEntry = ConcordInviteListEntry(
            token = minted.token.toHexKey(),
            signerSk = minted.linkSignerPrivKey.toHexKey(),
            communityId = communityId,
            url = bareUrl,
            createdAt = now,
        )
        val merged = ConcordInviteList.merge(previous, ConcordInviteListDocument(entries = listOf(newEntry)))
        publishConfirmed(ConcordInviteListEvent.create(quartz, merged, now))
    }

    private suspend fun queryEvents(filter: Filter, relays: List<String>): List<NostrEvent> = coroutineScope {
        val pool = requireNotNull(relayPool)
        val urls = relays.distinct().filter { it.isNotBlank() }
        require(urls.isNotEmpty()) { "Nessun relay disponibile per la richiesta Concord." }

        val subId = "concord-fetch-" + UUID.randomUUID().toString().replace("-", "").take(16)
        val received = Collections.synchronizedList(mutableListOf<NostrEvent>())
        val eoseRelays = ConcurrentHashMap.newKeySet<String>()
        val completed = CompletableDeferred<Unit>()

        val eventCollector = launch {
            pool.relayEvents.collect { relayEvent ->
                if (relayEvent.subscriptionId == subId) received.add(relayEvent.event)
            }
        }
        val eoseCollector = launch {
            pool.relayEoseSignals.collect { signal ->
                if (signal.subscriptionId == subId) {
                    eoseRelays.add(signal.relayUrl)
                    if (eoseRelays.size >= urls.size && !completed.isCompleted) completed.complete(Unit)
                }
            }
        }

        try {
            for (url in urls) {
                pool.ensureGroupRelay(url)
                pool.sendToRelayOrEphemeral(
                    url = url,
                    message = ClientMessage.req(subId, filter),
                    skipBadCheck = true,
                    autoReconnect = true,
                )
            }
            val ended = withTimeoutOrNull(EOSE_TIMEOUT_MS) {
                completed.await()
                true
            } ?: false
            require(ended) {
                "Risposta incompleta dai relay (" + eoseRelays.size + "/" + urls.size + " EOSE): non modifico dati Concord."
            }
        } finally {
            urls.forEach { pool.sendToRelayOrEphemeral(it, ClientMessage.close(subId), skipBadCheck = true) }
            eventCollector.cancel()
            eoseCollector.cancel()
        }
        received.distinctBy { it.id }
    }

    private suspend fun publishConfirmed(event: QuartzEvent) {
        val pool = requireNotNull(relayPool)
        val urls = pool.getWriteRelayUrls().distinct()
        require(urls.isNotEmpty()) { "Nessun relay scrivibile configurato." }
        val nostrEvent = NostrEvent.fromJson(event.toJson())
        urls.forEach { pool.ensureGroupRelay(it) }
        val outcome = pool.publishWithConfirmation(event.id, timeoutMs = 12_000L) {
            urls.count { url ->
                pool.sendToRelayOrEphemeral(
                    url = url,
                    message = ClientMessage.event(nostrEvent),
                    skipBadCheck = true,
                    write = true,
                    autoReconnect = true,
                )
            }
        }
        require(outcome.confirmed) {
            "Nessun relay ha confermato la pubblicazione dell'evento " + event.id.take(12) + "."
        }
    }

    private fun readRelays(): List<String> {
        val pool = relayPool ?: return emptyList()
        return (pool.getReadRelayUrls() + pool.getWriteRelayUrls()).distinct()
    }
}
