package com.darkwisp.app.repo

import com.darkwisp.app.nostr.ClientMessage
import com.darkwisp.app.nostr.Filter
import com.darkwisp.app.nostr.NostrEvent
import com.darkwisp.app.nostr.NostrSigner as PostillaSigner
import com.darkwisp.app.relay.RelayPool
import com.darkwisp.app.relay.SubscriptionManager
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityFactory
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityList
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityListEntry
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityListFragmentEvent
import com.vitorpamplona.quartz.concord.cord02Community.ConcordListFragmentSet
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityState
import com.vitorpamplona.quartz.concord.cord02Community.Guestbook
import com.vitorpamplona.quartz.concord.cord02Community.HeldRoot
import com.vitorpamplona.quartz.concord.cord02Community.ImagePointer
import com.vitorpamplona.quartz.concord.cord02Community.PrivateChannelKey
import com.vitorpamplona.quartz.concord.cord03Channels.ChannelChat
import com.vitorpamplona.quartz.concord.cord03Channels.ConcordChannelKeyring
import com.vitorpamplona.quartz.concord.cord03Channels.ConcordChannelKeys
import com.vitorpamplona.quartz.concord.cord04Roles.AuthorityCitation
import com.vitorpamplona.quartz.concord.cord04Roles.ConcordJson
import com.vitorpamplona.quartz.concord.cord04Roles.ConcordPermissions
import com.vitorpamplona.quartz.concord.cord04Roles.ControlEdition
import com.vitorpamplona.quartz.concord.cord04Roles.ControlEditionBuilder
import com.vitorpamplona.quartz.concord.cord04Roles.ControlEntityKind
import com.vitorpamplona.quartz.concord.cord04Roles.MetadataEntity
import com.vitorpamplona.quartz.concord.cord05Invites.CommunityInvite
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteBundle
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteLink
import com.vitorpamplona.quartz.concord.cord05Invites.InviteBundleStatus
import com.vitorpamplona.quartz.concord.cord05Invites.InviteChannel
import com.vitorpamplona.quartz.concord.cord05Invites.InviteRelayDictionary
import com.vitorpamplona.quartz.concord.crypto.ConcordKeyDerivation
import com.vitorpamplona.quartz.concord.crypto.ControlPlaneKeys
import com.vitorpamplona.quartz.concord.crypto.GroupKey
import com.vitorpamplona.quartz.concord.envelope.ConcordStreamEnvelope
import com.vitorpamplona.quartz.nip01Core.core.Event as QuartzEvent
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner as QuartzSigner
import com.vitorpamplona.quartz.nip57Zaps.PrivateZapEvent
import com.vitorpamplona.quartz.nip57Zaps.ZapRequestEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ConcordChannelSummary(
    val id: String,
    val name: String,
    val isPrivate: Boolean,
    val epoch: Long,
    val canRead: Boolean,
)

data class ConcordCommunitySummary(
    val id: String,
    val name: String,
    val description: String?,
    val owner: String,
    val relays: List<String>,
    val channels: List<ConcordChannelSummary>,
    val canManageMetadata: Boolean,
    val messageExpirationSecs: Long?,
)

data class ConcordChatMessage(
    val id: String,
    val author: String,
    val content: String,
    val createdAt: Long,
)

data class ConcordCreatedCommunity(
    val community: ConcordCommunitySummary,
    val inviteUrl: String,
)

class ConcordRepository(
    private val relayPool: RelayPool,
    private val subscriptions: SubscriptionManager,
    private val blossomRepository: BlossomRepository,
) {
    companion object {
        const val OFFICIAL_POSTILLA_INVITE =
            "https://v3.coinos.io/invite/naddr1qvzqqqyzz5pzpgqw540hvar6acgeuyrfnll60n6qrg9me4h90yucs3u2eh8tqmqrqqqqhhcy6s#BAADABJtZXNzYWdlcy5jb2lub3MuaW8AD3JlbGF5LmNvaW5vcy5pbwAQcmVsYXkucHJpbWFsLm5ldJqwIkTDMxKJDWju8TxtQzg"

        private const val LEGACY_USER_COMMUNITY_LIST_KIND = 13302
        private const val USER_COMMUNITY_LIST_FRAGMENT_KIND = 33302
        private const val CONTROL_WRAP_KIND = 1059
    }

    private data class CommunitySnapshot(
        val entry: ConcordCommunityListEntry,
        val keys: ControlPlaneKeys,
        val state: ConcordCommunityState,
        val editions: List<ControlEdition>,
    )

    private data class ConcordCommunityListDocument(
        val entries: List<ConcordCommunityListEntry>,
        val residue: com.vitorpamplona.quartz.concord.cord02Community.ConcordListResidue,
        val fragments: ConcordListFragmentSet,
        val relays: List<String>,
    )

    private val snapshots = HashMap<String, CommunitySnapshot>()
    private val streamAuthLock = Any()
    private val streamAuthKeysByRelay = ConcurrentHashMap<String, ConcurrentHashMap<String, GroupKey>>()
    @Volatile private var streamAuthAccount: String? = null

    init {
        relayPool.setConcordStreamAuthSigner(::signConcordStreamAuthEvents)
    }

    private fun ensureStreamAuthAccount(signer: PostillaSigner) {
        synchronized(streamAuthLock) {
            if (streamAuthAccount != signer.pubkeyHex) {
                streamAuthKeysByRelay.clear()
                relayPool.clearConcordAuthTargets()
                streamAuthAccount = signer.pubkeyHex
            }
        }
    }

    private fun registerConcordStreamKeys(
        signer: PostillaSigner,
        relays: List<String>,
        keys: Iterable<GroupKey>,
    ) {
        ensureStreamAuthAccount(signer)
        val targets = relays
            .filter { it.startsWith("wss://") || it.startsWith("ws://") }
            .distinct()
        if (targets.isEmpty()) return

        val uniqueKeys = keys.distinctBy { it.publicKeyHex }
        for (url in targets) {
            val relayKeys = streamAuthKeysByRelay.computeIfAbsent(url) { ConcurrentHashMap() }
            uniqueKeys.forEach { relayKeys[it.publicKeyHex] = it }
        }
        relayPool.registerConcordAuthTargets(targets)
    }

    private fun registerCommunityStreamKeys(
        signer: PostillaSigner,
        entry: ConcordCommunityListEntry,
        additionalKeys: Iterable<GroupKey> = emptyList(),
    ) {
        val communityId = entry.id.hexToByteArray()
        val keys = ArrayList<GroupKey>()
        val currentRoot = entry.root.hexToByteArray()
        val currentControl = ControlPlaneKeys.of(
            communityRoot = currentRoot,
            communityId = communityId,
            epoch = entry.rootEpoch,
            controlPk = entry.controlPk,
            controlRoot = entry.controlRoot,
        )
        currentControl.signer?.let(keys::add)
        keys += ConcordKeyDerivation.guestbookPlaneKey(currentRoot, communityId, entry.rootEpoch)
        keys += ConcordKeyDerivation.dissolvedPlaneKey(communityId)
        if (entry.rootEpoch < Long.MAX_VALUE) {
            keys += ConcordKeyDerivation.baseRekeyAddress(currentRoot, communityId, entry.rootEpoch + 1)
        }

        entry.heldRoots.forEach { held ->
            val heldRoot = held.key.hexToByteArray()
            val heldControl = ControlPlaneKeys.of(
                communityRoot = heldRoot,
                communityId = communityId,
                epoch = held.epoch,
                controlPk = held.controlPk,
                controlRoot = held.controlRoot,
            )
            heldControl.signer?.let(keys::add)
            keys += ConcordKeyDerivation.guestbookPlaneKey(heldRoot, communityId, held.epoch)
            if (held.epoch < entry.rootEpoch) {
                keys += ConcordKeyDerivation.baseRekeyAddress(heldRoot, communityId, entry.rootEpoch)
            }
            if (held.epoch < Long.MAX_VALUE) {
                keys += ConcordKeyDerivation.baseRekeyAddress(heldRoot, communityId, held.epoch + 1)
            }
        }

        keys += additionalKeys
        registerConcordStreamKeys(signer, entry.relays, keys)
    }

    private suspend fun signConcordStreamAuthEvents(
        relayUrl: String,
        challenge: String,
    ): List<NostrEvent> {
        val keys = streamAuthKeysByRelay[relayUrl]?.values?.toList().orEmpty()
        return keys.mapNotNull { key ->
            runCatching {
                NostrEvent.create(
                    privkey = key.secretKey,
                    pubkey = key.publicKey,
                    kind = 22242,
                    content = "",
                    tags = listOf(
                        listOf("relay", relayUrl),
                        listOf("challenge", challenge),
                    ),
                )
            }.getOrNull()
        }
    }

    suspend fun ensureOfficialPostillaCommunity(signer: PostillaSigner): ConcordCommunitySummary {
        val parsed = ConcordInviteLink.parseUrl(OFFICIAL_POSTILLA_INVITE)
            ?: throw IllegalStateException("The official Postilla Concord invite is malformed.")
        val bundle = resolveInvite(OFFICIAL_POSTILLA_INVITE, signer)
        val existing = loadUserCommunityList(signer).entries.firstOrNull {
            it.id.equals(bundle.communityId, ignoreCase = true)
        }
        if (existing == null || existing.rootEpoch < bundle.rootEpoch) {
            joinResolvedInvite(OFFICIAL_POSTILLA_INVITE, parsed, bundle, signer)
        }
        val entry = loadUserCommunityList(signer).entries.firstOrNull {
            it.id.equals(bundle.communityId, ignoreCase = true)
        } ?: throw IllegalStateException("Postilla invite was resolved, but the joined-community list was not updated.")
        return loadCommunity(entry, signer)
    }

    suspend fun loadJoinedCommunities(signer: PostillaSigner): List<ConcordCommunitySummary> {
        val entries = loadUserCommunityList(signer).entries
        val loaded = ArrayList<ConcordCommunitySummary>(entries.size)
        for (entry in entries.sortedBy { it.name.lowercase() }) {
            try {
                loaded += loadCommunity(entry, signer)
            } catch (_: Exception) {
                // A temporarily unreachable Control Plane must not hide the community itself.
                loaded += ConcordCommunitySummary(
                    id = entry.id,
                    name = entry.name.ifBlank { "Community" },
                    description = null,
                    owner = entry.owner,
                    relays = entry.relays,
                    channels = emptyList(),
                    canManageMetadata = false,
                    messageExpirationSecs = null,
                )
            }
        }
        return loaded
    }

    suspend fun joinInvite(inviteUrl: String, signer: PostillaSigner): ConcordCommunitySummary {
        val parsed = ConcordInviteLink.parseUrl(inviteUrl.trim())
            ?: throw IllegalArgumentException("This is not a valid Concord invitation link.")
        val invite = resolveInvite(inviteUrl.trim(), signer)
        val list = loadUserCommunityList(signer)
        val existing = list.entries.firstOrNull { it.id.equals(invite.communityId, ignoreCase = true) }
        if (existing == null || existing.rootEpoch < invite.rootEpoch) {
            joinResolvedInvite(inviteUrl.trim(), parsed, invite, signer, list)
        }
        val updated = loadUserCommunityList(signer).entries.firstOrNull {
            it.id.equals(invite.communityId, ignoreCase = true)
        } ?: throw IllegalStateException("The invitation was opened, but Postilla could not save the membership.")
        return loadCommunity(updated, signer)
    }

    private suspend fun resolveInvite(inviteUrl: String, signer: PostillaSigner): CommunityInvite {
        val parsed = ConcordInviteLink.parseUrl(inviteUrl)
            ?: throw IllegalArgumentException("Invalid Concord invitation URL.")
        val relays = (
            parsed.fragment.relays +
                InviteRelayDictionary.STOCK +
                relayPool.getRelayUrls()
            )
            .filter { it.startsWith("wss://") || it.startsWith("ws://") }
            .distinct()
            .take(28)
        if (relays.isEmpty()) throw IllegalArgumentException("The invitation has no reachable bootstrap relays.")
        val events = queryAcrossRelays(
            relays,
            Filter(
                kinds = listOf(parsed.kind),
                authors = listOf(parsed.linkSignerPubKey),
                // Do not pin #d to an empty value: some interoperable clients/relays return
                // the current addressable bundle more reliably by author + kind alone.
                limit = 1000,
            ),
        ).mapNotNull { runCatching { QuartzEvent.fromJson(it.toJson()) }.getOrNull() }
        return when (val status = ConcordInviteBundle.classify(
            events,
            parsed.linkSignerPubKey,
            parsed.fragment.token,
        )) {
            is InviteBundleStatus.Live -> status.invite
            is InviteBundleStatus.Expired -> throw IllegalStateException("This Concord invitation has expired.")
            InviteBundleStatus.Revoked -> throw IllegalStateException("This Concord invitation has been revoked.")
            InviteBundleStatus.Absent -> throw IllegalStateException("The invitation was not found on its bootstrap relays.")
            InviteBundleStatus.Unreadable -> throw IllegalStateException("The invitation could not be decrypted or validated.")
        }
    }

    private suspend fun joinResolvedInvite(
        inviteUrl: String,
        parsed: com.vitorpamplona.quartz.concord.cord05Invites.ParsedInviteLink,
        invite: CommunityInvite,
        signer: PostillaSigner,
        current: ConcordCommunityListDocument? = null,
    ) {
        val currentDocument = current ?: loadUserCommunityList(signer)
        if (!ConcordInviteBundle.validate(invite)) {
            throw IllegalStateException("Concord rejected the invitation's self-certifying community identity.")
        }
        val heldRoots = listOf(
            HeldRoot(
                epoch = invite.rootEpoch,
                key = invite.communityRoot.lowercase(),
                controlPk = invite.controlPk?.lowercase(),
            ),
        )
        val privateChannels = invite.channels
            .filter { it.key.isNotBlank() }
            .map {
                PrivateChannelKey(
                    channelId = it.id.lowercase(),
                    key = it.key.lowercase(),
                    epoch = it.epoch,
                    name = it.name,
                )
            }
        val entry = ConcordCommunityListEntry(
            id = invite.communityId.lowercase(),
            owner = invite.owner.lowercase(),
            ownerSalt = invite.ownerSalt.lowercase(),
            root = invite.communityRoot.lowercase(),
            rootEpoch = invite.rootEpoch,
            controlPk = invite.controlPk?.lowercase(),
            heldRoots = heldRoots,
            privateChannels = privateChannels,
            relays = (invite.relays + parsed.fragment.relays).distinct().take(8),
            name = invite.name,
            addedAt = System.currentTimeMillis() / 1000,
            inviteRef = inviteUrl.substringBefore('#'),
        )
        val entries = currentDocument.entries.filterNot { it.id.equals(entry.id, ignoreCase = true) } + entry
        publishUserCommunityList(signer, entries, currentDocument.residue, currentDocument.fragments, currentDocument.relays)

        val root = invite.communityRoot.hexToByteArray()
        val id = invite.communityId.hexToByteArray()
        registerCommunityStreamKeys(signer, entry)
        val guestbook = ConcordKeyDerivation.guestbookPlaneKey(root, id, invite.rootEpoch)
        val quartzSigner = PostillaQuartzSigner(signer)
        val joinRumor = Guestbook.join(
            memberPubKey = signer.pubkeyHex,
            createdAt = System.currentTimeMillis() / 1000,
        )
        val joinWrap = ConcordStreamEnvelope.wrap(
            rumor = joinRumor,
            stream = guestbook,
            authorSigner = quartzSigner,
            encrypted = true,
        )
        publishToCommunityRelays(entry.relays, joinWrap)
    }

    private suspend fun loadUserCommunityList(signer: PostillaSigner): ConcordCommunityListDocument {
        ensureStreamAuthAccount(signer)
        // Reference clients keep the fragmented private list on Concord's stock relays;
        // some accounts also publish it to their own NIP-65/outbox relays.
        val relays = (InviteRelayDictionary.STOCK + relayPool.getRelayUrls())
            .filter { it.startsWith("wss://") || it.startsWith("ws://") }
            .distinct()
            .take(28)
        if (relays.isEmpty()) {
            return ConcordCommunityListDocument(
                emptyList(),
                com.vitorpamplona.quartz.concord.cord02Community.ConcordListResidue.EMPTY,
                ConcordListFragmentSet.EMPTY,
                emptyList(),
            )
        }

        val events = queryAcrossRelays(
            relays,
            Filter(
                kinds = listOf(USER_COMMUNITY_LIST_FRAGMENT_KIND, LEGACY_USER_COMMUNITY_LIST_KIND),
                authors = listOf(signer.pubkeyHex),
                limit = 1000,
            ),
        ).sortedByDescending { it.created_at }

        val quartzEvents = events.mapNotNull { nostr ->
            runCatching { QuartzEvent.fromJson(nostr.toJson()) }.getOrNull()
        }
        val fragments = quartzEvents
            .filter { it.kind == USER_COMMUNITY_LIST_FRAGMENT_KIND }
            .mapNotNull { event ->
                runCatching {
                    ConcordCommunityListFragmentEvent(
                        id = event.id,
                        pubKey = event.pubKey,
                        createdAt = event.createdAt,
                        tags = event.tags,
                        content = event.content,
                        sig = event.sig,
                    )
                }.getOrNull()
            }
        val fragmentSet = ConcordListFragmentSet.resolve(fragments, signer.pubkeyHex) { fragment ->
            runCatching { signer.nip44Decrypt(fragment.content, signer.pubkeyHex) }.getOrNull()
        }

        val legacy = events.firstOrNull { it.kind == LEGACY_USER_COMMUNITY_LIST_KIND }
        val legacyPlaintext = legacy?.let {
            runCatching { signer.nip44Decrypt(it.content, signer.pubkeyHex) }.getOrNull()
        }
        // The old 13302 event is a rescue source only. New writes always use kind 33302 fragments.
        val merged = ConcordCommunityList.readWithLegacy(fragmentSet, legacyPlaintext)
        val decoded = ConcordCommunityList.decodeDocument(merged)
        return ConcordCommunityListDocument(decoded.entries, decoded.residue, fragmentSet, relays)
    }

    private suspend fun publishUserCommunityList(
        signer: PostillaSigner,
        entries: List<ConcordCommunityListEntry>,
        residue: com.vitorpamplona.quartz.concord.cord02Community.ConcordListResidue,
        fragments: ConcordListFragmentSet,
        relays: List<String>,
    ) {
        val newDocument = ConcordCommunityList.encodeInternal(entries, residue)
        val writes = fragments.planWrites(newDocument, System.currentTimeMillis() / 1000)
        if (writes.isEmpty()) return

        val quartzSigner = PostillaQuartzSigner(signer)
        val targets = (relays + InviteRelayDictionary.STOCK + relayPool.getRelayUrls())
            .filter { it.startsWith("wss://") || it.startsWith("ws://") }
            .distinct()
            .take(28)
        if (targets.isEmpty()) throw IllegalStateException("No relay is available to publish the joined-Community list.")

        for (write in writes) {
            val event = ConcordCommunityListFragmentEvent.create(
                signer = quartzSigner,
                index = write.index,
                plaintext = write.plaintext,
                createdAt = write.createdAt,
            )
            val nostrEvent = NostrEvent.fromJson(event.toJson())
            val message = ClientMessage.event(nostrEvent)
            val outcome = relayPool.publishWithConfirmation(nostrEvent.id) {
                targets.count { url ->
                    relayPool.sendToRelayOrEphemeral(
                        url,
                        message,
                        skipBadCheck = true,
                        write = true,
                        autoReconnect = true,
                    )
                }
            }
            if (!outcome.confirmed) {
                throw IllegalStateException("No Concord relay confirmed the joined-Community list fragment ${write.index}.")
            }
        }
    }

    private suspend fun loadCommunity(
        entry: ConcordCommunityListEntry,
        signer: PostillaSigner,
    ): ConcordCommunitySummary {
        val root = entry.root.hexToByteArray()
        val communityId = entry.id.hexToByteArray()
        val keys = ControlPlaneKeys.of(
            communityRoot = root,
            communityId = communityId,
            epoch = entry.rootEpoch,
            controlPk = entry.controlPk,
            controlRoot = entry.controlRoot,
        )
        registerCommunityStreamKeys(signer, entry)
        val relays = entry.relays.distinct().take(8)
        val wraps = queryAcrossRelays(
            relays,
            Filter(kinds = listOf(CONTROL_WRAP_KIND), authors = listOf(keys.address), limit = 500),
        )
        val editions = wraps.mapNotNull { nostr ->
            runCatching {
                val opened = ConcordStreamEnvelope.openOrNull(QuartzEvent.fromJson(nostr.toJson()), keys)
                opened?.let { ControlEdition.fromOpened(it) }
            }.getOrNull()
        }
        val state = ConcordCommunityState.fold(editions, communityId, entry.owner)
        val channelStreamKeys = state.channels.values.mapNotNull { channel ->
            val channelBytes = channel.channelIdHex.hexToByteArray()
            val held = ConcordChannelKeyring.heldKey(entry, channel.channelIdHex)
            if (channel.definition.private) {
                held?.let {
                    ConcordChannelKeys.privateChannel(it.key.hexToByteArray(), channelBytes, it.epoch)
                }
            } else {
                ConcordChannelKeys.publicChannel(root, channelBytes, entry.rootEpoch)
            }
        }
        registerCommunityStreamKeys(signer, entry, channelStreamKeys)
        val channels = state.channels.values
            .sortedBy { it.definition.name.lowercase() }
            .map { channel ->
                val channelId = channel.channelIdHex
                val held = ConcordChannelKeyring.heldKey(entry, channelId)
                val isPrivate = channel.definition.private
                ConcordChannelSummary(
                    id = channelId,
                    name = channel.definition.name,
                    isPrivate = isPrivate,
                    epoch = held?.epoch ?: entry.rootEpoch,
                    canRead = !isPrivate || held != null,
                )
            }
        val canManage = state.authority.isOwner(signer.pubkeyHex) ||
            state.authority.hasPermission(signer.pubkeyHex, ConcordPermissions.MANAGE_METADATA)
        val snapshot = CommunitySnapshot(entry, keys, state, editions)
        synchronized(snapshots) { snapshots[entry.id] = snapshot }
        val metadata = state.metadata
        return ConcordCommunitySummary(
            id = entry.id,
            name = metadata?.name?.takeIf { it.isNotBlank() } ?: entry.name.ifBlank { "Community" },
            description = metadata?.description,
            owner = entry.owner,
            relays = metadata?.relays?.takeIf { it.isNotEmpty() } ?: entry.relays,
            channels = channels,
            canManageMetadata = canManage && keys.canWrite,
            messageExpirationSecs = metadata?.messageExpirationSecs(),
        )
    }

    suspend fun updateMetadata(
        communityId: String,
        signer: PostillaSigner,
        name: String,
        description: String?,
        relays: List<String>,
        messageExpirationSecs: Long?,
        iconBytes: ByteArray? = null,
        iconMimeType: String = "image/jpeg",
        bannerBytes: ByteArray? = null,
        bannerMimeType: String = "image/jpeg",
    ): ConcordCommunitySummary {
        val snapshot = synchronized(snapshots) { snapshots[communityId] }
            ?: throw IllegalStateException("Community state is not loaded. Refresh and try again.")
        if (!snapshot.keys.canWrite) {
            throw IllegalStateException("This account has no Concord Control Plane write key.")
        }
        val authority = snapshot.state.authority
        val isOwner = authority.isOwner(signer.pubkeyHex)
        val allowed = isOwner || authority.hasPermission(signer.pubkeyHex, ConcordPermissions.MANAGE_METADATA)
        if (!allowed) throw SecurityException("This account is not allowed to edit this Community's metadata.")

        val previous = snapshot.editions
            .filter { it.entityKind == ControlEntityKind.METADATA && it.entityIdHex.equals(communityId, ignoreCase = true) }
            .maxByOrNull { it.version }
        val nextVersion = (previous?.version ?: 0L) + 1L
        val currentMetadata = snapshot.state.metadata ?: MetadataEntity(name = snapshot.entry.name)
        val nextIcon: ImagePointer? = iconBytes?.let {
            blossomRepository.uploadConcordImage(it, iconMimeType, signer)
        } ?: currentMetadata.icon
        val nextBanner: ImagePointer? = bannerBytes?.let {
            blossomRepository.uploadConcordImage(it, bannerMimeType, signer)
        } ?: currentMetadata.banner
        val nextMetadata = currentMetadata.copy(
            name = name.trim(),
            description = description?.trim()?.takeIf { it.isNotEmpty() },
            relays = relays.map { it.trim() }.filter { it.startsWith("wss://") || it.startsWith("ws://") }.distinct().take(5),
            icon = nextIcon,
            banner = nextBanner,
        ).withMessageExpiration(messageExpirationSecs)

        val content = ConcordJson.encodePreserving(
            serializer = MetadataEntity.serializer(),
            value = nextMetadata,
            previousContent = previous?.content,
        )
        val citation: AuthorityCitation? = if (isOwner) null else authority.citationFor(signer.pubkeyHex)
            ?: throw SecurityException("The current staff authorization is not available yet.")
        val rumor = ControlEditionBuilder.rumor(
            authorPubKey = signer.pubkeyHex,
            entityKind = ControlEntityKind.METADATA,
            entityId = communityId.hexToByteArray(),
            version = nextVersion,
            prevHash = previous?.hash,
            content = content,
            createdAt = System.currentTimeMillis() / 1000,
            authorityCitation = citation,
        )
        val wrap = ConcordStreamEnvelope.wrap(
            rumor = rumor,
            keys = snapshot.keys,
            authorSigner = PostillaQuartzSigner(signer),
            encrypted = false,
        )
        publishToCommunityRelays(snapshot.entry.relays, wrap)
        return loadCommunity(
            ConcordCommunityListEntry(
                id = snapshot.entry.id,
                owner = snapshot.entry.owner,
                ownerSalt = snapshot.entry.ownerSalt,
                root = snapshot.entry.root,
                rootEpoch = snapshot.entry.rootEpoch,
                controlPk = snapshot.entry.controlPk,
                controlRoot = snapshot.entry.controlRoot,
                heldRoots = snapshot.entry.heldRoots,
                privateChannels = snapshot.entry.privateChannels,
                relays = nextMetadata.relays,
                name = name.trim(),
                addedAt = snapshot.entry.addedAt,
                inviteRef = snapshot.entry.inviteRef,
                excludedAtEpoch = snapshot.entry.excludedAtEpoch,
                residue = snapshot.entry.residue,
            ),
            signer,
        )
    }

    suspend fun createCommunity(
        signer: PostillaSigner,
        name: String,
        description: String,
        relays: List<String>,
    ): ConcordCreatedCommunity {
        val selectedRelays = relays.map { it.trim() }.filter { it.startsWith("wss://") || it.startsWith("ws://") }.distinct().take(5)
        if (selectedRelays.isEmpty()) throw IllegalArgumentException("Choose at least one Community relay.")
        val quartzSigner = PostillaQuartzSigner(signer)
        val now = System.currentTimeMillis() / 1000
        val created = ConcordCommunityFactory.create(
            ownerSigner = quartzSigner,
            name = name.trim(),
            createdAt = now,
            description = description.trim().takeIf { it.isNotEmpty() },
            relays = selectedRelays,
        )
        val invite = CommunityInvite(
            communityId = created.communityIdHex,
            owner = created.ownerPubKey,
            ownerSalt = created.ownerSalt.toHexKey(),
            communityRoot = created.communityRoot.toHexKey(),
            rootEpoch = created.rootEpoch,
            controlPk = created.controlPkHex,
            channels = listOf(
                InviteChannel(
                    id = created.generalChannelIdHex,
                    key = "",
                    epoch = created.rootEpoch,
                    name = ConcordCommunityFactory.GENERAL_CHANNEL_NAME,
                ),
            ),
            relays = selectedRelays,
            name = name.trim(),
        )
        val minted = ConcordInviteBundle.mintLink(
            base = "https://v3.coinos.io",
            invite = invite,
            createdAt = now,
            relays = selectedRelays.take(3),
        )
        publishToCommunityRelays(selectedRelays, created.genesisWraps)
        val parsedMinted = ConcordInviteLink.parseUrl(minted.url)
            ?: throw IllegalStateException("The new Concord invitation could not be encoded.")
        publishToCommunityRelays(parsedMinted.fragment.relays, minted.bundleEvent)

        val entry = ConcordCommunityListEntry(
            id = created.communityIdHex,
            owner = created.ownerPubKey,
            ownerSalt = created.ownerSalt.toHexKey(),
            root = created.communityRoot.toHexKey(),
            rootEpoch = created.rootEpoch,
            controlPk = created.controlPkHex,
            controlRoot = created.controlRoot.toHexKey(),
            heldRoots = listOf(
                HeldRoot(
                    epoch = created.rootEpoch,
                    key = created.communityRoot.toHexKey(),
                    controlPk = created.controlPkHex,
                    controlRoot = created.controlRoot.toHexKey(),
                ),
            ),
            relays = selectedRelays,
            name = name.trim(),
            addedAt = now,
            inviteRef = minted.url.substringBefore('#'),
        )
        val current = loadUserCommunityList(signer)
        publishUserCommunityList(signer, current.entries.filterNot { it.id == entry.id } + entry, current.residue)

        val guestbook = ConcordKeyDerivation.guestbookPlaneKey(created.communityRoot, created.communityId, created.rootEpoch)
        val joinRumor = Guestbook.join(signer.pubkeyHex, now)
        val joinWrap = ConcordStreamEnvelope.wrap(joinRumor, guestbook, quartzSigner, encrypted = true)
        publishToCommunityRelays(selectedRelays, joinWrap)

        val summary = loadCommunity(entry, signer)
        return ConcordCreatedCommunity(summary, minted.url)
    }

    suspend fun loadChannelMessages(
        communityId: String,
        channel: ConcordChannelSummary,
    ): List<ConcordChatMessage> {
        val snapshot = synchronized(snapshots) { snapshots[communityId] }
            ?: throw IllegalStateException("Community state is not loaded.")
        if (!channel.canRead) throw SecurityException("You do not have a key for this private channel.")
        val channelBytes = channel.id.hexToByteArray()
        val held = ConcordChannelKeyring.heldKey(snapshot.entry, channel.id)
        val key = if (channel.isPrivate) {
            val privateKey = held ?: throw SecurityException("The private-channel key is missing.")
            ConcordChannelKeys.privateChannel(
                privateKey.key.hexToByteArray(),
                channelBytes,
                privateKey.epoch,
            )
        } else {
            ConcordChannelKeys.publicChannel(
                snapshot.entry.root.hexToByteArray(),
                channelBytes,
                snapshot.entry.rootEpoch,
            )
        }
        val wraps = queryAcrossRelays(
            snapshot.entry.relays,
            Filter(kinds = listOf(CONTROL_WRAP_KIND), authors = listOf(key.publicKeyHex), limit = 1000),
        )
        return wraps.mapNotNull { nostr ->
            val opened = runCatching {
                ConcordStreamEnvelope.openOrNull(QuartzEvent.fromJson(nostr.toJson()), key)
            }.getOrNull() ?: return@mapNotNull null
            val rumor = ChannelChat.acceptOpened(opened, channel.id, channel.epoch)
                ?: return@mapNotNull null
            ConcordChatMessage(
                id = rumor.id,
                author = rumor.pubKey,
                content = rumor.content,
                createdAt = rumor.createdAt,
            )
        }.distinctBy { it.id }.sortedBy { it.createdAt }
    }

    suspend fun sendChannelMessage(
        communityId: String,
        channel: ConcordChannelSummary,
        text: String,
        signer: PostillaSigner,
    ) {
        val body = text.trim()
        if (body.isEmpty()) return
        val snapshot = synchronized(snapshots) { snapshots[communityId] }
            ?: throw IllegalStateException("Community state is not loaded.")
        if (!channel.canRead) throw SecurityException("You do not have a key for this private channel.")
        val channelBytes = channel.id.hexToByteArray()
        val held = ConcordChannelKeyring.heldKey(snapshot.entry, channel.id)
        val key = if (channel.isPrivate) {
            val privateKey = held ?: throw SecurityException("The private-channel key is missing.")
            ConcordChannelKeys.privateChannel(privateKey.key.hexToByteArray(), channelBytes, privateKey.epoch)
        } else {
            ConcordChannelKeys.publicChannel(snapshot.entry.root.hexToByteArray(), channelBytes, snapshot.entry.rootEpoch)
        }
        val now = System.currentTimeMillis() / 1000
        val rumor = ChannelChat.message(signer.pubkeyHex, channel.id, channel.epoch, body, now)
        val wrap = ConcordStreamEnvelope.wrap(
            rumor = rumor,
            stream = key,
            authorSigner = PostillaQuartzSigner(signer),
            encrypted = true,
        )
        publishToCommunityRelays(snapshot.entry.relays, wrap)
    }

    private suspend fun publishToCommunityRelays(relays: List<String>, events: List<QuartzEvent>) {
        for (event in events) publishToCommunityRelays(relays, event)
    }

    private suspend fun publishToCommunityRelays(relays: List<String>, event: QuartzEvent) {
        val message = ClientMessage.event(NostrEvent.fromJson(event.toJson()))
        val targets = relays.distinct().take(5)
        var sent = 0
        for (url in targets) {
            if (relayPool.sendToRelayOrEphemeral(
                url,
                message,
                skipBadCheck = true,
                write = true,
                autoReconnect = true,
            )) sent++
        }
        if (sent == 0) throw IllegalStateException("No Community relay accepted the event for publishing.")
    }

    private suspend fun query(relays: List<String>, filter: Filter): List<NostrEvent> = coroutineScope {
        val targets = relays.filter { it.startsWith("wss://") || it.startsWith("ws://") }.distinct().take(5)
        if (targets.isEmpty()) return@coroutineScope emptyList()
        val subscriptionId = "postilla-concord-" + UUID.randomUUID()
        val received = LinkedHashMap<String, NostrEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            relayPool.relayEvents
                .filter { it.subscriptionId == subscriptionId }
                .collect { relayEvent ->
                    received.putIfAbsent(relayEvent.event.id, relayEvent.event)
                }
        }
        val request = ClientMessage.req(subscriptionId, filter)
        // A NIP-42 challenge may close the first REQ before the stream-key AUTH is
        // completed. Re-issue the same subscription once the derived AUTH is sent.
        val authRetry = launch(start = CoroutineStart.UNDISPATCHED) {
            relayPool.authCompleted
                .filter { it in targets }
                .collect { url ->
                    relayPool.sendToRelayOrEphemeral(url, request, skipBadCheck = true)
                }
        }
        val sentTargets = targets.filter { url ->
            relayPool.sendToRelayOrEphemeral(url, request, skipBadCheck = true)
        }
        if (sentTargets.isNotEmpty()) {
            withTimeoutOrNull(18_000) {
                subscriptions.awaitEoseCount(subscriptionId, sentTargets.size, timeoutMs = 18_000)
            }
        }
        authRetry.cancelAndJoin()
        val close = ClientMessage.close(subscriptionId)
        targets.forEach { relayPool.sendToRelayOrEphemeral(it, close, skipBadCheck = true) }
        collector.cancelAndJoin()
        received.values.toList()
    }

    private suspend fun queryAcrossRelays(
        relays: List<String>,
        filter: Filter,
    ): List<NostrEvent> = coroutineScope {
        val batches = relays
            .filter { it.startsWith("wss://") || it.startsWith("ws://") }
            .distinct()
            .chunked(5)
        if (batches.isEmpty()) return@coroutineScope emptyList()
        batches.map { batch -> async { query(batch, filter) } }
            .awaitAll()
            .flatten()
            .distinctBy { it.id }
    }
}

/**
 * Adapter between Postilla's LocalSigner/NIP-55 RemoteSigner and Quartz's Concord signer API.
 * Concord's regular community flow uses only event signing and NIP-44; unsupported
 * Quartz-only methods fail explicitly instead of silently using a different key.
 */
private class PostillaQuartzSigner(
    private val delegate: PostillaSigner,
) : QuartzSigner(delegate.pubkeyHex) {
    override fun isWriteable(): Boolean = true

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : QuartzEvent> sign(
        createdAt: Long,
        kind: Int,
        tags: Array<Array<String>>,
        content: String,
    ): T {
        val signed = delegate.signEvent(
            kind = kind,
            content = content,
            tags = tags.map { it.toList() },
            createdAt = createdAt,
        )
        return QuartzEvent.fromJson(signed.toJson()) as T
    }

    override suspend fun nip04Encrypt(plaintext: String, toPublicKey: String): String =
        throw UnsupportedOperationException("NIP-04 is not used by Concord.")

    override suspend fun nip04Decrypt(ciphertext: String, fromPublicKey: String): String =
        throw UnsupportedOperationException("NIP-04 is not used by Concord.")

    override suspend fun nip44Encrypt(plaintext: String, toPublicKey: String): String =
        delegate.nip44Encrypt(plaintext, toPublicKey)

    override suspend fun nip44Decrypt(ciphertext: String, fromPublicKey: String): String =
        delegate.nip44Decrypt(ciphertext, fromPublicKey)

    override suspend fun decryptZapEvent(event: ZapRequestEvent): PrivateZapEvent =
        throw UnsupportedOperationException("Private-zap decryption is not used by Concord.")

    override suspend fun deriveKey(nonce: String): String =
        throw UnsupportedOperationException("Generic signer key derivation is not used by Concord.")

    override suspend fun signPsbt(psbtHex: String): String =
        throw UnsupportedOperationException("PSBT signing is not used by Concord.")

    override fun hasForegroundSupport(): Boolean = true
}
