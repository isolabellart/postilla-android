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
import com.vitorpamplona.quartz.concord.cord02Community.ConcordListFragments
import com.vitorpamplona.quartz.concord.cord05Invites.CommunityInvite
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteBundle
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteList
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteListDocument
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteListEntry as InviteListEntry
import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteListEvent
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonObject
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
    private val json = Json { ignoreUnknownKeys = true }

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

        val community = ConcordCommunityFactory.create(
            ownerSigner = signer,
            name = name.trim(),
            createdAt = createdAt,
            description = description?.trim()?.takeIf { it.isNotEmpty() },
            relays = relays.distinct(),
        )

        // Concord's kind-33302 list is fragmented and self-encrypted. Never persist
        // community_root/control_root as plaintext in app preferences or ObjectBox.
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
        val listDocument = json.parseToJsonElement(ConcordCommunityList.encode(listOf(entry))).jsonObject
        val fragmentPlaintexts = ConcordListFragments.pack(listDocument)
        require(fragmentPlaintexts.isNotEmpty()) { "Quartz produced an empty Community List" }

        val listEvents = fragmentPlaintexts.mapIndexed { index, plaintext ->
            ConcordCommunityListFragmentEvent.create(
                signer = signer,
                index = index,
                plaintext = plaintext,
                createdAt = createdAt,
            )
        }

        // The two genesis wraps and membership fragments are all saved locally before
        // network I/O. Their payloads are encrypted by the Concord envelope / NIP-44 list.
        val events = (community.genesisWraps + listEvents).map { NostrEvent.fromJson(it.toJson()) }
        withContext(Dispatchers.IO) {
            journalEvents(community.communityIdHex, events)
            prefs.edit().putString(generalChannelKey(community.communityIdHex), community.generalChannelIdHex).commit()
        }

        var published = 0
        val pending = mutableListOf<String>()
        for (event in events) {
            val outcome = relayPool.publishWithConfirmation(event.id) {
                relayPool.sendToWriteRelays(ClientMessage.event(event))
            }
            if (outcome.confirmed) {
                published++
            } else {
                pending += event.id
            }
        }

        return CreationResult(
            communityId = community.communityIdHex,
            generalChannelId = community.generalChannelIdHex,
            name = name.trim(),
            publishedCount = published,
            totalCount = events.size,
            pendingEventIds = pending,
        )
    }


    /**
     * Rebuilds locally journaled memberships from the self-encrypted kind-33302
     * fragments. This is a recovery path, not a substitute for relay discovery.
     */
    suspend fun loadLocalMemberships(signer: PostillaQuartzSigner): List<ConcordCommunityListEntry> {
        val encodedEvents = prefs.all.keys
            .filter { it.startsWith("events_") }
            .flatMap { key -> prefs.getStringSet(key, emptySet()).orEmpty() }
        val events = encodedEvents.mapNotNull { encoded ->
            runCatching { NostrEvent.fromJson(encoded) }.getOrNull()
        }.filter {
            it.kind == ConcordCommunityListFragmentEvent.KIND && it.pubkey == signer.pubKey
        }

        var merged: JsonObject? = null
        for (event in events) {
            try {
                val plaintext = signer.nip44Decrypt(event.content, signer.pubKey)
                val fragment = ConcordListFragments.decodeFragment(plaintext)
                merged = merged?.let { ConcordListFragments.mergeDocs(it, fragment.doc) } ?: fragment.doc
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // An unreadable local fragment is not treated as an empty membership list.
            }
        }
        return merged?.let { ConcordCommunityList.decodeDocument(it).entries } ?: emptyList()
    }

    fun getLocalGeneralChannelId(communityId: String): String? =
        prefs.getString(generalChannelKey(communityId), null)


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
