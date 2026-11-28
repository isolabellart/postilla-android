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
import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityState
import com.vitorpamplona.quartz.concord.cord03Channels.ConcordWritersLedger
import com.vitorpamplona.quartz.concord.cord03Channels.WriterWindow
import com.vitorpamplona.quartz.concord.cord04Roles.AuthorityResolver
import com.vitorpamplona.quartz.concord.cord04Roles.ConcordPermissions
import com.vitorpamplona.crypto.sha2.sha256
import com.vitorpamplona.quartz.utils.Secp256k1Instance
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArrayOrNull
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
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * ObjectBox's unique event key must be room-scoped: the same outer event can be
 * encountered through different accounts or room contexts without those cache rows
 * evicting one another.
 */
internal fun concordChatCacheRecordKey(roomKey: String, eventId: String): String {
    require(roomKey.isNotBlank()) { "Room key cannot be blank" }
    require(eventId.isNotBlank()) { "Event ID cannot be blank" }
    return "$roomKey|${eventId.lowercase()}"
}

/**
 * A history cursor is valid only for the exact set of read planes used by its filter.
 * A new writer window, a newly-held historical key, or a root/channel rotation changes
 * this signature and starts an independent cursor rather than skipping the new stream.
 */
internal fun concordChatHistoryStreamSignature(planes: List<ConcordChatReadPlane>): String {
    val canonical = planes.asSequence()
        .map { plane ->
            "${plane.stream.publicKeyHex.lowercase()}:${plane.channelEpoch}:${plane.writerContext?.window?.epoch ?: "read"}"
        }
        .distinct()
        .sorted()
        .joinToString("|")
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.encodeToByteArray())
    val digits = "0123456789abcdef"
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(digits[value ushr 4])
            append(digits[value and 0x0f])
        }
    }
}

/**
 * Restricted channels route normal messages to the writer stream while reactions and
 * deletions remain on the read stream. Locked channels route every channel event to writers.
 */
internal fun concordEventUsesWriterPlane(kind: Int, locked: Boolean): Boolean =
    locked || (kind != 7 && kind != 5)

/**
 * The rcert wire scope is narrower than a role permission. SEND_MESSAGES cannot be
 * granted by rcert; MANAGE_MESSAGES is checked independently by the caller.
 */
internal fun concordScopedWriterRightsCoverKind(kind: Int, rights: ULong): Boolean {
    val allowedMask =
        (1uL shl ConcordPermissions.ADD_REACTIONS) or
            (1uL shl ConcordPermissions.CONNECT)
    if ((rights and allowedMask.inv()) != 0uL) return false
    return when (kind) {
        7 -> (rights and (1uL shl ConcordPermissions.ADD_REACTIONS)) != 0uL
        5 -> true // MANAGE_MESSAGES is enforced separately; an rcert may legitimately carry rights=0.
        ChannelChat.KIND_TYPING -> (rights and (1uL shl ConcordPermissions.CONNECT)) != 0uL
        else -> false
    }
}

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

/** The authorization snapshot needed to accept a CORD-03 writer-address post. */
data class ConcordChatChannelContext(
    val communityIdHex: String,
    val channelIdHex: String,
    val communityState: ConcordCommunityState,
    val channelPrivate: Boolean,
    val locked: Boolean,
    val ledger: ConcordWritersLedger?,
)

/** The authorization snapshot needed to accept a CORD-03 writer-address post. */
data class ConcordChatWriterContext(
    val communityIdHex: String,
    val ledger: ConcordWritersLedger,
    val window: WriterWindow,
    val authority: AuthorityResolver,
    val channelPrivate: Boolean = false,
    val communityState: ConcordCommunityState? = null,
    val locked: Boolean = false,
)

/** In-memory restricted-stream credential. The secret never enters persistent storage. */
data class ConcordChatWriterCredential(
    val streamKey: GroupKey,
    val context: ConcordChatWriterContext,
    val certificate: String?,
    val canSendMessages: Boolean,
    val canManageChannel: Boolean,
    val scopedCertificate: String? = null,
    val scopedRights: ULong? = null,
    val canAddReactions: Boolean = false,
    val canConnect: Boolean = false,
    val canManageMessages: Boolean = false,
    val locked: Boolean = false,
)

/** A CORD-03 plane: a direct/historical read address or a writer address with its read key. */
data class ConcordChatReadPlane(
    val stream: GroupKey,
    val channelEpoch: Long,
    /** Non-null only for a writer address; carries the folded ledger and role snapshot. */
    val writerContext: ConcordChatWriterContext? = null,
    /** Permission and routing snapshot used for read-plane events as well as writer events. */
    val channelContext: ConcordChatChannelContext? = null,
)

/** CORD-03 scoped writer certificate digest, wire-compatible with Vector's rcert. */
internal fun concordScopedWriterCertificateDigest(
    communityId: ByteArray,
    channelId: ByteArray,
    epoch: Long,
    writerPubkey: ByteArray,
    rights: ULong,
): ByteArray {
    require(communityId.size == 32 && channelId.size == 32 && writerPubkey.size == 32)
    val epochBytes = ByteArray(8) { i -> (epoch ushr (56 - i * 8)).toByte() }
    val rightsBytes = ByteArray(8) { i -> (rights shr (56 - i * 8)).toByte() }
    return sha256(
        "concord/writer-cert-scoped".encodeToByteArray() +
            communityId + channelId + epochBytes + writerPubkey + rightsBytes,
    )
}

internal fun verifyConcordScopedWriterCertificate(
    communityId: ByteArray,
    channelId: ByteArray,
    epoch: Long,
    writerPubkeyHex: String,
    certifierHex: String,
    signatureHex: String,
    rights: ULong,
): Boolean {
    val signature = signatureHex.hexToByteArrayOrNull()?.takeIf { it.size == 64 } ?: return false
    val writer = writerPubkeyHex.hexToByteArrayOrNull()?.takeIf { it.size == 32 } ?: return false
    val certifier = certifierHex.hexToByteArrayOrNull()?.takeIf { it.size == 32 } ?: return false
    val digest = concordScopedWriterCertificateDigest(communityId, channelId, epoch, writer, rights)
    val eventId = ConcordWritersLedger.certificateEventId(certifierHex, digest)
    return try {
        Secp256k1Instance.verifySchnorr(signature, eventId, certifier)
    } catch (_: Exception) {
        false
    }
}

data class ConcordObservedAuthorActivity(
    val firstSeenAtMs: Long,
    val lastSeenAtMs: Long,
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
        writerCredential: ConcordChatWriterCredential? = null,
        createdAt: Long = System.currentTimeMillis() / 1000,
    ): ConcordChatMessage? {
        val message = text.trim()
        require(message.isNotEmpty() || imetas.isNotEmpty()) { "Message cannot be empty" }
        val channelEpoch = privateChannelEpoch ?: epoch
        val channelKey = channelKey(communityRootHex, channelIdHex, epoch, privateChannelKeyHex, privateChannelEpoch)
        val messageTags = writerExtraTags(extraTags, writerCredential, kind = 9)
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
                    extraTags = messageTags,
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
                    extraTags = messageTags,
                )
            parent != null ->
                ChannelChat.reply(
                    authorPubKey = signer.pubKey,
                    channelId = channelIdHex,
                    epoch = channelEpoch,
                    text = message,
                    parent = parent,
                    createdAt = createdAt,
                    extraTags = messageTags,
                )
            imetas.isNotEmpty() ->
                ChannelChat.imageMessage(
                    authorPubKey = signer.pubKey,
                    channelId = channelIdHex,
                    epoch = channelEpoch,
                    text = message,
                    imetas = imetas,
                    createdAt = createdAt,
                    extraTags = messageTags,
                )
            else ->
                ChannelChat.message(
                    authorPubKey = signer.pubKey,
                    channelId = channelIdHex,
                    epoch = channelEpoch,
                    text = message,
                    createdAt = createdAt,
                    extraTags = messageTags,
                )
        }

        val (event, outboundPlane) = encodeOutboundRumor(
            rumor = rumor,
            signer = signer,
            communityIdHex = communityIdHex,
            channelIdHex = channelIdHex,
            channelEpoch = channelEpoch,
            channelKey = channelKey,
            writerCredential = writerCredential,
            createdAt = createdAt,
        )
        val outcome = publish(event, communityRelays)
        if (!outcome) return null

        // Store only the encrypted outer event, so an ACKed message survives screen/app restarts.
        persistEncryptedWrap(signer.pubKey, communityIdHex, channelIdHex, event)
        return decode(event, listOf(outboundPlane), channelIdHex)
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
        writerCredential: ConcordChatWriterCredential? = null,
        createdAt: Long = System.currentTimeMillis() / 1000,
    ): Boolean {
        require(reaction.isNotBlank()) { "Reaction cannot be empty" }
        val channelEpoch = privateChannelEpoch ?: epoch
        val key = channelKey(communityRootHex, channelIdHex, epoch, privateChannelKeyHex, privateChannelEpoch)
        val reactionTags = writerExtraTags(emptyArray(), writerCredential, kind = 7)
        val rumor = ChannelChat.reaction(
            authorPubKey = signer.pubKey,
            channelId = channelIdHex,
            epoch = channelEpoch,
            targetId = target.id,
            targetAuthor = target.authorPubkey,
            targetKind = target.kind,
            content = reaction,
            createdAt = createdAt,
            extraTags = reactionTags,
        )
        val (event, _) = encodeOutboundRumor(
            rumor = rumor,
            signer = signer,
            communityIdHex = communityIdHex,
            channelIdHex = channelIdHex,
            channelEpoch = channelEpoch,
            channelKey = key,
            writerCredential = writerCredential,
            createdAt = createdAt,
        )
        if (!publish(event, communityRelays)) return false
        persistEncryptedWrap(signer.pubKey, communityIdHex, channelIdHex, event)
        return true
    }

    private fun writerExtraTags(
        existing: Array<Array<String>>,
        credential: ConcordChatWriterCredential?,
        kind: Int,
    ): Array<Array<String>> {
        require(existing.none {
            it.firstOrNull() == ConcordWritersLedger.WEPOCH ||
                it.firstOrNull() == ConcordWritersLedger.WCERT ||
                it.firstOrNull() == "rcert"
        }) { "Writer binding tags are reserved for the authorized writer credential" }
        val useWriter = credential != null && concordEventUsesWriterPlane(kind, credential.locked)
        if (!useWriter) return existing
        val allowed = when (kind) {
            7 -> credential!!.canAddReactions
            5 -> credential!!.canManageMessages
            ChannelChat.KIND_TYPING -> credential!!.canConnect
            else -> credential!!.canSendMessages
        }
        require(allowed) {
            when (kind) {
                7 -> "This account does not have ADD_REACTIONS in this channel"
                5 -> "This account does not have MANAGE_MESSAGES in this channel"
                ChannelChat.KIND_TYPING -> "This account does not have CONNECT in this channel"
                else -> "This account does not have SEND_MESSAGES in this channel"
            }
        }
        val base = existing + arrayOf(arrayOf(ConcordWritersLedger.WEPOCH, credential!!.context.window.epoch.toString()))
        credential.certificate?.let { return base + arrayOf(arrayOf(ConcordWritersLedger.WCERT, it)) }
        credential.scopedCertificate?.let { signature ->
            val rights = credential.scopedRights ?: throw IllegalStateException("Scoped writer certificate has no rights")
            return base + arrayOf(arrayOf("rcert", signature, rights.toString()))
        }
        require(credential.context.window.certifier == null || credential.canManageChannel) {
            "The current writer delivery has no certificate for this account"
        }
        return base
    }

    private suspend fun encodeOutboundRumor(
        rumor: Event,
        signer: PostillaQuartzSigner,
        communityIdHex: String,
        channelIdHex: String,
        channelEpoch: Long,
        channelKey: GroupKey,
        writerCredential: ConcordChatWriterCredential?,
        createdAt: Long,
    ): Pair<NostrEvent, ConcordChatReadPlane> {
        // Restricted channels route normal posts to the writer address. A reaction stays
        // on the read address unless the Channel is locked, in which case every kind rides
        // the writer plane and an ADD_REACTIONS-scoped certificate may be used.
        val writer = writerCredential?.takeIf { concordEventUsesWriterPlane(rumor.kind, it.locked) }
        val stream = if (writer == null) {
            channelKey
        } else {
            val context = writer.context
            val window = context.window
            val live = context.ledger.live
            val allowed = when (rumor.kind) {
                7 -> writer.canAddReactions
                5 -> writer.canManageMessages
                ChannelChat.KIND_TYPING -> writer.canConnect
                else -> writer.canSendMessages
            }
            require(allowed) {
                when (rumor.kind) {
                    7 -> "This account does not have ADD_REACTIONS in this channel"
                    5 -> "This account does not have MANAGE_MESSAGES in this channel"
                    ChannelChat.KIND_TYPING -> "This account does not have CONNECT in this channel"
                    else -> "This account does not have SEND_MESSAGES in this channel"
                }
            }
            require(!context.authority.isBanned(signer.pubKey)) { "This account is banned from the community" }
            require(live?.epoch == window.epoch && live.writerPubKey.equals(window.writerPubKey, ignoreCase = true)) {
                "The writer authorization changed; refresh the channel before sending"
            }
            require(writer.streamKey.publicKeyHex.equals(window.writerPubKey, ignoreCase = true)) {
                "The delivered writer key does not match the live writer address"
            }
            val postMs = ChannelChat.orderingMs(rumor)
                ?: throw IllegalStateException("The message has an invalid Concord ordering timestamp")
            require(postMs >= window.fromMs && (window.toMs == null || postMs < window.toMs)) {
                "The writer window is not active for this post"
            }
            val privileged = writer.canManageChannel || context.authority.isOwner(signer.pubKey)
            val stands = when {
                writer.certificate != null -> context.ledger.postStands(
                    context.communityIdHex.hexToByteArray(),
                    window,
                    signer.pubKey,
                    postMs,
                    writer.certificate,
                    privileged,
                )
                writer.scopedCertificate != null -> {
                    val rights = writer.scopedRights
                        ?: throw IllegalStateException("Scoped writer certificate has no rights")
                    require(concordScopedWriterRightsCoverKind(rumor.kind, rights)) {
                        "This scoped writer certificate does not authorize this event kind"
                    }
                    val covers = true
                    val certifier = window.certifier
                    covers && certifier != null &&
                        verifyConcordScopedWriterCertificate(
                            context.communityIdHex.hexToByteArray(),
                            channelIdHex.hexToByteArray(),
                            window.epoch,
                            signer.pubKey,
                            certifier,
                            writer.scopedCertificate,
                            rights,
                        ) && !context.ledger.isStopped(window, signer.pubKey, postMs)
                }
                else -> context.ledger.postStands(
                    context.communityIdHex.hexToByteArray(),
                    window,
                    signer.pubKey,
                    postMs,
                    null,
                    privileged,
                )
            }
            require(stands) { "The writer certificate or active writer window does not authorize this post" }
            GroupKey(writer.streamKey.secretKey, writer.streamKey.publicKey, channelKey.conversationKey)
        }

        val seal = ConcordStreamEnvelope.seal(rumor, stream, signer, encrypted = true)
        val wrap = if (writer == null) {
            ConcordStreamEnvelope.wrap(
                rumor = rumor,
                stream = channelKey,
                authorSigner = signer,
                encrypted = true,
                createdAt = createdAt,
            )
        } else {
            ConcordStreamEnvelope.wrapSeal(
                seal = seal,
                signerKey = stream,
                readConversationKey = channelKey.conversationKey,
                createdAt = createdAt,
            )
        }
        val event = NostrEvent.fromJson(wrap.toJson())
        val accessContext = writerCredential?.context?.let { context ->
            val state = context.communityState
            if (state == null) null else ConcordChatChannelContext(
                communityIdHex = context.communityIdHex,
                channelIdHex = channelIdHex,
                communityState = state,
                channelPrivate = context.channelPrivate,
                locked = context.locked,
                ledger = context.ledger,
            )
        }
        return event to ConcordChatReadPlane(
            stream = stream,
            channelEpoch = channelEpoch,
            writerContext = writer?.context,
            channelContext = accessContext,
        )
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
        val streamSignature = concordChatHistoryStreamSignature(planes)
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
            val pages = fetchFirstHistoryPages(ownerPubkey, communityIdHex, channelIdHex, streamSignature, authorKeys, targets)
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

    /**
     * CORD-02 §5 Complete Member List input: observe authors of valid decrypted Chat Plane
     * events. Guestbook alone is not enough because a Join can be missing or a late member
     * may publish before their Join reaches this client. Only cryptographically opened and
     * channel/epoch-bound rumors contribute.
     */
    suspend fun loadObservedAuthorActivity(
        ownerPubkey: String,
        communityIdHex: String,
        channels: List<ConcordCommunityRepository.CommunityChannel>,
        communityRelays: List<String>,
    ): Map<String, ConcordObservedAuthorActivity> {
        val usable = channels.mapNotNull { channel ->
            val planes = channel.readPlanes
            val authors = planes.map { it.stream.publicKeyHex }.distinct()
            if (authors.isEmpty()) null else channel to (planes to authors)
        }
        if (usable.isEmpty()) return emptyMap()

        val activity = LinkedHashMap<String, ConcordObservedAuthorActivity>()
        val authorsToChannels = HashMap<String, MutableList<Pair<ConcordCommunityRepository.CommunityChannel, List<ConcordChatReadPlane>>>>()
        for ((channel, pair) in usable) {
            val (planes, authors) = pair
            for (author in authors) {
                authorsToChannels.getOrPut(author.lowercase()) { mutableListOf() }.add(channel to planes)
            }
            // Local encrypted cache is always useful even when every relay is currently silent.
            val cached = withContext(Dispatchers.IO) {
                loadCachedWraps(roomKey(ownerPubkey, communityIdHex, channel.id))
            }
            for (wrap in cached) {
                val decoded = decode(wrap, planes, channel.id) ?: continue
                mergeObserved(activity, decoded)
            }
        }

        val targets = (communityRelays + relayPool.getReadRelayUrls()).distinct()
        val filters = usable.map { (_, pair) ->
            Filter(
                kinds = listOf(ConcordStreamEnvelope.KIND_WRAP),
                authors = pair.second,
                limit = MEMBER_ACTIVITY_PAGE_SIZE,
            )
        }
        if (targets.isEmpty() || filters.isEmpty()) return activity

        coroutineScope {
            targets.map { relayUrl ->
                async {
                    val subscriptionId = "concord-members-${UUID.randomUUID().toString().take(12)}"
                    val events = ConcurrentHashMap<String, NostrEvent>()
                    val eose = CompletableDeferred<Unit>()
                    val request = ClientMessage.req(subscriptionId, filters)
                    val eventCollector = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        relayPool.relayEvents
                            .filter { it.subscriptionId == subscriptionId && it.relayUrl == relayUrl }
                            .collect { events.putIfAbsent(it.event.id, it.event) }
                    }
                    val eoseCollector = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        relayPool.relayEoseSignals
                            .filter { it.subscriptionId == subscriptionId && it.relayUrl == relayUrl }
                            .collect { eose.complete(Unit) }
                    }
                    val sent = relayPool.sendToRelayOrEphemeral(
                        relayUrl, request, skipBadCheck = true, write = false, autoReconnect = true,
                    )
                    if (sent) {
                        val completed = withTimeoutOrNull(HISTORY_PAGE_TIMEOUT_MS) {
                            eose.await()
                            true
                        } ?: false
                        if (completed) delay(100)
                    }
                    eventCollector.cancel()
                    eoseCollector.cancel()
                    relayPool.closeOnAllRelays(subscriptionId)

                    for (wrap in events.values) {
                        val candidates = authorsToChannels[wrap.pubkey.lowercase()].orEmpty()
                        for ((channel, planes) in candidates) {
                            val decoded = decode(wrap, planes, channel.id) ?: continue
                            persistEncryptedWrap(ownerPubkey, communityIdHex, channel.id, wrap)
                            mergeObserved(activity, decoded)
                            break
                        }
                    }
                }
            }.awaitAll()
        }
        return activity
    }

    private fun mergeObserved(
        target: MutableMap<String, ConcordObservedAuthorActivity>,
        message: ConcordChatMessage,
    ) {
        val key = message.authorPubkey.lowercase()
        val current = target[key]
        target[key] = if (current == null) {
            ConcordObservedAuthorActivity(message.orderingAtMs, message.orderingAtMs)
        } else {
            ConcordObservedAuthorActivity(
                firstSeenAtMs = minOf(current.firstSeenAtMs, message.orderingAtMs),
                lastSeenAtMs = maxOf(current.lastSeenAtMs, message.orderingAtMs),
            )
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
        val streamSignature = concordChatHistoryStreamSignature(readPlanes)
        val wraps = coroutineScope {
            targets.map { relay ->
                async {
                    val exhausted = historyPrefs.getBoolean(
                        exhaustedKey(ownerPubkey, communityIdHex, channelIdHex, streamSignature, relay),
                        false,
                    )
                    if (exhausted) return@async emptyList()
                    val cursor = historyPrefs.getLong(
                        cursorKey(ownerPubkey, communityIdHex, channelIdHex, streamSignature, relay),
                        0L,
                    )
                    val page = fetchRelayHistoryPage(relay, authorKeys, cursor.takeIf { it > 0L })
                    if (page.reachedEose) {
                        persistCursor(ownerPubkey, communityIdHex, channelIdHex, streamSignature, relay, page.events)
                    }
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
        readPlanes: List<ConcordChatReadPlane> = emptyList(),
    ): Boolean {
        val targets = (communityRelays + relayPool.getReadRelayUrls()).distinct()
        val streamSignature = concordChatHistoryStreamSignature(readPlanes)
        return targets.any { relay ->
            !historyPrefs.getBoolean(
                exhaustedKey(ownerPubkey, communityIdHex, channelIdHex, streamSignature, relay),
                false,
            )
        }
    }

    private suspend fun fetchFirstHistoryPages(
        ownerPubkey: String,
        communityIdHex: String,
        channelIdHex: String,
        streamSignature: String,
        authorKeys: List<String>,
        targets: List<String>,
    ): List<NostrEvent> = coroutineScope {
        targets.map { relay ->
            async {
                val exhausted = historyPrefs.getBoolean(
                    exhaustedKey(ownerPubkey, communityIdHex, channelIdHex, streamSignature, relay),
                    false,
                )
                val cursor = historyPrefs.getLong(
                    cursorKey(ownerPubkey, communityIdHex, channelIdHex, streamSignature, relay),
                    0L,
                )
                if (exhausted || cursor > 0L) emptyList()
                else {
                    val page = fetchRelayHistoryPage(relay, authorKeys, null)
                    if (page.reachedEose) {
                        persistCursor(ownerPubkey, communityIdHex, channelIdHex, streamSignature, relay, page.events)
                    }
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
        streamSignature: String,
        relayUrl: String,
        events: List<NostrEvent>,
    ) {
        val oldest = events.minOfOrNull { it.created_at }
        val cursor = if (oldest == null) 0L else (oldest - 1L).coerceAtLeast(1L)
        val exhausted = events.size < HISTORY_PAGE_SIZE
        historyPrefs.edit()
            .putLong(cursorKey(ownerPubkey, communityIdHex, channelIdHex, streamSignature, relayUrl), cursor)
            .putBoolean(exhaustedKey(ownerPubkey, communityIdHex, channelIdHex, streamSignature, relayUrl), exhausted)
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
        val recordId = concordChatCacheRecordKey(key, event.id)

        // The previous cache keyed rows only by the Nostr event ID. ObjectBox therefore
        // allowed an event received by a second account/community/channel to replace the
        // first room's row. Keep the existing schema, but scope the unique value to its room.
        val existingScoped = box.query(GroupMessageEntity_.eventId.equal(recordId))
            .build().use { it.findFirst() }
        // Migrate an old row in place only when it already belongs to this exact room.
        // A legacy row belonging to another room must remain untouched.
        val legacy = if (existingScoped == null) {
            box.query(GroupMessageEntity_.eventId.equal(event.id)).build().use { it.findFirst() }
                ?.takeIf { it.roomKey == key }
        } else {
            null
        }
        val existing = existingScoped ?: legacy

        box.put(
            GroupMessageEntity(
                dbId = existing?.dbId ?: 0L,
                eventId = recordId,
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
                    val writer = plane.writerContext
                    if (writer == null) {
                        val rumor = ChannelChat.acceptOpened(
                            envelope,
                            channelIdHex,
                            plane.channelEpoch,
                            ChannelChat.CHAT_KINDS,
                        ) ?: return@runCatching null
                        acceptReadPlanePost(rumor, channelIdHex, plane)
                    } else {
                        val rumor = ChannelChat.acceptWriterOpened(
                            envelope,
                            channelIdHex,
                            plane.channelEpoch,
                            writer.window.epoch,
                            ChannelChat.CHAT_KINDS,
                        ) ?: return@runCatching null
                        acceptWriterPost(rumor, channelIdHex, plane, writer)
                    }
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

    private fun permissionAllows(
        state: ConcordCommunityState?,
        channelIdHex: String,
        channelPrivate: Boolean,
        author: String,
        kind: Int,
    ): Boolean {
        if (state?.authority?.isBanned(author) == true) return false
        // Legacy Concord roles have no channel-scoped CORD-04 permission model.
        if (state == null || !state.isRolesV2) return true
        val permissions = state.channelPermissions(author, channelIdHex, isPrivate = channelPrivate)
        return when (kind) {
            7 -> permissions.has(ConcordPermissions.ADD_REACTIONS)
            5 -> permissions.has(ConcordPermissions.MANAGE_MESSAGES)
            ChannelChat.KIND_TYPING -> permissions.has(ConcordPermissions.CONNECT)
            else -> permissions.has(ConcordPermissions.SEND_MESSAGES)
        }
    }

    private fun acceptReadPlanePost(
        rumor: Event,
        channelIdHex: String,
        plane: ConcordChatReadPlane,
    ): Event? {
        val context = plane.channelContext ?: return rumor
        if (!permissionAllows(
                context.communityState,
                channelIdHex,
                context.channelPrivate,
                rumor.pubKey,
                rumor.kind,
            )
        ) return null

        val postMs = ChannelChat.orderingMs(rumor) ?: return null
        val matchingWriterWindow = context.ledger?.windows?.firstOrNull { window ->
            postMs >= window.fromMs && (window.toMs == null || postMs < window.toMs)
        }
        if (matchingWriterWindow != null) {
            // In restricted (not locked) channels ordinary posts belong to the writer
            // stream, while reactions and deletes remain on the read stream. A locked
            // channel moves every kind to the writer address.
            if (concordEventUsesWriterPlane(rumor.kind, context.locked)) return null
        }
        return rumor
    }

    private fun acceptWriterPost(
        rumor: Event,
        channelIdHex: String,
        plane: ConcordChatReadPlane,
        writer: ConcordChatWriterContext,
    ): Event? {
        if (!writer.window.writerPubKey.equals(plane.stream.publicKeyHex, ignoreCase = true)) return null
        val foldedWindow = writer.ledger.windowAt(plane.stream.publicKeyHex) ?: return null
        if (foldedWindow.epoch != writer.window.epoch) return null
        if (!concordEventUsesWriterPlane(rumor.kind, writer.locked)) return null

        val author = rumor.pubKey
        if (!permissionAllows(writer.communityState, channelIdHex, writer.channelPrivate, author, rumor.kind)) return null
        val postMs = ChannelChat.orderingMs(rumor) ?: return null
        if (postMs < writer.window.fromMs || (writer.window.toMs != null && postMs >= writer.window.toMs)) return null

        val state = writer.communityState
        val authorPermissions = state?.takeIf { it.isRolesV2 }?.channelPermissions(
            author,
            channelIdHex,
            isPrivate = writer.channelPrivate,
        )
        val privileged = writer.authority.isOwner(author) ||
            (!writer.authority.isBanned(author) &&
                (authorPermissions?.has(ConcordPermissions.MANAGE_CHANNELS)
                    ?: writer.authority.hasPermission(author, ConcordPermissions.MANAGE_CHANNELS)))
        val certifier = writer.window.certifier
        val fullTags = rumor.tags.count { it.firstOrNull() == ConcordWritersLedger.WCERT }
        val scopedTags = rumor.tags.filter { it.firstOrNull() == "rcert" }
        if (fullTags > 1 || scopedTags.size > 1 || (fullTags > 0 && scopedTags.isNotEmpty())) return null

        val fullCertificate = ConcordWritersLedger.wcertOf(rumor)
        if (fullTags == 1 && fullCertificate == null) return null
        val scoped = scopedTags.singleOrNull()?.let { tag ->
            if (tag.size != 3) return null
            val signature = tag.getOrNull(1)?.takeIf {
                it.length == 128 && it.hexToByteArrayOrNull()?.size == 64
            } ?: return null
            val rightsText = tag.getOrNull(2) ?: return null
            val rights = rightsText.toULongOrNull()?.takeIf { it.toString() == rightsText } ?: return null
            signature to rights
        }

        val stands = when {
            fullCertificate != null -> writer.ledger.postStands(
                writer.communityIdHex.hexToByteArray(),
                writer.window,
                author,
                postMs,
                fullCertificate,
                privileged,
            )
            scoped != null -> {
                val (signature, rights) = scoped
                val scopedMask =
                    (1uL shl ConcordPermissions.ADD_REACTIONS) or
                        (1uL shl ConcordPermissions.CONNECT)
                if ((rights and scopedMask.inv()) != 0uL) return null
                concordScopedWriterRightsCoverKind(rumor.kind, rights) && certifier != null &&
                    verifyConcordScopedWriterCertificate(
                        writer.communityIdHex.hexToByteArray(),
                        channelIdHex.hexToByteArray(),
                        writer.window.epoch,
                        author,
                        certifier,
                        signature,
                        rights,
                    ) && !writer.ledger.isStopped(writer.window, author, postMs)
            }
            else -> writer.ledger.postStands(
                writer.communityIdHex.hexToByteArray(),
                writer.window,
                author,
                postMs,
                null,
                privileged,
            )
        }
        return rumor.takeIf { stands }
    }

    private fun roomKey(owner: String, communityId: String, channelId: String) =
        "concord|${owner.lowercase()}|${communityId.lowercase()}|${channelId.lowercase()}"

    private fun cursorKey(
        owner: String,
        communityId: String,
        channelId: String,
        streamSignature: String,
        relay: String,
    ) = "cursor|${owner.lowercase()}|${communityId.lowercase()}|${channelId.lowercase()}|$streamSignature|$relay"

    private fun exhaustedKey(
        owner: String,
        communityId: String,
        channelId: String,
        streamSignature: String,
        relay: String,
    ) = "exhausted|${owner.lowercase()}|${communityId.lowercase()}|${channelId.lowercase()}|$streamSignature|$relay"

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
        private const val MEMBER_ACTIVITY_PAGE_SIZE = 500
    }
}
