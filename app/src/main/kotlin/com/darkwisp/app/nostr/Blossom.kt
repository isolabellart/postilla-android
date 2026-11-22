package com.darkwisp.app.nostr

import android.util.Base64
import java.security.MessageDigest

object Blossom {
    const val KIND_SERVER_LIST = 10063
    const val KIND_AUTH = 24242
    const val DEFAULT_SERVER = "https://blossom.primal.net"

    const val BLOSSOM_BAND_SERVER = "https://blossom.band"
    const val AZZAMO_BLOSSOM_SERVER = "https://blossom.azzamo.media"

    data class RecommendedServer(
        val name: String,
        val url: String
    )

    /**
     * Curated free tiers confirmed from the providers' own documentation.
     * The UI explains their different limits; recommendations never replace a user's list.
     */
    val RECOMMENDED_FREE_SERVERS = listOf(
        RecommendedServer("Blossom.band", BLOSSOM_BAND_SERVER),
        RecommendedServer("Azzamo", AZZAMO_BLOSSOM_SERVER)
    )

    /** Upload defaults used only when no per-account Blossom list has been saved. */
    val DEFAULT_SERVERS: List<String> = RECOMMENDED_FREE_SERVERS.map { it.url }

    fun parseServerList(event: NostrEvent): List<String> {
        return event.tags.mapNotNull { tag ->
            if (tag.size >= 2 && tag[0] == "server") tag[1] else null
        }
    }

    fun buildServerListTags(urls: List<String>): List<List<String>> {
        return urls.map { listOf("server", it) }
    }

    fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).toHex()
    }

    enum class AuthAction(val tagValue: String) {
        UPLOAD("upload"),
        MEDIA("media")
    }

    fun createUploadAuth(
        privkey: ByteArray,
        pubkey: ByteArray,
        sha256Hex: String
    ): String = createAuthHeader(privkey, pubkey, sha256Hex, AuthAction.UPLOAD)

    fun createMediaAuth(
        privkey: ByteArray,
        pubkey: ByteArray,
        sha256Hex: String
    ): String = createAuthHeader(privkey, pubkey, sha256Hex, AuthAction.MEDIA)

    private fun createAuthHeader(
        privkey: ByteArray,
        pubkey: ByteArray,
        sha256Hex: String,
        action: AuthAction
    ): String {
        val event = NostrEvent.create(
            privkey = privkey,
            pubkey = pubkey,
            kind = KIND_AUTH,
            content = "Upload",
            tags = authTags(sha256Hex, action)
        )
        return encodeAuthHeader(event)
    }

    suspend fun createUploadAuth(
        signer: NostrSigner,
        sha256Hex: String
    ): String = createAuthHeader(signer, sha256Hex, AuthAction.UPLOAD)

    suspend fun createMediaAuth(
        signer: NostrSigner,
        sha256Hex: String
    ): String = createAuthHeader(signer, sha256Hex, AuthAction.MEDIA)

    private suspend fun createAuthHeader(
        signer: NostrSigner,
        sha256Hex: String,
        action: AuthAction
    ): String {
        val event = signer.signEvent(
            kind = KIND_AUTH,
            content = "Upload",
            tags = authTags(sha256Hex, action)
        )
        return encodeAuthHeader(event)
    }

    internal fun authTags(sha256Hex: String, action: AuthAction): List<List<String>> {
        val expiration = (System.currentTimeMillis() / 1000 + 300).toString()
        return listOf(
            listOf("t", action.tagValue),
            listOf("x", sha256Hex),
            listOf("expiration", expiration)
        )
    }

    private fun encodeAuthHeader(event: NostrEvent): String {
        val json = event.toJson()
        val base64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "Nostr $base64"
    }
}
