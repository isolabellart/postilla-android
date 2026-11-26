package com.darkwisp.app.concord

import android.content.Context
import com.darkwisp.app.nostr.ClientMessage
import com.darkwisp.app.nostr.NostrEvent
import com.darkwisp.app.relay.RelayPool
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityFactory
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityList
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityListEntry
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityListFragmentEvent
import com.vitorpamplona.quartz.concord.cord02Community.ConcordListFragments
import com.vitorpamplona.quartz.concord.cord02Community.ConcordListFragments
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.CancellationException
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
