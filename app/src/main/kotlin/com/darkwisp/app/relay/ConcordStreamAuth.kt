package com.darkwisp.app.relay

import com.darkwisp.app.nostr.NostrEvent
import com.vitorpamplona.quartz.concord.crypto.GroupKey
import com.vitorpamplona.quartz.nip01Core.core.Event as QuartzEvent
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair as QuartzKeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal

/** Builds the relay- and challenge-bound NIP-42 payload without loading crypto providers. */
internal fun concordStreamAuthTags(relayUrl: String, challenge: String): List<List<String>> {
    require(relayUrl.isNotBlank()) { "A relay URL is required for NIP-42 AUTH" }
    require(challenge.isNotBlank()) { "A relay challenge is required for NIP-42 AUTH" }
    return listOf(
        listOf("relay", relayUrl),
        listOf("challenge", challenge),
    )
}

/**
 * Signs NIP-42 as a Concord stream address. The stream secret is never persisted
 * and is scoped by the caller to the community relay that owns that stream.
 */
internal suspend fun signConcordStreamAuthEvent(
    key: GroupKey,
    relayUrl: String,
    challenge: String,
    createdAtSeconds: Long = System.currentTimeMillis() / 1000L,
): NostrEvent {
    require(key.secretKey.size == 32) { "A Concord stream secret must be exactly 32 bytes" }
    val signer = NostrSignerInternal(QuartzKeyPair(privKey = key.secretKey.copyOf()))
    require(signer.pubKey.equals(key.publicKeyHex, ignoreCase = true)) {
        "The Concord stream secret does not match its advertised stream address"
    }

    val tags = concordStreamAuthTags(relayUrl, challenge).map { it.toTypedArray() }.toTypedArray()
    val signed = signer.sign<QuartzEvent>(createdAtSeconds, 22242, tags, "")
    return NostrEvent.fromJson(signed.toJson())
}
