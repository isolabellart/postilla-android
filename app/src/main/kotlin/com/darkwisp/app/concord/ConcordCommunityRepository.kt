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
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityList
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityListEntry
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityListFragmentEvent
import com.vitorpamplona.quartz.concord.cord02Community.ConcordListFragmentSet
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityListDocument
import com.vitorpamplona.quartz.concord.cord02Community.PrivateChannelKey
import com.vitorpamplona.quartz.concord.cord02Community.HeldRoot
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityState
import com.vitorpamplona.quartz.concord.cord02Community.Guestbook
import com.vitorpamplona.quartz.concord.cord02Community.GuestbookAction
import com.vitorpamplona.quartz.concord.cord02Community.GuestbookEntry
import com.vitorpamplona.quartz.concord.cord04Roles.ControlEdition
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
import com.vitorpamplona.quartz.concord.cord05Invites.CommunityInvite
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
        val role = authority.roles()[roleId]
            ?: throw IllegalArgumentException("Role does not exist in the current Control Plane")
        require(!role.deleted && role.isWellFormedAt(roleId)) { "Role is not assignable" }
        val actorRank = authority.rank(signer.pubKey)
            ?: throw IllegalStateException("The actor has no valid role rank")
        require(actorRank < role.position) { "The actor does not outrank the role being granted" }

        val roleIds = (authority.rolesOf(target) + roleId.lowercase()).distinct()
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
        if (hadStaffPermissions && !effective.hasAny(ConcordPermissions.STAFF_BITS)) {
            refoundCommunity(signer, communityId, extraControlEvents = listOf(event))
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
        val event = publishControlEdition(
            signer = signer,
            membership = membership,
            control = control,
            entityKind = ControlEntityKind.ROLE,
            entityId = roleIdBytes,
            content = content,
            permission = ConcordPermissions.MANAGE_ROLES,
            previousVersion = head?.version,
            previousHash = head?.hashHex,
        )
        if (current.permissionBits().hasAny(ConcordPermissions.STAFF_BITS)) {
            refoundCommunity(signer, communityId, extraControlEvents = listOf(event))
        }
        return event.id
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
        val event = NostrEvent.fromJson(
            ConcordStreamEnvelope.wrap(
                rumor = rumor,
                keys = control.keys,
                authorSigner = signer,
                encrypted = false,
            ).toJson(),
        )
        withContext(Dispatchers.IO) { journalEvents(membership.id, listOf(event)) }
        if (!publishConfirmed(event, membership.relays)) {
            throw IllegalStateException("Control Plane edition was not confirmed by a community relay")
        }
        return event
    }



    suspend fun kickAndRotate(
        signer: PostillaQuartzSigner,
        communityId: String,
        targetPubKey: String,
    ): RefoundingResult {
        val target = targetPubKey.lowercase()
        require(target.hexToByteArray().size == 32) { "Target public key must be 32 bytes" }
        val membership = loadLocalMemberships(signer).firstOrNull { it.id == communityId }
            ?: throw IllegalArgumentException("Community membership is not available")
        val control = loadControlPlaneSnapshot(membership)
        val authority = control.state.authority
        if (!authority.canActOn(signer.pubKey, target, ConcordPermissions.KICK)) {
            throw IllegalStateException("This account cannot kick the selected member")
        }
        if (!authority.hasPermission(signer.pubKey, ConcordPermissions.BAN)) {
            throw IllegalStateException("A kick must be followed by an authorized whole-community rotation")
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
        withContext(Dispatchers.IO) { journalEvents(communityId, listOf(kickEvent)) }
        if (!publishConfirmed(kickEvent, membership.relays)) {
            throw IllegalStateException("The Kick was not confirmed; no root rotation was attempted")
        }
        return refoundCommunity(
            signer = signer,
            communityId = communityId,
            excludedMembers = setOf(target),
            extraGuestbookEntries = listOf(kickEntry),
        )
    }

    suspend fun banAndRotate(
        signer: PostillaQuartzSigner,
        communityId: String,
        targetPubKey: String,
    ): RefoundingResult {
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
        val banEvent = publishControlEdition(
            signer = signer,
            membership = membership,
            control = control,
            entityKind = ControlEntityKind.BANLIST,
            entityId = banlistId,
            content = content,
            permission = ConcordPermissions.BAN,
            previousVersion = head?.version,
            previousHash = head?.hashHex,
        )
        return refoundCommunity(
            signer = signer,
            communityId = communityId,
            excludedMembers = setOf(target),
            extraControlEvents = listOf(banEvent),
        )
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
        extraControlEvents: List<NostrEvent> = emptyList(),
    ): RefoundingResult {
        val membership = loadLocalMemberships(signer).firstOrNull { it.id == communityId }
            ?: throw IllegalArgumentException("Community membership is not available")
        val pending = pendingRefounding(communityId)

        if (pending != null && prefs.getBoolean(refoundingReadyKey(communityId), false)) {
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
        val foreignInviteCreators = control.state.inviteRegistries.keys
            .filter { it.isNotBlank() && it != signer.pubKey.lowercase() }
        if (foreignInviteCreators.isNotEmpty()) {
            throw IllegalStateException("Other members own live invite links; those creators must refresh or revoke their links before this community can rotate")
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
                    updatedInviteList = ConcordInviteList.revoke(updatedInviteList, entry.token, nowSecs)
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
            residue = oldEntry.residue,
            extras = oldEntry.extras,
        )
        val mergedEntries = ConcordCommunityList.merge(currentList.document.entries, listOf(rotatedEntry))
        val nextDocument = ConcordCommunityList.encodeInternal(mergedEntries, currentList.document.residue)
        val listWrites = currentList.fragments.planWrites(nextDocument, nowSecs)
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
    )

    private data class ControlPlaneSnapshot(
        val keys: ControlPlaneKeys,
        val wraps: List<Event>,
        val editions: List<ControlEdition>,
        val state: ConcordCommunityState,
    )

    private suspend fun loadControlPlaneSnapshot(
        membership: ConcordCommunityListEntry,
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
            requireAllResponders = true,
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

    /** Reads and coalesces the Guestbook against the currently folded authority roster. */
    suspend fun loadMembers(
        signer: PostillaQuartzSigner,
        communityId: String,
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
            requireAllResponders = true,
        )
        val entries = events.mapNotNull { event ->
            try {
                val opened = ConcordStreamEnvelope.open(Event.fromJson(event.toJson()), guestbookKey)
                Guestbook.parse(opened.rumor)
            } catch (_: Exception) {
                null
            }
        }
        return Guestbook.coalesce(entries, System.currentTimeMillis(), control.state.authority)
            .values
            .filter { it.action == GuestbookAction.JOIN }
            .sortedBy { it.createdAt }
            .map {
                CommunityMember(
                    pubkey = it.member,
                    joinedAt = it.createdAt,
                    inviteCreator = it.inviteCreator,
                    inviteLabel = it.inviteLabel,
                )
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
        val rekey: List<NostrEvent>,
        val membership: List<NostrEvent>,
        val guestbook: List<NostrEvent>,
    ) {
        fun all(): List<NostrEvent> = control + inviteList + registry + inviteBundles + rekey + membership + guestbook
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
        val REFUNDING_CATEGORIES = listOf("control", "invite_list", "registry", "invite_bundles", "rekey", "membership", "guestbook")
        val journalLock = Any()
    }
}
