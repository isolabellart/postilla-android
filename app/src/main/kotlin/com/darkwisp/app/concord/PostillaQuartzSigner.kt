package com.darkwisp.app.concord

import com.darkwisp.app.nostr.NostrSigner as PostillaSigner
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner as QuartzSigner
import com.vitorpamplona.quartz.nip57Zaps.PrivateZapEvent
import com.vitorpamplona.quartz.nip57Zaps.ZapRequestEvent

/**
 * Bridges Postilla's signer abstraction (including NIP-55 remote signers)
 * into Quartz. User identity signing and NIP-44 operations remain delegated
 * to the active Postilla signer; Concord stream keys are handled separately.
 *
 * The unused methods deliberately fail closed instead of silently falling
 * back to local keys or a different encryption protocol.
 */
class PostillaQuartzSigner(
    private val delegate: PostillaSigner
) : QuartzSigner(delegate.pubkeyHex) {

    override fun isWriteable(): Boolean = true

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Event> sign(
        createdAt: Long,
        kind: Int,
        tags: Array<Array<String>>,
        content: String
    ): T {
        val signed = delegate.signEvent(
            kind = kind,
            content = content,
            tags = tags.map { it.toList() },
            createdAt = createdAt
        )
        return Event.fromJson(signed.toJson()) as T
    }

    override suspend fun nip44Encrypt(plaintext: String, toPublicKey: HexKey): String =
        delegate.nip44Encrypt(plaintext, toPublicKey)

    override suspend fun nip44Decrypt(ciphertext: String, fromPublicKey: HexKey): String =
        delegate.nip44Decrypt(ciphertext, fromPublicKey)

    override suspend fun nip04Encrypt(plaintext: String, toPublicKey: HexKey): String =
        throw UnsupportedOperationException("Concord integration does not use NIP-04")

    override suspend fun nip04Decrypt(ciphertext: String, fromPublicKey: HexKey): String =
        throw UnsupportedOperationException("Concord integration does not use NIP-04")

    override suspend fun decryptZapEvent(event: ZapRequestEvent): PrivateZapEvent =
        throw UnsupportedOperationException("Zap decryption is not part of the Concord signer bridge")

    override suspend fun deriveKey(nonce: HexKey): HexKey =
        throw UnsupportedOperationException("Key derivation is not exposed by Postilla's signer")

    override suspend fun signPsbt(psbtHex: String): String =
        throw UnsupportedOperationException("PSBT signing is not part of the Concord signer bridge")

    override fun hasForegroundSupport(): Boolean = true
}
