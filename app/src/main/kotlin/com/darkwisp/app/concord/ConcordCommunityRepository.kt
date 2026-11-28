package com.darkwisp.app.concord

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.darkwisp.app.nostr.ClientMessage
import com.darkwisp.app.nostr.Filter
import com.darkwisp.app.nostr.NostrEvent
import com.darkwisp.app.relay.RelayPool
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityFactory
import com.vitorpamplona.quartz.concord.cord02Community.ImagePointer
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityList
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityListEntry
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityListFragmentEvent
import com.vitorpamplona.quartz.concord.cord02Community.ConcordListFragmentSet
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityListDocument
import com.vitorpamplona.quartz.concord.cord02Community.PrivateChannelKey
import com.vitorpamplona.quartz.concord.cord03Channels.ConcordChannelAccess
import com.vitorpamplona.quartz.concord.cord03Channels.ConcordChannelKeyring
import com.vitorpamplona.quartz.concord.cord03Channels.ConcordChannelKeys
import com.vitorpamplona.quartz.concord.cord03Channels.ConcordWritersLedger
import com.vitorpamplona.quartz.concord.cord02Community.HeldRoot
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityState
import com.vitorpamplona.quartz.concord.cord02Community.Guestbook
import com.vitorpamplona.quartz.concord.cord02Community.GuestbookAction
import com.vitorpamplona.quartz.concord.cord02Community.GuestbookEntry
import com.vitorpamplona.quartz.concord.cord04Roles.ControlEdition
import com.vitorpamplona.quartz.concord.cord04Roles.ConcordLimits
import com.vitorpamplona.quartz.concord.cord04Roles.MetadataEntity
import com.vitorpamplona.quartz.concord.cord04Roles.ChannelEntity
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordDirectInvite
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteVend
import com.vitorpamplona.quartz.concord.cord05Invites.CommunityInvite
import com.vitorpamplona.quartz.concord.cord05Invites.InviteChannel
import com.vitorpamplona.quartz.concord.cord05Invites.OpenedDirectInvite
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import com.vitorpamplona.quartz.concord.cord04Roles.ControlEditionBuilder
import com.vitorpamplona.quartz.concord.cord04Roles.ControlEntityKind
import com.vitorpamplona.quartz.concord.cord04Roles.ConcordJson
import com.vitorpamplona.quartz.concord.cord04Roles.GrantEntity
import com.vitorpamplona.quartz.concord.cord04Roles.RoleEntity
import com.vitorpamplona.quartz.concord.cord04Roles.RoleScope
import com.vitorpamplona.quartz.concord.cord04Roles.ControlRootWrap
import com.vitorpamplona.quartz.concord.cord04Roles.ConcordPermissions
import com.vitorpamplona.quartz.concord.cord04Roles.RankKey
import com.vitorpamplona.quartz.concord.cord04Roles.RankedRoster
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteLink
import com.vitorpamplona.quartz.concord.cord05Invites.InviteBundleStatus
import com.vitorpamplona.quartz.concord.crypto.ControlPlaneKeys
import com.vitorpamplona.quartz.concord.crypto.GroupKey
import com.vitorpamplona.quartz.concord.crypto.ConcordKeyDerivation
import com.vitorpamplona.quartz.concord.envelope.ConcordStreamEnvelope
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteBundle
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteList
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteListDocument
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteListEntry as InviteListEntry
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteListTombstone
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteListEvent
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteRegistry
import com.vitorpamplona.quartz.concord.cord06Rekey.ConcordRefounding
import com.vitorpamplona.quartz.concord.cord06Rekey.ConcordRotationAuthority
import com.vitorpamplona.quartz.concord.cord06Rekey.PendingRefounding
import com.vitorpamplona.quartz.concord.cord06Rekey.ConcordRekey
import com.vitorpamplona.quartz.nip59Giftwrap.rumors.RumorAssembler
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.crypto.random.RandomInstance
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * A read is complete only when every requested relay accepted the subscription and
 * returned EOSE. A relay that never accepted REQ must not disappear from the quorum.
 */
internal fun concordRelayReadIsComplete(
    requestedRelays: Collection<String>,
    sentRelays: Collection<String>,
    eoseRelays: Collection<String>,
): Boolean {
    val requested = requestedRelays.toSet()
    return requested.isNotEmpty() &&
        sentRelays.containsAll(requested) &&
        eoseRelays.containsAll(requested)
}

/**
 * A chat read is ready only when strictly more than two thirds of the requested relays
 * both accepted the subscription and reached EOSE. A relay which did not accept REQ never
 * counts, even if an EOSE-like callback is observed. Administrative writes continue to use
 * [concordRelayReadIsComplete] and require every requested relay.
 */
internal fun concordRelayReadHasQuorum(
    requestedRelays: Collection<String>,
    sentRelays: Collection<String>,
    eoseRelays: Collection<String>,
): Boolean {
    val requested = requestedRelays.toSet()
    if (requested.isEmpty()) return false
    val sent = sentRelays.toSet()
    val eose = eoseRelays.toSet()
    val confirmed = requested.count { it in sent && it in eose }
    val required = minOf(
        requested.size,
        maxOf(2, (requested.size * 2L / 3L + 1L).toInt()),
    )
    return confirmed >= required
}

/** The community owner retains the ordinary channel capabilities even if an overwrite denies them. */
internal fun concordRolePermissionAllowsChannelAction(
    rolesV2: Boolean,
    isOwner: Boolean,
    rolePermissionGranted: Boolean,
): Boolean = !rolesV2 || isOwner || rolePermissionGranted

/**
 * Concord writer streams are required as soon as @everyone cannot send, even when
 * @everyone may still react or connect. A live ledger also keeps the channel on its
 * established writer plane until the ledger is retired.
 */
internal fun concordChannelRequiresWriterPlane(
    rolesV2: Boolean,
    everyoneCanSend: Boolean,
    hasLiveWriter: Boolean,
): Boolean = hasLiveWriter || (rolesV2 && !everyoneCanSend)

/** Owner and role-holders are part of the privileged roster even if they have not posted recently. */
internal fun concordPrivilegedRosterCandidates(
    ownerPubkey: String,
    roleHolders: Collection<String>,
): Set<String> = (listOf(ownerPubkey) + roleHolders)
    .map { it.trim().lowercase() }
    .filter(String::isNotBlank)
    .toCollection(LinkedHashSet())

/** All held community-root epochs whose Guestbooks may contain still-relevant member motion. */
internal fun concordGuestbookEpochs(
    currentEpoch: Long,
    heldEpochs: Collection<Long>,
): List<Long> = (heldEpochs + currentEpoch).distinct().sorted()

/**
 * Appends a CORD-03 writer epoch without dropping unknown ledger fields or retired windows.
 * Quartz validates the resulting ledger before it can be published.
 */
internal fun concordAppendWriterLedgerContent(
    previousContent: String?,
    channelIdHex: String,
    atMs: Long,
    epoch: Long,
    writerPubKey: String,
    rootEpoch: Long,
    minterPubKey: String,
    certifierPubKey: String,
): String {
    val channel = channelIdHex.lowercase()
    val previous = previousContent?.let { raw ->
        Json.parseToJsonElement(raw) as? JsonObject
            ?: throw IllegalStateException("The existing Writers Ledger is not a JSON object")
    }
    if (previous != null) {
        val previousChannel = (previous["channel"] as? JsonPrimitive)?.content
        require(previousChannel.equals(channel, ignoreCase = true)) {
            "The existing Writers Ledger belongs to another channel"
        }
    }
    val entries = previous?.get("ledger")?.let { it as? JsonArray }
        ?: if (previous == null) JsonArray(emptyList())
        else throw IllegalStateException("The existing Writers Ledger has no valid ledger array")
    require(entries.size < 64) {
        "The Writers Ledger reached its 64-entry limit; safe compaction is required before another writer epoch"
    }
    require(epoch > 0L && rootEpoch >= 0L && atMs > 0L) { "Invalid Concord writer epoch metadata" }
    fun validXOnly(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    require(validXOnly(writerPubKey) && validXOnly(minterPubKey) && validXOnly(certifierPubKey)) {
        "A Concord writer or certifier public key is malformed"
    }
    val previousEpoch = entries.maxOfOrNull { entry ->
        val objectEntry = entry as? JsonObject
        (objectEntry?.get("epoch") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
    } ?: 0L
    require(epoch > previousEpoch) { "The new writer epoch must be greater than every existing epoch" }
    val lastAt = entries.lastOrNull()?.let { entry ->
        val objectEntry = entry as? JsonObject
        (objectEntry?.get("at") as? JsonPrimitive)?.content?.toLongOrNull()
    } ?: 0L
    require(atMs > lastAt) { "The writer ledger entry timestamp must advance monotonically" }
    val writerEntry = buildJsonObject {
        put("at", JsonPrimitive(atMs))
        put("epoch", JsonPrimitive(epoch))
        put("writer", JsonPrimitive(writerPubKey.lowercase()))
        put("root", JsonPrimitive(rootEpoch))
        put("by", JsonPrimitive(minterPubKey.lowercase()))
        put("certifier", JsonPrimitive(certifierPubKey.lowercase()))
    }
    return buildJsonObject {
        previous?.forEach { (key, value) ->
            if (key != "channel" && key != "ledger") put(key, value)
        }
        put("channel", JsonPrimitive(channel))
        put("ledger", JsonArray(entries + writerEntry))
    }.toString()
}

/**
 * First Concord vertical slice: create a CORD-02 community, publish its two genesis
 * Control Plane wraps, and publish the owner's self-encrypted fragmented membership list.
 *
 * The local journal stores only signed event JSON. Community secrets are carried only
 * inside Quartz's NIP-44 self-encrypted kind-33302 list fragments.
 */
internal fun concordHasDirectInviteKeyCatchUp(
    held: ConcordCommunityListEntry?,
    invite: CommunityInvite,
): Boolean = ConcordInviteVend.catchUpChannelIds(held, invite).isNotEmpty()

class ConcordCommunityRepository(
    context: Context,
    private val relayPool: RelayPool,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val securePrefs: SharedPreferences = EncryptedSharedPreferences.create(
        appContext,
        "concord_secrets",
        MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val writerDutyLocks = ConcurrentHashMap<String, Mutex>()

    @Volatile
    private var communityListReadWarning: String? = null

    fun getCommunityListReadWarning(): String? = communityListReadWarning

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

    data class RelayReadSummary(
        val requestedRelays: List<String>,
        val sentRelays: List<String>,
        val respondedRelays: List<String>,
    ) {
        val requestedCount: Int get() = requestedRelays.size
        val sentCount: Int get() = sentRelays.size
        val respondedCount: Int get() = respondedRelays.size
        val missingRelays: List<String> get() = requestedRelays.filterNot { it in respondedRelays }
        val isComplete: Boolean
            get() = concordRelayReadIsComplete(requestedRelays, sentRelays, respondedRelays)
        val hasQuorum: Boolean
            get() = concordRelayReadHasQuorum(requestedRelays, sentRelays, respondedRelays)
    }

    data class CommunityChannelSnapshot(
        val channels: List<CommunityChannel>,
        val controlRead: RelayReadSummary,
        val writerAccessRead: RelayReadSummary?,
        /** Current Control Plane name, when its relay read reached the chat quorum. */
        val communityName: String? = null,
    ) {
        val isControlReadComplete: Boolean get() = controlRead.isComplete
        val hasControlReadQuorum: Boolean get() = controlRead.hasQuorum
    }

    private data class RelayFetchResult(
        val events: List<NostrEvent>,
        val summary: RelayReadSummary,
    )

    /**
     * Creates a community using Quartz's reference factory and wire encoders.
     * Each event is journaled before publication; an unconfirmed event remains available
     * for a future retry instead of being silently discarded.
     */
    suspend fun createCommunity(
        signer: PostillaQuartzSigner,
        name: String,
        description: String? = null,
        icon: ImagePointer? = null,
        relays: List<String> = relayPool.getWriteRelayUrls(),
        createdAt: Long = System.currentTimeMillis() / 1000,
    ): CreationResult {
        require(name.isNotBlank()) { "Community name cannot be blank" }
        require(relays.isNotEmpty()) { "At least one community relay is required" }

        // Read/merge the existing list before publishing anything. A new community must
        // never replace the user's other memberships or their anti-resurrection tombstones.
        val existingList = fetchCommunityList(signer)
        requireSafeCommunityListForWrite(existingList, "Community creation")
        val community = ConcordCommunityFactory.create(
            ownerSigner = signer,
            name = name.trim(),
            createdAt = createdAt,
            description = description?.trim()?.takeIf { it.isNotEmpty() },
            relays = relays.distinct(),
            icon = icon,
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
        val listWrites = planCommunityListWrites(existingList, nextDocument, createdAt)
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
        val allReadRelaysResponded: Boolean,
        val respondingReadRelays: Int,
        val configuredReadRelays: Int,
    )

    /**
     * A partial relay read may proceed only when at least one readable fragment exists.
     * Its update is then scoped to the fragment(s) being changed; a full repack is reserved
     * for a read completed by every configured read relay.
     */
    private fun requireSafeCommunityListForWrite(snapshot: CommunityListSnapshot, operation: String) {
        val hasWritableFragment = snapshot.fragments.held.keys.any {
            it < snapshot.fragments.declared && it !in snapshot.fragments.unreadable
        }
        if (
            ConcordCommunityListWritePolicy.mayWrite(
                allReadRelaysResponded = snapshot.allReadRelaysResponded,
                readQuorumReached = snapshot.respondingReadRelays >=
                    ConcordCommunityListWritePolicy.requiredReadRelayResponses(snapshot.configuredReadRelays),
                hasWritableFragment = hasWritableFragment,
            )
        ) return

        val requiredResponses = ConcordCommunityListWritePolicy.requiredReadRelayResponses(snapshot.configuredReadRelays)
        val reason = when {
            snapshot.respondingReadRelays < requiredResponses ->
                "the safe read quorum was not reached (need $requiredResponses responding relays)"
            snapshot.fragments.isEmpty ->
                "no kind-33302 membership fragment was found on the responding relays"
            snapshot.fragments.held.isEmpty() ->
                "the membership fragments found on the responding relays could not be decrypted"
            else ->
                "no readable fragment is available for a scoped update"
        }
        throw IllegalStateException(
            "$operation stopped safely: only ${snapshot.respondingReadRelays} of " +
                "${snapshot.configuredReadRelays} read relays completed the Community List read; $reason. " +
                "Postilla will not assume that an unavailable list is empty."
        )
    }

    /**
     * On a partial read, always use the CORD-02 scoped-write path—even if the fragments
     * currently look complete. This avoids repacking from a snapshot that may be missing
     * a newer copy held by one of the relays that did not answer.
     */
    private fun planCommunityListWrites(
        snapshot: CommunityListSnapshot,
        nextDocument: JsonObject,
        createdAt: Long,
    ): List<ConcordListFragmentSet.Write> =
        if (snapshot.allReadRelaysResponded) {
            snapshot.fragments.planWrites(nextDocument, createdAt)
        } else {
            ConcordCommunityListWritePolicy.planScopedWrites(snapshot.fragments, nextDocument, createdAt)
        }

    /**
     * Reads every configured read relay to a per-relay EOSE, then merges remote and
     * locally journaled fragments. A failed decrypt stays unreadable in the fragment
     * set, so the scoped-write planner can preserve unseen memberships instead of repacking.
     */
    private suspend fun fetchCommunityList(signer: PostillaQuartzSigner): CommunityListSnapshot {
        // The private Community List is commonly published to the account's write relays.
        // Querying only read-enabled relays can therefore return EOSE from many relays while
        // missing the only relays that actually hold the user's membership fragments.
        val readRelays = relayPool.getRelayUrls().distinct()
        require(readRelays.isNotEmpty()) { "No configured relays available; refusing to replace the Community List" }

        val subId = "concord-community-list-${signer.pubKey.take(12)}-${System.nanoTime()}"
        val events = java.util.concurrent.ConcurrentHashMap<String, NostrEvent>()
        val responded = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val allEose = CompletableDeferred<Unit>()
        val readQuorumEose = CompletableDeferred<Unit>()
        val readQuorum = ConcordCommunityListWritePolicy.requiredReadRelayResponses(readRelays.size)
        var allResponded = false

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
                        if (responded.size >= readQuorum) readQuorumEose.complete(Unit)
                        if (responded.containsAll(readRelays)) allEose.complete(Unit)
                    }
            }
            val request = ClientMessage.req(
                subId,
                listOf(
                    Filter(
                        kinds = listOf(ConcordCommunityListFragmentEvent.KIND, 13302),
                        authors = listOf(signer.pubKey),
                        limit = 5000,
                    ),
                ),
            )
            // Use every configured persistent relay, including write-only relays, through
            // the normal/ephemeral transport. EOSE accounting below is against this same set.
            readRelays.forEach { url ->
                relayPool.sendToRelayOrEphemeral(url, request, skipBadCheck = true, write = false)
            }
            // Do not make a slow tail of configured relays hold the whole screen hostage.
            // A two-thirds EOSE quorum provides a useful snapshot; remaining relays still
            // receive REQ and may contribute during the settling window. Partial snapshots
            // are updated only through scoped writes, never by repacking unseen fragments.
            withTimeoutOrNull(5_000L) { readQuorumEose.await() }
            // Let the first responders' EVENT frames drain before deciding the list is empty.
            // If no fragment arrived, give slower relays a second bounded window: treating two
            // fast empty responses as proof of an empty private list caused false-safe blocks.
            delay(350)
            if (events.isEmpty() && !allEose.isCompleted) {
                withTimeoutOrNull(2_000L) { allEose.await() }
            }
            allResponded = allEose.isCompleted
            delay(350)
            eventCollector.cancelAndJoin()
            eoseCollector.cancelAndJoin()
            relayPool.closeOnAllRelays(subId)
            if (responded.isEmpty()) {
                throw IllegalStateException(
                    "No configured read relay completed the Community List read; refusing to treat it as empty."
                )
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

        // Cache the signed, self-encrypted membership events received from relays. This
        // makes a later partial/empty relay response recoverable without storing plaintext
        // community secrets, and allows the next refresh to merge local and remote copies.
        val membershipEvents = events.values.filter {
            it.kind in setOf(ConcordCommunityListFragmentEvent.KIND, 13302)
        }
        if (membershipEvents.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                journalEvents("membership_${signer.pubKey}", membershipEvents.toList())
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
        return CommunityListSnapshot(
            fragments = fragmentSet,
            document = ConcordCommunityList.decodeDocument(mergedDocument),
            allReadRelaysResponded = allResponded,
            respondingReadRelays = responded.size,
            configuredReadRelays = readRelays.size,
        )
    }


    /** Loads remote and local memberships, retaining a visible warning for incomplete relay sync. */
    suspend fun loadLocalMemberships(signer: PostillaQuartzSigner): List<ConcordCommunityListEntry> = try {
        val snapshot = fetchCommunityList(signer)
        val requiredResponses = ConcordCommunityListWritePolicy.requiredReadRelayResponses(snapshot.configuredReadRelays)
        communityListReadWarning = when {
            snapshot.allReadRelaysResponded -> null
            snapshot.respondingReadRelays < requiredResponses ->
                "Community list is read-only for now: ${snapshot.respondingReadRelays}/${snapshot.configuredReadRelays} relays answered; the safe quorum is $requiredResponses. Refresh when more relays are available."
            snapshot.fragments.isEmpty ->
                "Community list check incomplete: ${snapshot.respondingReadRelays}/${snapshot.configuredReadRelays} read relays answered, but no membership fragment was found. Postilla will not treat this as an empty list."
            snapshot.fragments.complete ->
                "Community list loaded from ${snapshot.respondingReadRelays}/${snapshot.configuredReadRelays} read relays. The others did not answer; changes will use scoped updates to preserve fragments not seen."
            else ->
                "Community list partially loaded from ${snapshot.respondingReadRelays}/${snapshot.configuredReadRelays} read relays. Some fragments are still missing; changes will be scoped and will not repack unseen fragments."
        }
        snapshot.document.entries
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        communityListReadWarning =
            "Could not synchronize the private Community List: ${error.message ?: "relay read failed"}. " +
                "Only memberships saved on this device can be shown."
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

    data class CommunityRole(
        val id: String,
        val name: String,
        val position: Long,
        val permissions: ConcordPermissions,
        val canEdit: Boolean,
        val rankKey: String? = null,
        val color: Long = 0L,
        val display: Boolean = false,
        val mentionable: Boolean = false,
    )

    data class CommunityRolesSnapshot(
        val roles: List<CommunityRole>,
        val rolesV2: Boolean,
        val canManageRoles: Boolean,
        val delegablePermissionBits: List<Int>,
    )

    /** Reads the complete Control Plane before exposing roles for a management operation. */
    /**
     * Publish a new Community Metadata edition (CORD-02 §6 / CORD-04 vsk 0).
     * The current metadata is folded first and unknown fields are preserved, so an edit
     * from Postilla cannot erase another client's custom fields or the CORD-08 timer.
     */
    suspend fun updateCommunityMetadata(
        signer: PostillaQuartzSigner,
        communityId: String,
        name: String,
        description: String?,
        icon: ImagePointer?,
        banner: ImagePointer?,
        relays: List<String>,
    ) {
        val normalizedName = name.trim()
        require(ConcordLimits.nameFits(normalizedName)) {
            "Community name must be at most 64 UTF-8 bytes"
        }
        val normalizedDescription = description?.trim()?.takeIf { it.isNotEmpty() }
        require(ConcordLimits.descriptionFits(normalizedDescription)) {
            "Community description must be at most 10,000 UTF-8 bytes"
        }
        val normalizedRelays = relays.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        require(normalizedRelays.isNotEmpty()) {
            "A community must retain at least one bootstrap relay"
        }
        require(normalizedRelays.all { it.startsWith("wss://") }) {
            "Community relays must use secure wss:// URLs"
        }

        val membership = loadLocalMemberships(signer).firstOrNull { it.id == communityId }
            ?: throw IllegalArgumentException("Community membership is not available")
        val control = loadControlPlaneSnapshot(membership, requireAllResponders = true)
        if (control.state.dissolved) throw IllegalStateException("This community has been dissolved")
        if (!control.state.authority.isOwner(signer.pubKey) &&
            !control.state.authority.hasPermission(signer.pubKey, ConcordPermissions.MANAGE_METADATA)
        ) {
            throw IllegalStateException("This account cannot manage community metadata")
        }

        val heads = ConcordCommunityState.authorizedHeads(
            control.editions,
            membership.id.hexToByteArray(),
            membership.owner,
        )
        val currentHead = heads[membership.id]
        val standing = control.state.metadata ?: MetadataEntity()
        val updated = standing.copy(
            name = normalizedName,
            description = normalizedDescription,
            // A null image argument means "leave the existing encrypted pointer untouched".
            // The editor never replaces or clears an image unless a new encrypted pointer is supplied.
            icon = icon ?: standing.icon,
            banner = banner ?: standing.banner,
            relays = normalizedRelays,
        )
        require(ConcordLimits.metadataFits(updated)) {
            "Community metadata exceeds the Concord protocol limits"
        }
        val content = ConcordJson.encodePreserving(
            MetadataEntity.serializer(),
            updated,
            currentHead?.known?.content,
        )
        publishControlEdition(
            signer = signer,
            membership = membership,
            control = control,
            entityKind = ControlEntityKind.METADATA,
            entityId = membership.id.hexToByteArray(),
            content = content,
            permission = ConcordPermissions.MANAGE_METADATA,
            previousVersion = currentHead?.version,
            previousHash = currentHead?.hashHex,
        )
    }

    suspend fun loadCommunityRoles(
        signer: PostillaQuartzSigner,
        communityId: String,
    ): CommunityRolesSnapshot {
        val membership = loadLocalMemberships(signer).firstOrNull { it.id == communityId }
            ?: throw IllegalArgumentException("Community membership is not available")
        val control = loadControlPlaneSnapshot(membership, requireAllResponders = true)
        val authority = control.state.authority
        val canManage = authority.isOwner(signer.pubKey) ||
            authority.hasPermission(signer.pubKey, ConcordPermissions.MANAGE_ROLES)
        if (control.state.isRolesV2) {
            val roster = authority.rankedRoster()
                ?: throw IllegalStateException("The Roles v2 Control Plane did not produce a roster")
            check(roster.converged) {
                "The Roles v2 roster is still converging. Refresh before managing roles."
            }
            val actorRank = authority.rank(signer.pubKey)
            val roles = roster.roles
                .sortedWith(compareBy({ it.position }, { it.rank }, { it.roleId }))
                .map { role ->
                    val head = roster.heads[role.roleId]
                    val support = if (authority.isOwner(signer.pubKey)) {
                        ConcordPermissions.ALL
                    } else {
                        roster.permissionsAbove(signer.pubKey, role.position) union
                            (head?.let { authority.sealedFor(signer.pubKey, it.hashHex, role.position) }
                                ?: ConcordPermissions.NONE)
                    }
                    CommunityRole(
                        id = role.roleId,
                        name = role.name,
                        position = role.position,
                        permissions = role.permissions,
                        canEdit = canManage && (authority.isOwner(signer.pubKey) ||
                            (actorRank != null && actorRank < role.position &&
                                support.has(ConcordPermissions.MANAGE_ROLES) &&
                                (role.permissions minus support).isEmpty())),
                        rankKey = role.rank,
                        color = role.color,
                        display = role.display,
                        mentionable = role.mentionable,
                    )
                }
            val delegableBits = (0..29)
                .filterNot { it == 7 || it == 10 || it == 12 || it == 27 }
                .filter { authority.effectivePermissions(signer.pubKey).has(it) }
            return CommunityRolesSnapshot(
                roles = roles,
                rolesV2 = true,
                canManageRoles = canManage,
                delegablePermissionBits = delegableBits,
            )
        }
        val actorRank = authority.rank(signer.pubKey)
        val roles = authority.roles().entries
            .filter { !it.value.deleted }
            .sortedWith(compareBy({ it.value.position }, { it.key.lowercase() }))
            .map { (id, role) ->
                CommunityRole(
                    id = id,
                    name = role.name,
                    position = role.position,
                    permissions = role.permissionBits(),
                    canEdit = actorRank != null &&
                        actorRank < role.position &&
                        authority.canWriteRole(signer.pubKey, id, role),
                )
            }
        val delegableBits = listOf(
            ConcordPermissions.MANAGE_ROLES,
            ConcordPermissions.MANAGE_CHANNELS,
            ConcordPermissions.MANAGE_METADATA,
            ConcordPermissions.KICK,
            ConcordPermissions.BAN,
            ConcordPermissions.MANAGE_MESSAGES,
            ConcordPermissions.CREATE_INVITE,
            ConcordPermissions.VIEW_AUDIT_LOG,
            ConcordPermissions.MENTION_EVERYONE,
            ConcordPermissions.PIN_MESSAGES,
        ).filter { authority.hasPermission(signer.pubKey, it) }
        return CommunityRolesSnapshot(
            roles = roles,
            rolesV2 = false,
            canManageRoles = canManage,
            delegablePermissionBits = delegableBits,
        )
    }

    /**
     * A Community List synced from another device carries the community secrets, but this
     * device may not have the cached #general id. Recover it from the read-only Control Plane.
     */
    suspend fun restoreGeneralChannelId(membership: ConcordCommunityListEntry): String {
        getLocalGeneralChannelId(membership.id)?.takeIf { it.isNotBlank() }?.let { return it }
        val control = loadControlPlaneSnapshot(membership, requireAllResponders = false)
        val channel = control.state.channels.values.firstOrNull {
            it.definition.name.equals("general", ignoreCase = true)
        } ?: throw IllegalStateException("The community Control Plane has no live #general channel")
        val channelId = channel.channelIdHex
        withContext(Dispatchers.IO) {
            check(prefs.edit().putString(generalChannelKey(membership.id), channelId).commit()) {
                "Could not save the restored #general channel key locally"
            }
        }
        return channelId
    }




    private fun requireLegacyRoleActions(state: ConcordCommunityState) {
        check(!state.isRolesV2) {
            "This community uses Roles v2. Legacy role and grant edits are not compatible; no changes were published."
        }
    }

    private fun requireDelegableRolePermissions(
        authority: com.vitorpamplona.quartz.concord.cord04Roles.AuthorityResolver,
        actor: String,
        permissions: ConcordPermissions,
    ) {
        val bits = listOf(
            ConcordPermissions.MANAGE_ROLES,
            ConcordPermissions.MANAGE_CHANNELS,
            ConcordPermissions.MANAGE_METADATA,
            ConcordPermissions.KICK,
            ConcordPermissions.BAN,
            ConcordPermissions.MANAGE_MESSAGES,
            ConcordPermissions.CREATE_INVITE,
            ConcordPermissions.VIEW_AUDIT_LOG,
            ConcordPermissions.MENTION_EVERYONE,
            ConcordPermissions.PIN_MESSAGES,
        )
        require(bits.all { !permissions.has(it) || authority.hasPermission(actor, it) }) {
            "A role cannot grant permissions this account does not hold."
        }
    }


    private fun rolesV2RoleContent(
        roleId: String,
        name: String,
        rank: String,
        permissions: ConcordPermissions,
        color: Long,
        display: Boolean,
        mentionable: Boolean,
        previous: ControlEdition? = null,
    ): String = buildJsonObject {
        val prior = previous?.let {
            runCatching { ConcordJson.instance.parseToJsonElement(it.content) as? JsonObject }.getOrNull()
        }
        prior?.filterKeys { it != "deleted" }?.forEach { (key, value) -> put(key, value) }
        put("role_id", JsonPrimitive(roleId.lowercase()))
        put("name", JsonPrimitive(name))
        put("rank", JsonPrimitive(rank))
        put("permissions", JsonPrimitive(permissions.toWire()))
        put("color", JsonPrimitive(color))
        put("display", JsonPrimitive(display))
        put("mentionable", JsonPrimitive(mentionable))
    }.toString()

    private fun rolesV2GrantContent(
        member: String,
        roleIds: Collection<String>,
        controlWrap: String?,
    ): String = buildJsonObject {
        put("member", JsonPrimitive(member.lowercase()))
        put("role_ids", buildJsonArray {
            roleIds.map { it.lowercase() }.distinct().sorted().forEach { add(JsonPrimitive(it)) }
        })
        controlWrap?.let { put("control_wrap", JsonPrimitive(it)) }
    }.toString()

    private fun rolesV2PositionForWrite(
        state: ConcordCommunityState,
        requestedPosition: Long,
        excludingRoleId: String? = null,
    ): Pair<String, Long> {
        val roster = state.authority.rankedRoster()
            ?: throw IllegalStateException("Roles v2 roster is unavailable")
        val orderedKeys = roster.roles
            .filterNot { excludingRoleId != null && it.roleId.equals(excludingRoleId, ignoreCase = true) }
            .map { it.rank }
            .distinct()
            .sorted()
        val target = requestedPosition.coerceIn(1L, orderedKeys.size.toLong() + 1L).toInt()
        val lower = orderedKeys.getOrNull(target - 2)
        val upper = orderedKeys.getOrNull(target - 1)
        val key = RankKey.between(lower, upper)
            ?: throw IllegalStateException("No valid rank key fits at this position. Move another role first.")
        return key to target.toLong()
    }

    private fun requireRolesV2RoleAuthority(
        state: ConcordCommunityState,
        actor: String,
        position: Long,
        permissions: ConcordPermissions,
        currentHeadHash: String? = null,
    ) {
        val authority = state.authority
        if (authority.isOwner(actor)) return
        if (!authority.hasPermission(actor, ConcordPermissions.MANAGE_ROLES)) {
            throw IllegalStateException("This account cannot manage community roles")
        }
        val actorRank = authority.rank(actor)
            ?: throw IllegalStateException("The actor has no ranked role authority")
        require(actorRank < position) {
            "The actor must strictly outrank the role's current and proposed position"
        }
        val roster = authority.rankedRoster()
            ?: throw IllegalStateException("Roles v2 roster is unavailable")
        val sealed = currentHeadHash?.let { authority.sealedFor(actor, it, position) }
            ?: ConcordPermissions.NONE
        val support = roster.permissionsAbove(actor, position) union sealed
        require(support.has(ConcordPermissions.MANAGE_ROLES)) {
            "The actor must hold MANAGE_ROLES above the role's rank"
        }
        require((permissions minus support).isEmpty()) {
            "A role cannot grant permissions this account does not hold above its rank"
        }
    }

    private suspend fun buildRolesV2ControlEvent(
        signer: PostillaQuartzSigner,
        membership: ConcordCommunityListEntry,
        control: ControlPlaneSnapshot,
        entityKind: ControlEntityKind,
        entityId: ByteArray,
        content: String,
        previous: ControlEdition?,
    ): NostrEvent {
        val authority = control.state.authority
        if (!authority.hasPermission(signer.pubKey, ConcordPermissions.MANAGE_ROLES)) {
            throw IllegalStateException("This account cannot manage community roles")
        }
        check(control.keys.signer != null) { "The Control Plane write key is unavailable on this device" }
        val citation = authority.citationFor(signer.pubKey)
        if (!authority.isOwner(signer.pubKey) && citation == null) {
            throw IllegalStateException("The actor's current Grant head is not synchronized. Refresh before publishing.")
        }
        val rumor = ControlEditionBuilder.rumor(
            authorPubKey = signer.pubKey,
            entityKind = entityKind,
            entityId = entityId,
            version = (previous?.version ?: 0L) + 1L,
            prevHash = previous?.hash,
            content = content,
            createdAt = System.currentTimeMillis() / 1000L,
            authorityCitation = citation,
            binding = RankedRoster.bindingForWrite(
                authority, membership.id.hexToByteArray(), entityKind,
            ),
        )
        return NostrEvent.fromJson(
            ConcordStreamEnvelope.wrap(
                rumor = rumor,
                keys = control.keys,
                authorSigner = signer,
                encrypted = false,
            ).toJson(),
        )
    }

    private suspend fun publishRolesV2ControlEvent(
        signer: PostillaQuartzSigner,
        membership: ConcordCommunityListEntry,
        control: ControlPlaneSnapshot,
        entityKind: ControlEntityKind,
        entityId: ByteArray,
        content: String,
        previous: ControlEdition?,
    ): NostrEvent {
        val event = buildRolesV2ControlEvent(signer, membership, control, entityKind, entityId, content, previous)
        withContext(Dispatchers.IO) { journalEvents(membership.id, listOf(event)) }
        if (!publishConfirmed(event, membership.relays)) {
            throw IllegalStateException("The Roles v2 Control Plane edition was not confirmed by a community relay")
        }
        return event
    }

    private suspend fun createRolesV2Role(
        signer: PostillaQuartzSigner,
        membership: ConcordCommunityListEntry,
        control: ControlPlaneSnapshot,
        name: String,
        position: Long,
        permissions: ConcordPermissions,
    ): String {
        val normalized = name.trim()
        require(normalized.isNotBlank() && normalized.encodeToByteArray().size <= 64) {
            "Role name must be 1–64 UTF-8 bytes"
        }
        val roster = control.state.authority.rankedRoster()
            ?: throw IllegalStateException("Roles v2 roster is unavailable")
        check(roster.converged) { "The Roles v2 roster is incomplete. Refresh before creating a role." }
        require(roster.roles.size < 100) { "The Roles v2 community already has the maximum of 100 roles" }
        val (rank, densePosition) = rolesV2PositionForWrite(control.state, position)
        requireRolesV2RoleAuthority(control.state, signer.pubKey, densePosition, permissions)
        val roleIdBytes = RandomInstance.bytes(32)
        val roleId = roleIdBytes.toHexKey()
        val content = rolesV2RoleContent(roleId, normalized, rank, permissions, 0L, true, false)
        publishRolesV2ControlEvent(
            signer, membership, control, ControlEntityKind.RANKED_ROLE,
            roleIdBytes, content, null,
        )
        return roleId
    }

    private suspend fun updateRolesV2Role(
        signer: PostillaQuartzSigner,
        membership: ConcordCommunityListEntry,
        control: ControlPlaneSnapshot,
        roleId: String,
        name: String,
        position: Long,
        permissions: ConcordPermissions,
    ): String {
        val roleKey = roleId.lowercase()
        val normalized = name.trim()
        require(normalized.isNotBlank() && normalized.encodeToByteArray().size <= 64) {
            "Role name must be 1–64 UTF-8 bytes"
        }
        val roster = control.state.authority.rankedRoster()
            ?: throw IllegalStateException("Roles v2 roster is unavailable")
        check(roster.converged) { "The Roles v2 roster is incomplete. Refresh before editing a role." }
        val current = roster.role(roleKey) ?: throw IllegalArgumentException("Roles v2 role does not exist")
        val head = roster.heads[roleKey] ?: throw IllegalStateException("The role's current head is unavailable")
        val (rank, densePosition) = if (position == current.position) {
            current.rank to current.position
        } else {
            rolesV2PositionForWrite(control.state, position, roleKey)
        }
        requireRolesV2RoleAuthority(control.state, signer.pubKey, current.position, current.permissions, head.hashHex)
        requireRolesV2RoleAuthority(control.state, signer.pubKey, densePosition, permissions, head.hashHex)
        require(current.permissions.hasAny(ConcordPermissions.STAFF_BITS) ==
            permissions.hasAny(ConcordPermissions.STAFF_BITS)) {
            "Changing STAFF permissions on an existing role needs coordinated Control Root redistribution. No change was published."
        }
        val content = rolesV2RoleContent(
            roleKey, normalized, rank, permissions, current.color, current.display, current.mentionable, head,
        )
        val event = publishRolesV2ControlEvent(
            signer, membership, control, ControlEntityKind.RANKED_ROLE,
            roleKey.hexToByteArray(), content, head,
        )
        return event.id
    }

    private suspend fun deleteRolesV2Role(
        signer: PostillaQuartzSigner,
        membership: ConcordCommunityListEntry,
        control: ControlPlaneSnapshot,
        roleId: String,
    ): String {
        val roleKey = roleId.lowercase()
        val roster = control.state.authority.rankedRoster()
            ?: throw IllegalStateException("Roles v2 roster is unavailable")
        check(roster.converged) { "The Roles v2 roster is incomplete. Refresh before deleting a role." }
        val current = roster.role(roleKey) ?: throw IllegalArgumentException("Roles v2 role does not exist")
        val holders = roster.grants.filter { roleKey in it.roleIds }.map { it.member }
        require(holders.isEmpty()) {
            "This role is still assigned to members. Remove its assignments first; deletion was not published."
        }
        val head = roster.heads[roleKey] ?: throw IllegalStateException("The role's current head is unavailable")
        requireRolesV2RoleAuthority(control.state, signer.pubKey, current.position, current.permissions, head.hashHex)
        val content = buildJsonObject {
            val prior = runCatching { ConcordJson.instance.parseToJsonElement(head.content) as? JsonObject }.getOrNull()
            prior?.forEach { (key, value) -> put(key, value) }
            put("role_id", JsonPrimitive(roleKey))
            put("deleted", JsonPrimitive(true))
        }.toString()
        val event = publishRolesV2ControlEvent(
            signer, membership, control, ControlEntityKind.RANKED_ROLE,
            roleKey.hexToByteArray(), content, head,
        )
        return event.id
    }

    private suspend fun setRolesV2MemberRoles(
        signer: PostillaQuartzSigner,
        membership: ConcordCommunityListEntry,
        control: ControlPlaneSnapshot,
        memberPubKey: String,
        requestedRoleIds: Collection<String>,
    ): String {
        val target = memberPubKey.lowercase()
        val targetBytes = target.hexToByteArray()
        require(targetBytes.size == 32) { "Member public key must be 32 bytes" }
        require(target != signer.pubKey.lowercase()) { "An account cannot edit its own role grant" }
        val authority = control.state.authority
        val roster = authority.rankedRoster()
            ?: throw IllegalStateException("Roles v2 roster is unavailable")
        check(roster.converged) { "The Roles v2 roster is incomplete. Refresh before changing member roles." }
        require(authority.canActOn(signer.pubKey, target, ConcordPermissions.MANAGE_ROLES)) {
            "The actor does not outrank this member or cannot manage member roles"
        }
        require(!authority.isBanned(target)) { "A banned member cannot receive roles" }
        val roleIds = requestedRoleIds.map { it.lowercase() }.distinct().sorted()
        require(roleIds.size <= 64) { "A member cannot hold more than 64 roles" }
        val currentRoleIds = roster.rolesOf(target).map { it.roleId }.distinct().sorted()
        if (roleIds == currentRoleIds) return ""
        val selected = roleIds.map { id ->
            roster.role(id) ?: throw IllegalArgumentException("Roles v2 role " + id + " does not exist")
        }
        if (!authority.isOwner(signer.pubKey)) {
            val reach = selected.minOfOrNull { it.position } ?: RankedRoster.BENEATH_EVERY_ROLE
            val support = roster.permissionsAbove(signer.pubKey, reach)
            require(support.has(ConcordPermissions.MANAGE_ROLES)) {
                "The actor must hold MANAGE_ROLES above every role being assigned"
            }
            selected.forEach { role ->
                require((role.permissions minus support).isEmpty()) {
                    "The actor cannot delegate every permission held by role " + role.name
                }
            }
        }

        // Do not revoke an ACL-based private-channel permission while the target still has its old key.
        val removedRoleIds = currentRoleIds.filterNot { it in roleIds }.toSet()
        val privateAccessRemoval = control.state.channels.values.any { channel ->
            if (!channel.definition.private || channel.definition.deleted) return@any false
            val scope = control.state.syncs[channel.channelIdHex] ?: channel.channelIdHex
            control.state.overwrites.any { overwrite ->
                overwrite.scope == scope && overwrite.targetKind == "role" &&
                    overwrite.targetId in removedRoleIds &&
                    overwrite.allow.has(ConcordPermissions.VIEW_CHANNEL)
            }
        }
        require(!privateAccessRemoval) {
            "Removing this role would revoke private-channel access. Rotate the affected channel key first; no Grant was published."
        }

        val hadStaff = authority.isStaff(target)
        val everyoneStaff = roster.everyone?.hasAny(ConcordPermissions.STAFF_BITS) == true
        val permissions = selected.fold(ConcordPermissions.NONE) { acc, role -> acc union role.permissions }
        val willHaveStaff = everyoneStaff || permissions.hasAny(ConcordPermissions.STAFF_BITS)
        val controlWrap = if (willHaveStaff) {
            val root = membership.controlRoot
                ?: throw IllegalStateException("The Control Root is unavailable; the staff key handoff cannot be created")
            ControlRootWrap.build(signer, target, membership.rootEpoch, root.hexToByteArray())
        } else null

        val grantId = ConcordKeyDerivation.grantCoordinate(membership.id.hexToByteArray(), targetBytes)
        val head = roster.heads[grantId.toHexKey()]
        val content = rolesV2GrantContent(target, roleIds, controlWrap)
        val event = buildRolesV2ControlEvent(
            signer, membership, control, ControlEntityKind.GRANT,
            grantId, content, head,
        )
        val published = if (hadStaff && !willHaveStaff) {
            refoundCommunity(signer, membership.id, extraControlEvents = listOf(event))
            event
        } else {
            withContext(Dispatchers.IO) { journalEvents(membership.id, listOf(event)) }
            if (!publishConfirmed(event, membership.relays)) {
                throw IllegalStateException("The Roles v2 Grant was not confirmed by a community relay")
            }
            event
        }
        return published.id
    }
    suspend fun createRole(
        signer: PostillaQuartzSigner,
        communityId: String,
        name: String,
        position: Long,
        permissions: ConcordPermissions,
    ): String {
        require(name.isNotBlank() && name.trim().encodeToByteArray().size <= 64) { "Role name must be 1–64 UTF-8 bytes" }
        require(position >= 1L) { "Role position 0 is reserved for the owner" }
        val membership = loadLocalMemberships(signer).firstOrNull { it.id == communityId }
            ?: throw IllegalArgumentException("Community membership is not available")
        val control = loadControlPlaneSnapshot(membership, requireAllResponders = true)
        if (control.state.isRolesV2) {
            return createRolesV2Role(signer, membership, control, name, position, permissions)
        }
        requireLegacyRoleActions(control.state)
        val authority = control.state.authority
        if (!authority.hasPermission(signer.pubKey, ConcordPermissions.MANAGE_ROLES)) {
            throw IllegalStateException("This account cannot manage community roles")
        }
        val actorRank = authority.rank(signer.pubKey)
            ?: throw IllegalStateException("The actor has no valid role rank")
        require(actorRank < position) { "A role cannot be created at or above the actor's authority" }
        requireDelegableRolePermissions(authority, signer.pubKey, permissions)

        val roleIdBytes = RandomInstance.bytes(32)
        val roleId = roleIdBytes.toHexKey()
        val role = RoleEntity(
            roleId = roleId,
            name = name.trim(),
            position = position,
            permissions = permissions.toWire(),
            scope = RoleScope(kind = "server"),
        )
        val content = ConcordJson.instance.encodeToString(RoleEntity.serializer(), role)
        publishControlEdition(
            signer = signer,
            membership = membership,
            control = control,
            entityKind = ControlEntityKind.ROLE,
            entityId = roleIdBytes,
            content = content,
            permission = ConcordPermissions.MANAGE_ROLES,
        )
        return roleId
    }

    suspend fun grantRole(
        signer: PostillaQuartzSigner,
        communityId: String,
        memberPubKey: String,
        roleId: String,
    ): String {
        val membership = loadLocalMemberships(signer).firstOrNull { it.id == communityId }
            ?: throw IllegalArgumentException("Community membership is not available")
        val control = loadControlPlaneSnapshot(membership, requireAllResponders = true)
        if (control.state.isRolesV2) {
            val current = control.state.authority.rankedRoster()?.rolesOf(memberPubKey)?.map { it.roleId }.orEmpty()
            require(roleId.lowercase() !in current) { "The member already holds this role" }
            return setMemberRoles(signer, communityId, memberPubKey, current + roleId.lowercase())
        }
        requireLegacyRoleActions(control.state)
        val current = control.state.authority.rolesOf(memberPubKey.lowercase())
        require(roleId.lowercase() !in current) { "The member already holds this role" }
        return setMemberRoles(signer, communityId, memberPubKey, current + roleId.lowercase())
    }

    suspend fun revokeRole(
        signer: PostillaQuartzSigner,
        communityId: String,
        memberPubKey: String,
        roleId: String,
    ): String {
        val membership = loadLocalMemberships(signer).firstOrNull { it.id == communityId }
            ?: throw IllegalArgumentException("Community membership is not available")
        val control = loadControlPlaneSnapshot(membership, requireAllResponders = true)
        if (control.state.isRolesV2) {
            val current = control.state.authority.rankedRoster()?.rolesOf(memberPubKey)?.map { it.roleId }.orEmpty()
            require(roleId.lowercase() in current) { "The member does not hold this role" }
            return setMemberRoles(signer, communityId, memberPubKey, current.filterNot { it == roleId.lowercase() })
        }
        requireLegacyRoleActions(control.state)
        val current = control.state.authority.rolesOf(memberPubKey.lowercase())
        require(roleId.lowercase() in current) { "The member does not hold this role" }
        return setMemberRoles(signer, communityId, memberPubKey, current.filterNot { it == roleId.lowercase() })
    }

    suspend fun setMemberRoles(
        signer: PostillaQuartzSigner,
        communityId: String,
        memberPubKey: String,
        requestedRoleIds: Collection<String>,
    ): String {
        val target = memberPubKey.lowercase()
        val targetBytes = target.hexToByteArray()
        require(targetBytes.size == 32) { "Member public key must be 32 bytes" }
        require(target != signer.pubKey.lowercase()) { "An account cannot edit its own role grant" }
        val membership = loadLocalMemberships(signer).firstOrNull { it.id == communityId }
            ?: throw IllegalArgumentException("Community membership is not available")
        val control = loadControlPlaneSnapshot(membership, requireAllResponders = true)
        if (control.state.isRolesV2) {
            return setRolesV2MemberRoles(signer, membership, control, target, requestedRoleIds)
        }
        requireLegacyRoleActions(control.state)
        val authority = control.state.authority
        if (!authority.hasPermission(signer.pubKey, ConcordPermissions.MANAGE_ROLES)) {
            throw IllegalStateException("This account cannot manage community roles")
        }
        require(authority.canActOn(signer.pubKey, target, ConcordPermissions.MANAGE_ROLES)) {
            "The actor cannot manage this member's roles"
        }
        require(!authority.isBanned(target)) { "A banned member cannot receive roles" }

        val roleIds = requestedRoleIds.map { it.lowercase() }.distinct().sorted()
        val actorRank = authority.rank(signer.pubKey)
            ?: throw IllegalStateException("The actor has no valid role rank")
        val roles = authority.roles()
        roleIds.forEach { id ->
            val role = roles[id] ?: throw IllegalArgumentException("Role $id does not exist")
            require(!role.deleted && role.isWellFormedAt(id)) { "Role $id is not assignable" }
            require(actorRank < role.position) { "The actor does not outrank role $id" }
        }
        val currentRoleIds = authority.rolesOf(target).distinct().sorted()
        if (roleIds == currentRoleIds) return ""
        val removedPrivateAccess = currentRoleIds
            .filterNot { it in roleIds }
            .mapNotNull { id ->
                val role = roles[id] ?: return@mapNotNull null
                role.scope?.takeIf { it.kind == "channel" }?.channelId
                    ?.takeIf { control.state.channels[it.lowercase()]?.let { channel ->
                        channel.definition.private && !channel.definition.deleted
                    } == true }
            }
            .distinct()
        require(removedPrivateAccess.isEmpty()) {
            "Private channel access cannot be removed until the channel key is rotated. No Grant was published."
        }
        val gainedPrivateAccess = roleIds
            .filterNot { it in currentRoleIds }
            .mapNotNull { id ->
                val role = roles[id] ?: return@mapNotNull null
                role.scope?.takeIf { it.kind == "channel" }?.channelId
                    ?.takeIf { control.state.channels[it.lowercase()]?.let { channel ->
                        channel.definition.private && !channel.definition.deleted
                    } == true }
            }
            .distinct()
        val hadStaffPermissions = currentRoleIds.any { id ->
            roles[id]?.permissionBits()?.hasAny(ConcordPermissions.STAFF_BITS) == true
        }
        val effective = roleIds.fold(ConcordPermissions.NONE) { acc, id ->
            roles[id]?.let { acc union it.permissionBits() } ?: acc
        }
        val controlWrap = if (effective.hasAny(ConcordPermissions.STAFF_BITS)) {
            val root = membership.controlRoot
                ?: throw IllegalStateException("The current Control Plane write key is unavailable")
            ControlRootWrap.build(signer, target, membership.rootEpoch, root.hexToByteArray())
        } else {
            null
        }
        val grant = GrantEntity(member = target, roleIds = roleIds, controlWrap = controlWrap)
        val grantId = ConcordKeyDerivation.grantCoordinate(membership.id.hexToByteArray(), targetBytes)
        val heads = ConcordCommunityState.authorizedHeads(
            control.editions,
            membership.id.hexToByteArray(),
            membership.owner,
        )
        val head = heads[grantId.toHexKey()]
        val content = ConcordJson.encodePreserving(
            GrantEntity.serializer(),
            grant,
            head?.known?.content,
        )
        val event = if (hadStaffPermissions && !effective.hasAny(ConcordPermissions.STAFF_BITS)) {
            val pendingEdition = buildControlEdition(
                signer, membership, control, ControlEntityKind.GRANT, grantId, content,
                ConcordPermissions.MANAGE_ROLES, head?.version, head?.hashHex,
            )
            refoundCommunity(signer, communityId, extraControlEvents = listOf(pendingEdition))
            pendingEdition
        } else {
            publishControlEdition(
                signer, membership, control, ControlEntityKind.GRANT, grantId, content,
                ConcordPermissions.MANAGE_ROLES, head?.version, head?.hashHex,
            )
        }
        if (gainedPrivateAccess.isNotEmpty()) {
            try {
                val verifiedAfterGrant = loadControlPlaneSnapshot(membership, requireAllResponders = true)
                val notYetEntitled = gainedPrivateAccess.filterNot { channelId ->
                    ConcordInviteVend.isEntitled(verifiedAfterGrant.state.authority, target, channelId)
                }
                check(notYetEntitled.isEmpty()) {
                    "The Grant was published, but the complete Control Plane does not yet show access to every new private channel. Refresh and retry the Direct Invite."
                }
                sendDirectInvite(signer, communityId, target)
            } catch (error: Exception) {
                throw IllegalStateException(
                    "The role Grant was published, but private channel key delivery was not confirmed. Retry the Direct Invite; the member may not yet be able to read those channels.",
                    error,
                )
            }
        }
        return event.id
    }

    suspend fun updateRole(
        signer: PostillaQuartzSigner,
        communityId: String,
        roleId: String,
        name: String,
        position: Long,
        permissions: ConcordPermissions,
    ): String {
        val normalizedName = name.trim()
        require(normalizedName.isNotBlank() && normalizedName.encodeToByteArray().size <= 64) {
            "Role name must be 1–64 UTF-8 bytes"
        }
        require(position >= 1L) { "Role position 0 is reserved for the owner" }
        val roleIdBytes = roleId.hexToByteArray()
        require(roleIdBytes.size == 32) { "Role id must be 32 bytes" }
        val membership = loadLocalMemberships(signer).firstOrNull { it.id == communityId }
            ?: throw IllegalArgumentException("Community membership is not available")
        val control = loadControlPlaneSnapshot(membership, requireAllResponders = true)
        if (control.state.isRolesV2) {
            return updateRolesV2Role(signer, membership, control, roleIdBytes.toHexKey(), normalizedName, position, permissions)
        }
        requireLegacyRoleActions(control.state)
        val authority = control.state.authority
        if (!authority.hasPermission(signer.pubKey, ConcordPermissions.MANAGE_ROLES)) {
            throw IllegalStateException("This account cannot manage community roles")
        }
        val roleIdHex = roleIdBytes.toHexKey()
        val current = authority.roles()[roleIdHex]
            ?: throw IllegalArgumentException("Role does not exist")
        val actorRank = authority.rank(signer.pubKey)
            ?: throw IllegalStateException("The actor has no valid role rank")
        require(actorRank < current.position && actorRank < position) {
            "The actor must strictly outrank the role's current and new position"
        }
        requireDelegableRolePermissions(authority, signer.pubKey, permissions)
        val hadStaffPermissions = current.permissionBits().hasAny(ConcordPermissions.STAFF_BITS)
        val willHaveStaffPermissions = permissions.hasAny(ConcordPermissions.STAFF_BITS)
        require(hadStaffPermissions == willHaveStaffPermissions) {
            "Changing STAFF privileges on an existing role requires coordinated Grant updates and Control Root delivery; no role change was published."
        }
        val updated = current.copy(
            name = normalizedName,
            position = position,
            permissions = permissions.toWire(),
        )
        val heads = ConcordCommunityState.authorizedHeads(
            control.editions,
            membership.id.hexToByteArray(),
            membership.owner,
        )
        val head = heads[roleIdHex]
        val content = ConcordJson.encodePreserving(
            RoleEntity.serializer(),
            updated,
            head?.known?.content,
        )
        val event = publishControlEdition(
            signer,
            membership,
            control,
            ControlEntityKind.ROLE,
            roleIdBytes,
            content,
            ConcordPermissions.MANAGE_ROLES,
            head?.version,
            head?.hashHex,
        )
        return event.id
    }

    suspend fun deleteRole(
        signer: PostillaQuartzSigner,
        communityId: String,
        roleId: String,
    ): String {
        val roleIdBytes = roleId.hexToByteArray()
        require(roleIdBytes.size == 32) { "Role id must be 32 bytes" }
        val membership = loadLocalMemberships(signer).firstOrNull { it.id == communityId }
            ?: throw IllegalArgumentException("Community membership is not available")
        val control = loadControlPlaneSnapshot(membership, requireAllResponders = true)
        if (control.state.isRolesV2) {
            return deleteRolesV2Role(signer, membership, control, roleIdBytes.toHexKey())
        }
        requireLegacyRoleActions(control.state)
        if (!control.state.authority.hasPermission(signer.pubKey, ConcordPermissions.MANAGE_ROLES)) {
            throw IllegalStateException("This account cannot manage community roles")
        }
        val roleIdHex = roleIdBytes.toHexKey()
        val current = control.state.authority.roles()[roleIdHex]
            ?: throw IllegalArgumentException("Role does not exist")
        val scopedChannelId = current.scope?.takeIf { it.kind == "channel" }?.channelId
        if (scopedChannelId != null &&
            control.state.channels[scopedChannelId.lowercase()]?.let {
                it.definition.private && !it.definition.deleted
            } == true
        ) {
            throw IllegalStateException(
                "This role grants access to a private channel. Rotate that channel key before deleting the role; no change was published.",
            )
        }
        val actorRank = control.state.authority.rank(signer.pubKey)
            ?: throw IllegalStateException("The actor has no valid role rank")
        require(actorRank < current.position) { "The actor does not outrank this role" }
        val deleted = current.copy(deleted = true)
        val heads = ConcordCommunityState.authorizedHeads(
            control.editions,
            membership.id.hexToByteArray(),
            membership.owner,
        )
        val head = heads[roleIdHex]
        val content = ConcordJson.encodePreserving(
            RoleEntity.serializer(),
            deleted,
            head?.known?.content,
        )
        val event = if (current.permissionBits().hasAny(ConcordPermissions.STAFF_BITS)) {
            val pendingEdition = buildControlEdition(
                signer, membership, control, ControlEntityKind.ROLE, roleIdBytes, content,
                ConcordPermissions.MANAGE_ROLES, head?.version, head?.hashHex,
            )
            refoundCommunity(signer, communityId, extraControlEvents = listOf(pendingEdition))
            pendingEdition
        } else {
            publishControlEdition(
                signer, membership, control, ControlEntityKind.ROLE, roleIdBytes, content,
                ConcordPermissions.MANAGE_ROLES, head?.version, head?.hashHex,
            )
        }
        return event.id
    }

    private suspend fun buildControlEdition(
        signer: PostillaQuartzSigner,
        membership: ConcordCommunityListEntry,
        control: ControlPlaneSnapshot,
        entityKind: ControlEntityKind,
        entityId: ByteArray,
        content: String,
        permission: Int,
        previousVersion: Long? = null,
        previousHash: String? = null,
    ): NostrEvent {
        if (!control.state.authority.hasPermission(signer.pubKey, permission)) {
            throw IllegalStateException("This account is not authorized for this Control Plane action")
        }
        require(control.keys.signer != null) { "The Control Plane write key is unavailable on this device" }
        val heads = ConcordCommunityState.authorizedHeads(
            control.editions,
            membership.id.hexToByteArray(),
            membership.owner,
        )
        val head = heads[entityId.toHexKey()]
        val version = (previousVersion ?: head?.version ?: 0L) + 1L
        val prevHash = previousHash?.hexToByteArray() ?: head?.hashHex?.hexToByteArray()
        val rumor = ControlEditionBuilder.rumor(
            authorPubKey = signer.pubKey,
            entityKind = entityKind,
            entityId = entityId,
            version = version,
            prevHash = prevHash,
            content = content,
            createdAt = System.currentTimeMillis() / 1000,
            authorityCitation = control.state.authority.citationFor(signer.pubKey),
        )
        return NostrEvent.fromJson(
            ConcordStreamEnvelope.wrap(
                rumor = rumor,
                keys = control.keys,
                authorSigner = signer,
                encrypted = false,
            ).toJson(),
        )
    }

    private suspend fun publishControlEdition(
        signer: PostillaQuartzSigner,
        membership: ConcordCommunityListEntry,
        control: ControlPlaneSnapshot,
        entityKind: ControlEntityKind,
        entityId: ByteArray,
        content: String,
        permission: Int,
        previousVersion: Long? = null,
        previousHash: String? = null,
    ): NostrEvent {
        val event = buildControlEdition(
            signer, membership, control, entityKind, entityId, content, permission, previousVersion, previousHash,
        )
        withContext(Dispatchers.IO) { journalEvents(membership.id, listOf(event)) }
        if (!publishConfirmed(event, membership.relays)) {
            throw IllegalStateException("Control Plane edition was not confirmed by a community relay")
        }
        return event
    }



    data class KickResult(val communityId: String, val targetPubKey: String, val didRefound: Boolean, val newEpoch: Long)

    suspend fun kickAndRotate(
        signer: PostillaQuartzSigner,
        communityId: String,
        targetPubKey: String,
    ): KickResult {
        val target = targetPubKey.lowercase()
        require(target.hexToByteArray().size == 32) { "Target public key must be 32 bytes" }
        val membership = loadLocalMemberships(signer).firstOrNull { it.id == communityId }
            ?: throw IllegalArgumentException("Community membership is not available")
        val control = loadControlPlaneSnapshot(membership, requireAllResponders = true)
        val authority = control.state.authority
        if (!authority.canActOn(signer.pubKey, target, ConcordPermissions.KICK)) {
            throw IllegalStateException("This account cannot kick the selected member")
        }
        if (control.state.isPublic) {
            val publicKickKey = ConcordKeyDerivation.guestbookPlaneKey(
                communityRoot = membership.root.hexToByteArray(),
                communityId = communityId.hexToByteArray(),
                epoch = membership.rootEpoch,
            )
            val publicKick = Guestbook.kick(
                actorPubKey = signer.pubKey,
                target = target,
                createdAt = System.currentTimeMillis() / 1000,
                citation = authority.citationFor(signer.pubKey),
            )
            val publicKickEvent = NostrEvent.fromJson(
                ConcordStreamEnvelope.wrap(publicKick, publicKickKey, signer, encrypted = true).toJson(),
            )
            withContext(Dispatchers.IO) { journalEvents(communityId, listOf(publicKickEvent)) }
            if (!publishConfirmed(publicKickEvent, membership.relays)) {
                throw IllegalStateException("The public-community Kick was not confirmed")
            }
            return KickResult(communityId, target, false, membership.rootEpoch)
        }
        if (!authority.hasPermission(signer.pubKey, ConcordPermissions.BAN)) {
            throw IllegalStateException("A private-community kick requires authority to rotate the community root")
        }
        val guestbookKey = ConcordKeyDerivation.guestbookPlaneKey(
            communityRoot = membership.root.hexToByteArray(),
            communityId = communityId.hexToByteArray(),
            epoch = membership.rootEpoch,
        )
        val kickRumor = Guestbook.kick(
            actorPubKey = signer.pubKey,
            target = target,
            createdAt = System.currentTimeMillis() / 1000,
            citation = authority.citationFor(signer.pubKey),
        )
        val kickEntry = Guestbook.parse(kickRumor)
            ?: throw IllegalStateException("Quartz could not parse the generated Kick entry")
        val kickEvent = NostrEvent.fromJson(
            ConcordStreamEnvelope.wrap(
                rumor = kickRumor,
                stream = guestbookKey,
                authorSigner = signer,
                encrypted = true,
            ).toJson(),
        )
        val result = refoundCommunity(
            signer = signer,
            communityId = communityId,
            excludedMembers = setOf(target),
            extraGuestbookEntries = listOf(kickEntry),
            extraGuestbookEvents = listOf(kickEvent),
        )
        return KickResult(communityId, target, true, result.newEpoch)
    }

    data class BanResult(val communityId: String, val targetPubKey: String, val didRefound: Boolean, val epoch: Long)

    suspend fun banAndRotate(
        signer: PostillaQuartzSigner,
        communityId: String,
        targetPubKey: String,
    ): BanResult {
        val target = targetPubKey.lowercase()
        require(target.hexToByteArray().size == 32) { "Target public key must be 32 bytes" }
        val membership = loadLocalMemberships(signer).firstOrNull { it.id == communityId }
            ?: throw IllegalArgumentException("Community membership is not available")
        val control = loadControlPlaneSnapshot(membership, requireAllResponders = true)
        val authority = control.state.authority
        if (!authority.canActOn(signer.pubKey, target, ConcordPermissions.BAN)) {
            throw IllegalStateException("This account cannot ban the selected member")
        }
        val banned = (authority.bannedMembers() + target).distinct().sorted()
        val banlistId = ConcordKeyDerivation.banlistCoordinate(communityId.hexToByteArray())
        val heads = ConcordCommunityState.authorizedHeads(
            control.editions,
            communityId.hexToByteArray(),
            membership.owner,
        )
        val head = heads[banlistId.toHexKey()]
        val content = ConcordJson.instance.encodeToString(ListSerializer(String.serializer()), banned)
        val requiresRefounding = control.state.banRequiresRefounding(listOf(target))
        if (!requiresRefounding) {
            publishControlEdition(
                signer, membership, control, ControlEntityKind.BANLIST, banlistId, content,
                ConcordPermissions.BAN, head?.version, head?.hashHex,
            )
            return BanResult(communityId, target, false, membership.rootEpoch)
        }
        val banEvent = buildControlEdition(
            signer, membership, control, ControlEntityKind.BANLIST, banlistId, content,
            ConcordPermissions.BAN, head?.version, head?.hashHex,
        )
        val result = refoundCommunity(
            signer = signer,
            communityId = communityId,
            excludedMembers = setOf(target),
            extraControlEvents = listOf(banEvent),
        )
        return BanResult(communityId, target, true, result.newEpoch)
    }

    data class RefoundingResult(
        val communityId: String,
        val newEpoch: Long,
        val retainedMembers: Int,
        val refreshedInvites: Int,
        val publishedEvents: Int,
    )

    /**
     * Whole-community CORD-06 refounding. The pending root/control-root pair and every
     * signed event are persisted before the first publish, so retries reuse identical
     * keys and event IDs. Communities with private channels are refused until their
     * independently keyed channels can be rekeyed in the same operation.
     */
    suspend fun refoundCommunity(
        signer: PostillaQuartzSigner,
        communityId: String,
        excludedMembers: Set<String> = emptySet(),
        extraGuestbookEntries: List<GuestbookEntry> = emptyList(),
        extraGuestbookEvents: List<NostrEvent> = emptyList(),
        extraControlEvents: List<NostrEvent> = emptyList(),
    ): RefoundingResult {
        val membership = loadLocalMemberships(signer).firstOrNull { it.id == communityId }
            ?: throw IllegalArgumentException("Community membership is not available")
        val pending = pendingRefounding(communityId)
        val savedPlanReady = prefs.getBoolean(refoundingReadyKey(communityId), false)
        if (savedPlanReady && pending == null) {
            throw IllegalStateException("A refounding plan exists but its encrypted key reservation is unavailable; refusing to create a second rotation")
        }
        if (pending != null && savedPlanReady) {
            if (excludedMembers.isNotEmpty() || extraGuestbookEvents.isNotEmpty() || extraControlEvents.isNotEmpty()) {
                throw IllegalStateException("A different refounding is already pending; resume it before starting another moderation action")
            }
            val stillOnOldEpoch = pending.matches(communityId, membership.rootEpoch, membership.root.hexToByteArray())
            val alreadyAdopted = membership.rootEpoch == pending.rootEpoch + 1 &&
                membership.root == pending.newRoot.toHexKey()
            if (!stillOnOldEpoch && !alreadyAdopted) {
                throw IllegalStateException("The community has moved to a different epoch while a refounding is pending")
            }
            val plan = loadPendingRefoundingPlan(communityId)
            publishRefoundingPlan(communityId, membership.relays, plan)
            clearPendingRefounding(communityId)
            return RefoundingResult(
                communityId = communityId,
                newEpoch = pending.rootEpoch + 1,
                retainedMembers = 0,
                refreshedInvites = plan.inviteBundles.size,
                publishedEvents = plan.all().size,
            )
        }
        if (pending != null && !pending.matches(communityId, membership.rootEpoch, membership.root.hexToByteArray())) {
            throw IllegalStateException("A pending refounding belongs to a different root epoch; refusing to start another rotation")
        }
        if (membership.privateChannels.isNotEmpty()) {
            throw IllegalStateException("This community has private channels; their independent keys must be rekeyed before a whole-community rotation")
        }
        val fetchedControl = loadControlPlaneSnapshot(membership, requireAllResponders = true)
        val control = if (extraControlEvents.isEmpty()) {
            fetchedControl
        } else {
            val fetchedIds = fetchedControl.wraps.map { it.id }.toSet()
            val extraWraps = extraControlEvents
                .filterNot { it.id in fetchedIds }
                .map { Event.fromJson(it.toJson()) }
            val extraEditions = extraWraps.map { wrap ->
                val opened = ConcordStreamEnvelope.open(wrap, fetchedControl.keys)
                ControlEdition.fromOpened(opened)
                    ?: throw IllegalStateException("A newly published Control Plane edition is not canonical")
            }
            val allEditions = fetchedControl.editions + extraEditions
            ControlPlaneSnapshot(
                keys = fetchedControl.keys,
                wraps = fetchedControl.wraps + extraWraps,
                editions = allEditions,
                state = ConcordCommunityState.fold(allEditions, membership.id.hexToByteArray(), membership.owner),
                readSummary = fetchedControl.readSummary,
            )
        }
        val authority = control.state.authority
        if (!authority.hasPermission(signer.pubKey, ConcordPermissions.BAN)) {
            throw IllegalStateException("This account is not authorized to refound the community")
        }
        if (membership.controlRoot == null) {
            throw IllegalStateException("The current Control Plane write key is unavailable on this device")
        }
        if (control.state.hasForeignLiveLinks(signer.pubKey)) {
            throw IllegalStateException("Other members still own live invite links; those creators must refresh or revoke their links before this community can rotate")
        }

        val nowSecs = System.currentTimeMillis() / 1000
        val guestbookKey = ConcordKeyDerivation.guestbookPlaneKey(
            communityRoot = membership.root.hexToByteArray(),
            communityId = membership.id.hexToByteArray(),
            epoch = membership.rootEpoch,
        )
        val guestbookEvents = fetchEventsFromRelays(
            relays = membership.relays,
            filter = Filter(
                kinds = listOf(ConcordStreamEnvelope.KIND_WRAP),
                authors = listOf(guestbookKey.publicKeyHex),
                limit = 5000,
            ),
            label = "refounding-guestbook",
            requireAllResponders = true,
        )
        val guestbookEntries = guestbookEvents.mapNotNull { event ->
            try {
                val opened = ConcordStreamEnvelope.open(Event.fromJson(event.toJson()), guestbookKey)
                Guestbook.parse(opened.rumor)
            } catch (_: Exception) {
                null
            }
        } + extraGuestbookEntries
        val coalesced = Guestbook.coalesce(guestbookEntries, System.currentTimeMillis(), authority)
        val staffMembers = authority.staffMembers().map { it.lowercase() }.toSet()
        val retained = coalesced.values
            .filter { it.action == GuestbookAction.JOIN }
            .map { it.member.lowercase() }
            .toMutableSet()
        retained += staffMembers
        retained += membership.owner.lowercase()
        retained.removeAll(excludedMembers.map { it.lowercase() }.toSet())
        require(membership.owner.lowercase() in retained) { "The owner must remain a recipient of the new root" }
        require(retained.isNotEmpty()) { "No retained members were found; refusing to rotate into an empty community" }
        val staffRecipients = staffMembers.intersect(retained)

        val reserved = ConcordRefounding.reserveKeys(
            pending = pending,
            communityId = membership.id,
            rootEpoch = membership.rootEpoch,
            priorRoot = membership.root.hexToByteArray(),
        )
        savePendingRefounding(reserved)

        val inviteList = fetchInviteList(signer)
        var updatedInviteList = inviteList
        var inviteListChanged = false
        val communityInviteEntries = inviteList.entries.filter { it.communityId == communityId }
        val liveInviteEntries = communityInviteEntries.filterNot { it.isExpired(nowSecs) }
        for (entry in communityInviteEntries.filter { it.isExpired(nowSecs) }) {
            updatedInviteList = retireInvite(updatedInviteList, entry.token, communityId)
            inviteListChanged = true
        }

        val bundleEvents = if (liveInviteEntries.isEmpty()) {
            emptyList()
        } else {
            fetchEventsFromRelays(
                relays = membership.relays,
                filter = Filter(
                    kinds = listOf(ConcordInviteBundle.KIND),
                    authors = liveInviteEntries.map { it.signerPubKeyHex() }.distinct(),
                    limit = 5000,
                ),
                label = "refounding-invites",
                requireAllResponders = true,
            ).map { Event.fromJson(it.toJson()) }
        }
        val refreshedBundles = mutableListOf<NostrEvent>()
        for (entry in liveInviteEntries) {
            val status = ConcordInviteBundle.classify(
                wraps = bundleEvents,
                linkSignerPubKey = entry.signerPubKeyHex(),
                token = entry.token.hexToByteArray(),
            )
            when (status) {
                is InviteBundleStatus.Live -> {
                    val oldInvite = status.invite
                    if (oldInvite.communityId != communityId ||
                        oldInvite.communityRoot != membership.root ||
                        oldInvite.rootEpoch != membership.rootEpoch
                    ) {
                        throw IllegalStateException("An active invite bundle does not match the current community epoch")
                    }
                    val updatedInvite = oldInvite.copy(
                        communityRoot = reserved.newRoot.toHexKey(),
                        rootEpoch = membership.rootEpoch + 1,
                        controlPk = ControlPlaneKeys.forStaff(
                            reserved.newRoot,
                            communityId.hexToByteArray(),
                            membership.rootEpoch + 1,
                            reserved.newControlRoot,
                        ).address,
                    )
                    refreshedBundles += NostrEvent.fromJson(
                        ConcordInviteBundle.build(
                            linkSignerPrivKey = entry.signerSk.hexToByteArray(),
                            token = entry.token.hexToByteArray(),
                            invite = updatedInvite,
                            createdAt = nowSecs,
                        ).toJson(),
                    )
                }
                is InviteBundleStatus.Expired, InviteBundleStatus.Revoked -> {
                    updatedInviteList = retireInvite(updatedInviteList, entry.token, communityId)
                    inviteListChanged = true
                }
                InviteBundleStatus.Absent, InviteBundleStatus.Unreadable ->
                    throw IllegalStateException("An active invite bundle cannot be verified; refusing an incomplete rotation")
            }
        }

        val refounding = ConcordRefounding.build(
            rotatorSigner = signer,
            communityId = communityId.hexToByteArray(),
            priorRoot = membership.root.hexToByteArray(),
            newRoot = reserved.newRoot,
            newControlRoot = reserved.newControlRoot,
            rootEpoch = membership.rootEpoch,
            priorControlWraps = control.wraps,
            priorControlKeys = control.keys,
            recipientsXOnly = retained.sorted(),
            staffXOnly = staffRecipients,
            createdAt = nowSecs,
            ownerPubKey = membership.owner,
            authority = ConcordRotationAuthority.citationFor(
                communityId,
                signer.pubKey,
                membership.owner,
                ConcordRotationAuthority.headsOf(control.editions, communityId, membership.owner),
            ),
            mustCarry = ConcordRefounding.headVersions(control.editions, communityId.hexToByteArray(), membership.owner),
        )

        val newEditions = refounding.controlWraps.map { wrap ->
            val opened = ConcordStreamEnvelope.open(wrap, refounding.newControlKeys)
            ControlEdition.fromOpened(opened)
                ?: throw IllegalStateException("The compacted Control Plane contains a non-canonical edition")
        }
        val newState = ConcordCommunityState.fold(newEditions, communityId.hexToByteArray(), membership.owner)
        val newHeads = ConcordRotationAuthority.headsOf(newEditions, communityId, membership.owner)
        val publishedLinks = newState.inviteRegistries[signer.pubKey.lowercase()].orEmpty()
        val liveLinks = ConcordInviteRegistry.nextLinks(
            published = publishedLinks,
            list = updatedInviteList,
            communityIdHex = communityId,
            nowSecs = nowSecs,
        )
        val registryEvents = if (liveLinks != publishedLinks) {
            if (!newState.authority.hasPermission(signer.pubKey, ConcordPermissions.CREATE_INVITE)) {
                throw IllegalStateException("This account cannot refresh the community Invite Registry during rotation")
            }
            val coordinate = ConcordInviteRegistry.coordinateHex(communityId.hexToByteArray(), signer.pubKey)
            val head = newHeads[coordinate]
            val rumor = ConcordInviteRegistry.rumor(
                creator = signer.pubKey,
                communityId = communityId.hexToByteArray(),
                linkSigners = liveLinks,
                version = (head?.version ?: 0L) + 1L,
                prevHash = head?.hashHex?.hexToByteArray(),
                createdAt = nowSecs,
                authorityCitation = ConcordRotationAuthority.citationFor(communityId, signer.pubKey, membership.owner, newHeads),
            )
            listOf(
                NostrEvent.fromJson(
                    ConcordStreamEnvelope.wrap(
                        rumor = rumor,
                        keys = refounding.newControlKeys,
                        authorSigner = signer,
                        encrypted = false,
                        createdAt = nowSecs,
                    ).toJson(),
                ),
            )
        } else {
            emptyList()
        }
        val inviteListEvents = if (inviteListChanged) {
            listOf(NostrEvent.fromJson(ConcordInviteListEvent.create(signer, updatedInviteList, nowSecs).toJson()))
        } else {
            emptyList()
        }

        val currentList = fetchCommunityList(signer)
        requireSafeCommunityListForWrite(currentList, "Community refounding")
        val oldEntry = currentList.document.entries.firstOrNull { it.id == communityId }
            ?: throw IllegalStateException("The owner membership disappeared during refounding")
        require(oldEntry.root == membership.root && oldEntry.rootEpoch == membership.rootEpoch) {
            "The membership changed while refounding; refusing to publish against a stale epoch"
        }
        val heldRoots = (oldEntry.heldRoots + HeldRoot(
            epoch = oldEntry.rootEpoch,
            key = oldEntry.root,
            controlPk = oldEntry.controlPk,
            controlRoot = oldEntry.controlRoot,
        )).distinctBy { it.epoch to it.key.lowercase() }
        val rotatedEntry = ConcordCommunityListEntry(
            id = oldEntry.id,
            owner = oldEntry.owner,
            ownerSalt = oldEntry.ownerSalt,
            root = reserved.newRoot.toHexKey(),
            rootEpoch = refounding.newEpoch,
            controlPk = refounding.newControlKeys.address,
            controlRoot = reserved.newControlRoot.toHexKey(),
            privateChannels = oldEntry.privateChannels,
            relays = oldEntry.relays,
            name = oldEntry.name,
            addedAt = oldEntry.addedAt,
            heldRoots = heldRoots,
            inviteRef = oldEntry.inviteRef,
            excludedAtEpoch = oldEntry.excludedAtEpoch,
            residue = oldEntry.residue,
        )
        val mergedEntries = ConcordCommunityList.merge(currentList.document.entries, listOf(rotatedEntry))
        val nextDocument = ConcordCommunityList.encodeInternal(mergedEntries, currentList.document.residue)
        val listWrites = planCommunityListWrites(currentList, nextDocument, nowSecs)
        val membershipEvents = listWrites.map { write ->
            NostrEvent.fromJson(
                ConcordCommunityListFragmentEvent.create(
                    signer = signer,
                    index = write.index,
                    plaintext = write.plaintext,
                    createdAt = write.createdAt,
                ).toJson(),
            )
        }
        val newGuestbookKey = ConcordKeyDerivation.guestbookPlaneKey(
            communityRoot = reserved.newRoot,
            communityId = communityId.hexToByteArray(),
            epoch = refounding.newEpoch,
        )
        val newOwnerJoin = Guestbook.join(signer.pubKey, nowSecs)
        val guestbookEventsNew = listOf(
            NostrEvent.fromJson(
                ConcordStreamEnvelope.wrap(
                    rumor = newOwnerJoin,
                    stream = newGuestbookKey,
                    authorSigner = signer,
                    encrypted = true,
                    createdAt = nowSecs,
                ).toJson(),
            ),
        )
        val eventPlan = RefoundingPlanEvents(
            control = refounding.controlWraps.map { NostrEvent.fromJson(it.toJson()) },
            inviteList = inviteListEvents,
            registry = registryEvents,
            inviteBundles = refreshedBundles,
            kick = extraGuestbookEvents,
            rekey = refounding.rekeyWraps.map { NostrEvent.fromJson(it.toJson()) },
            membership = membershipEvents,
            guestbook = guestbookEventsNew,
        )
        storePendingRefoundingPlan(communityId, eventPlan)
        publishRefoundingPlan(communityId, membership.relays, eventPlan)
        clearPendingRefounding(communityId)
        return RefoundingResult(
            communityId = communityId,
            newEpoch = refounding.newEpoch,
            retainedMembers = retained.size,
            refreshedInvites = refreshedBundles.size,
            publishedEvents = eventPlan.all().size,
        )
    }

    data class CommunityMember(
        val pubkey: String,
        val joinedAt: Long,
        val inviteCreator: String? = null,
        val inviteLabel: String? = null,
        val lastSeenAt: Long = joinedAt * 1000L,
        val roleIds: List<String> = emptyList(),
        val roleNames: List<String> = emptyList(),
        val canKick: Boolean = false,
        val canBan: Boolean = false,
        val requiresRefounding: Boolean = false,
    )

    private data class ControlPlaneSnapshot(
        val keys: ControlPlaneKeys,
        val wraps: List<Event>,
        val editions: List<ControlEdition>,
        val state: ConcordCommunityState,
        val readSummary: RelayReadSummary,
    )

    private suspend fun loadControlPlaneSnapshot(
        membership: ConcordCommunityListEntry,
        requireAllResponders: Boolean = true,
    ): ControlPlaneSnapshot {
        val keys = ControlPlaneKeys.of(
            communityRoot = membership.root.hexToByteArray(),
            communityId = membership.id.hexToByteArray(),
            epoch = membership.rootEpoch,
            controlPk = membership.controlPk,
            controlRoot = membership.controlRoot,
        )
        // Only register a key that derives the queried Control Plane address.
        // Split member read keys are not write/auth credentials for a separate signer.
        val controlAuthKey = when {
            keys.signer?.publicKeyHex?.equals(keys.address, ignoreCase = true) == true -> keys.signer
            keys.readKey.publicKeyHex.equals(keys.address, ignoreCase = true) -> keys.readKey
            else -> null
        }
        if (controlAuthKey != null) {
            relayPool.registerConcordStreamKeys(listOf(controlAuthKey), membership.relays)
        }
        val relayRead = fetchEventsFromRelaysDetailed(
            relays = membership.relays,
            filter = Filter(
                kinds = listOf(ConcordStreamEnvelope.KIND_WRAP),
                authors = listOf(keys.address),
                limit = 5000,
            ),
            label = "control-state",
            requireAllResponders = requireAllResponders,
        )
        val wraps = relayRead.events.map { Event.fromJson(it.toJson()) }
        val editions = wraps.map { wrap ->
            val opened = ConcordStreamEnvelope.open(wrap, keys)
            ControlEdition.fromOpened(opened)
                ?: throw IllegalStateException("A Control Plane edition is not canonical")
        }
        if (editions.isEmpty()) throw IllegalStateException("The community Control Plane is empty or unavailable")
        val state = ConcordCommunityState.fold(editions, membership.id.hexToByteArray(), membership.owner)
        return ControlPlaneSnapshot(keys, wraps, editions, state, relayRead.summary)
    }

    /** Downloads and decrypts the community icon using its member-sealed CORD-02 pointer. */
    suspend fun loadCommunityIcon(membership: ConcordCommunityListEntry): ByteArray? {
        val pointer = loadControlPlaneSnapshot(membership, requireAllResponders = false).state.metadata?.icon
            ?: return null
        require(pointer.url.startsWith("https://", ignoreCase = true)) {
            "Community icon URL must use HTTPS"
        }
        val ciphertext = withContext(Dispatchers.IO) {
            val connection = java.net.URL(pointer.url).openConnection()
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000
            connection.getInputStream().use { it.readBytes() }
        }
        return if (pointer.isResolvable()) {
            pointer.decryptOrNull(ciphertext)
                ?: throw IllegalStateException("Community icon failed its decryption or integrity check")
        } else {
            ciphertext
        }
    }

    data class CommunityChannel(
        val id: String,
        val name: String,
        val isPrivate: Boolean,
        /** Null means the membership does not currently hold this private channel key. */
        val privateKeyHex: String? = null,
        val privateChannelEpoch: Long? = null,
        val readPlanes: List<ConcordChatReadPlane> = emptyList(),
        /** True when a live CORD-03 writer window restricts publishing to delivered writer keys. */
        val writeRestricted: Boolean = false,
        /** True when @everyone lacks SEND_MESSAGES, ADD_REACTIONS and CONNECT in Roles v2. */
        val isLocked: Boolean = false,
        val canViewChannel: Boolean = true,
        val canSendMessages: Boolean = true,
        val canAttachFiles: Boolean = true,
        val canAddReactions: Boolean = true,
        val canConnect: Boolean = true,
        val canManageChannel: Boolean = false,
        val canManageMessages: Boolean = false,
        /** Secret-bearing credential held only in memory for the active account. */
        val writerCredential: ConcordChatWriterCredential? = null,
    )

    /** Loads the live Control Plane catalogue, held read planes and entitled writer keys. */
    suspend fun loadCommunityChannels(
        membership: ConcordCommunityListEntry,
        signer: PostillaQuartzSigner? = null,
    ): List<CommunityChannel> = loadCommunityChannelSnapshot(membership, signer).channels

    /** Same snapshot with explicit relay completeness, so the UI never mistakes partial data for a full catalogue. */
    suspend fun loadCommunityChannelSnapshot(
        membership: ConcordCommunityListEntry,
        signer: PostillaQuartzSigner? = null,
        requireAllResponders: Boolean = false,
    ): CommunityChannelSnapshot {
        val control = loadControlPlaneSnapshot(membership, requireAllResponders = requireAllResponders)
        val heldRootsByEpoch = buildMap<Long, String> {
            membership.heldRoots.forEach { held -> put(held.epoch, held.key) }
            put(membership.rootEpoch, membership.root)
        }
        val accessPlanes = if (signer == null) emptyMap() else control.state.writers
            .mapNotNull { (channelId, ledger) ->
                val live = ledger.live ?: return@mapNotNull null
                // A live writer entry without a root epoch cannot define a valid access plane.
                val rootEpoch = live.root ?: return@mapNotNull null
                val root = heldRootsByEpoch[rootEpoch] ?: return@mapNotNull null
                channelId to ConcordChannelAccess.plane(
                    root.hexToByteArray(),
                    channelId.hexToByteArray(),
                    rootEpoch,
                )
            }
            .toMap()
        val accessTargets = membership.relays.distinct()
        if (accessPlanes.isNotEmpty()) {
            relayPool.registerConcordStreamKeys(accessPlanes.values, accessTargets)
        }
        val accessRead = if (accessPlanes.isEmpty() || accessTargets.isEmpty()) {
            null
        } else {
            fetchEventsFromRelaysDetailed(
                relays = accessTargets,
                filter = Filter(
                    kinds = listOf(ConcordStreamEnvelope.KIND_WRAP),
                    authors = accessPlanes.values.map { it.publicKeyHex }.distinct(),
                    limit = 5000,
                ),
                label = "writer-access-${membership.id.take(12)}",
                requireAllResponders = requireAllResponders,
            )
        }
        val accessEventsByAddress = accessRead?.events?.groupBy { it.pubkey.lowercase() }.orEmpty()
        val channels = control.state.channels.values
            .map { channel ->
                val channelId = channel.channelIdHex
                val privateEntry = if (channel.definition.private) {
                    ConcordChannelKeyring.heldKey(membership, channelId)
                } else null

                val currentReadKey = if (channel.definition.private) {
                    privateEntry?.let {
                        ConcordChannelKeys.privateChannel(
                            it.key.hexToByteArray(), channelId.hexToByteArray(), it.epoch,
                        )
                    }
                } else {
                    ConcordChannelKeys.publicChannel(
                        membership.root.hexToByteArray(), channelId.hexToByteArray(), membership.rootEpoch,
                    )
                }

                // Preserve both prior public-root eras and any private-key eras this member
                // still holds. A channel can have changed visibility without erasing its history.
                val historicalReadKeys = if (channel.definition.private) {
                    ConcordChannelKeyring.historicalKeys(membership, channelId).map { old ->
                        old.epoch to ConcordChannelKeys.privateChannel(
                            old.key.hexToByteArray(), channelId.hexToByteArray(), old.epoch,
                        )
                    }
                } else {
                    val rootEras = membership.heldRoots
                        .sortedByDescending { it.epoch }
                        .take(8)
                        .map { old ->
                            old.epoch to ConcordChannelKeys.publicChannel(
                                old.key.hexToByteArray(), channelId.hexToByteArray(), old.epoch,
                            )
                        }
                    val heldPrivateEra = ConcordChannelKeyring.heldKey(membership, channelId)?.let { held ->
                        held.epoch to ConcordChannelKeys.privateChannel(
                            held.key.hexToByteArray(), channelId.hexToByteArray(), held.epoch,
                        )
                    }
                    val olderPrivateEras = ConcordChannelKeyring.historicalKeys(membership, channelId).map { old ->
                        old.epoch to ConcordChannelKeys.privateChannel(
                            old.key.hexToByteArray(), channelId.hexToByteArray(), old.epoch,
                        )
                    }
                    rootEras + listOfNotNull(heldPrivateEra) + olderPrivateEras
                }

                val readKeys = buildList {
                    if (currentReadKey != null) {
                        add((privateEntry?.epoch ?: membership.rootEpoch) to currentReadKey)
                    }
                    addAll(historicalReadKeys)
                }.distinctBy { (channelEpoch, key) -> "${channelEpoch}:${key.publicKeyHex}" }

                val writerLedger = control.state.writers[channelId]
                val rolesV2 = control.state.isRolesV2
                val isOwner = signer?.let { control.state.authority.isOwner(it.pubKey) } == true
                val signerPermissions = signer?.let {
                    control.state.channelPermissions(
                        it.pubKey,
                        channelId,
                        isPrivate = channel.definition.private,
                    )
                }
                val canViewChannel = concordRolePermissionAllowsChannelAction(
                    rolesV2, isOwner, signerPermissions?.has(ConcordPermissions.VIEW_CHANNEL) == true,
                )
                val canSendMessages = concordRolePermissionAllowsChannelAction(
                    rolesV2, isOwner, signerPermissions?.has(ConcordPermissions.SEND_MESSAGES) == true,
                )
                val canAttachFiles = concordRolePermissionAllowsChannelAction(
                    rolesV2, isOwner, signerPermissions?.has(ConcordPermissions.ATTACH_FILES) == true,
                )
                val canAddReactions = concordRolePermissionAllowsChannelAction(
                    rolesV2, isOwner, signerPermissions?.has(ConcordPermissions.ADD_REACTIONS) == true,
                )
                val canConnect = concordRolePermissionAllowsChannelAction(
                    rolesV2, isOwner, signerPermissions?.has(ConcordPermissions.CONNECT) == true,
                )
                val canManageChannel = signer != null && (
                    isOwner || signerPermissions?.has(ConcordPermissions.MANAGE_CHANNELS) == true
                    )
                val canManageMessages = signer != null && (
                    isOwner || signerPermissions?.has(ConcordPermissions.MANAGE_MESSAGES) == true
                    )
                val everyonePermissions = control.state.authority.everyonePermissions()
                val everyoneScope = control.state.syncs[channelId] ?: channelId
                val everyoneOverwrite = control.state.overwritesIn(everyoneScope)
                    .firstOrNull { it.targetKind == "everyone" }
                val effectiveEveryone = if (everyonePermissions != null && everyoneOverwrite != null) {
                    (everyonePermissions minus everyoneOverwrite.deny) union everyoneOverwrite.allow
                } else {
                    everyonePermissions
                }
                val channelLocked = rolesV2 && effectiveEveryone != null &&
                    !effectiveEveryone.has(ConcordPermissions.SEND_MESSAGES) &&
                    !effectiveEveryone.has(ConcordPermissions.ADD_REACTIONS) &&
                    !effectiveEveryone.has(ConcordPermissions.CONNECT)
                val channelWriteRestricted = concordChannelRequiresWriterPlane(
                    rolesV2 = rolesV2,
                    everyoneCanSend = effectiveEveryone?.has(ConcordPermissions.SEND_MESSAGES) == true,
                    hasLiveWriter = writerLedger?.live != null,
                )
                val channelContext = ConcordChatChannelContext(
                    communityIdHex = membership.id,
                    channelIdHex = channelId,
                    communityState = control.state,
                    channelPrivate = channel.definition.private,
                    locked = channelLocked,
                    ledger = writerLedger,
                )
                val directPlanes = readKeys.map { (channelEpoch, key) ->
                    ConcordChatReadPlane(
                        stream = key,
                        channelEpoch = channelEpoch,
                        channelContext = channelContext,
                    )
                }.distinctBy { "${it.stream.publicKeyHex}:${it.channelEpoch}" }
                relayPool.registerConcordStreamKeys(directPlanes.map { it.stream }, accessTargets)

                val writerPlanes = writerLedger?.let { ledger ->
                    ledger.windows.flatMap { window ->
                        directPlanes.map { readPlane ->
                            val writerAddress = GroupKey(
                                ByteArray(0),
                                window.writerPubKey.hexToByteArray(),
                                readPlane.stream.conversationKey,
                            )
                            ConcordChatReadPlane(
                                stream = writerAddress,
                                channelEpoch = readPlane.channelEpoch,
                                writerContext = ConcordChatWriterContext(
                                    communityIdHex = membership.id,
                                    ledger = ledger,
                                    window = window,
                                    authority = control.state.authority,
                                    channelPrivate = channel.definition.private,
                                    communityState = control.state,
                                    locked = channelLocked,
                                ),
                                channelContext = channelContext,
                            )
                        }
                    }
                }.orEmpty()
                val liveWriterContext = writerLedger?.live?.let { live ->
                    writerPlanes.firstOrNull { it.writerContext?.window?.epoch == live.epoch }?.writerContext
                }
                val writerCredential = if (
                    signer != null &&
                    writerLedger?.live != null &&
                    currentReadKey != null &&
                    liveWriterContext != null
                ) {
                    openWriterCredential(
                        signer = signer,
                        membership = membership,
                        channelIdHex = channelId,
                        channelPrivate = channel.definition.private,
                        ledger = writerLedger,
                        context = liveWriterContext,
                        accessPlane = accessPlanes[channelId],
                        accessEvents = accessPlanes[channelId]?.publicKeyHex?.lowercase()?.let(accessEventsByAddress::get).orEmpty(),
                    )
                } else {
                    null
                }
                writerCredential?.streamKey?.let {
                    relayPool.registerConcordStreamKeys(listOf(it), accessTargets)
                }

                CommunityChannel(
                    id = channelId,
                    name = channel.definition.name,
                    isPrivate = channel.definition.private,
                    privateKeyHex = privateEntry?.key,
                    privateChannelEpoch = privateEntry?.epoch,
                    readPlanes = (directPlanes + writerPlanes).distinctBy {
                        "${it.stream.publicKeyHex}:${it.channelEpoch}:${it.writerContext?.window?.epoch ?: "read"}"
                    },
                    writeRestricted = channelWriteRestricted,
                    isLocked = channelLocked,
                    canViewChannel = canViewChannel,
                    canSendMessages = canSendMessages,
                    canAttachFiles = canAttachFiles,
                    canAddReactions = canAddReactions,
                    canConnect = canConnect,
                    canManageChannel = canManageChannel,
                    canManageMessages = canManageMessages,
                    writerCredential = writerCredential,
                )
            }
            .sortedWith(compareBy<CommunityChannel> { it.name.lowercase() }.thenBy { it.id })
        return CommunityChannelSnapshot(
            channels = channels,
            controlRead = control.readSummary,
            writerAccessRead = accessRead?.summary,
            communityName = control.state.metadata?.name
                ?.takeIf { it.isNotBlank() && control.readSummary.hasQuorum },
        )
    }

    private suspend fun openWriterCredential(
        signer: PostillaQuartzSigner,
        membership: ConcordCommunityListEntry,
        channelIdHex: String,
        channelPrivate: Boolean,
        ledger: ConcordWritersLedger,
        context: ConcordChatWriterContext,
        accessPlane: GroupKey?,
        accessEvents: List<NostrEvent>,
    ): ConcordChatWriterCredential? {
        val live = ledger.live ?: return null
        val plane = accessPlane ?: return null
        val state = context.communityState ?: return null
        if (context.authority.isBanned(signer.pubKey)) return null

        val rolesV2 = state.isRolesV2
        val isOwner = context.authority.isOwner(signer.pubKey)
        val permissions = state.channelPermissions(
            signer.pubKey,
            channelIdHex,
            isPrivate = channelPrivate,
        )
        val canView = concordRolePermissionAllowsChannelAction(
            rolesV2, isOwner, permissions.has(ConcordPermissions.VIEW_CHANNEL),
        )
        val canSend = concordRolePermissionAllowsChannelAction(
            rolesV2, isOwner, permissions.has(ConcordPermissions.SEND_MESSAGES),
        )
        val canAddReactions = concordRolePermissionAllowsChannelAction(
            rolesV2, isOwner, permissions.has(ConcordPermissions.ADD_REACTIONS),
        )
        val canConnect = concordRolePermissionAllowsChannelAction(
            rolesV2, isOwner, permissions.has(ConcordPermissions.CONNECT),
        )
        val canManage = isOwner || permissions.has(ConcordPermissions.MANAGE_CHANNELS)
        val canManageMessages = context.authority.isOwner(signer.pubKey) ||
            permissions.has(ConcordPermissions.MANAGE_MESSAGES)
        if (!canView) return null

        val scopedRights =
            (if (canAddReactions) (1uL shl ConcordPermissions.ADD_REACTIONS) else 0uL) or
                (if (canConnect) (1uL shl ConcordPermissions.CONNECT) else 0uL)
        val needsFullCredential = canSend || canManage
        val needsScopedCredential = !needsFullCredential &&
            (canConnect || (context.locked && (canAddReactions || canManageMessages)))
        if (!needsFullCredential && !needsScopedCredential) return null

        val writerScopeHex = ConcordKeyDerivation.writerScope(channelIdHex.hexToByteArray()).toHexKey()
        for (outer in accessEvents.sortedByDescending { it.created_at }) {
            val opened = try {
                ConcordStreamEnvelope.open(Event.fromJson(outer.toJson()), plane)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                continue
            }
            val delivery = ConcordChannelAccess.parse(opened) ?: continue
            if (delivery.form != ConcordChannelAccess.FORM_ACCESS) continue
            if (!delivery.scopeIdHex.equals(writerScopeHex, ignoreCase = true)) continue
            if (delivery.newEpoch != live.epoch) continue
            if (delivery.writer?.equals(live.writerPubKey, ignoreCase = true) != true) continue
            if (!ConcordChannelAccess.holdsAccessBlobFor(delivery, signer.pubKey, writerScopeHex)) continue
            if (!ConcordChannelAccess.deliveryTrusted(state, channelIdHex, signer.pubKey, delivery.deliverer)) continue

            val secret = when (
                val result = ConcordChannelAccess.openAccessFor(delivery, signer, writerScopeHex)
            ) {
                is ConcordChannelAccess.AccessOpening.Key -> result.key
                ConcordChannelAccess.AccessOpening.Refused,
                ConcordChannelAccess.AccessOpening.SignerUnavailable -> continue
            }
            if (secret.size != 32) continue
            val writerKey = ConcordKeyDerivation.channelWriterKey(
                secret,
                channelIdHex.hexToByteArray(),
                live.epoch,
            )
            if (!writerKey.publicKeyHex.equals(live.writerPubKey, ignoreCase = true)) continue

            // Quartz parses full wcert entries. Scoped rcert entries are read explicitly
            // because the current Quartz parser does not expose them yet.
            val certificate = delivery.certs[signer.pubKey.lowercase()]
            val scopedTags = opened.rumor.tags.filter {
                it.getOrNull(0) == "rcert" &&
                    it.getOrNull(1)?.equals(signer.pubKey, ignoreCase = true) == true
            }
            if (scopedTags.size > 1 || (certificate != null && scopedTags.isNotEmpty())) continue
            val scopedCertificate = scopedTags.singleOrNull()?.let { tag ->
                if (tag.size != 4) return@let null
                val signature = tag.getOrNull(2)?.takeIf {
                    it.length == 128 && it.all { char ->
                        char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'
                    }
                } ?: return@let null
                val rightsText = tag.getOrNull(3) ?: return@let null
                val rights = rightsText.toULongOrNull()?.takeIf { it.toString() == rightsText }
                    ?: return@let null
                signature to rights
            }
            if (scopedTags.isNotEmpty() && scopedCertificate == null) continue

            val certifier = live.certifier
            val privileged = canManage
            val certificateValid = when {
                certificate != null -> certifier != null && ConcordWritersLedger.verifyCertificate(
                    membership.id.hexToByteArray(),
                    channelIdHex.hexToByteArray(),
                    live.epoch,
                    signer.pubKey,
                    certifier,
                    certificate,
                )
                scopedCertificate != null -> {
                    val (signature, rights) = scopedCertificate
                    val scopedMask =
                        (1uL shl ConcordPermissions.ADD_REACTIONS) or
                            (1uL shl ConcordPermissions.CONNECT)
                    rights == scopedRights && (rights and scopedMask.inv()) == 0uL &&
                        certifier != null && verifyConcordScopedWriterCertificate(
                            membership.id.hexToByteArray(),
                            channelIdHex.hexToByteArray(),
                            live.epoch,
                            signer.pubKey,
                            certifier,
                            signature,
                            rights,
                        )
                }
                else -> certifier == null || privileged
            }
            if (!certificateValid) continue

            val now = System.currentTimeMillis()
            val liveWindowEndMs = live.toMs
            if (now < live.fromMs || (liveWindowEndMs != null && now >= liveWindowEndMs)) continue
            if (scopedCertificate != null) {
                if (ledger.isStopped(live, signer.pubKey, now)) continue
            } else if (!ledger.postStands(
                    membership.id.hexToByteArray(),
                    live,
                    signer.pubKey,
                    now,
                    certificate,
                    privileged,
                )
            ) {
                continue
            }

            return ConcordChatWriterCredential(
                streamKey = writerKey,
                context = context,
                certificate = certificate,
                canSendMessages = canSend,
                canManageChannel = canManage,
                scopedCertificate = scopedCertificate?.first,
                scopedRights = scopedCertificate?.second,
                canAddReactions = canAddReactions,
                canConnect = canConnect,
                canManageMessages = canManageMessages,
                locked = context.locked,
            )
        }
        return null
    }

    /**
     * Persists a private channel key in the owner's encrypted Community List and requires every
     * fragment write to be acknowledged before any Control Plane edition can make the channel live.
     */
    private suspend fun persistPrivateChannelKey(
        signer: PostillaQuartzSigner,
        expectedMembership: ConcordCommunityListEntry,
        privateKey: PrivateChannelKey,
    ): ConcordCommunityListEntry {
        val snapshot = fetchCommunityList(signer)
        requireSafeCommunityListForWrite(snapshot, "Private channel key storage")
        val current = snapshot.document.entries.firstOrNull {
            it.id.equals(expectedMembership.id, ignoreCase = true)
        } ?: throw IllegalStateException("The community membership disappeared before the private key could be stored")
        require(
            current.root.equals(expectedMembership.root, ignoreCase = true) &&
                current.rootEpoch == expectedMembership.rootEpoch &&
                (current.controlPk ?: "").equals(expectedMembership.controlPk ?: "", ignoreCase = true),
        ) {
            "The community membership changed before the private channel key could be stored"
        }
        val updated = ConcordChannelKeyring.withChannelKey(current, privateKey)
            ?: throw IllegalStateException("Quartz refused the new private channel key or its epoch")
        val nextEntries = snapshot.document.entries.map {
            if (it.id.equals(current.id, ignoreCase = true)) updated else it
        }
        val nextDocument = ConcordCommunityList.encodeInternal(nextEntries, snapshot.document.residue)
        val writes = planCommunityListWrites(snapshot, nextDocument, System.currentTimeMillis() / 1000)
        require(writes.isNotEmpty()) { "Quartz produced no Community List fragments for the private channel key" }
        val events = writes.map { write ->
            NostrEvent.fromJson(
                ConcordCommunityListFragmentEvent.create(
                    signer = signer,
                    index = write.index,
                    plaintext = write.plaintext,
                    createdAt = write.createdAt,
                ).toJson(),
            )
        }
        withContext(Dispatchers.IO) { journalEvents(current.id, events) }
        for (event in events) {
            if (!publishConfirmed(event, emptyList())) {
                throw IllegalStateException(
                    "The private channel key was saved locally, but its Community List fragment was not confirmed. " +
                        "The channel was not published; retry synchronization before creating another one.",
                )
            }
        }
        return updated
    }

    /**
     * Creates a CORD-03 private channel. The independent epoch-0 key is stored and confirmed in the
     * encrypted Community List first; its channel-scoped access role is published next; the private
     * Channel edition is last. A failed later step leaves only an inert orphan key/role, never an
     * unkeyed live channel or a channel readable by the whole community.
     */
    suspend fun createPrivateChannel(
        signer: PostillaQuartzSigner,
        communityId: String,
        name: String,
    ): String {
        val normalized = name.trim()
        require(ChannelEntity.isValidName(normalized)) { "Channel name must be 1–64 UTF-8 bytes" }
        val membership = loadLocalMemberships(signer).firstOrNull { it.id == communityId }
            ?: throw IllegalArgumentException("Community membership is not available")
        val control = loadControlPlaneSnapshot(membership, requireAllResponders = true)
        if (control.state.dissolved) throw IllegalStateException("This community has been dissolved")
        requireLegacyRoleActions(control.state)
        val authority = control.state.authority
        if (!authority.hasPermission(signer.pubKey, ConcordPermissions.MANAGE_CHANNELS)) {
            throw IllegalStateException("This account cannot manage community channels")
        }
        if (!authority.isOwner(signer.pubKey) &&
            !authority.hasPermission(signer.pubKey, ConcordPermissions.MANAGE_ROLES)
        ) {
            throw IllegalStateException("Creating a private channel also requires MANAGE_ROLES to mint its access role")
        }
        require(control.state.channels.values.none {
            !it.definition.deleted && it.definition.name.equals(normalized, ignoreCase = true)
        }) { "A channel with this name already exists" }

        val ceiling = if (authority.isOwner(signer.pubKey)) {
            1L
        } else {
            (authority.rank(signer.pubKey) ?: throw IllegalStateException("The actor has no valid role rank")) + 1L
        }
        val lowest = authority.roles().values.filterNot { it.deleted }.maxOfOrNull { it.position } ?: 0L
        val accessRolePosition = maxOf(ceiling, lowest + 1L)
        require(accessRolePosition >= 1L) { "A private-channel access role cannot occupy the owner's rank" }

        val channelId = RandomInstance.bytes(32)
        val channelIdHex = channelId.toHexKey()
        val roleId = RandomInstance.bytes(32)
        val roleIdHex = roleId.toHexKey()
        val channelKey = PrivateChannelKey(
            channelId = channelIdHex,
            key = RandomInstance.bytes(32).toHexKey(),
            epoch = 0L,
            name = normalized,
        )
        val storedMembership = persistPrivateChannelKey(signer, membership, channelKey)

        val accessRole = RoleEntity(
            roleId = roleIdHex,
            name = normalized,
            position = accessRolePosition,
            permissions = "0",
            scope = RoleScope(kind = "channel", channelId = channelIdHex),
        )
        val roleContent = ConcordJson.instance.encodeToString(RoleEntity.serializer(), accessRole)
        val roleEvent = buildControlEdition(
            signer = signer,
            membership = storedMembership,
            control = control,
            entityKind = ControlEntityKind.ROLE,
            entityId = roleId,
            content = roleContent,
            permission = ConcordPermissions.MANAGE_ROLES,
        )
        val channelContent = ConcordJson.instance.encodeToString(
            ChannelEntity.serializer(),
            ChannelEntity(name = normalized, private = true),
        )
        val channelEvent = buildControlEdition(
            signer = signer,
            membership = storedMembership,
            control = control,
            entityKind = ControlEntityKind.CHANNEL,
            entityId = channelId,
            content = channelContent,
            permission = ConcordPermissions.MANAGE_CHANNELS,
        )

        withContext(Dispatchers.IO) { journalEvents(communityId, listOf(roleEvent, channelEvent)) }
        if (!publishConfirmed(roleEvent, membership.relays)) {
            throw IllegalStateException("The private-channel access role was not confirmed; the channel was not published")
        }
        if (!publishConfirmed(channelEvent, membership.relays)) {
            throw IllegalStateException(
                "The access role was published but the private Channel edition was not confirmed. " +
                    "The channel key and role are retained; refresh the Control Plane before retrying.",
            )
        }
        return channelIdHex
    }

    /** Creates a public CORD-03 channel after a fresh MANAGE_CHANNELS authority check. */
    suspend fun createPublicChannel(
        signer: PostillaQuartzSigner,
        communityId: String,
        name: String,
    ): String {
        val normalized = name.trim()
        require(ChannelEntity.isValidName(normalized)) { "Channel name must be 1–64 UTF-8 bytes" }
        val membership = loadLocalMemberships(signer).firstOrNull { it.id == communityId }
            ?: throw IllegalArgumentException("Community membership is not available")
        val control = loadControlPlaneSnapshot(membership, requireAllResponders = true)
        if (control.state.dissolved) throw IllegalStateException("This community has been dissolved")
        if (!control.state.authority.hasPermission(signer.pubKey, ConcordPermissions.MANAGE_CHANNELS)) {
            throw IllegalStateException("This account cannot manage community channels")
        }
        require(control.state.channels.values.none { it.definition.name.equals(normalized, ignoreCase = true) }) {
            "A channel with this name already exists"
        }
        val channelId = RandomInstance.bytes(32)
        val content = ConcordJson.instance.encodeToString(
            ChannelEntity.serializer(),
            ChannelEntity(name = normalized, private = false),
        )
        publishControlEdition(
            signer, membership, control, ControlEntityKind.CHANNEL, channelId, content,
            ConcordPermissions.MANAGE_CHANNELS,
        )
        return channelId.toHexKey()
    }

    /** Renames a live channel, preserving unknown CORD extension fields verbatim. */
    suspend fun renameChannel(
        signer: PostillaQuartzSigner,
        communityId: String,
        channelId: String,
        newName: String,
    ) {
        val normalized = newName.trim()
        require(ChannelEntity.isValidName(normalized)) { "Channel name must be 1–64 UTF-8 bytes" }
        val membership = loadLocalMemberships(signer).firstOrNull { it.id == communityId }
            ?: throw IllegalArgumentException("Community membership is not available")
        val control = loadControlPlaneSnapshot(membership, requireAllResponders = true)
        if (control.state.dissolved) throw IllegalStateException("This community has been dissolved")
        if (!control.state.authority.hasPermission(signer.pubKey, ConcordPermissions.MANAGE_CHANNELS)) {
            throw IllegalStateException("This account cannot manage community channels")
        }
        val current = control.state.channels[channelId]
            ?: throw IllegalArgumentException("Channel does not exist or has been deleted")
        require(control.state.channels.values.none {
            it.channelIdHex != channelId && it.definition.name.equals(normalized, ignoreCase = true)
        }) { "A channel with this name already exists" }
        val heads = ConcordCommunityState.authorizedHeads(
            control.editions, membership.id.hexToByteArray(), membership.owner,
        )
        val head = heads[channelId]
        val content = ConcordJson.encodePreserving(
            ChannelEntity.serializer(),
            current.definition.copy(name = normalized),
            head?.known?.content,
        )
        publishControlEdition(
            signer, membership, control, ControlEntityKind.CHANNEL, channelId.hexToByteArray(), content,
            ConcordPermissions.MANAGE_CHANNELS, head?.version, head?.hashHex,
        )
    }

    /** CORD channel deletion is terminal; the id is never reused. */
    suspend fun deleteChannel(
        signer: PostillaQuartzSigner,
        communityId: String,
        channelId: String,
    ) {
        val membership = loadLocalMemberships(signer).firstOrNull { it.id == communityId }
            ?: throw IllegalArgumentException("Community membership is not available")
        val control = loadControlPlaneSnapshot(membership, requireAllResponders = true)
        if (control.state.dissolved) throw IllegalStateException("This community has been dissolved")
        if (!control.state.authority.hasPermission(signer.pubKey, ConcordPermissions.MANAGE_CHANNELS)) {
            throw IllegalStateException("This account cannot manage community channels")
        }
        val current = control.state.channels[channelId]
            ?: throw IllegalArgumentException("Channel does not exist or has already been deleted")
        require(!current.definition.deleted) { "Channel has already been deleted" }
        val heads = ConcordCommunityState.authorizedHeads(
            control.editions, membership.id.hexToByteArray(), membership.owner,
        )
        val head = heads[channelId]
        val content = ConcordJson.encodePreserving(
            ChannelEntity.serializer(),
            current.definition.copy(deleted = true),
            head?.known?.content,
        )
        publishControlEdition(
            signer, membership, control, ControlEntityKind.CHANNEL, channelId.hexToByteArray(), content,
            ConcordPermissions.MANAGE_CHANNELS, head?.version, head?.hashHex,
        )
    }

    /** Reads and coalesces the Guestbook against the currently folded authority roster. */
    /**
     * First writer mint for a channel whose Roles v2 policy restricts @everyone from sending.
     * A complete Control Plane and complete current/historical Guestbook read are mandatory
     * before a new writer epoch is published.
     */
    suspend fun ensureWriterAccess(
        signer: PostillaQuartzSigner,
        communityId: String,
        channelId: String,
    ): CommunityChannelSnapshot {
        val lockKey = "${signer.pubKey.lowercase()}:${communityId.lowercase()}:${channelId.lowercase()}"
        return writerDutyLocks.getOrPut(lockKey) { Mutex() }.withLock {
            val membership = loadLocalMemberships(signer).firstOrNull {
                it.id.equals(communityId, ignoreCase = true)
            } ?: throw IllegalArgumentException("Community membership is not available on this device")
            val channelHex = channelId.lowercase()
            val channelBytes = channelHex.hexToByteArray()
            val communityBytes = membership.id.hexToByteArray()
            var control = loadControlPlaneSnapshot(membership, requireAllResponders = false)
            require(control.readSummary.hasQuorum) {
                "Writer Duty requires a verified two-thirds Control Plane read quorum"
            }
            if (control.state.dissolved) throw IllegalStateException("This community has been dissolved")
            var channel = control.state.channels[channelHex]
                ?: throw IllegalArgumentException("The channel is not present in the complete Concord Control Plane")
            if (channel.definition.deleted) throw IllegalStateException("This channel has been deleted")
            var authority = control.state.authority
            if (authority.isBanned(signer.pubKey)) throw IllegalStateException("This account is banned from the community")
            val canManage = authority.isOwner(signer.pubKey) || control.state.channelPermissions(
                signer.pubKey, channelHex, isPrivate = channel.definition.private,
            ).has(ConcordPermissions.MANAGE_CHANNELS)
            if (!canManage) throw IllegalStateException("Only the owner or a channel manager can initialize writer access")
            require(membership.relays.isNotEmpty()) { "This community has no relays for writer-key delivery" }

            val identity = writerDutyIdentity(signer.pubKey, membership.id, channelHex)
            retryWriterDutyPending(signer, membership, channelHex)
            control = loadControlPlaneSnapshot(membership, requireAllResponders = false)
            require(control.readSummary.hasQuorum) {
                "Writer Duty could not re-read a verified Control Plane quorum after retry"
            }
            if (control.state.dissolved || control.state.authority.isBanned(signer.pubKey)) {
                throw IllegalStateException("This account no longer has valid community authority")
            }
            // All following recipient, permission and signing decisions must use the
            // same refreshed Control Plane snapshot, not the pre-retry authority object.
            authority = control.state.authority
            channel = control.state.channels[channelHex]
                ?: throw IllegalStateException("The channel disappeared from the refreshed Control Plane")
            if (channel.definition.deleted) throw IllegalStateException("This channel was deleted during writer synchronization")
            val refreshedCanManage = authority.isOwner(signer.pubKey) ||
                control.state.channelPermissions(
                    signer.pubKey,
                    channelHex,
                    isPrivate = channel.definition.private,
                ).has(ConcordPermissions.MANAGE_CHANNELS)
            if (!refreshedCanManage) throw IllegalStateException("Writer Duty authority changed during synchronization")

            var snapshot = loadCommunityChannelSnapshot(membership, signer)
            require(snapshot.controlRead.hasQuorum) {
                "Writer Duty requires a verified Control Plane read quorum"
            }
            snapshot.writerAccessRead?.let { accessRead ->
                require(accessRead.hasQuorum) {
                    "Writer Duty requires a verified two-thirds Writer Access Plane read quorum"
                }
            }
            var currentChannel = snapshot.channels.firstOrNull { it.id.equals(channelHex, ignoreCase = true) }
                ?: throw IllegalStateException("The channel catalogue is not available")
            if (currentChannel.writerCredential != null) return@withLock snapshot

            val everyonePermissions = authority.everyonePermissions()
            val everyoneScope = control.state.syncs[channelHex] ?: channelHex
            val everyoneOverwrite = control.state.overwritesIn(everyoneScope).firstOrNull { it.targetKind == "everyone" }
            val effectiveEveryone = if (everyonePermissions != null && everyoneOverwrite != null) {
                (everyonePermissions minus everyoneOverwrite.deny) union everyoneOverwrite.allow
            } else {
                everyonePermissions
            }
            val restricted = control.state.isRolesV2 &&
                effectiveEveryone != null &&
                !effectiveEveryone.has(ConcordPermissions.SEND_MESSAGES)
            val existingLedger = control.state.writers[channelHex]
            if (!restricted && existingLedger?.live == null) return@withLock snapshot

            val members = loadMembers(
                signer = signer,
                communityId = membership.id,
                requireCompleteRead = true,
            )
            val memberPubkeys = (members.map { it.pubkey } + authority.roleHolders() + membership.owner + signer.pubKey)
                .map { it.lowercase() }
                .filter { it.isNotBlank() && !authority.isBanned(it) }
                .toSet()
            require(membership.owner.lowercase() in memberPubkeys) {
                "The community owner is missing from the complete member roster"
            }

            val recipients = memberPubkeys.filter { pubkey ->
                val permissions = control.state.channelPermissions(pubkey, channelHex, isPrivate = channel.definition.private)
                authority.isOwner(pubkey) ||
                    permissions.has(ConcordPermissions.SEND_MESSAGES) ||
                    permissions.has(ConcordPermissions.MANAGE_CHANNELS)
            }.distinct().sorted()
            val managers = recipients.filter { pubkey ->
                authority.isOwner(pubkey) || control.state.channelPermissions(
                    pubkey, channelHex, isPrivate = channel.definition.private,
                ).has(ConcordPermissions.MANAGE_CHANNELS)
            }
            require(recipients.isNotEmpty() && signer.pubKey.lowercase() in recipients) {
                "This account is not entitled to a writer key in the selected channel"
            }
            require(managers.isNotEmpty()) { "No authorized manager can hold the channel certifier key" }

            val writersEntityId = ConcordKeyDerivation.writersCoordinate(communityBytes, channelBytes)
            val writersEntityHex = writersEntityId.toHexKey()
            val heads = ConcordCommunityState.authorizedHeads(control.editions, communityBytes, membership.owner)
            val head = heads[writersEntityHex]
            val previousContent = head?.known?.content
            val previousLedger = if (previousContent != null) {
                ConcordWritersLedger.parse(previousContent, communityBytes, writersEntityHex)
                    ?: throw IllegalStateException("The existing Writers Ledger is malformed; refusing to overwrite it")
            } else {
                if (existingLedger != null) {
                    throw IllegalStateException("The Writers Ledger state exists but its authorized edition content is unavailable")
                }
                null
            }
            val epoch = (previousLedger?.windows?.maxOfOrNull { it.epoch } ?: 0L) + 1L
            require(epoch > 0L) { "The Writers Ledger epoch overflowed" }
            val nowMs = System.currentTimeMillis()
            val lastAt = previousContent?.let { raw ->
                val json = Json.parseToJsonElement(raw) as? JsonObject
                (json?.get("ledger") as? JsonArray)?.lastOrNull()?.let { entry ->
                    ((entry as? JsonObject)?.get("at") as? JsonPrimitive)?.content?.toLongOrNull()
                }
            } ?: 0L
            val entryAtMs = maxOf(nowMs, lastAt + 1L)

            val writerSecret = RandomInstance.bytes(32)
            val certifierPair = KeyPair()
            val certifierSecret = requireNotNull(certifierPair.privKey) { "Quartz did not generate a certifier private key" }
            val certifierPub = certifierPair.pubKey.toHexKey()
            val writerKey = ConcordKeyDerivation.channelWriterKey(writerSecret, channelBytes, epoch)
            val writerPub = writerKey.publicKeyHex.lowercase()
            val content = concordAppendWriterLedgerContent(
                previousContent = previousContent,
                channelIdHex = channelHex,
                atMs = entryAtMs,
                epoch = epoch,
                writerPubKey = writerPub,
                rootEpoch = membership.rootEpoch,
                minterPubKey = signer.pubKey,
                certifierPubKey = certifierPub,
            )
            check(ConcordWritersLedger.parse(content, communityBytes, writersEntityHex) != null) {
                "Quartz rejected the generated Writers Ledger; nothing was published"
            }

            val binding = RankedRoster.bindingForWrite(
                authority, communityBytes, ControlEntityKind.WRITERS,
            )
            val ledgerRumor = ControlEditionBuilder.rumor(
                authorPubKey = signer.pubKey,
                entityKind = ControlEntityKind.WRITERS,
                entityId = writersEntityId,
                version = (head?.version ?: 0L) + 1L,
                prevHash = head?.hashHex?.hexToByteArray(),
                content = content,
                createdAt = entryAtMs / 1000L,
                authorityCitation = authority.citationFor(signer.pubKey),
                binding = binding,
            )
            val ledgerEvent = NostrEvent.fromJson(
                ConcordStreamEnvelope.wrap(
                    rumor = ledgerRumor,
                    keys = control.keys,
                    authorSigner = signer,
                    encrypted = false,
                    createdAt = entryAtMs / 1000L,
                ).toJson(),
            )

            val accessPlane = ConcordChannelAccess.plane(
                membership.root.hexToByteArray(), channelBytes, membership.rootEpoch,
            )
            relayPool.registerConcordStreamKeys(listOf(accessPlane), membership.relays)
            val certs = recipients.associate { recipient ->
                recipient to ConcordWritersLedger.signCertificate(
                    communityBytes, channelBytes, epoch, writerPub, certifierSecret, certifierPub,
                )
            }
            val writerDeliveries = buildWriterAccessEvents(
                signer, accessPlane, ConcordKeyDerivation.writerScope(channelBytes),
                epoch, writerSecret, writerPub, recipients, certs, mint = true,
            )
            val certifierDeliveries = buildWriterAccessEvents(
                signer, accessPlane, ConcordKeyDerivation.certifierScope(channelBytes),
                epoch, certifierSecret, certifierPub, managers, emptyMap(), mint = false,
            )
            val accessEvents = writerDeliveries + certifierDeliveries
            require(accessEvents.isNotEmpty()) { "Quartz produced no writer-access delivery events" }

            val pendingControlKey = "$identity:control"
            val pendingAccessKey = "$identity:access"
            val pendingEpochKey = "$identity:epoch"
            securePrefs.edit()
                .putString(pendingControlKey, ledgerEvent.toJson())
                .putStringSet(pendingAccessKey, accessEvents.map { it.toJson() }.toSet())
                .putLong(pendingEpochKey, epoch)
                .putString("$identity:secret:$epoch", writerSecret.toHexKey())
                .putString("$identity:certifier:$epoch", certifierSecret.toHexKey())
                .commit()
            journalEvents(membership.id, listOf(ledgerEvent) + accessEvents)

            if (!publishConfirmed(ledgerEvent, membership.relays)) {
                throw IllegalStateException("The Writers Ledger has not been confirmed by a relay; writer deliveries remain safely queued")
            }
            securePrefs.edit().remove(pendingControlKey).commit()

            val confirmedControl = loadControlPlaneSnapshot(membership, requireAllResponders = false)
            require(confirmedControl.readSummary.hasQuorum) {
                "The Writers Ledger cannot be verified because the Control Plane quorum was lost"
            }
            val confirmedLive = confirmedControl.state.writers[channelHex]?.live
            if (confirmedLive?.epoch != epoch || !confirmedLive.writerPubKey.equals(writerPub, ignoreCase = true)) {
                throw IllegalStateException("The Writers Ledger is not yet visible in the complete Control Plane; writer deliveries remain queued")
            }
            for (event in accessEvents) {
                if (!publishConfirmed(event, membership.relays)) {
                    throw IllegalStateException("Writer keys are not yet confirmed on the Access Plane; refresh to retry the same signed deliveries")
                }
            }
            securePrefs.edit().remove(pendingAccessKey).remove(pendingEpochKey).commit()

            snapshot = loadCommunityChannelSnapshot(membership, signer)
            require(snapshot.controlRead.hasQuorum) {
                "The channel Control Plane quorum was lost after writer-key delivery"
            }
            snapshot.writerAccessRead?.let { accessRead ->
                require(accessRead.hasQuorum) {
                    "The Writer Access Plane quorum was lost after key delivery"
                }
            }
            currentChannel = snapshot.channels.firstOrNull { it.id.equals(channelHex, ignoreCase = true) }
                ?: throw IllegalStateException("The channel disappeared after writer-key delivery")
            if (currentChannel.writerCredential?.canSendMessages != true &&
                currentChannel.writerCredential?.canManageChannel != true
            ) {
                throw IllegalStateException("Writer deliveries were confirmed, but Quartz could not open a valid credential for this account")
            }
            snapshot
        }
    }

    private fun writerDutyIdentity(ownerPubkey: String, communityId: String, channelId: String): String =
        "writer_duty:${ownerPubkey.lowercase()}:${communityId.lowercase()}:${channelId.lowercase()}"

    private suspend fun buildWriterAccessEvents(
        signer: PostillaQuartzSigner,
        accessPlane: GroupKey,
        scopeId: ByteArray,
        epoch: Long,
        key: ByteArray,
        writerPubKey: String,
        recipients: List<String>,
        certificates: Map<String, String>,
        mint: Boolean,
    ): List<NostrEvent> {
        val blobs = recipients.map { recipient ->
            ConcordRekey.blobForSigner(signer, recipient.hexToByteArray(), scopeId, epoch, key)
        }
        val chunks = ConcordRekey.chunkBlobs(
            blobs,
            envelopeBytes = 2_048,
            perEvent = if (certificates.isEmpty()) ConcordChannelAccess.MAX_BLOBS else ConcordChannelAccess.MAX_BLOBS_WITH_CERTS,
        )
        val recipientByLocator = blobs.zip(recipients).associate { it.first.locator to it.second }
        val createdAt = System.currentTimeMillis() / 1000L
        return chunks.mapIndexed { index, chunk ->
            val tags = ConcordChannelAccess.accessTags(scopeId, epoch, writerPubKey).toMutableList()
            chunk.forEach { blob ->
                val recipient = recipientByLocator[blob.locator]
                    ?: throw IllegalStateException("A writer delivery lost its recipient locator")
                certificates[recipient]?.let { tags += arrayOf(ConcordChannelAccess.TAG_CERT, recipient, it) }
            }
            if (mint) tags += arrayOf(ConcordChannelAccess.TAG_MINT, (index + 1).toString(), chunks.size.toString())
            val rumor = RumorAssembler.assembleRumor<Event>(
                pubKey = signer.pubKey,
                createdAt = createdAt,
                kind = ConcordRekey.KIND,
                tags = tags.toTypedArray(),
                content = ConcordRekey.encodeContent(chunk),
            )
            NostrEvent.fromJson(
                ConcordStreamEnvelope.wrap(
                    rumor = rumor,
                    stream = accessPlane,
                    authorSigner = signer,
                    encrypted = true,
                    createdAt = createdAt,
                ).toJson(),
            )
        }
    }

    private suspend fun retryWriterDutyPending(
        signer: PostillaQuartzSigner,
        membership: ConcordCommunityListEntry,
        channelId: String,
    ) {
        val identity = writerDutyIdentity(signer.pubKey, membership.id, channelId)
        val pendingControlKey = "$identity:control"
        val pendingAccessKey = "$identity:access"
        val pendingEpochKey = "$identity:epoch"
        securePrefs.getString(pendingControlKey, null)?.let { raw ->
            if (!publishConfirmed(NostrEvent.fromJson(raw), membership.relays)) {
                throw IllegalStateException("The Writers Ledger is still pending relay confirmation")
            }
            securePrefs.edit().remove(pendingControlKey).commit()
        }
        val pending = securePrefs.getStringSet(pendingAccessKey, emptySet()).orEmpty().toList()
        if (pending.isEmpty()) return
        val expectedEpoch = securePrefs.getLong(pendingEpochKey, -1L)
        val control = loadControlPlaneSnapshot(membership, requireAllResponders = false)
        require(control.readSummary.hasQuorum) {
            "Pending writer deliveries require a verified two-thirds Control Plane quorum"
        }
        val live = control.state.writers[channelId]?.live
        if (expectedEpoch <= 0L || live?.epoch != expectedEpoch) {
            throw IllegalStateException("Pending writer deliveries do not match the current Writers Ledger")
        }
        for (raw in pending.sorted()) {
            if (!publishConfirmed(NostrEvent.fromJson(raw), membership.relays)) {
                throw IllegalStateException("Writer keys are still awaiting relay confirmation")
            }
        }
        securePrefs.edit().remove(pendingAccessKey).remove(pendingEpochKey).commit()
    }

    suspend fun loadMembers(
        signer: PostillaQuartzSigner,
        communityId: String,
        observedAuthors: Map<String, ConcordObservedAuthorActivity> = emptyMap(),
        requireCompleteRead: Boolean = false,
    ): List<CommunityMember> {
        val membership = loadLocalMemberships(signer).firstOrNull { it.id == communityId }
            ?: throw IllegalArgumentException("Community membership is not available")
        // Member visibility is read-only and can use a partial snapshot when relays are
        // silent. Kick/Ban and every other mutation re-fetch a complete Control Plane
        // immediately before publishing, so this display snapshot cannot authorize a write.
        val control = loadControlPlaneSnapshot(membership, requireAllResponders = false)
        if (requireCompleteRead) {
            require(control.readSummary.hasQuorum) {
                "The Control Plane did not reach the verified member-read quorum"
            }
        }
        // A Refounding changes the current Guestbook plane. Older roots remain in the
        // member's private list so the roster can still apply historical JOIN/LEAVE/KICK
        // motions in timestamp order instead of losing everyone who joined before rotation.
        val rootsByEpoch = buildMap<Long, String> {
            membership.heldRoots.forEach { held -> put(held.epoch, held.key) }
            put(membership.rootEpoch, membership.root)
        }
        val guestbookKeys = concordGuestbookEpochs(
            currentEpoch = membership.rootEpoch,
            heldEpochs = membership.heldRoots.map { it.epoch },
        ).mapNotNull { epoch ->
            val root = rootsByEpoch[epoch] ?: return@mapNotNull null
            runCatching {
                ConcordKeyDerivation.guestbookPlaneKey(
                    communityRoot = root.hexToByteArray(),
                    communityId = membership.id.hexToByteArray(),
                    epoch = epoch,
                )
            }.getOrNull()
        }.distinctBy { it.publicKeyHex.lowercase() }
        require(guestbookKeys.isNotEmpty()) { "No current or historical Guestbook key is available" }
        val guestbookKeyByAddress = guestbookKeys.associateBy { it.publicKeyHex.lowercase() }
        relayPool.registerConcordStreamKeys(guestbookKeys, membership.relays)
        val guestbookRead = fetchEventsFromRelaysDetailed(
            relays = membership.relays,
            filter = Filter(
                kinds = listOf(ConcordStreamEnvelope.KIND_WRAP),
                authors = guestbookKeys.map { it.publicKeyHex },
                limit = 5000,
            ),
            label = "guestbook",
            requireAllResponders = false,
        )
        if (requireCompleteRead) {
            require(guestbookRead.summary.hasQuorum) {
                "The Guestbook did not reach the verified two-thirds member-read quorum"
            }
        }
        val events = guestbookRead.events
        val entries = events.mapNotNull { event ->
            val guestbookKey = guestbookKeyByAddress[event.pubkey.lowercase()] ?: return@mapNotNull null
            try {
                val opened = ConcordStreamEnvelope.open(Event.fromJson(event.toJson()), guestbookKey)
                Guestbook.parse(opened.rumor)
            } catch (_: Exception) {
                null
            }
        }
        val authority = control.state.authority
        val rankedRoster = authority.rankedRoster()
        fun rolesForMember(pubkey: String): Pair<List<String>, List<String>> {
            return if (rankedRoster != null) {
                val held = rankedRoster.rolesOf(pubkey).sortedWith(compareBy({ it.position }, { it.roleId }))
                held.map { it.roleId } to held.map { it.name }.filter(String::isNotBlank)
            } else {
                val held = authority.rolesOf(pubkey).sorted()
                held to held.mapNotNull { authority.roles()[it]?.name?.takeIf(String::isNotBlank) }
            }
        }
        val isPublic = control.state.isPublic
        val coalesced = Guestbook.coalesce(entries, System.currentTimeMillis(), authority)
        val members = LinkedHashMap<String, CommunityMember>()

        // Concord has no relay-owned roster. Match Armada's privileged roster sources:
        // the owner and every live role-holder are included even when they have not posted.
        for (pubkey in concordPrivilegedRosterCandidates(membership.owner, authority.roleHolders())) {
            if (authority.isBanned(pubkey)) continue
            val activity = observedAuthors[pubkey]
            val (heldRoleIds, heldRoleNames) = rolesForMember(pubkey)
            members[pubkey] = CommunityMember(
                pubkey = pubkey,
                joinedAt = activity?.firstSeenAtMs?.div(1000L)?.coerceAtLeast(0L) ?: 0L,
                lastSeenAt = activity?.lastSeenAtMs ?: 0L,
                roleIds = heldRoleIds,
                roleNames = heldRoleNames,
                canKick = authority.canActOn(signer.pubKey, pubkey, ConcordPermissions.KICK) &&
                    (isPublic || authority.hasPermission(signer.pubKey, ConcordPermissions.BAN)),
                canBan = authority.canActOn(signer.pubKey, pubkey, ConcordPermissions.BAN),
                requiresRefounding = !isPublic,
            )
        }

        // Self-reported Guestbook JOINs are the primary membership signal.
        for (entry in coalesced.values.sortedBy { it.createdAt }) {
            if (entry.action != GuestbookAction.JOIN) continue
            val pubkey = entry.member.lowercase()
            if (authority.isBanned(pubkey)) continue
            val activity = observedAuthors[pubkey]
            val (heldRoleIds, heldRoleNames) = rolesForMember(pubkey)
            members[pubkey] = CommunityMember(
                pubkey = pubkey,
                joinedAt = entry.createdAt,
                inviteCreator = entry.inviteCreator,
                inviteLabel = entry.inviteLabel,
                lastSeenAt = activity?.lastSeenAtMs ?: entry.ms,
                roleIds = heldRoleIds,
                roleNames = heldRoleNames,
                canKick = authority.canActOn(signer.pubKey, pubkey, ConcordPermissions.KICK) &&
                    (isPublic || authority.hasPermission(signer.pubKey, ConcordPermissions.BAN)),
                canBan = authority.canActOn(signer.pubKey, pubkey, ConcordPermissions.BAN),
                requiresRefounding = !isPublic,
            )
        }

        // CORD-02 §5: a valid author observed publishing is also present, even when their
        // JOIN has not reached this device. Observation only revives a prior Leave/Kick
        // when the activity is newer than that final Guestbook motion. The Control Plane
        // Banlist always wins; a banned author can never be re-added by chat activity.
        for ((rawPubkey, activity) in observedAuthors) {
            val pubkey = rawPubkey.lowercase()
            if (authority.isBanned(pubkey)) continue
            val lastMotion = coalesced[pubkey]
            if (lastMotion != null && activity.lastSeenAtMs <= lastMotion.ms) continue
            if (pubkey in members) {
                val current = members.getValue(pubkey)
                members[pubkey] = current.copy(lastSeenAt = maxOf(current.lastSeenAt, activity.lastSeenAtMs))
            } else {
                val (heldRoleIds, heldRoleNames) = rolesForMember(pubkey)
                members[pubkey] = CommunityMember(
                    pubkey = pubkey,
                    joinedAt = (activity.firstSeenAtMs / 1000L).coerceAtLeast(0L),
                    lastSeenAt = activity.lastSeenAtMs,
                    roleIds = heldRoleIds,
                    roleNames = heldRoleNames,
                    canKick = authority.canActOn(signer.pubKey, pubkey, ConcordPermissions.KICK) &&
                        (isPublic || authority.hasPermission(signer.pubKey, ConcordPermissions.BAN)),
                    canBan = authority.canActOn(signer.pubKey, pubkey, ConcordPermissions.BAN),
                    requiresRefounding = !isPublic,
                )
            }
        }
        return members.values.sortedWith(
            compareBy<CommunityMember> { it.joinedAt }.thenBy { it.pubkey },
        )
    }

    /**
     * Sends a CORD-05 §6 Direct Invite (kind 3313 inside a standard NIP-59 giftwrap).
     * Unlike public links this is an ungated, one-recipient key handoff and cannot be revoked.
     */
    internal fun privateChannelKeysForInvite(
        heldKeys: List<PrivateChannelKey>,
        authority: com.vitorpamplona.quartz.concord.cord04Roles.AuthorityResolver,
        recipient: String,
    ): List<PrivateChannelKey> =
        ConcordInviteVend.vendableChannels(heldKeys, authority, recipient)

    suspend fun sendDirectInvite(
        signer: PostillaQuartzSigner,
        communityId: String,
        recipientInput: String,
    ) {
        val rawRecipient = recipientInput.trim()
        val recipient = when {
            Regex("^[0-9a-fA-F]{64}$").matches(rawRecipient) -> rawRecipient.lowercase()
            else -> (Nip19Parser.uriToRoute(rawRecipient)?.entity as? NPub)?.hex
        } ?: throw IllegalArgumentException("Enter a valid npub or 64-character public key")

        val membership = loadLocalMemberships(signer).firstOrNull { it.id == communityId }
            ?: throw IllegalArgumentException("Community membership is not available on this device")
        // Direct invites transfer the community root and held private-channel keys.
        // Require a complete Control Plane before checking bans or constructing that bundle.
        val control = loadControlPlaneSnapshot(membership, requireAllResponders = true)
        val authority = control.state.authority
        if (control.state.dissolved) throw IllegalStateException("This community has been dissolved")
        if (authority.isBanned(signer.pubKey)) throw IllegalStateException("This account is banned from the community")
        if (authority.isBanned(recipient)) throw IllegalStateException("This recipient is banned from the community")

        val vendablePrivateChannels = privateChannelKeysForInvite(
            membership.privateChannels,
            authority,
            recipient,
        ).associateBy { it.channelId.lowercase() }
        val channels = control.state.channels.values.mapNotNull { channel ->
            val privateEntry = if (channel.definition.private) {
                vendablePrivateChannels[channel.channelIdHex.lowercase()]
            } else null
            if (channel.definition.private && privateEntry == null) {
                null
            } else {
                InviteChannel(
                    id = channel.channelIdHex,
                    key = privateEntry?.key.orEmpty(),
                    epoch = privateEntry?.epoch ?: membership.rootEpoch,
                    name = channel.definition.name,
                )
            }
        }
        require(membership.relays.isNotEmpty()) { "This community has no bootstrap relays; a Direct Invite cannot be delivered safely" }
        require(channels.size <= 256) { "This community has more channels than the Direct Invite limit (256)" }
        val invite = CommunityInvite(
            communityId = membership.id,
            owner = membership.owner,
            ownerSalt = membership.ownerSalt,
            communityRoot = membership.root,
            rootEpoch = membership.rootEpoch,
            controlPk = membership.controlPk,
            channels = channels,
            relays = membership.relays.distinct().take(5),
            name = control.state.metadata?.name?.ifBlank { membership.name } ?: membership.name,
            icon = control.state.metadata?.icon,
            creatorNpub = NPub.create(signer.pubKey),
        )
        require(ConcordInviteBundle.validate(invite)) {
            "The local membership does not pass the CORD-05 community owner proof"
        }

        // CORD-05 §6: deliver only to the recipient's kind-10050 DM relays, or
        // their NIP-65 read relays when no DM relay list exists. Never spray a
        // private key bundle to an unrelated public fallback relay.
        val bootstrap = relayPool.getRelayUrls().distinct()
        val recipientLists = if (bootstrap.isNotEmpty()) {
            fetchEventsFromRelays(
                relays = bootstrap,
                filter = Filter(kinds = listOf(10050, 10002), authors = listOf(recipient), limit = 20),
                label = "direct-invite-recipient-relays",
                requireAllResponders = false,
            )
        } else emptyList()
        val dmRelays = recipientLists
            .filter { it.kind == 10050 }
            .maxByOrNull { it.created_at }
            ?.tags
            ?.filter { it.firstOrNull() == "relay" }
            ?.mapNotNull { it.getOrNull(1)?.takeIf(String::isNotBlank) }
            .orEmpty()
        val nip65Relays = recipientLists
            .filter { it.kind == 10002 }
            .maxByOrNull { it.created_at }
            ?.tags
            ?.filter { tag ->
                tag.firstOrNull() == "r" && (tag.getOrNull(2).isNullOrBlank() || tag.getOrNull(2) == "read")
            }
            ?.mapNotNull { it.getOrNull(1)?.takeIf(String::isNotBlank) }
            .orEmpty()
        val deliveryRelays = dmRelays.ifEmpty { nip65Relays }.distinct().take(10)
        require(deliveryRelays.isNotEmpty()) {
            "The recipient publishes neither kind-10050 DM relays nor NIP-65 read relays; invite not sent"
        }

        val wrap = ConcordDirectInvite.build(signer, recipient, invite)
        val event = NostrEvent.fromJson(wrap.toJson())
        val message = ClientMessage.event(event)
        val outcome = relayPool.publishWithConfirmation(event.id) {
            deliveryRelays.count { url ->
                relayPool.sendToRelayOrEphemeral(url, message, skipBadCheck = true, write = true)
            }
        }
        if (!outcome.confirmed) {
            throw IllegalStateException("Direct Invite was not acknowledged by a recipient inbox relay; it was not delivered")
        }
    }

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
        return joinCommunityFromBundle(signer, invite, ConcordInviteLink.bareForm(inviteUrl))
    }

    /**
     * Fetches Direct Invites from the recipient inbox. The k=3313 tag is only an index hint:
     * query p-tagged kind-1059 wraps as well, so valid untagged invites from older peers are honored.
     * The cursor rewinds by NIP-59's backdate window to avoid missing a wrap published after a
     * previous sweep but carrying an older created_at.
     */
    suspend fun fetchDirectInvites(signer: PostillaQuartzSigner): List<OpenedDirectInvite> {
        val inboxRelays = relayPool.getDmRelayUrls().distinct().ifEmpty { relayPool.getReadRelayUrls().distinct() }
        val inboxCursorKey = "concord_direct_invites_newest_wrap_${signer.pubKey.lowercase()}"
        val cursor = prefs.getLong(inboxCursorKey, 0L).takeIf { it > 0L }
        val events = fetchEventsFromRelays(
            relays = inboxRelays,
            filter = Filter(
                kinds = listOf(1059),
                pTags = listOf(signer.pubKey),
                since = ConcordDirectInvite.inboxSince(cursor),
                limit = 1000,
            ),
            label = "direct-invites",
            requireAllResponders = false,
        )
        // openOrRetry preserves the inbox cursor if an external signer is temporarily
        // unavailable; a transient decrypt failure must not make an invite disappear forever.
        val opened = events.mapNotNull { event ->
            ConcordDirectInvite.openOrRetry(Event.fromJson(event.toJson()), signer)
        }.distinctBy { it.wrapId }.sortedByDescending { it.sentAt }
        val heldMemberships = try {
            loadLocalMemberships(signer).associateBy { it.id.lowercase() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
        }
        val declinedKey = "${PREF_DECLINED_DIRECT_INVITES}_${signer.pubKey.lowercase()}"
        val declined = prefs.getStringSet(declinedKey, emptySet()).orEmpty()
        val visible = opened.filterNot { it.wrapId.lowercase() in declined }.filter { direct ->
            val held = heldMemberships[direct.invite.communityId.lowercase()]
            held == null || concordHasDirectInviteKeyCatchUp(held, direct.invite)
        }
        val newest = events.maxOfOrNull { it.created_at } ?: cursor
        if (newest != null && newest > (cursor ?: 0L)) {
            withContext(Dispatchers.IO) {
                prefs.edit().putLong(inboxCursorKey, newest).apply()
            }
        }
        return visible
    }

    /** Remember an explicit decline so the same NIP-59 wrap does not resurface on the next sweep. */
    fun declineDirectInvite(wrapId: String, signerPubkey: String) {
        val declinedKey = "${PREF_DECLINED_DIRECT_INVITES}_${signerPubkey.lowercase()}"
        val declined = prefs.getStringSet(declinedKey, emptySet()).orEmpty().toMutableSet()
        declined.add(wrapId.lowercase())
        prefs.edit().putStringSet(declinedKey, declined).apply()
    }

    suspend fun joinCommunityFromDirectInvite(
        signer: PostillaQuartzSigner,
        opened: OpenedDirectInvite,
    ): JoinResult {
        if (opened.isExpired()) throw IllegalStateException("This Direct Invite has expired")
        if (!ConcordInviteBundle.validate(opened.invite)) {
            throw IllegalStateException("This Direct Invite failed community identity validation")
        }
        val existing = loadLocalMemberships(signer).firstOrNull {
            it.id.equals(opened.invite.communityId, ignoreCase = true)
        }
        return if (existing == null) {
            joinCommunityFromBundle(signer, opened.invite, inviteRef = null)
        } else {
            adoptDirectInviteCatchUp(signer, opened, existing)
        }
    }

    private suspend fun adoptDirectInviteCatchUp(
        signer: PostillaQuartzSigner,
        opened: OpenedDirectInvite,
        held: ConcordCommunityListEntry,
    ): JoinResult {
        val invite = opened.invite
        require(invite.communityId.equals(held.id, ignoreCase = true) &&
            invite.communityRoot.equals(held.root, ignoreCase = true) &&
            invite.rootEpoch == held.rootEpoch &&
            (invite.controlPk ?: "").equals(held.controlPk ?: "", ignoreCase = true)
        ) {
            "This Direct Invite belongs to a different community base and cannot update the existing membership"
        }

        val snapshot = fetchCommunityList(signer)
        requireSafeCommunityListForWrite(snapshot, "Private channel key catch-up")
        val latest = snapshot.document.entries.firstOrNull {
            it.id.equals(held.id, ignoreCase = true)
        } ?: throw IllegalStateException("The existing community membership is no longer in the local list")
        require(latest.root.equals(held.root, ignoreCase = true) &&
            latest.rootEpoch == held.rootEpoch &&
            (latest.controlPk ?: "").equals(held.controlPk ?: "", ignoreCase = true)
        ) {
            "The community membership changed while the key catch-up was being accepted"
        }

        val control = loadControlPlaneSnapshot(latest, requireAllResponders = true)
        val livePrivateChannels = control.state.channels.values
            .filter { it.definition.private && !it.definition.deleted }
            .mapTo(HashSet()) { it.channelIdHex.lowercase() }
        when (
            ConcordInviteVend.judgeCatchUp(
                authority = control.state.authority,
                privateChannelIds = livePrivateChannels,
                recipient = signer.pubKey,
                sender = opened.sender,
                bundle = invite,
                held = latest,
            )
        ) {
            ConcordInviteVend.CatchUpVerdict.ADOPT -> Unit
            ConcordInviteVend.CatchUpVerdict.NO_FOLD ->
                throw IllegalStateException("The community roster is not ready yet; refresh and retry this invite")
            ConcordInviteVend.CatchUpVerdict.NOTHING_NEW ->
                throw IllegalStateException("This Direct Invite carries no new private channel keys")
            ConcordInviteVend.CatchUpVerdict.BANNED ->
                throw IllegalStateException("A banned account cannot receive private channel keys")
            ConcordInviteVend.CatchUpVerdict.SENDER_NOT_STAFF ->
                throw IllegalStateException("Only a current community staff member can deliver private channel keys")
            ConcordInviteVend.CatchUpVerdict.NOT_ENTITLED ->
                throw IllegalStateException("The Control Plane does not grant access to every delivered private channel")
        }

        val updated = ConcordInviteVend.adoptCatchUp(latest, invite)
            ?: throw IllegalStateException("The delivered private channel keys did not advance the local keyring")
        // ConcordCommunityList.merge intentionally does not merge private keyrings on the same
        // epoch. Replace the exact entry so the new keys are durably persisted without changing
        // the community base or overwriting another membership.
        val nextEntries = snapshot.document.entries.map {
            if (it.id.equals(latest.id, ignoreCase = true)) updated else it
        }
        val nextDocument = ConcordCommunityList.encodeInternal(nextEntries, snapshot.document.residue)
        val writes = planCommunityListWrites(snapshot, nextDocument, System.currentTimeMillis() / 1000)
        val listEvents = writes.map { write ->
            NostrEvent.fromJson(
                ConcordCommunityListFragmentEvent.create(
                    signer = signer,
                    index = write.index,
                    plaintext = write.plaintext,
                    createdAt = write.createdAt,
                ).toJson(),
            )
        }
        withContext(Dispatchers.IO) { journalEvents(latest.id, listEvents) }
        var published = 0
        val pending = mutableListOf<String>()
        for (event in listEvents) {
            if (publishConfirmed(event, emptyList())) published++ else pending += event.id
        }
        return JoinResult(
            communityId = latest.id,
            name = latest.name,
            generalChannelId = getLocalGeneralChannelId(latest.id) ?: restoreGeneralChannelId(latest),
            publishedCount = published,
            totalCount = listEvents.size,
            pendingEventIds = pending,
        )
    }

    private suspend fun joinCommunityFromBundle(
        signer: PostillaQuartzSigner,
        invite: CommunityInvite,
        inviteRef: String?,
    ): JoinResult {
        val communityIdBytes = invite.communityId.hexToByteArray()
        val communityRootBytes = invite.communityRoot.hexToByteArray()
        val controlKeys = ControlPlaneKeys.of(
            communityRoot = communityRootBytes,
            communityId = communityIdBytes,
            epoch = invite.rootEpoch,
            controlPk = invite.controlPk,
        )
        val communityRelays = invite.relays.distinct().take(5)
        require(communityRelays.isNotEmpty()) { "The invite bundle contains no community bootstrap relays" }
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
                    ?: throw IllegalStateException("The decrypted wrap is not a canonical Control Plane edition")
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

        val currentList = fetchCommunityList(signer)
        requireSafeCommunityListForWrite(currentList, "Joining a community")
        val existingMembership = currentList.document.entries.firstOrNull { it.id == invite.communityId }
        val memberControlRoot = when {
            state.authority.isOwner(signer.pubKey) -> {
                if (existingMembership == null ||
                    existingMembership.root != invite.communityRoot ||
                    existingMembership.rootEpoch != invite.rootEpoch
                ) {
                    throw IllegalStateException("The owner must restore a matching private membership list; an invite link does not disclose the owner's Control Root")
                }
                existingMembership.controlRoot
                    ?: throw IllegalStateException("The owner's Control Root is missing from the private membership list")
            }
            state.authority.effectivePermissions(signer.pubKey).hasAny(ConcordPermissions.STAFF_BITS) -> {
                val grantHead = state.authority.grantHead(signer.pubKey)
                    ?: throw IllegalStateException("A staff member has no honored Grant head")
                val grantId = ConcordKeyDerivation.grantCoordinate(
                    communityIdBytes,
                    signer.pubKey.hexToByteArray(),
                ).toHexKey()
                val grantEdition = editions.firstOrNull {
                    it.entityKind == ControlEntityKind.GRANT &&
                        it.entityIdHex == grantId &&
                        it.version == grantHead.version &&
                        it.hashHex == grantHead.hashHex
                } ?: throw IllegalStateException("The staff Grant edition is missing from the Control Plane")
                val grant = ConcordJson.instance.decodeFromString(GrantEntity.serializer(), grantEdition.content)
                val wrappedRoot = grant.controlWrap
                    ?: throw IllegalStateException("The staff Grant does not carry a Control Root")
                val delivered = ControlRootWrap.openOrNull(wrappedRoot, signer, grantEdition.author)
                    ?: throw IllegalStateException("The Control Root could not be opened from the staff Grant")
                val controlPk = invite.controlPk
                    ?: throw IllegalStateException("This legacy invite has no split Control Plane public key")
                if (delivered.epoch != invite.rootEpoch ||
                    !ControlRootWrap.derivesTo(delivered.controlRoot, communityIdBytes, invite.rootEpoch, controlPk)
                ) {
                    throw IllegalStateException("The delivered Control Root does not match this invite's Control Plane")
                }
                delivered.controlRoot.toHexKey()
            }
            else -> null
        }

        val savedRelays = metadata.relays.distinct().take(5).ifEmpty { communityRelays }
        val newEntry = ConcordCommunityListEntry(
            id = invite.communityId,
            owner = invite.owner,
            ownerSalt = invite.ownerSalt,
            root = invite.communityRoot,
            rootEpoch = invite.rootEpoch,
            controlPk = invite.controlPk,
            controlRoot = memberControlRoot,
            privateChannels = invite.channels
                .filter { grant ->
                    grant.key.isNotBlank() &&
                        state.channels[grant.id.lowercase()]?.let {
                            it.definition.private && !it.definition.deleted
                        } == true &&
                        ConcordInviteVend.isEntitled(state.authority, signer.pubKey, grant.id)
                }
                .map { PrivateChannelKey(it.id, it.key, it.epoch, it.name) },
            relays = savedRelays,
            name = metadata.name.ifBlank { invite.name },
            addedAt = System.currentTimeMillis(),
            inviteRef = inviteRef,
        )

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
        val writes = planCommunityListWrites(currentList, nextDocument, System.currentTimeMillis() / 1000)
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
    ): List<NostrEvent> = fetchEventsFromRelaysDetailed(
        relays = relays,
        filter = filter,
        label = label,
        requireAllResponders = requireAllResponders,
    ).events

    private suspend fun fetchEventsFromRelaysDetailed(
        relays: List<String>,
        filter: Filter,
        label: String,
        requireAllResponders: Boolean = false,
    ): RelayFetchResult {
        val targets = relays.distinct()
        require(targets.isNotEmpty()) { "No bootstrap relays were supplied for $label discovery" }
        val subscriptionId = "concord-$label-${UUID.randomUUID().toString().take(12)}"
        val request = ClientMessage.req(subscriptionId, listOf(filter))
        val events = java.util.concurrent.ConcurrentHashMap<String, NostrEvent>()
        val responded = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        var sent: List<String> = emptyList()

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
            sent = targets.filter {
                relayPool.sendToRelayOrEphemeral(
                    it,
                    request,
                    skipBadCheck = true,
                    write = false,
                    concordStreamAuthors = filter.authors,
                )
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
        }
        val missing = targets.filterNot { it in responded }
        val summary = RelayReadSummary(
            requestedRelays = targets,
            sentRelays = sent,
            respondedRelays = responded.toList(),
        )
        if (requireAllResponders && !summary.isComplete) {
            throw IllegalStateException(
                "The $label data is incomplete: " + missing.size + "/" + targets.size +
                    " relays did not confirm the read.",
            )
        }
        return RelayFetchResult(events.values.toList(), summary)
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

        val controlRootHex = membership.controlRoot
            ?: throw IllegalStateException("This account does not hold the current Control Plane write key")
        val preflightKeys = ControlPlaneKeys.of(
            communityRoot = membership.root.hexToByteArray(),
            communityId = membership.id.hexToByteArray(),
            epoch = membership.rootEpoch,
            controlPk = membership.controlPk,
            controlRoot = controlRootHex,
        )
        val preflightWraps = fetchEventsFromRelays(
            relays = relays,
            filter = Filter(
                kinds = listOf(ConcordStreamEnvelope.KIND_WRAP),
                authors = listOf(preflightKeys.address),
                limit = 5000,
            ),
            label = "invite-permission-check",
            requireAllResponders = true,
        )
        val preflightEditions = preflightWraps.map { event ->
            val opened = ConcordStreamEnvelope.open(Event.fromJson(event.toJson()), preflightKeys)
            ControlEdition.fromOpened(opened)
                ?: throw IllegalStateException("A Control Plane edition is not canonical")
        }
        if (preflightEditions.isEmpty()) throw IllegalStateException("The current Control Plane could not be read")
        val preflightState = ConcordCommunityState.fold(
            preflightEditions,
            membership.id.hexToByteArray(),
            membership.owner,
        )
        if (preflightState.dissolved) throw IllegalStateException("This community has been dissolved")
        if (!preflightState.authority.hasPermission(signer.pubKey, ConcordPermissions.CREATE_INVITE)) {
            throw IllegalStateException("This account is not authorized to create community invites")
        }

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
                ) ?: throw IllegalStateException("The decrypted wrap is not a canonical Control Plane edition")
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
            published = state.inviteRegistries[signer.pubKey.lowercase()].orEmpty(),
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


    private fun retireInvite(
        document: ConcordInviteListDocument,
        token: String,
        communityId: String,
    ): ConcordInviteListDocument {
        if (document.tombstones.any { it.token == token }) return document
        return ConcordInviteListDocument(
            entries = document.entries.filterNot { it.token == token },
            tombstones = document.tombstones + ConcordInviteListTombstone(token, communityId),
            residue = document.residue,
            opaqueEntries = document.opaqueEntries,
            opaqueTombstones = document.opaqueTombstones,
        )
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


    private data class RefoundingPlanEvents(
        val control: List<NostrEvent>,
        val inviteList: List<NostrEvent>,
        val registry: List<NostrEvent>,
        val inviteBundles: List<NostrEvent>,
        val kick: List<NostrEvent>,
        val rekey: List<NostrEvent>,
        val membership: List<NostrEvent>,
        val guestbook: List<NostrEvent>,
    ) {
        fun all(): List<NostrEvent> = control + inviteList + registry + inviteBundles + kick + rekey + membership + guestbook
    }

    private fun pendingRefounding(communityId: String): PendingRefounding? {
        val prefix = "pending_refounding_$communityId."
        val root = securePrefs.getString(prefix + "root", null)?.hexToByteArray() ?: return null
        val controlRoot = securePrefs.getString(prefix + "control_root", null)?.hexToByteArray() ?: return null
        val epoch = securePrefs.getLong(prefix + "epoch", -1L)
        val prevCommit = securePrefs.getString(prefix + "prev_commit", null) ?: return null
        val id = securePrefs.getString(prefix + "community_id", null) ?: return null
        if (epoch < 0L) return null
        return PendingRefounding(id, epoch, prevCommit, root, controlRoot)
    }

    private fun savePendingRefounding(pending: PendingRefounding) {
        val prefix = "pending_refounding_${pending.communityId}."
        securePrefs.edit()
            .putString(prefix + "community_id", pending.communityId)
            .putLong(prefix + "epoch", pending.rootEpoch)
            .putString(prefix + "prev_commit", pending.prevCommit)
            .putString(prefix + "root", pending.newRoot.toHexKey())
            .putString(prefix + "control_root", pending.newControlRoot.toHexKey())
            .commit()
    }

    private fun clearPendingRefounding(communityId: String) {
        val prefix = "pending_refounding_$communityId."
        securePrefs.edit()
            .remove(prefix + "community_id")
            .remove(prefix + "epoch")
            .remove(prefix + "prev_commit")
            .remove(prefix + "root")
            .remove(prefix + "control_root")
            .commit()
        val editor = prefs.edit().remove(refoundingReadyKey(communityId))
        REFUNDING_CATEGORIES.forEach { editor.remove(refoundingIdsKey(communityId, it)) }
        editor.commit()
    }

    private fun storePendingRefoundingPlan(communityId: String, plan: RefoundingPlanEvents) {
        journalEvents(communityId, plan.all())
        val editor = prefs.edit().putBoolean(refoundingReadyKey(communityId), true)
        val groups = mapOf(
            "control" to plan.control,
            "invite_list" to plan.inviteList,
            "registry" to plan.registry,
            "invite_bundles" to plan.inviteBundles,
            "kick" to plan.kick,
            "rekey" to plan.rekey,
            "membership" to plan.membership,
            "guestbook" to plan.guestbook,
        )
        groups.forEach { (category, events) ->
            editor.putStringSet(refoundingIdsKey(communityId, category), events.map { it.id }.toSet())
        }
        editor.commit()
    }

    private fun loadPendingRefoundingPlan(communityId: String): RefoundingPlanEvents {
        require(prefs.getBoolean(refoundingReadyKey(communityId), false)) {
            "The refounding key reservation exists but its signed event plan has not been saved"
        }
        val journal = getJournaledEvents(communityId).associateBy { it.id }
        fun group(category: String): List<NostrEvent> =
            prefs.getStringSet(refoundingIdsKey(communityId, category), emptySet()).orEmpty().map { id ->
                journal[id] ?: throw IllegalStateException("A saved refounding event is missing from the local journal")
            }
        return RefoundingPlanEvents(
            control = group("control"),
            inviteList = group("invite_list"),
            registry = group("registry"),
            inviteBundles = group("invite_bundles"),
            kick = group("kick"),
            rekey = group("rekey"),
            membership = group("membership"),
            guestbook = group("guestbook"),
        )
    }

    private suspend fun publishRefoundingPlan(
        communityId: String,
        relays: List<String>,
        plan: RefoundingPlanEvents,
    ) {
        suspend fun publishGroup(events: List<NostrEvent>, extraRelays: List<String>, label: String) {
            for (event in events) {
                if (!publishConfirmed(event, extraRelays)) {
                    throw IllegalStateException("$label publication was not confirmed; the same signed events are retained for retry")
                }
            }
        }
        publishGroup(plan.control, relays, "Compacted Control Plane")
        publishGroup(plan.inviteList, emptyList(), "Private Invite List")
        publishGroup(plan.registry, relays, "Invite Registry")
        publishGroup(plan.inviteBundles, relays, "Refreshed invite bundles")
        publishGroup(plan.kick, relays, "Guestbook kick")
        publishGroup(plan.rekey, relays, "Base rekey")
        publishGroup(plan.membership, emptyList(), "Rotated membership list")
        publishGroup(plan.guestbook, relays, "New Guestbook")
    }

    private fun refoundingReadyKey(communityId: String) = "refounding_ready_$communityId"
    private fun refoundingIdsKey(communityId: String, category: String) = "refounding_${category}_$communityId"

    private fun eventKey(communityId: String) = "events_$communityId"
    private fun generalChannelKey(communityId: String) = "general_channel_$communityId"

    private companion object {
        const val PREFS_NAME = "concord_community_journal"
        private const val PREF_DECLINED_DIRECT_INVITES = "declined_direct_invite_wrap_ids"
        val REFUNDING_CATEGORIES = listOf("control", "invite_list", "registry", "invite_bundles", "kick", "rekey", "membership", "guestbook")
        val journalLock = Any()
    }
}
