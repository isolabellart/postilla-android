package com.darkwisp.app.concord

import android.content.Context
import com.darkwisp.app.nostr.ClientMessage
import com.darkwisp.app.nostr.Filter
import com.darkwisp.app.nostr.NostrEvent
import com.darkwisp.app.relay.RelayPool
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityFactory
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityList
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityListEntry
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityListFragmentEvent
import com.vitorpamplona.quartz.concord.cord02Community.ConcordListFragmentSet
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityListDocument
import com.vitorpamplona.quartz.concord.cord02Community.PrivateChannelKey
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityState
import com.vitorpamplona.quartz.concord.cord02Community.Guestbook
import com.vitorpamplona.quartz.concord.cord04Roles.ControlEdition
import com.vitorpamplona.quartz.concord.cord04Roles.ConcordPermissions
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteLink
import com.vitorpamplona.quartz.concord.cord05Invites.InviteBundleStatus
import com.vitorpamplona.quartz.concord.crypto.ControlPlaneKeys
import com.vitorpamplona.quartz.concord.crypto.ConcordKeyDerivation
import com.vitorpamplona.quartz.concord.envelope.ConcordStreamEnvelope
import com.vitorpamplona.quartz.concord.cord05Invites.CommunityInvite
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteBundle
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteList
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteListDocument
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteListEntry as InviteListEntry
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteListEvent
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteRegistry
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * First Concord vertical slice: create a CORD-02 community, publish its two genesis
 * Control Plane wraps, and publish the owner's self-encrypted fragmented membership list.
 *
 * The local journal stores only signed event JSON. Community secrets are carried only
 * inside Quartz's NIP-44 self-encrypted kind-33302 list fragments.
 */
class ConcordCommunityRepository(
    context: Context,
    private val relayPool: RelayPool,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class CreationResult(
        val communityId: String,
        val generalChannelId: String,
        val name: String,
        val publishedCount: Int,
        val totalCount: Int,
        val pendingEventIds: List<String>,
    ) {
        val isFullyPublished: Boolean get() = pendingEventIds.isEmpty() && publishedCount == totalCount
    }

    /**
     * Creates a community using Quartz's reference factory and wire encoders.
     * Each event is journaled before publication; an unconfirmed event remains available
     * for a future retry instead of being silently discarded.
     */
    suspend fun createCommunity(
        signer: PostillaQuartzSigner,
        name: String,
        description: String? = null,
        relays: List<String> = relayPool.getWriteRelayUrls(),
        createdAt: Long = System.currentTimeMillis() / 1000,
    ): CreationResult {
        require(name.isNotBlank()) { "Community name cannot be blank" }
        require(relays.isNotEmpty()) { "At least one community relay is required" }

        // Read/merge the existing list before publishing anything. A new community must
        // never replace the user's other memberships or their anti-resurrection tombstones.
        val existingList = fetchCommunityList(signer)
        val community = ConcordCommunityFactory.create(
            ownerSigner = signer,
            name = name.trim(),
            createdAt = createdAt,
            description = description?.trim()?.takeIf { it.isNotEmpty() },
            relays = relays.distinct(),
        )

        val entry = ConcordCommunityListEntry(
            id = community.communityIdHex,
            owner = community.ownerPubKey,
            ownerSalt = community.ownerSalt.toHexKey(),
            root = community.communityRoot.toHexKey(),
            rootEpoch = community.rootEpoch,
            controlPk = community.controlPkHex,
            controlRoot = community.controlRoot.toHexKey(),
            relays = relays.distinct(),
            name = name.trim(),
            addedAt = System.currentTimeMillis(),
        )
        val mergedEntries = ConcordCommunityList.merge(existingList.document.entries, listOf(entry))
        val nextDocument = ConcordCommunityList.encodeInternal(mergedEntries, existingList.document.residue)
        val listWrites = existingList.fragments.planWrites(nextDocument, createdAt)
        val listEvents = listWrites.map { write ->
            ConcordCommunityListFragmentEvent.create(
                signer = signer,
                index = write.index,
                plaintext = write.plaintext,
                createdAt = write.createdAt,
            )
        }

        val guestbookKey = ConcordKeyDerivation.guestbookPlaneKey(
            communityRoot = community.communityRoot,
            communityId = community.communityIdHex.hexToByteArray(),
            epoch = community.rootEpoch,
        )
        val ownerJoin = Guestbook.join(signer.pubKey, createdAt)
        val guestbookJoinWrap = ConcordStreamEnvelope.wrap(
            rumor = ownerJoin,
            stream = guestbookKey,
            authorSigner = signer,
            encrypted = true,
            createdAt = createdAt,
        )
        val genesisEvents = (community.genesisWraps + guestbookJoinWrap).map { NostrEvent.fromJson(it.toJson()) }
        val allEvents = genesisEvents + listEvents.map { NostrEvent.fromJson(it.toJson()) }
        withContext(Dispatchers.IO) {
            journalEvents(community.communityIdHex, allEvents)
            prefs.edit().putString(generalChannelKey(community.communityIdHex), community.generalChannelIdHex).commit()
        }

        var published = 0
        val pending = mutableListOf<String>()
        for (event in genesisEvents) {
            if (publishConfirmed(event, relays)) published++ else pending += event.id
        }
        for (event in allEvents.drop(genesisEvents.size)) {
            if (publishConfirmed(event, emptyList())) published++ else pending += event.id
        }

        return CreationResult(
            communityId = community.communityIdHex,
            generalChannelId = community.generalChannelIdHex,
            name = name.trim(),
            publishedCount = published,
            totalCount = allEvents.size,
            pendingEventIds = pending,
        )
    }

    private data class CommunityListSnapshot(
        val fragments: ConcordListFragmentSet,
        val document: ConcordCommunityListDocument,
    )

    /**
     * Reads every configured read relay to a per-relay EOSE, then merges remote and
     * locally journaled fragments. A failed decrypt stays unreadable in the fragment
     * set, so Quartz can scope a safe write instead of repacking missing memberships.
     */
    private suspend fun fetchCommunityList(signer: PostillaQuartzSigner): CommunityListSnapshot {
        val readRelays = relayPool.getReadRelayUrls().distinct()
        require(readRelays.isNotEmpty()) { "No read relays configured; refusing to replace the Community List" }

        val subId = "concord-community-list-${signer.pubKey.take(12)}-${System.nanoTime()}"
        val events = java.util.concurrent.ConcurrentHashMap<String, NostrEvent>()
        val responded = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val allEose = CompletableDeferred<Unit>()

        coroutineScope {
            val eventCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                relayPool.relayEvents
                    .filter {
                        it.subscriptionId == subId &&
                            it.event.kind in setOf(ConcordCommunityListFragmentEvent.KIND, 13302)
                    }
                    .collect { relayEvent ->
                        if (relayEvent.event.pubkey == signer.pubKey) {
                            events.putIfAbsent(relayEvent.event.id, relayEvent.event)
                        }
                    }
            }
            val eoseCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                relayPool.relayEoseSignals
                    .filter { it.subscriptionId == subId && it.relayUrl in readRelays }
                    .collect { signal ->
                        responded.add(signal.relayUrl)
                        if (responded.containsAll(readRelays)) allEose.complete(Unit)
                    }
            }
            relayPool.sendToReadRelays(
                ClientMessage.req(
                    subId,
                    listOf(
                        Filter(
                            kinds = listOf(ConcordCommunityListFragmentEvent.KIND, 13302),
                            authors = listOf(signer.pubKey),
                            limit = 5000,
                        ),
                    ),
                ),
            )
            val complete = withTimeoutOrNull(8_000L) {
                allEose.await()
                true
            } ?: false
            delay(150)
            eventCollector.cancelAndJoin()
            eoseCollector.cancelAndJoin()
            relayPool.closeOnAllRelays(subId)
            if (!complete) {
                throw IllegalStateException("Could not read the Community List from every configured read relay; refusing a destructive update")
            }
        }

        // Include signed local events that may not have reached a relay yet.
        prefs.all.keys.filter { it.startsWith("events_") }.forEach { key ->
            prefs.getStringSet(key, emptySet()).orEmpty().forEach { encoded ->
                runCatching { NostrEvent.fromJson(encoded) }.getOrNull()?.let { event ->
                    if (event.pubkey == signer.pubKey &&
                        event.kind in setOf(ConcordCommunityListFragmentEvent.KIND, 13302)
                    ) events.putIfAbsent(event.id, event)
                }
            }
        }

        val copies = events.values
            .filter { it.kind == ConcordCommunityListFragmentEvent.KIND }
            .mapNotNull { event ->
                val d = event.tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1) ?: return@mapNotNull null
                val index = ConcordCommunityListFragmentEvent.parseIndex(d) ?: return@mapNotNull null
                val plaintext = try {
                    signer.nip44Decrypt(event.content, signer.pubKey)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                ConcordListFragmentSet.Copy(index, event.created_at, event.id, plaintext)
            }
        val fragmentSet = ConcordListFragmentSet.of(copies)

        val legacyEvent = events.values
            .filter { it.kind == 13302 }
            .maxByOrNull { it.created_at }
        val legacyPlaintext = legacyEvent?.let { event ->
            try {
                signer.nip44Decrypt(event.content, signer.pubKey)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw IllegalStateException("The legacy Community List could not be decrypted; refusing to overwrite it", error)
            }
        }
        val mergedDocument = ConcordCommunityList.readWithLegacy(fragmentSet, legacyPlaintext)
        return CommunityListSnapshot(fragmentSet, ConcordCommunityList.decodeDocument(mergedDocument))
    }


    /** Loads the relay-merged membership list, falling back to the local encrypted journal for display. */
    suspend fun loadLocalMemberships(signer: PostillaQuartzSigner): List<ConcordCommunityListEntry> = try {
        fetchCommunityList(signer).document.entries
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        loadJournaledMemberships(signer)
    }

    private suspend fun loadJournaledMemberships(signer: PostillaQuartzSigner): List<ConcordCommunityListEntry> {
        val encodedEvents = prefs.all.keys
            .filter { it.startsWith("events_") }
            .flatMap { key -> prefs.getStringSet(key, emptySet()).orEmpty() }
        val events = encodedEvents.mapNotNull { encoded ->
            runCatching { NostrEvent.fromJson(encoded) }.getOrNull()
        }.filter { it.pubkey == signer.pubKey }

        val copies = events
            .filter { it.kind == ConcordCommunityListFragmentEvent.KIND }
            .mapNotNull { event ->
                val d = event.tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1) ?: return@mapNotNull null
                val index = ConcordCommunityListFragmentEvent.parseIndex(d) ?: return@mapNotNull null
                val plaintext = try {
                    signer.nip44Decrypt(event.content, signer.pubKey)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                ConcordListFragmentSet.Copy(index, event.created_at, event.id, plaintext)
            }
        val fragments = ConcordListFragmentSet.of(copies)

        val legacyPlaintext = events
            .filter { it.kind == 13302 }
            .maxByOrNull { it.created_at }
            ?.let { event ->
                try {
                    signer.nip44Decrypt(event.content, signer.pubKey)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
        val merged = ConcordCommunityList.readWithLegacy(fragments, legacyPlaintext)
        return ConcordCommunityList.decodeDocument(merged).entries
    }

    fun getLocalGeneralChannelId(communityId: String): String? =
        prefs.getString(generalChannelKey(communityId), null)


    data class JoinResult(
        val communityId: String,
        val name: String,
        val generalChannelId: String,
        val publishedCount: Int,
        val totalCount: Int,
        val pendingEventIds: List<String>,
    ) {
        val isFullyPublished: Boolean get() = pendingEventIds.isEmpty() && publishedCount == totalCount
    }

    /**
     * Redeems a CORD-05 invite exactly as Quartz/Vector does: resolve the addressable
     * bundle, validate its token and self-certifying identity, fold the read-only
     * Control Plane, then merge a new member entry into the fragmented private list.
     */
    suspend fun joinCommunityFromInvite(
        signer: PostillaQuartzSigner,
        inviteUrl: String,
    ): JoinResult {
        val parsed = ConcordInviteLink.parseUrl(inviteUrl.trim())
            ?: throw IllegalArgumentException("This is not a valid Concord invite link")

        val bundleEvents = fetchEventsFromRelays(
            relays = parsed.fragment.relays,
            filter = Filter(
                kinds = listOf(ConcordInviteBundle.KIND),
                authors = listOf(parsed.linkSignerPubKey),
                limit = 20,
            ),
            label = "invite",
        )
        val bundleStatus = ConcordInviteBundle.classify(
            wraps = bundleEvents.map { Event.fromJson(it.toJson()) },
            linkSignerPubKey = parsed.linkSignerPubKey,
            token = parsed.fragment.token,
        )
        val invite = (bundleStatus as? InviteBundleStatus.Live)?.invite
            ?: throw IllegalStateException(
                when (bundleStatus) {
                    InviteBundleStatus.Revoked -> "This invite has been revoked"
                    is InviteBundleStatus.Expired -> "This invite has expired"
                    InviteBundleStatus.Absent -> "Invite bundle not found on its bootstrap relays"
                    InviteBundleStatus.Unreadable -> "Invite bundle could not be opened with this link"
                    is InviteBundleStatus.Live -> "Invite is valid"
                },
            )

        val communityIdBytes = invite.communityId.hexToByteArray()
        val communityRootBytes = invite.communityRoot.hexToByteArray()
        val controlKeys = ControlPlaneKeys.of(
            communityRoot = communityRootBytes,
            communityId = communityIdBytes,
            epoch = invite.rootEpoch,
            controlPk = invite.controlPk,
        )
        val communityRelays = invite.relays.distinct().take(5).ifEmpty { parsed.fragment.relays }
        val controlEvents = fetchEventsFromRelays(
            relays = communityRelays,
            filter = Filter(
                kinds = listOf(ConcordStreamEnvelope.KIND_WRAP),
                authors = listOf(controlKeys.address),
                limit = 5000,
            ),
            label = "control",
            requireAllResponders = true,
        )
        val editions = controlEvents.map { nostrEvent ->
            try {
                val opened = ConcordStreamEnvelope.open(
                    Event.fromJson(nostrEvent.toJson()),
                    controlKeys.address,
                    controlKeys.readKey.conversationKey,
                )
                ControlEdition.fromOpened(opened)
            } catch (error: Exception) {
                throw IllegalStateException("A Control Plane edition failed cryptographic validation", error)
            }
        }
        if (editions.isEmpty()) {
            throw IllegalStateException("The community Control Plane could not be read or verified")
        }
        val state = ConcordCommunityState.fold(editions, communityIdBytes, invite.owner)
        if (state.dissolved) throw IllegalStateException("This community has been dissolved")
        val metadata = state.metadata ?: throw IllegalStateException("The community has no valid metadata edition")
        val generalChannel = state.channels.values.firstOrNull {
            it.definition.name.equals("general", ignoreCase = true)
        } ?: throw IllegalStateException("The community has no live #general channel")

        val savedRelays = metadata.relays.distinct().take(5).ifEmpty { communityRelays }
        val newEntry = ConcordCommunityListEntry(
            id = invite.communityId,
            owner = invite.owner,
            ownerSalt = invite.ownerSalt,
            root = invite.communityRoot,
            rootEpoch = invite.rootEpoch,
            controlPk = invite.controlPk,
            privateChannels = invite.channels
                .filter { it.key.isNotBlank() }
                .map { PrivateChannelKey(it.id, it.key, it.epoch, it.name) },
            relays = savedRelays,
            name = metadata.name.ifBlank { invite.name },
            addedAt = System.currentTimeMillis(),
            inviteRef = ConcordInviteLink.bareForm(inviteUrl),
        )

        val currentList = fetchCommunityList(signer)
        val mergedEntries = ConcordCommunityList.merge(currentList.document.entries, listOf(newEntry))
        val winner = mergedEntries.firstOrNull { it.id == invite.communityId }
            ?: throw IllegalStateException("The membership could not be merged")
        if (winner.rootEpoch > invite.rootEpoch) {
            throw IllegalStateException("A newer membership epoch already exists; refresh the community list before joining this older invite")
        }
        if (winner.rootEpoch == invite.rootEpoch &&
            (winner.root != invite.communityRoot || winner.controlPk != invite.controlPk)
        ) {
            throw IllegalStateException("This invite conflicts with the keys already stored for the same community epoch")
        }
        val nextDocument = ConcordCommunityList.encodeInternal(mergedEntries, currentList.document.residue)
        val writes = currentList.fragments.planWrites(nextDocument, System.currentTimeMillis() / 1000)
        val listEvents = writes.map { write ->
            ConcordCommunityListFragmentEvent.create(
                signer = signer,
                index = write.index,
                plaintext = write.plaintext,
                createdAt = write.createdAt,
            )
        }.map { NostrEvent.fromJson(it.toJson()) }
        val guestbookKey = ConcordKeyDerivation.guestbookPlaneKey(
            communityRoot = invite.communityRoot.hexToByteArray(),
            communityId = communityIdBytes,
            epoch = invite.rootEpoch,
        )
        val memberJoin = Guestbook.join(
            memberPubKey = signer.pubKey,
            createdAt = System.currentTimeMillis() / 1000,
        )
        val guestbookJoinEvent = NostrEvent.fromJson(
            ConcordStreamEnvelope.wrap(
                rumor = memberJoin,
                stream = guestbookKey,
                authorSigner = signer,
                encrypted = true,
            ).toJson(),
        )

        withContext(Dispatchers.IO) {
            journalEvents(invite.communityId, listEvents + guestbookJoinEvent)
            if (winner.rootEpoch == invite.rootEpoch) {
                prefs.edit().putString(generalChannelKey(invite.communityId), generalChannel.channelIdHex).commit()
            }
        }

        var published = 0
        val pending = mutableListOf<String>()
        for (event in listEvents) {
            if (publishConfirmed(event, emptyList())) published++ else pending += event.id
        }
        if (publishConfirmed(guestbookJoinEvent, savedRelays)) published++ else pending += guestbookJoinEvent.id
        val channelId = getLocalGeneralChannelId(invite.communityId) ?: generalChannel.channelIdHex
        return JoinResult(
            communityId = invite.communityId,
            name = winner.name,
            generalChannelId = channelId,
            publishedCount = published,
            totalCount = listEvents.size + 1,
            pendingEventIds = pending,
        )
    }

    private suspend fun fetchEventsFromRelays(
        relays: List<String>,
        filter: Filter,
        label: String,
        requireAllResponders: Boolean = false,
    ): List<NostrEvent> {
        val targets = relays.distinct()
        require(targets.isNotEmpty()) { "No bootstrap relays were supplied for $label discovery" }
        val subscriptionId = "concord-$label-${UUID.randomUUID().toString().take(12)}"
        val request = ClientMessage.req(subscriptionId, listOf(filter))
        val events = java.util.concurrent.ConcurrentHashMap<String, NostrEvent>()
        val responded = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        coroutineScope {
            val eventCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                relayPool.relayEvents
                    .filter { it.subscriptionId == subscriptionId }
                    .collect { relayEvent -> events.putIfAbsent(relayEvent.event.id, relayEvent.event) }
            }
            val eoseCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                relayPool.relayEoseSignals
                    .filter { it.subscriptionId == subscriptionId && it.relayUrl in targets }
                    .collect { responded.add(it.relayUrl) }
            }
            val sent = targets.filter {
                relayPool.sendToRelayOrEphemeral(it, request, skipBadCheck = true, write = false)
            }
            if (sent.isEmpty()) {
                eventCollector.cancelAndJoin()
                eoseCollector.cancelAndJoin()
                throw IllegalStateException("Could not connect to any bootstrap relay for $label discovery")
            }
            withTimeoutOrNull(8_000L) {
                while (!responded.containsAll(sent)) delay(50)
            }
            delay(150)
            eventCollector.cancelAndJoin()
            eoseCollector.cancelAndJoin()
            relayPool.closeOnAllRelays(subscriptionId)
            if (responded.isEmpty()) {
                throw IllegalStateException("No bootstrap relay completed $label discovery")
            }
            if (requireAllResponders && !responded.containsAll(sent)) {
                throw IllegalStateException("The $label data is incomplete: not every community relay completed the read")
            }
        }
        return events.values.toList()
    }

    /**
     * Mints and publishes a Vector-compatible public invite link.
     *
     * The creator's encrypted kind-13303 Invite List is merged and confirmed first;
     * only then is the public kind-33301 bundle published. This preserves the
     * per-link token and signer secret needed to refresh or revoke the link later.
     */
    suspend fun createInviteLink(
        signer: PostillaQuartzSigner,
        communityId: String,
        baseUrl: String = "https://vector.chat",
        expiresAt: Long? = null,
    ): String {
        val membership = loadLocalMemberships(signer).firstOrNull { it.id == communityId }
            ?: throw IllegalArgumentException("Community membership is not available on this device")
        val relays = membership.relays.distinct().take(5)
        require(relays.isNotEmpty()) { "Community has no relay configured for invite discovery" }

        val createdAt = System.currentTimeMillis() / 1000
        val invite = CommunityInvite(
            communityId = membership.id,
            owner = membership.owner,
            ownerSalt = membership.ownerSalt,
            communityRoot = membership.root,
            rootEpoch = membership.rootEpoch,
            controlPk = membership.controlPk,
            channels = emptyList(),
            relays = relays,
            name = membership.name,
            expiresAt = expiresAt,
        )
        val minted = ConcordInviteBundle.mintLink(
            base = baseUrl,
            invite = invite,
            createdAt = createdAt,
            relays = relays,
        )

        // Never replace kind 13303 from a local-only cache: merge all currently readable
        // relay copies so a second device's links and tombstones are not silently lost.
        val existing = fetchInviteList(signer)
        val addition = ConcordInviteListDocument(
            entries = listOf(
                InviteListEntry(
                    token = minted.token.toHexKey(),
                    signerSk = minted.linkSignerPrivKey.toHexKey(),
                    communityId = communityId,
                    url = minted.url,
                    createdAt = createdAt,
                    expiresAt = expiresAt,
                ),
            ),
        )
        val merged = ConcordInviteList.merge(existing, addition)
        val listEvent = NostrEvent.fromJson(ConcordInviteListEvent.create(signer, merged, createdAt).toJson())
        withContext(Dispatchers.IO) {
            journalEvents("invites_${signer.pubKey}", listOf(listEvent))
        }
        if (!publishConfirmed(listEvent, emptyList())) {
            throw IllegalStateException("Invite List was not confirmed by a write relay; the link was not published")
        }

        val bundleEvent = NostrEvent.fromJson(minted.bundleEvent.toJson())
        withContext(Dispatchers.IO) {
            journalEvents(communityId, listOf(bundleEvent))
        }
        if (!publishConfirmed(bundleEvent, relays)) {
            throw IllegalStateException("Invite bundle was not confirmed by a community relay; do not share the link yet")
        }

        val controlRootHex = membership.controlRoot
            ?: throw IllegalStateException("This account does not hold the current Control Plane write key")
        val controlKeys = ControlPlaneKeys.of(
            communityRoot = membership.root.hexToByteArray(),
            communityId = membership.id.hexToByteArray(),
            epoch = membership.rootEpoch,
            controlPk = membership.controlPk,
            controlRoot = controlRootHex,
        )
        val controlWraps = fetchEventsFromRelays(
            relays = relays,
            filter = Filter(
                kinds = listOf(ConcordStreamEnvelope.KIND_WRAP),
                authors = listOf(controlKeys.address),
                limit = 5000,
            ),
            label = "invite-registry-control",
            requireAllResponders = true,
        )
        val editions = controlWraps.map { event ->
            try {
                ControlEdition.fromOpened(
                    ConcordStreamEnvelope.open(Event.fromJson(event.toJson()), controlKeys),
                )
            } catch (error: Exception) {
                throw IllegalStateException("A Control Plane edition failed cryptographic validation", error)
            }
        }
        if (editions.isEmpty()) throw IllegalStateException("The current Control Plane could not be folded for invite registration")
        val state = ConcordCommunityState.fold(editions, membership.id.hexToByteArray(), membership.owner)
        if (state.dissolved) throw IllegalStateException("This community has been dissolved")
        if (!state.authority.hasPermission(signer.pubKey, ConcordPermissions.CREATE_INVITE)) {
            throw IllegalStateException("This account is not authorized to create community invites")
        }
        val registryCoordinate = ConcordInviteRegistry.coordinateHex(membership.id.hexToByteArray(), signer.pubKey)
        val heads = ConcordCommunityState.authorizedHeads(editions, membership.id.hexToByteArray(), membership.owner)
        val head = heads[registryCoordinate]
        val liveLinks = ConcordInviteRegistry.nextLinks(
            published = state.inviteRegistries[signer.pubKey].orEmpty(),
            list = merged,
            communityIdHex = membership.id,
            nowSecs = createdAt,
            minted = listOf(minted.linkSignerPubKey),
        )
        val registryRumor = ConcordInviteRegistry.rumor(
            creator = signer.pubKey,
            communityId = membership.id.hexToByteArray(),
            linkSigners = liveLinks,
            version = (head?.version ?: 0L) + 1L,
            prevHash = head?.hashHex?.hexToByteArray(),
            createdAt = createdAt,
            authorityCitation = state.authority.citationFor(signer.pubKey),
        )
        val registryEvent = NostrEvent.fromJson(
            ConcordStreamEnvelope.wrap(
                rumor = registryRumor,
                keys = controlKeys,
                authorSigner = signer,
                encrypted = false,
                createdAt = createdAt,
            ).toJson(),
        )
        withContext(Dispatchers.IO) {
            journalEvents(communityId, listOf(registryEvent))
        }
        if (!publishConfirmed(registryEvent, relays)) {
            throw IllegalStateException("The Invite Registry was not confirmed by the Control Plane relay; do not share the link yet")
        }
        return minted.url
    }

    private suspend fun fetchInviteList(signer: PostillaQuartzSigner): ConcordInviteListDocument {
        val readRelays = relayPool.getReadRelayUrls().distinct()
        require(readRelays.isNotEmpty()) { "No read relays configured; refusing to overwrite the private Invite List" }

        val subId = "concord-invite-list-${signer.pubKey.take(12)}-${System.nanoTime()}"
        val events = java.util.concurrent.ConcurrentHashMap<String, NostrEvent>()
        val responded = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val allEose = CompletableDeferred<Unit>()

        coroutineScope {
            val eventCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                relayPool.relayEvents
                    .filter { it.subscriptionId == subId && it.event.kind == ConcordInviteListEvent.KIND }
                    .collect { relayEvent ->
                        if (relayEvent.event.pubkey == signer.pubKey) {
                            events.putIfAbsent(relayEvent.event.id, relayEvent.event)
                        }
                    }
            }
            val eoseCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                relayPool.relayEoseSignals
                    .filter { it.subscriptionId == subId && it.relayUrl in readRelays }
                    .collect { signal ->
                        responded.add(signal.relayUrl)
                        if (responded.containsAll(readRelays)) allEose.complete(Unit)
                    }
            }
            relayPool.sendToReadRelays(
                ClientMessage.req(
                    subId,
                    listOf(Filter(kinds = listOf(ConcordInviteListEvent.KIND), authors = listOf(signer.pubKey), limit = 50)),
                ),
            )
            val complete = withTimeoutOrNull(8_000L) {
                allEose.await()
                true
            } ?: false
            delay(150)
            eventCollector.cancelAndJoin()
            eoseCollector.cancelAndJoin()
            relayPool.closeOnAllRelays(subId)
            if (!complete) {
                throw IllegalStateException("Could not read the Invite List from every configured read relay; refusing a destructive update")
            }
        }

        prefs.all.keys.filter { it.startsWith("events_") }.forEach { key ->
            prefs.getStringSet(key, emptySet()).orEmpty().forEach { encoded ->
                runCatching { NostrEvent.fromJson(encoded) }.getOrNull()?.let { event ->
                    if (event.kind == ConcordInviteListEvent.KIND && event.pubkey == signer.pubKey) {
                        events.putIfAbsent(event.id, event)
                    }
                }
            }
        }

        var merged: ConcordInviteListDocument? = null
        for (event in events.values) {
            val plaintext = try {
                signer.nip44Decrypt(event.content, signer.pubKey)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw IllegalStateException("An existing Invite List copy could not be decrypted; refusing to overwrite it", error)
            }
            val decoded = ConcordInviteList.decodeOrNull(plaintext)
                ?: throw IllegalStateException("An existing Invite List copy is malformed; refusing to overwrite it")
            merged = merged?.let { ConcordInviteList.merge(it, decoded) } ?: decoded
        }
        return merged ?: ConcordInviteListDocument.EMPTY
    }

    private suspend fun publishConfirmed(event: NostrEvent, additionalRelays: List<String>): Boolean {
        val message = ClientMessage.event(event)
        val personalWriteRelays = relayPool.getWriteRelayUrls().toSet()
        val outcome = relayPool.publishWithConfirmation(event.id) {
            var sent = relayPool.sendToWriteRelays(message)
            for (url in additionalRelays.distinct().filterNot { it in personalWriteRelays }) {
                if (relayPool.sendToRelayOrEphemeral(url, message, skipBadCheck = true, write = true)) sent++
            }
            sent
        }
        return outcome.confirmed
    }

    /** Returns the locally journaled, already-signed events for recovery/retry tooling. */
    fun getJournaledEvents(communityId: String): List<NostrEvent> {
        val stored = prefs.getStringSet(eventKey(communityId), emptySet()).orEmpty()
        return stored.mapNotNull { encoded ->
            runCatching { NostrEvent.fromJson(encoded) }.getOrNull()
        }.sortedBy { it.created_at }
    }

    private fun journalEvents(communityId: String, events: List<NostrEvent>) {
        synchronized(journalLock) {
            val key = eventKey(communityId)
            val updated = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
            events.forEach { updated += it.toJson() }
            prefs.edit().putStringSet(key, updated).commit()
        }
    }

    private fun eventKey(communityId: String) = "events_$communityId"
    private fun generalChannelKey(communityId: String) = "general_channel_$communityId"

    private companion object {
        const val PREFS_NAME = "concord_community_journal"
        val journalLock = Any()
    }
}
