package com.darkwisp.app.concord

import android.content.Context
import com.darkwisp.app.db.GroupMessageEntity
import com.darkwisp.app.db.GroupMessageEntity_
import com.darkwisp.app.db.WispObjectBox
import com.darkwisp.app.nostr.ClientMessage
import com.darkwisp.app.nostr.Filter
import com.darkwisp.app.nostr.NostrEvent
import com.darkwisp.app.relay.RelayPool
import com.vitorpamplona.quartz.concord.cord03Channels.ChannelChat
import com.vitorpamplona.quartz.concord.cord03Channels.ConcordImageAttachment
import com.vitorpamplona.quartz.nip92IMeta.IMetaTag
import com.vitorpamplona.quartz.concord.cord03Channels.ConcordChannelKeys
import com.vitorpamplona.quartz.concord.crypto.GroupKey
import com.vitorpamplona.quartz.concord.envelope.ConcordStreamEnvelope
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import io.objectbox.query.QueryBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ConcordChatMessage(
    val id: String,
    val authorPubkey: String,
    val content: String,
    val createdAt: Long,
    val imageAttachments: List<ConcordImageAttachment> = emptyList(),
    val kind: Int = 9,
    val replyToId: String? = null,
    val rootId: String? = null,
    val orderingAtMs: Long = createdAt * 1000L,
    /** Signed-inside, unsigned Nostr rumor JSON. It never contains an outer relay wrap. */
    val rawRumorJson: String = "",
)

/** A CORD-03 read plane: direct channel address or a historical held-epoch address. */
data class ConcordChatReadPlane(
    val stream: GroupKey,
    val channelEpoch: Long,
)

private data class ConcordRelayHistoryPage(
    val relayUrl: String,
    val events: List<NostrEvent>,
    val reachedEose: Boolean,
)

/**
 * Concord channel transport.
 *
 * The relay carries only encrypted kind-1059 wraps. The local ObjectBox cache stores
 * those outer encrypted events, never the decrypted message body. On screen entry the
 * cache is emitted first, followed by relay history and the reconnecting live stream.
 */
class ConcordChatRepository(
    private val relayPool: RelayPool,
    context: Context,
) {
    private val appContext = context.applicationContext
    private val historyPrefs = appContext.getSharedPreferences("concord_chat_history_v1", Context.MODE_PRIVATE)
    private val messageBox by lazy {
        check(WispObjectBox.isInitialized) { "Local message database is not initialized" }
        WispObjectBox.store.boxFor(GroupMessageEntity::class.java)
    }

    suspend fun sendMessage(
        signer: PostillaQuartzSigner,
        communityIdHex: String,
        communityRootHex: String,
        channelIdHex: String,
        epoch: Long,
        text: String,
        privateChannelKeyHex: String? = null,
        privateChannelEpoch: Long? = null,
        communityRelays: List<String> = emptyList(),
        extraTags: Array<Array<String>> = emptyArray(),
        imetas: List<IMetaTag> = emptyList(),
        replyTo: ConcordChatMessage? = null,
        inlineReply: Boolean = false,
        createdAt: Long = System.currentTimeMillis() / 1000,
    ): ConcordChatMessage? {
        val message = text.trim()
        require(message.isNotEmpty() || imetas.isNotEmpty()) { "Message cannot be empty" }
        val channelEpoch = privateChannelEpoch ?: epoch
        val parent = replyTo?.rawRumorJson?.takeIf { it.isNotBlank() }?.let(Event::fromJson)

        val rumor = when {
            parent != null && imetas.isNotEmpty() ->
                ChannelChat.imageReply(
                    authorPubKey = signer.pubKey,
                    channelId = channelIdHex,
                    epoch = channelEpoch,
                    text = message,
                    imetas = imetas,
                    parent = parent,
                    createdAt = createdAt,
                    extraTags = extraTags,
                )
            parent != null && inlineReply ->
                ChannelChat.inlineReply(
                    authorPubKey = signer.pubKey,
                    channelId = channelIdHex,
                    epoch = channelEpoch,
                    text = message,
                    parentId = parent.id,
                    parentAuthor = parent.pubKey,
                    createdAt = createdAt,
                    extraTags = extraTags,
                )
            parent != null ->
                ChannelChat.reply(
                    authorPubKey = signer.pubKey,
                    channelId = channelIdHex,
                    epoch = channelEpoch,
                    text = message,
                    parent = parent,
                    createdAt = createdAt,
                    extraTags = extraTags,
                )
            imetas.isNotEmpty() ->
                ChannelChat.imageMessage(
                    authorPubKey = signer.pubKey,
                    channelId = channelIdHex,
                    epoch = channelEpoch,
                    text = message,
                    imetas = imetas,
                    createdAt = createdAt,
                    extraTags = extraTags,
                )
            else ->
                ChannelChat.message(
                    authorPubKey = signer.pubKey,
                    channelId = channelIdHex,
                    epoch = channelEpoch,
                    text = message,
                    createdAt = createdAt,
                    extraTags = extraTags,
                )
        }

        val channelKey = channelKey(communityRootHex, channelIdHex, epoch, privateChannelKeyHex, privateChannelEpoch)
        val wrap = ConcordStreamEnvelope.wrap(
            rumor = rumor,
            stream = channelKey,
            authorSigner = signer,
            encrypted = true,
            createdAt = createdAt,
        )
        val event = NostrEvent.fromJson(wrap.toJson())
        val outcome = publish(event, communityRelays)
        if (!outcome) return null

        // Store only the encrypted outer event, so an ACKed message survives screen/app restarts.
        persistEncryptedWrap(signer.pubKey, communityIdHex, channelIdHex, event)
        return decode(event, listOf(ConcordChatReadPlane(channelKey, channelEpoch)), channelIdHex)
    }

    suspend fun sendReaction(
        signer: PostillaQuartzSigner,
        communityIdHex: String,
        communityRootHex: String,
        channelIdHex: String,
        epoch: Long,
        target: ConcordChatMessage,
        reaction: String,
        privateChannelKeyHex: String? = null,
        privateChannelEpoch: Long? = null,
        communityRelays: List<String> = emptyList(),
        createdAt: Long = System.currentTimeMillis() / 1000,
    ): Boolean {
        require(reaction.isNotBlank()) { "Reaction cannot be empty" }
        val channelEpoch = privateChannelEpoch ?: epoch
        val rumor = ChannelChat.reaction(
            authorPubKey = signer.pubKey,
            channelId = channelIdHex,
            epoch = channelEpoch,
            targetId = target.id,
            targetAuthor = target.authorPubkey,
            targetKind = target.kind,
            content = reaction,
            createdAt = createdAt,
        )
        val key = channelKey(communityRootHex, channelIdHex, epoch, privateChannelKeyHex, privateChannelEpoch)
        val event = NostrEvent.fromJson(
            ConcordStreamEnvelope.wrap(
                rumor = rumor,
                stream = key,
                authorSigner = signer,
                encrypted = true,
                createdAt = createdAt,
            ).toJson(),
        )
        if (!publish(event, communityRelays)) return false
        persistEncryptedWrap(signer.pubKey, communityIdHex, channelIdHex, event)
        return true
    }

    private suspend fun publish(event: NostrEvent, communityRelays: List<String>): Boolean {
        val eventMessage = ClientMessage.event(event)
        val personalWriteRelays = relayPool.getWriteRelayUrls().toSet()
        val outcome = relayPool.publishWithConfirmation(event.id) {
            var sent = relayPool.sendToWriteRelays(eventMessage)
            for (url in communityRelays.distinct().filterNot { it in personalWriteRelays }) {
                if (relayPool.sendToRelayOrEphemeral(url, eventMessage, skipBadCheck = true, write = true, autoReconnect = true)) sent++
            }
            sent
        }
        return outcome.confirmed
    }

    /**
     * Emits encrypted cached history first, then mounts a live subscription before
     * issuing REQ. The live collector is UNDISPATCHED to avoid losing the first
     * EVENT when a fast relay responds immediately.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeChannel(
        ownerPubkey: String,
        communityIdHex: String,
        communityRootHex: String,
        channelIdHex: String,
        epoch: Long,
        communityRelays: List<String> = emptyList(),
        privateChannelKeyHex: String? = null,
        privateChannelEpoch: Long? = null,
        readPlanes: List<ConcordChatReadPlane> = emptyList(),
    ): Flow<ConcordChatMessage> = channelFlow {
        val channelEpoch = privateChannelEpoch ?: epoch
        val channelKey = channelKey(communityRootHex, channelIdHex, epoch, privateChannelKeyHex, privateChannelEpoch)
        val planes = readPlanes.ifEmpty { listOf(ConcordChatReadPlane(channelKey, channelEpoch)) }
        val authorKeys = planes.map { it.stream.publicKeyHex }.distinct()
        val targets = (communityRelays + relayPool.getReadRelayUrls()).distinct()
        val roomKey = roomKey(ownerPubkey, communityIdHex, channelIdHex)
        val seen = ConcurrentHashMap.newKeySet<String>()

        val cached = withContext(Dispatchers.IO) { loadCachedWraps(roomKey) }
        for (outer in cached) {
            val message = decode(outer, planes, channelIdHex) ?: continue
            if (seen.add(message.id)) send(message)
        }

        val subscriptionId = "concord-live-${channelIdHex.take(12)}-${UUID.randomUUID().toString().take(8)}"
        val liveCollector = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            relayPool.relayEvents
                .filter {
                    it.subscriptionId == subscriptionId &&
                        it.relayUrl in targets &&
                        it.event.kind == ConcordStreamEnvelope.KIND_WRAP &&
                        it.event.pubkey in authorKeys
                }
                .collect { relayEvent ->
                    try {
                        val message = decode(relayEvent.event, planes, channelIdHex) ?: return@collect
                        persistEncryptedWrap(ownerPubkey, communityIdHex, channelIdHex, relayEvent.event)
                        if (seen.add(message.id)) send(message)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Malformed, unopenable or misbound wraps are ignored fail-closed.
                    }
                }
        }

        val liveSince = (System.currentTimeMillis() / 1000 - LIVE_OVERLAP_SECONDS).coerceAtLeast(0)
        val liveRequest = ClientMessage.req(
            subscriptionId,
            Filter(
                kinds = listOf(ConcordStreamEnvelope.KIND_WRAP),
                authors = authorKeys,
                since = liveSince,
                limit = 5000,
            ),
        )
        val sent = targets.count { url ->
            relayPool.sendToRelayOrEphemeral(url, liveRequest, skipBadCheck = true, write = false, autoReconnect = true)
        }
        if (sent == 0) {
            liveCollector.cancel()
            return@channelFlow
        }

        // Each relay owns an independent cursor. A slow or deep relay must not move
        // another relay's cursor forward and silently skip its older events.
        launch {
            val pages = fetchFirstHistoryPages(ownerPubkey, communityIdHex, channelIdHex, authorKeys, targets)
            for (outer in pages) {
                val message = decode(outer, planes, channelIdHex) ?: continue
                persistEncryptedWrap(ownerPubkey, communityIdHex, channelIdHex, outer)
                if (seen.add(message.id)) send(message)
            }
        }

        awaitClose {
            liveCollector.cancel()
            relayPool.closeOnAllRelays(subscriptionId)
        }
    }

    /** Fetches the next older page for each relay that has not been exhausted. */
    suspend fun loadOlder(
        ownerPubkey: String,
        communityIdHex: String,
        channelIdHex: String,
        readPlanes: List<ConcordChatReadPlane>,
        communityRelays: List<String>,
    ): List<ConcordChatMessage> {
        val targets = (communityRelays + relayPool.getReadRelayUrls()).distinct()
        val authorKeys = readPlanes.map { it.stream.publicKeyHex }.distinct()
        val wraps = coroutineScope {
            targets.map { relay ->
                async {
                    val exhausted = historyPrefs.getBoolean(exhaustedKey(ownerPubkey, communityIdHex, channelIdHex, relay), false)
                    if (exhausted) return@async emptyList()
                    val cursor = historyPrefs.getLong(cursorKey(ownerPubkey, communityIdHex, channelIdHex, relay), 0L)
                    val page = fetchRelayHistoryPage(relay, authorKeys, cursor.takeIf { it > 0L })
                    if (page.reachedEose) persistCursor(ownerPubkey, communityIdHex, channelIdHex, relay, page.events)
                    page.events
                }
            }.awaitAll().flatten().distinctBy { it.id }
        }
        val messages = wraps.mapNotNull { decode(it, readPlanes, channelIdHex) }
        for (wrap in wraps) persistEncryptedWrap(ownerPubkey, communityIdHex, channelIdHex, wrap)
        return messages.distinctBy { it.id }.sortedBy { it.orderingAtMs }
    }

    suspend fun hasOlder(
        ownerPubkey: String,
        communityIdHex: String,
        channelIdHex: String,
        communityRelays: List<String>,
    ): Boolean {
        val targets = (communityRelays + relayPool.getReadRelayUrls()).distinct()
        return targets.any { relay ->
            !historyPrefs.getBoolean(exhaustedKey(ownerPubkey, communityIdHex, channelIdHex, relay), false)
        }
    }

    private suspend fun fetchFirstHistoryPages(
        ownerPubkey: String,
        communityIdHex: String,
        channelIdHex: String,
        authorKeys: List<String>,
        targets: List<String>,
    ): List<NostrEvent> = coroutineScope {
        targets.map { relay ->
            async {
                val exhausted = historyPrefs.getBoolean(exhaustedKey(ownerPubkey, communityIdHex, channelIdHex, relay), false)
                val cursor = historyPrefs.getLong(cursorKey(ownerPubkey, communityIdHex, channelIdHex, relay), 0L)
                if (exhausted || cursor > 0L) emptyList()
                else {
                    val page = fetchRelayHistoryPage(relay, authorKeys, null)
                    if (page.reachedEose) persistCursor(ownerPubkey, communityIdHex, channelIdHex, relay, page.events)
                    page.events
                }
            }
        }.awaitAll().flatten().distinctBy { it.id }
    }

    private suspend fun fetchRelayHistoryPage(
        relayUrl: String,
        authorKeys: List<String>,
        until: Long?,
    ): ConcordRelayHistoryPage = coroutineScope {
        val subscriptionId = "concord-history-${UUID.randomUUID().toString().take(12)}"
        val events = ConcurrentHashMap<String, NostrEvent>()
        val reachedEose = CompletableDeferred<Unit>()
        val request = ClientMessage.req(
            subscriptionId,
            Filter(
                kinds = listOf(ConcordStreamEnvelope.KIND_WRAP),
                authors = authorKeys,
                until = until,
                limit = HISTORY_PAGE_SIZE,
            ),
        )

        val eventCollector = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            relayPool.relayEvents
                .filter { it.subscriptionId == subscriptionId && it.relayUrl == relayUrl }
                .collect { events.putIfAbsent(it.event.id, it.event) }
        }
        val eoseCollector = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            relayPool.relayEoseSignals
                .filter { it.subscriptionId == subscriptionId && it.relayUrl == relayUrl }
                .collect { reachedEose.complete(Unit) }
        }

        val sent = relayPool.sendToRelayOrEphemeral(
            relayUrl, request, skipBadCheck = true, write = false, autoReconnect = true,
        )
        if (!sent) {
            eventCollector.cancel()
            eoseCollector.cancel()
            relayPool.closeOnAllRelays(subscriptionId)
            return@coroutineScope ConcordRelayHistoryPage(relayUrl, emptyList(), false)
        }

        val complete = withTimeoutOrNull(HISTORY_PAGE_TIMEOUT_MS) {
            reachedEose.await()
            true
        } ?: false
        // EVENTS precede EOSE on a relay socket; this short drain also absorbs an event
        // already handed to RelayPool's shared flow while the EOSE collector resumes.
        if (complete) delay(100)
        eventCollector.cancel()
        eoseCollector.cancel()
        relayPool.closeOnAllRelays(subscriptionId)
        ConcordRelayHistoryPage(relayUrl, events.values.toList(), complete)
    }

    private fun persistCursor(
        ownerPubkey: String,
        communityIdHex: String,
        channelIdHex: String,
        relayUrl: String,
        events: List<NostrEvent>,
    ) {
        val oldest = events.minOfOrNull { it.created_at }
        val cursor = if (oldest == null) 0L else (oldest - 1L).coerceAtLeast(1L)
        val exhausted = events.size < HISTORY_PAGE_SIZE
        historyPrefs.edit()
            .putLong(cursorKey(ownerPubkey, communityIdHex, channelIdHex, relayUrl), cursor)
            .putBoolean(exhaustedKey(ownerPubkey, communityIdHex, channelIdHex, relayUrl), exhausted)
            .apply()
    }

    private suspend fun persistEncryptedWrap(
        ownerPubkey: String,
        communityIdHex: String,
        channelIdHex: String,
        event: NostrEvent,
    ) = withContext(Dispatchers.IO) {
        val key = roomKey(ownerPubkey, communityIdHex, channelIdHex)
        val box = messageBox
        val existing = box.query(GroupMessageEntity_.eventId.equal(event.id)).build().use { it.findFirst() }
        box.put(
            GroupMessageEntity(
                dbId = existing?.dbId ?: 0L,
                eventId = event.id,
                roomKey = key,
                senderPubkey = event.pubkey,
                content = event.toJson(), // Encrypted kind-1059 wrap only; plaintext rumors are never persisted.
                createdAt = event.created_at,
            ),
        )
    }

    private suspend fun loadCachedWraps(roomKey: String): List<NostrEvent> = withContext(Dispatchers.IO) {
        messageBox.query(GroupMessageEntity_.roomKey.equal(roomKey))
            .order(GroupMessageEntity_.createdAt, QueryBuilder.DESCENDING)
            .build()
            .use { query -> query.find(0, CACHE_MESSAGE_LIMIT.toLong()).asReversed() }
            .mapNotNull { stored -> runCatching { NostrEvent.fromJson(stored.content) }.getOrNull() }
    }

    private fun decode(
        outer: NostrEvent,
        planes: List<ConcordChatReadPlane>,
        channelIdHex: String,
    ): ConcordChatMessage? {
        if (outer.kind != ConcordStreamEnvelope.KIND_WRAP) return null
        val opened = planes.asSequence()
            .filter { it.stream.publicKeyHex.equals(outer.pubkey, ignoreCase = true) }
            .mapNotNull { plane ->
                runCatching {
                    val envelope = ConcordStreamEnvelope.open(Event.fromJson(outer.toJson()), plane.stream)
                    ChannelChat.acceptOpened(envelope, channelIdHex, plane.channelEpoch, ChannelChat.CHAT_KINDS)
                }.getOrNull()
            }
            .firstOrNull() ?: return null

        val replyTo = opened.tags.firstOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
            ?: opened.tags.firstOrNull { it.firstOrNull() == "q" }?.getOrNull(1)
        val root = opened.tags.firstOrNull { it.firstOrNull() == "E" }?.getOrNull(1)
        val orderingAt = ChannelChat.orderingMs(opened) ?: opened.createdAt * 1000L
        return ConcordChatMessage(
            id = opened.id,
            authorPubkey = opened.pubKey,
            content = opened.content,
            createdAt = opened.createdAt,
            imageAttachments = ChannelChat.encryptedImagesOf(opened),
            kind = opened.kind,
            replyToId = replyTo,
            rootId = root,
            orderingAtMs = orderingAt,
            rawRumorJson = opened.toJson(),
        )
    }

    private fun roomKey(owner: String, communityId: String, channelId: String) =
        "concord|${owner.lowercase()}|${communityId.lowercase()}|${channelId.lowercase()}"

    private fun cursorKey(owner: String, communityId: String, channelId: String, relay: String) =
        "cursor|${owner.lowercase()}|${communityId.lowercase()}|${channelId.lowercase()}|$relay"

    private fun exhaustedKey(owner: String, communityId: String, channelId: String, relay: String) =
        "exhausted|${owner.lowercase()}|${communityId.lowercase()}|${channelId.lowercase()}|$relay"

    private fun channelKey(
        communityRootHex: String,
        channelIdHex: String,
        epoch: Long,
        privateChannelKeyHex: String? = null,
        privateChannelEpoch: Long? = null,
    ) = if (!privateChannelKeyHex.isNullOrBlank()) {
        ConcordChannelKeys.privateChannel(
            channelKey = privateChannelKeyHex.hexToByteArray(),
            channelId = channelIdHex.hexToByteArray(),
            channelEpoch = privateChannelEpoch ?: epoch,
        )
    } else {
        ConcordChannelKeys.publicChannel(
            communityRoot = communityRootHex.hexToByteArray(),
            channelId = channelIdHex.hexToByteArray(),
            rootEpoch = epoch,
        )
    }

    companion object {
        private const val HISTORY_PAGE_SIZE = 100
        private const val HISTORY_PAGE_TIMEOUT_MS = 5000L
        private const val LIVE_OVERLAP_SECONDS = 60L
        private const val CACHE_MESSAGE_LIMIT = 500
    }
}
