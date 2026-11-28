package com.darkwisp.app.relay

import com.darkwisp.app.nostr.NostrEvent
import com.vitorpamplona.quartz.concord.crypto.GroupKey
import com.vitorpamplona.quartz.nip01Core.core.Event as QuartzEvent
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair as QuartzKeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal

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
    require(relayUrl.isNotBlank()) { "A relay URL is required for NIP-42 AUTH" }
    require(challenge.isNotBlank()) { "A relay challenge is required for NIP-42 AUTH" }

    val signer = NostrSignerInternal(QuartzKeyPair(privKey = key.secretKey.copyOf()))
    require(signer.pubKey.equals(key.publicKeyHex, ignoreCase = true)) {
        "The Concord stream secret does not match its advertised stream address"
    }

    val signed = signer.sign<QuartzEvent>(
        createdAtSeconds,
        22242,
        arrayOf(
            arrayOf("relay", relayUrl),
            arrayOf("challenge", challenge),
        ),
        "",
    )
    return NostrEvent.fromJson(signed.toJson())
}
