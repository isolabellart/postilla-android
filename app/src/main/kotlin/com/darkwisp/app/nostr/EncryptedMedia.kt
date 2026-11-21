package com.darkwisp.app.nostr

import kotlinx.serialization.json.*
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM file encryption for NIP-17 Kind 15 messages and private NIP-04 image attachments.
 * Kind 15 remains interoperable with Amethyst; the NIP-04 metadata envelope is a Postilla extension.
 */
object EncryptedMedia {
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val KEY_SIZE = 32       // 256-bit key
    private const val NONCE_SIZE = 12     // 96-bit nonce (standard GCM)
    private const val TAG_BITS = 128      // 128-bit auth tag
    const val ALGORITHM = "aes-gcm"

    private val random = SecureRandom()

    data class EncryptedFileResult(
        val encryptedBytes: ByteArray,
        val keyHex: String,
        val nonceHex: String,
        val originalSha256Hex: String,
        val encryptedSha256Hex: String
    )

    data class EncryptedFileMetadata(
        val fileUrl: String,
        val mimeType: String,
        val algorithm: String,
        val keyHex: String,
        val nonceHex: String,
        val encryptedHash: String,
        val originalHash: String,
        val size: Long?,
        val dimensions: String?,
        val thumbhash: String?,
        val blurhash: String?
    )


    /**
     * NIP-04 has no event tags for file metadata. Keep the media descriptor inside
     * the NIP-04-encrypted plaintext, so the URL and decryption key remain private
     * to the conversation. Other NIP-04 clients simply see an ordinary text payload.
     */
    private const val NIP04_MEDIA_MARKER = "[postilla-media-v1:"
    private val nip04Json = Json { ignoreUnknownKeys = true }

    fun encodeNip04Content(
        text: String,
        metadata: EncryptedFileMetadata?
    ): String {
        if (metadata == null) return text
        val descriptor = buildJsonObject {
            put("url", metadata.fileUrl)
            put("mime", metadata.mimeType)
            put("algorithm", metadata.algorithm)
            put("key", metadata.keyHex)
            put("nonce", metadata.nonceHex)
            put("encryptedHash", metadata.encryptedHash)
            put("originalHash", metadata.originalHash)
            put("size", metadata.size ?: 0L)
            metadata.dimensions?.let { put("dimensions", it) }
        }.toString()
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(descriptor.toByteArray(StandardCharsets.UTF_8))
        val separator = if (text.isBlank()) "" else "\n\n"
        return text + separator + NIP04_MEDIA_MARKER + encoded + "]"
    }

    fun decodeNip04Content(content: String): Pair<String, EncryptedFileMetadata?> {
        val markerIndex = content.lastIndexOf(NIP04_MEDIA_MARKER)
        if (markerIndex < 0) return content to null
        val payloadStart = markerIndex + NIP04_MEDIA_MARKER.length
        val payloadEnd = content.indexOf(']', payloadStart)
        if (payloadEnd < 0 || content.substring(payloadEnd + 1).isNotBlank()) return content to null
        return try {
            val encoded = content.substring(payloadStart, payloadEnd)
            val json = nip04Json.parseToJsonElement(
                String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
            ).jsonObject
            val url = json["url"]?.jsonPrimitive?.content ?: return content to null
            val mime = json["mime"]?.jsonPrimitive?.content ?: return content to null
            val algorithm = json["algorithm"]?.jsonPrimitive?.content ?: return content to null
            val key = json["key"]?.jsonPrimitive?.content ?: return content to null
            val nonce = json["nonce"]?.jsonPrimitive?.content ?: return content to null
            if (!url.startsWith("https://") || !mime.startsWith("image/") ||
                algorithm != ALGORITHM || !key.matches(Regex("[0-9a-fA-F]{64}")) ||
                !nonce.matches(Regex("[0-9a-fA-F]{24}"))) return content to null
            val metadata = EncryptedFileMetadata(
                fileUrl = url,
                mimeType = mime,
                algorithm = algorithm,
                keyHex = key,
                nonceHex = nonce,
                encryptedHash = json["encryptedHash"]?.jsonPrimitive?.contentOrNull ?: "",
                originalHash = json["originalHash"]?.jsonPrimitive?.contentOrNull ?: "",
                size = json["size"]?.jsonPrimitive?.longOrNull,
                dimensions = json["dimensions"]?.jsonPrimitive?.contentOrNull,
                thumbhash = null,
                blurhash = null
            )
            content.substring(0, markerIndex).trimEnd() to metadata
        } catch (_: Exception) {
            content to null
        }
    }

    fun encryptFile(plainBytes: ByteArray): EncryptedFileResult {
        val key = ByteArray(KEY_SIZE).also { random.nextBytes(it) }
        val nonce = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }

        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        val encryptedBytes = cipher.doFinal(plainBytes)

        val sha256 = MessageDigest.getInstance("SHA-256")
        val originalHash = sha256.digest(plainBytes).toHex()
        sha256.reset()
        val encryptedHash = sha256.digest(encryptedBytes).toHex()

        val result = EncryptedFileResult(
            encryptedBytes = encryptedBytes,
            keyHex = key.toHex(),
            nonceHex = nonce.toHex(),
            originalSha256Hex = originalHash,
            encryptedSha256Hex = encryptedHash
        )

        // Wipe the raw key from memory
        key.fill(0)

        return result
    }

    fun decryptFile(encryptedBytes: ByteArray, keyHex: String, nonceHex: String): ByteArray {
        val key = keyHex.hexToByteArray()
        val nonce = nonceHex.hexToByteArray()

        try {
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            return cipher.doFinal(encryptedBytes)
        } finally {
            key.fill(0)
        }
    }

    fun buildKind15Tags(
        mimeType: String,
        keyHex: String,
        nonceHex: String,
        encryptedHash: String,
        originalHash: String,
        size: Long,
        dimensions: String? = null
    ): List<List<String>> {
        val tags = mutableListOf(
            listOf("file-type", mimeType),
            listOf("encryption-algorithm", ALGORITHM),
            listOf("decryption-key", keyHex),
            listOf("decryption-nonce", nonceHex),
            listOf("x", encryptedHash),
            listOf("ox", originalHash),
            listOf("size", size.toString())
        )
        if (dimensions != null) tags.add(listOf("dim", dimensions))
        return tags
    }

    fun parseKind15Tags(tags: List<List<String>>, fileUrl: String): EncryptedFileMetadata? {
        val tagMap = mutableMapOf<String, String>()
        for (tag in tags) {
            if (tag.size >= 2) tagMap[tag[0]] = tag[1]
        }

        val mimeType = tagMap["file-type"] ?: return null
        val algorithm = tagMap["encryption-algorithm"] ?: return null
        val keyHex = tagMap["decryption-key"] ?: return null
        val nonceHex = tagMap["decryption-nonce"] ?: return null

        return EncryptedFileMetadata(
            fileUrl = fileUrl,
            mimeType = mimeType,
            algorithm = algorithm,
            keyHex = keyHex,
            nonceHex = nonceHex,
            encryptedHash = tagMap["x"] ?: "",
            originalHash = tagMap["ox"] ?: "",
            size = tagMap["size"]?.toLongOrNull(),
            dimensions = tagMap["dim"],
            thumbhash = tagMap["thumbhash"],
            blurhash = tagMap["blurhash"]
        )
    }
}
