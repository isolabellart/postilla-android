package com.darkwisp.app.concord

import com.darkwisp.app.nostr.ClientMessage
import com.darkwisp.app.nostr.Filter
import com.darkwisp.app.nostr.NostrEvent
import com.darkwisp.app.relay.RelayPool
import com.vitorpamplona.quartz.concord.cord03Channels.ChannelChat
import com.vitorpamplona.quartz.concord.cord03Channels.ConcordImageAttachment
import com.vitorpamplona.quartz.nip92IMeta.IMetaTag
import com.vitorpamplona.quartz.concord.cord03Channels.ConcordChannelKeys
import com.vitorpamplona.quartz.concord.envelope.ConcordStreamEnvelope
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

data class ConcordChatMessage(
    val id: String,
    val authorPubkey: String,
    val content: String,
    val createdAt: Long,
    val imageAttachments: List<ConcordImageAttachment> = emptyList(),
)

/**
 * CORD-03 channel transport. All cryptographic derivation, rumor construction,
 * wrapping and validation are delegated to Quartz 2.15.1.
 */
class ConcordChatRepository(
    private val relayPool: RelayPool,
) {
    private val seenMessageIds = ConcurrentHashMap.newKeySet<String>()

    suspend fun sendMessage(
        signer: PostillaQuartzSigner,
        communityRootHex: String,
        channelIdHex: String,
        epoch: Long,
        text: String,
        privateChannelKeyHex: String? = null,
        privateChannelEpoch: Long? = null,
        communityRelays: List<String> = emptyList(),
        extraTags: Array<Array<String>> = emptyArray(),
        imetas: List<IMetaTag> = emptyList(),
        createdAt: Long = System.currentTimeMillis() / 1000,
    ): ConcordChatMessage? {
        val message = text.trim()
        require(message.isNotEmpty() || imetas.isNotEmpty()) { "Message cannot be empty" }

        val channelKey = channelKey(communityRootHex, channelIdHex, epoch, privateChannelKeyHex, privateChannelEpoch)
        val rumor = if (imetas.isNotEmpty()) {
            ChannelChat.imageMessage(
                authorPubKey = signer.pubKey,
                channelId = channelIdHex,
                epoch = epoch,
                text = message,
                imetas = imetas,
                createdAt = createdAt,
                extraTags = extraTags,
            )
        } else {
            ChannelChat.message(
                authorPubKey = signer.pubKey,
                channelId = channelIdHex,
                epoch = epoch,
                text = message,
                createdAt = createdAt,
                extraTags = extraTags,
            )
        }
        val wrap = ConcordStreamEnvelope.wrap(
            rumor = rumor,
            stream = channelKey,
            authorSigner = signer,
            encrypted = true,
            createdAt = createdAt,
        )
        val event = NostrEvent.fromJson(wrap.toJson())
        val eventMessage = ClientMessage.event(event)
        val personalWriteRelays = relayPool.getWriteRelayUrls().toSet()
        val outcome = relayPool.publishWithConfirmation(event.id) {
            var sent = relayPool.sendToWriteRelays(eventMessage)
            for (url in communityRelays.distinct().filterNot { it in personalWriteRelays }) {
                if (relayPool.sendToRelayOrEphemeral(url, eventMessage, skipBadCheck = true, write = true)) sent++
            }
            sent
        }
        if (!outcome.confirmed) return null
        seenMessageIds.add(rumor.id)
        return ConcordChatMessage(
            id = rumor.id,
            authorPubkey = rumor.pubKey,
            content = rumor.content,
            createdAt = rumor.createdAt,
            imageAttachments = ChannelChat.encryptedImagesOf(rumor),
        )
    }

    /**
     * Observes one channel's write-restricted stream. The subscription is opened
     * before REQ is sent, and every received wrap is cryptographically opened and
     * checked against the exact channel/epoch binding before it reaches the UI.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeChannel(
        communityRootHex: String,
        channelIdHex: String,
        epoch: Long,
        communityRelays: List<String> = emptyList(),
        since: Long? = null,
        privateChannelKeyHex: String? = null,
    ): Flow<ConcordChatMessage> = channelFlow {
        val channelKey = channelKey(communityRootHex, channelIdHex, epoch, privateChannelKeyHex)
        val targets = communityRelays.distinct().ifEmpty { relayPool.getReadRelayUrls() }
        val subscriptionId = "concord-chat-${channelIdHex.take(12)}-$epoch-${UUID.randomUUID().toString().take(8)}"

        val collector = launch {
            relayPool.relayEvents
                .filter {
                    it.subscriptionId == subscriptionId &&
                        it.relayUrl in targets &&
                        it.event.kind == ConcordStreamEnvelope.KIND_WRAP
                }
                .collect { relayEvent ->
                    try {
                        val wrap = Event.fromJson(relayEvent.event.toJson())
                        val opened = ConcordStreamEnvelope.open(wrap, channelKey)
                        val rumor = opened.rumor
                        if (rumor.kind !in ChannelChat.CHAT_KINDS) return@collect
                        if (!ChannelChat.isBoundTo(rumor, channelIdHex, epoch)) return@collect
                        if (rumor.kind != 9 || !seenMessageIds.add(rumor.id)) return@collect

                        send(
                            ConcordChatMessage(
                                id = rumor.id,
                                authorPubkey = rumor.pubKey,
                                content = rumor.content,
                                createdAt = rumor.createdAt,
                                imageAttachments = ChannelChat.encryptedImagesOf(rumor),
                            )
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Unopenable, misbound or malformed wraps are ignored fail-closed.
                    }
                }
        }

        val request = ClientMessage.req(
            subscriptionId,
            listOf(
                Filter(
                    kinds = listOf(ConcordStreamEnvelope.KIND_WRAP),
                    authors = listOf(channelKey.publicKeyHex),
                    since = since,
                    limit = 500,
                ),
            ),
        )
        val sent = targets.count { url ->
            relayPool.sendToRelayOrEphemeral(url, request, skipBadCheck = true, write = false)
        }
        if (sent == 0) {
            collector.cancel()
            return@channelFlow
        }

        awaitClose {
            collector.cancel()
            relayPool.closeOnAllRelays(subscriptionId)
        }
    }

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
            rootEpoch = epoch,
        )
    } else {
        ConcordChannelKeys.publicChannel(
            communityRoot = communityRootHex.hexToByteArray(),
            channelId = channelIdHex.hexToByteArray(),
            rootEpoch = epoch,
        )
    }
}
