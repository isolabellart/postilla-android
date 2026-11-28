package com.darkwisp.app.concord

import com.vitorpamplona.quartz.concord.cord05Invites.ConcordInviteLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Vector's curated public Concord directory (HTTP, not a Nostr protocol feature). */
data class VectorHubListing(
    val id: Int,
    val inviteUrl: String,
    val linkSigner: String,
    val category: String,
    val memberCount: Int?,
    val official: Boolean,
    val createdAt: Long,
    val rank: Int,
)

class ConcordHubRepository(
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val client: OkHttpClient = defaultClient(),
) {
    suspend fun fetchListings(): List<VectorHubListing> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Vector Hub ha risposto con HTTP ${response.code}")
            val body = response.body ?: throw IOException("Vector Hub ha restituito una risposta vuota")
            if (body.contentLength() > MAX_RESPONSE_BYTES) {
                throw IOException("La risposta Vector Hub supera il limite consentito")
            }
            val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_RESPONSE_BYTES) {
                        throw IOException("La risposta Vector Hub supera il limite consentito")
                    }
                    output.write(buffer, 0, read)
                }
            }
            parseListings(output.toString(Charsets.UTF_8.name()))
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://vectorapp.io/api/hub"
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MAX_LISTINGS = 500
        private const val MAX_CATEGORY_LENGTH = 80

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()

        internal fun parseListings(body: String): List<VectorHubListing> {
            val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonArray
                ?: throw IOException("Formato della risposta Vector Hub non valido")
            val seen = HashSet<String>()
            val result = ArrayList<VectorHubListing>()
            for (element in root) {
                if (result.size >= MAX_LISTINGS) break
                val entry = element as? JsonObject ?: continue
                val inviteUrl = runCatching { entry["invite_url"]?.jsonPrimitive?.contentOrNull }
                    .getOrNull()?.trim().orEmpty()
                if (inviteUrl.isBlank()) continue
                val parsed = ConcordInviteLink.parseUrl(inviteUrl) ?: continue
                val linkSigner = parsed.linkSignerPubKey.lowercase()
                if (!seen.add(linkSigner)) continue

                val memberCount = runCatching { entry["member_count"]?.jsonPrimitive?.intOrNull }
                    .getOrNull()?.takeIf { it >= 0 }
                val category = runCatching { entry["category"]?.jsonPrimitive?.contentOrNull }
                    .getOrNull()?.trim()?.take(MAX_CATEGORY_LENGTH).orEmpty()
                val official = runCatching { entry["official"]?.jsonPrimitive?.booleanOrNull }
                    .getOrNull() ?: false
                val createdAt = runCatching { entry["created_at"]?.jsonPrimitive?.longOrNull }
                    .getOrNull()?.takeIf { it >= 0L } ?: 0L
                val id = runCatching { entry["id"]?.jsonPrimitive?.intOrNull }
                    .getOrNull()?.takeIf { it >= 0 } ?: 0

                result += VectorHubListing(
                    id = id,
                    inviteUrl = inviteUrl,
                    linkSigner = linkSigner,
                    category = category,
                    memberCount = memberCount,
                    official = official,
                    createdAt = createdAt,
                    rank = result.size,
                )
            }
            return result
        }
    }
}
