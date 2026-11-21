package com.darkwisp.app.repo

import android.util.Log
import com.darkwisp.app.nostr.NostrEvent
import com.darkwisp.app.relay.HttpClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.Request
import java.io.IOException

/** Read-only Nostr.wine REST client. All returned events must be signature-verified by callers. */
object NostrWineApiClient {
    private const val TAG = "NostrWineApi"
    private const val HOST = "api.nostr.wine"
    private const val MIN_REQUEST_INTERVAL_MS = 1_100L
    private val json = Json { ignoreUnknownKeys = true }
    private val requestMutex = Mutex()
    private var lastRequestStartedAtMs = 0L

    suspend fun search(query: String, kind: Int, pubkey: String? = null, limit: Int = 50): List<NostrEvent> {
        require(query.isNotBlank() && kind >= 0)
        val url = HttpUrl.Builder().scheme("https").host(HOST).addPathSegment("search")
            .addQueryParameter("query", query.trim())
            .addQueryParameter("kind", kind.toString())
            .addQueryParameter("sort", "relevance")
            .addQueryParameter("limit", limit.coerceIn(1, 100).toString())
            .addQueryParameter("page", "1")
            .apply { pubkey?.takeIf { it.length == 64 }?.let { addQueryParameter("pubkey", it) } }
            .build()
        return getEvents(url)
    }

    suspend fun trending(order: String, hours: Int, limit: Int = 200): List<NostrEvent> {
        require(order in setOf("replies", "reposts", "reactions", "zap_count", "zap_amount"))
        require(hours in 1..48)
        val url = HttpUrl.Builder().scheme("https").host(HOST).addPathSegment("trending")
            .addQueryParameter("order", order)
            .addQueryParameter("hours", hours.toString())
            .addQueryParameter("limit", limit.coerceIn(1, 200).toString())
            .build()
        return getEvents(url)
    }

    private suspend fun getEvents(url: HttpUrl): List<NostrEvent> {
        requestMutex.withLock {
            val waitMs = (lastRequestStartedAtMs + MIN_REQUEST_INTERVAL_MS - System.currentTimeMillis()).coerceAtLeast(0L)
            if (waitMs > 0) delay(waitMs)
            lastRequestStartedAtMs = System.currentTimeMillis()
        }
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
            HttpClientFactory.getGeneralClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Nostr.wine returned HTTP ${response.code}")
                parseEvents(response.body?.string() ?: throw IOException("Empty Nostr.wine response"))
            }
        }
    }

    internal fun parseEvents(body: String): List<NostrEvent> = try {
        val root = json.parseToJsonElement(body)
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> root["data"] as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            runCatching { NostrEvent.fromJsonObject(obj) }
                .onFailure { Log.w(TAG, "Skipping malformed event: ${it.message}") }
                .getOrNull()
        }
    } catch (error: Exception) {
        Log.w(TAG, "Unable to parse API response: ${error.message}")
        emptyList()
    }
}
