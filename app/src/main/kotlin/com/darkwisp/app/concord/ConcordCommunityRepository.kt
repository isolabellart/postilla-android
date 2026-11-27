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
import com.vitorpamplona.quartz.concord.cord03Channels.ConcordChannelKeyring
import com.vitorpamplona.quartz.concord.cord03Channels.ConcordChannelKeys
import com.vitorpamplona.quartz.concord.cord02Community.HeldRoot
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityState
import com.vitorpamplona.quartz.concord.cord02Community.Guestbook
import com.vitorpamplona.quartz.concord.cord02Community.GuestbookAction
import com.vitorpamplona.quartz.concord.cord02Community.GuestbookEntry
import com.vitorpamplona.quartz.concord.cord04Roles.ControlEdition
import com.vitorpamplona.quartz.concord.cord04Roles.ChannelEntity
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordDirectInvite
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
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteLink
import com.vitorpamplona.quartz.concord.cord05Invites.InviteBundleStatus
import com.vitorpamplona.quartz.concord.crypto.ControlPlaneKeys
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
import kotlinx.serialization.json.JsonObject
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
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val securePrefs: SharedPreferences = EncryptedSharedPreferences.create(
        appContext,
        "concord_secrets",
        MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

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
        val control = loadControlPlaneSnapshot(membership)
        val authority = control.state.authority
        if (!authority.hasPermission(signer.pubKey, ConcordPermissions.MANAGE_ROLES)) {
            throw IllegalStateException("This account cannot manage community roles")
        }
        val actorRank = authority.rank(signer.pubKey)
            ?: throw IllegalStateException("The actor has no valid role rank")
        require(actorRank < position) { "A role cannot be created at or above the actor's authority" }

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
        val target = memberPubKey.lowercase()
        val targetBytes = target.hexToByteArray()
        require(targetBytes.size == 32) { "Member public key must be 32 bytes" }
        val membership = loadLocalMemberships(signer).firstOrNull { it.id == communityId }
            ?: throw IllegalArgumentException("Community membership is not available")
        val control = loadControlPlaneSnapshot(membership)
        val authority = control.state.authority
        if (!authority.hasPermission(signer.pubKey, ConcordPermissions.MANAGE_ROLES)) {
            throw IllegalStateException("This account cannot manage community roles")
        }
        require(authority.canActOn(signer.pubKey, target, ConcordPermissions.MANAGE_ROLES)) {
            "The actor cannot manage this member's roles"
        }
        require(!authority.isBanned(target)) { "A banned member cannot receive a role" }
        val roleIdHex = roleId.lowercase()
        val role = authority.roles()[roleIdHex]
            ?: throw IllegalArgumentException("Role does not exist in the current Control Plane")
        require(!role.deleted && role.isWellFormedAt(roleIdHex)) { "Role is not assignable" }
        val actorRank = authority.rank(signer.pubKey)
            ?: throw IllegalStateException("The actor has no valid role rank")
        require(actorRank < role.position) { "The actor does not outrank the role being granted" }

        val roleIds = (authority.rolesOf(target) + roleIdHex).distinct()
        require(roleIds.size > authority.rolesOf(target).size) { "The member already holds this role" }
        require(roleIds.all { assignedId -> authority.roles()[assignedId]?.let { actorRank < it.position } == true }) {
            "The actor does not outrank every role in the resulting Grant"
        }
        val effective = roleIds.fold(ConcordPermissions.NONE) { acc, id ->
            authority.roles()[id]?.let { acc union it.permissionBits() } ?: acc
        }
        val controlRoot = membership.controlRoot
        val controlWrap = if (effective.hasAny(ConcordPermissions.STAFF_BITS)) {
            val root = controlRoot ?: throw IllegalStateException("The current Control Plane write key is unavailable")
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
        val event = publishControlEdition(
            signer = signer,
            membership = membership,
            control = control,
            entityKind = ControlEntityKind.GRANT,
            entityId = grantId,
            content = content,
            permission = ConcordPermissions.MANAGE_ROLES,
            previousVersion = head?.version,
            previousHash = head?.hashHex,
        )
        return event.id
    }

    suspend fun revokeRole(
        signer: PostillaQuartzSigner,
        communityId: String,
        memberPubKey: String,
        roleId: String,
    ): String {
        val target = memberPubKey.lowercase()
        val targetBytes = target.hexToByteArray()
        require(targetBytes.size == 32) { "Member public key must be 32 bytes" }
        val membership = loadLocalMemberships(signer).firstOrNull { it.id == communityId }
            ?: throw IllegalArgumentException("Community membership is not available")
        val control = loadControlPlaneSnapshot(membership)
        val authority = control.state.authority
        if (!authority.hasPermission(signer.pubKey, ConcordPermissions.MANAGE_ROLES)) {
            throw IllegalStateException("This account cannot manage community roles")
        }
        require(authority.canActOn(signer.pubKey, target, ConcordPermissions.MANAGE_ROLES)) {
            "The actor cannot manage this member's roles"
        }
        val hadStaffPermissions = authority.rolesOf(target).any { assignedId ->
            authority.roles()[assignedId]?.permissionBits()?.hasAny(ConcordPermissions.STAFF_BITS) == true
        }
        val roleIds = authority.rolesOf(target).filterNot { it == roleId.lowercase() }
        require(roleIds.size < authority.rolesOf(target).size) { "The member does not hold this role" }
        val actorRank = authority.rank(signer.pubKey)
            ?: throw IllegalStateException("The actor has no valid role rank")
        require(roleIds.all { assignedId -> authority.roles()[assignedId]?.let { actorRank < it.position } == true }) {
            "The actor does not outrank every role in the resulting Grant"
        }
        val effective = roleIds.fold(ConcordPermissions.NONE) { acc, id ->
            authority.roles()[id]?.let { acc union it.permissionBits() } ?: acc
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
        val control = loadControlPlaneSnapshot(membership)
        if (!control.state.authority.hasPermission(signer.pubKey, ConcordPermissions.MANAGE_ROLES)) {
            throw IllegalStateException("This account cannot manage community roles")
        }
        val roleIdHex = roleIdBytes.toHexKey()
        val current = control.state.authority.roles()[roleIdHex]
            ?: throw IllegalArgumentException("Role does not exist")
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
        val control = loadControlPlaneSnapshot(membership)
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
        val control = loadControlPlaneSnapshot(membership)
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
        val fetchedControl = loadControlPlaneSnapshot(membership)
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
        val canKick: Boolean = false,
        val canBan: Boolean = false,
        val requiresRefounding: Boolean = false,
    )

    private data class ControlPlaneSnapshot(
        val keys: ControlPlaneKeys,
        val wraps: List<Event>,
        val editions: List<ControlEdition>,
        val state: ConcordCommunityState,
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
        val events = fetchEventsFromRelays(
            relays = membership.relays,
            filter = Filter(
                kinds = listOf(ConcordStreamEnvelope.KIND_WRAP),
                authors = listOf(keys.address),
                limit = 5000,
            ),
            label = "control-state",
            requireAllResponders = requireAllResponders,
        )
        val wraps = events.map { Event.fromJson(it.toJson()) }
        val editions = wraps.map { wrap ->
            val opened = ConcordStreamEnvelope.open(wrap, keys)
            ControlEdition.fromOpened(opened)
                ?: throw IllegalStateException("A Control Plane edition is not canonical")
        }
        if (editions.isEmpty()) throw IllegalStateException("The community Control Plane is empty or unavailable")
        val state = ConcordCommunityState.fold(editions, membership.id.hexToByteArray(), membership.owner)
        return ControlPlaneSnapshot(keys, wraps, editions, state)
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
    )

    /** Loads the live Control Plane channel catalogue and every held CORD-03 read plane. */
    suspend fun loadCommunityChannels(membership: ConcordCommunityListEntry): List<CommunityChannel> {
        val control = loadControlPlaneSnapshot(membership, requireAllResponders = false)
        return control.state.channels.values
            .map { channel ->
                val channelId = channel.channelIdHex
                val privateEntry = if (channel.definition.private) {
                    membership.privateChannels.firstOrNull {
                        it.channelId.equals(channelId, ignoreCase = true)
                    }
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
                val historicalReadKeys = if (channel.definition.private) {
                    ConcordChannelKeyring.historicalKeys(membership, channelId).map { old ->
                        old.epoch to ConcordChannelKeys.privateChannel(
                            old.key.hexToByteArray(), channelId.hexToByteArray(), old.epoch,
                        )
                    }
                } else {
                    membership.heldRoots
                        .sortedByDescending { it.epoch }
                        .take(8)
                        .map { old ->
                            old.epoch to ConcordChannelKeys.publicChannel(
                                old.key.hexToByteArray(), channelId.hexToByteArray(), old.epoch,
                            )
                        }
                }
                val readKeys = buildList {
                    if (currentReadKey != null) add((privateEntry?.epoch ?: membership.rootEpoch) to currentReadKey)
                    addAll(historicalReadKeys)
                }.distinctBy { (channelEpoch, key) -> "${channelEpoch}:${key.publicKeyHex}" }
                val directPlanes = readKeys.map { (channelEpoch, key) ->
                    ConcordChatReadPlane(key, channelEpoch)
                }
                CommunityChannel(
                    id = channelId,
                    name = channel.definition.name,
                    isPrivate = channel.definition.private,
                    privateKeyHex = privateEntry?.key,
                    privateChannelEpoch = privateEntry?.epoch,
                    readPlanes = directPlanes.distinctBy {
                        "${it.stream.publicKeyHex}:${it.channelEpoch}"
                    },
                )
            }
            .sortedWith(compareBy<CommunityChannel> { it.name.lowercase() }.thenBy { it.id })
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
        val control = loadControlPlaneSnapshot(membership)
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
        val control = loadControlPlaneSnapshot(membership)
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
        val control = loadControlPlaneSnapshot(membership)
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
    suspend fun loadMembers(
        signer: PostillaQuartzSigner,
        communityId: String,
        observedAuthors: Map<String, ConcordObservedAuthorActivity> = emptyMap(),
    ): List<CommunityMember> {
        val membership = loadLocalMemberships(signer).firstOrNull { it.id == communityId }
            ?: throw IllegalArgumentException("Community membership is not available")
        val control = loadControlPlaneSnapshot(membership)
        val guestbookKey = ConcordKeyDerivation.guestbookPlaneKey(
            communityRoot = membership.root.hexToByteArray(),
            communityId = membership.id.hexToByteArray(),
            epoch = membership.rootEpoch,
        )
        val events = fetchEventsFromRelays(
            relays = membership.relays,
            filter = Filter(
                kinds = listOf(ConcordStreamEnvelope.KIND_WRAP),
                authors = listOf(guestbookKey.publicKeyHex),
                limit = 5000,
            ),
            label = "guestbook",
            // Membership visibility must degrade gracefully when a relay is silent.
            // A partial Guestbook is useful for display; it must never authorize destructive actions.
            requireAllResponders = false,
        )
        val entries = events.mapNotNull { event ->
            try {
                val opened = ConcordStreamEnvelope.open(Event.fromJson(event.toJson()), guestbookKey)
                Guestbook.parse(opened.rumor)
            } catch (_: Exception) {
                null
            }
        }
        val authority = control.state.authority
        val isPublic = control.state.isPublic
        val coalesced = Guestbook.coalesce(entries, System.currentTimeMillis(), authority)
        val members = LinkedHashMap<String, CommunityMember>()

        // Self-reported Guestbook JOINs are the primary membership signal.
        for (entry in coalesced.values.sortedBy { it.createdAt }) {
            if (entry.action != GuestbookAction.JOIN) continue
            val pubkey = entry.member.lowercase()
            if (authority.isBanned(pubkey)) continue
            val activity = observedAuthors[pubkey]
            members[pubkey] = CommunityMember(
                pubkey = pubkey,
                joinedAt = entry.createdAt,
                inviteCreator = entry.inviteCreator,
                inviteLabel = entry.inviteLabel,
                lastSeenAt = activity?.lastSeenAtMs ?: entry.ms,
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
                members[pubkey] = CommunityMember(
                    pubkey = pubkey,
                    joinedAt = (activity.firstSeenAtMs / 1000L).coerceAtLeast(0L),
                    lastSeenAt = activity.lastSeenAtMs,
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

        val channels = control.state.channels.values.mapNotNull { channel ->
            val privateEntry = if (channel.definition.private) {
                membership.privateChannels.firstOrNull {
                    it.channelId.equals(channel.channelIdHex, ignoreCase = true)
                }
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
        val cursor = prefs.getLong("concord_direct_invites_newest_wrap", 0L).takeIf { it > 0L }
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
        val joinedIds = try {
            loadLocalMemberships(signer).mapTo(HashSet()) { it.id }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptySet()
        }
        val declined = prefs.getStringSet(PREF_DECLINED_DIRECT_INVITES, emptySet()).orEmpty()
        val visible = opened.filterNot {
            it.invite.communityId in joinedIds || it.wrapId.lowercase() in declined
        }
        val newest = events.maxOfOrNull { it.created_at } ?: cursor
        if (newest != null && newest > (cursor ?: 0L)) {
            withContext(Dispatchers.IO) {
                prefs.edit().putLong("concord_direct_invites_newest_wrap", newest).apply()
            }
        }
        return visible
    }

    /** Remember an explicit decline so the same NIP-59 wrap does not resurface on the next sweep. */
    fun declineDirectInvite(wrapId: String) {
        val declined = prefs.getStringSet(PREF_DECLINED_DIRECT_INVITES, emptySet()).orEmpty().toMutableSet()
        declined.add(wrapId.lowercase())
        prefs.edit().putStringSet(PREF_DECLINED_DIRECT_INVITES, declined).apply()
    }

    suspend fun joinCommunityFromDirectInvite(
        signer: PostillaQuartzSigner,
        opened: OpenedDirectInvite,
    ): JoinResult {
        if (opened.isExpired()) throw IllegalStateException("This Direct Invite has expired")
        if (!ConcordInviteBundle.validate(opened.invite)) {
            throw IllegalStateException("This Direct Invite failed community identity validation")
        }
        return joinCommunityFromBundle(signer, opened.invite, inviteRef = null)
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
                .filter { it.key.isNotBlank() }
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
