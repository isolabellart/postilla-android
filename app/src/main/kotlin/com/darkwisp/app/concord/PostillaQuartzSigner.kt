package com.darkwisp.app.concord

import com.darkwisp.app.nostr.NostrSigner as PostillaSigner
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner as QuartzSigner
import com.vitorpamplona.quartz.nip57Zaps.PrivateZapEvent
import com.vitorpamplona.quartz.nip57Zaps.ZapRequestEvent

/**
 * Bridges Postilla's active signer (local or NIP-55) into Quartz.
 * Concord needs signing and NIP-44 only. Unsupported surfaces fail closed.
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
        throw UnsupportedOperationException("Concord does not use NIP-04")

    override suspend fun nip04Decrypt(ciphertext: String, fromPublicKey: HexKey): String =
        throw UnsupportedOperationException("Concord does not use NIP-04")

    override suspend fun decryptZapEvent(event: ZapRequestEvent): PrivateZapEvent =
        throw UnsupportedOperationException("Zap decryption is outside the Concord signer bridge")

    override suspend fun deriveKey(nonce: HexKey): HexKey = delegate.deriveKey(nonce)

    override suspend fun signPsbt(psbtHex: String): String =
        throw UnsupportedOperationException("PSBT signing is outside the Concord signer bridge")

    override fun hasForegroundSupport(): Boolean = true
}
