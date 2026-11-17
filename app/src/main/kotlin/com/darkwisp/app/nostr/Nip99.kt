package com.darkwisp.app.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * Marketplace listing readers.
 *
 * NIP-99 listings (30402) are the canonical publish format. Read-only
 * compatibility is also provided for legacy NIP-15 products (30018), whose
 * structured fields live in JSON content rather than NIP-99 tags.
 */
object Nip99 {
    const val KIND_LISTING = 30402
    const val KIND_DRAFT = 30403
    const val KIND_LEGACY_PRODUCT = 30018
    val MARKETPLACE_KINDS = listOf(KIND_LISTING, KIND_LEGACY_PRODUCT)
    val PRODUCT_TYPES = listOf("simple", "variable", "variation")
    val PRODUCT_FORMATS = listOf("digital", "physical")
    val VISIBILITIES = listOf("on-sale", "pre-order", "hidden")

    data class Price(val amount: String, val currency: String, val frequency: String? = null)
    enum class AvailabilityState { AVAILABLE, SOLD_OUT, UNKNOWN }
    /** Missing, malformed or ambiguous stock is unknown, never zero. */
    data class Availability(val state: AvailabilityState, val stock: Int? = null)

    fun isMarketplaceKind(kind: Int): Boolean = kind in MARKETPLACE_KINDS

    private fun jsonContent(event: NostrEvent): JsonObject? =
        if (!isMarketplaceKind(event.kind) || !event.content.trimStart().startsWith("{")) {
            null
        } else {
            runCatching { Json.parseToJsonElement(event.content) as? JsonObject }.getOrNull()
        }

    private fun jsonString(value: JsonElement?): String? = when (value) {
        is JsonPrimitive -> value.contentOrNull
        is JsonObject -> jsonString(value["url"] ?: value["value"] ?: value["amount"])
        else -> null
    }

    private fun jsonField(event: NostrEvent, name: String): String? =
        jsonString(jsonContent(event)?.get(name))?.takeIf(String::isNotBlank)

    /**
     * Preserve legacy NIP-15 extension fields when converting a product into NIP-99.
     *
     * Standard product fields are mapped to NIP-99 tags/content by the editor. Unknown
     * JSON fields are carried losslessly in one namespaced tag so that vendor metadata
     * (for example specifications or shipping data) is not silently discarded.
     */
    fun migrationPreservedTags(event: NostrEvent): List<List<String>> {
        if (event.kind != KIND_LEGACY_PRODUCT) return event.tags

        val preserved = event.tags.filter { it.size >= 2 && it[0] != "d" }.toMutableList()
        val legacyPrice = price(event)
        if (legacyPrice?.frequency != null && preserved.none { it.firstOrNull() == "price" }) {
            preserved.add(
                listOf(
                    "price",
                    legacyPrice.amount,
                    legacyPrice.currency.trim().uppercase(),
                    legacyPrice.frequency
                )
            )
        }

        val knownFields = setOf(
            "name", "title", "summary", "description", "price", "currency",
            "location", "categories", "images", "image", "status",
            "quantity", "stock", "published_at", "type", "visibility"
        )
        val extensionFields = jsonContent(event)
            ?.filterKeys { it !in knownFields }
            .orEmpty()
        if (extensionFields.isNotEmpty()) {
            preserved.add(listOf("nip15-metadata", JsonObject(extensionFields).toString()))
        }
        return preserved.distinct()
    }

    fun tag(event: NostrEvent, name: String): List<String>? =
        event.tags.firstOrNull { it.size >= 2 && it[0] == name }

    fun tags(event: NostrEvent, name: String): List<List<String>> =
        event.tags.filter { it.size >= 2 && it[0] == name }

    fun dTag(event: NostrEvent): String = tag(event, "d")?.getOrNull(1).orEmpty()
    fun addressTag(event: NostrEvent): String = "${event.kind}:${event.pubkey}:${dTag(event)}"

    fun title(event: NostrEvent): String =
        tag(event, "title")?.getOrNull(1)?.takeIf(String::isNotBlank)
            ?: jsonField(event, "name")
            ?: jsonField(event, "title")
            ?: ""

    /** Fallback heading for listings published by external Marketplace clients. */
    fun displayTitle(event: NostrEvent, fallback: String): String {
        title(event).trim().takeIf(String::isNotBlank)?.let { return it }
        summary(event).trim().takeIf(String::isNotBlank)?.let { return it }
        val firstDescriptionLine = description(event).lineSequence()
            .map { line ->
                line.trim()
                    .removePrefix(">").trim()
                    .removePrefix("#").trim()
                    .removePrefix("-").trim()
                    .removePrefix("*").trim()
            }
            .firstOrNull(String::isNotBlank)
            ?.take(120)
        return firstDescriptionLine ?: fallback
    }

    fun summary(event: NostrEvent): String =
        tag(event, "summary")?.getOrNull(1)?.takeIf(String::isNotBlank)
            ?: jsonField(event, "summary").orEmpty()

    /** Full user-facing description; never render legacy JSON as raw text. */
    fun description(event: NostrEvent): String =
        jsonField(event, "description") ?: event.content

    fun location(event: NostrEvent): String =
        tag(event, "location")?.getOrNull(1)?.takeIf(String::isNotBlank)
            ?: jsonField(event, "location").orEmpty()

    private fun normalizeMarketplaceValue(value: String): String =
        value.trim().lowercase().replace('-', '_').replace(' ', '_')

    /** OpenMarkets/Gamma visibility; absent or invalid values default to on-sale. */
    fun visibility(event: NostrEvent): String {
        val tagged = tag(event, "visibility")?.getOrNull(1) ?: jsonField(event, "visibility")
        val normalized = tagged?.trim()?.lowercase()?.replace('_', '-')
        if (normalized != null && normalized in VISIBILITIES) return normalized
        val legacyStatus = tag(event, "status")?.getOrNull(1) ?: jsonField(event, "status")
        return if (normalizeMarketplaceValue(legacyStatus.orEmpty()) == "hidden") "hidden" else "on-sale"
    }

    fun productType(event: NostrEvent): String =
        tag(event, "type")?.getOrNull(1)?.trim()?.lowercase()?.takeIf { it in PRODUCT_TYPES } ?: "simple"

    fun productFormat(event: NostrEvent): String =
        tag(event, "type")?.getOrNull(2)?.trim()?.lowercase()?.takeIf { it in PRODUCT_FORMATS } ?: "digital"

    /** Existing sale-state accessor retained for legacy NIP-99 UI and JSON payloads. */
    fun status(event: NostrEvent): String {
        val rawStatus = tag(event, "status")?.getOrNull(1) ?: jsonField(event, "status")
        val normalizedStatus = rawStatus?.let(::normalizeMarketplaceValue)
        return when {
            normalizedStatus != null && normalizedStatus in setOf("sold", "sold_out", "out_of_stock") -> "sold"
            normalizedStatus != null && normalizedStatus in setOf("hidden", "withdrawn", "deleted", "cancelled", "canceled") -> normalizedStatus
            visibility(event) == "hidden" -> "hidden"
            visibility(event) == "pre-order" -> "pre-order"
            normalizedStatus.isNullOrBlank() -> "active"
            else -> normalizedStatus.replace('_', '-')
        }
    }

    /** True only when the active public key owns a supported Marketplace event. */
    fun isOwnedListing(event: NostrEvent, pubkey: String?): Boolean =
        !pubkey.isNullOrBlank() && isMarketplaceKind(event.kind) && event.pubkey.equals(pubkey, ignoreCase = true)

    /** Hidden listings are visible only to their author. */
    fun isVisibleToViewer(event: NostrEvent, viewerPubkey: String?): Boolean =
        visibility(event) != "hidden" || isOwnedListing(event, viewerPubkey)

    fun matchesLocation(event: NostrEvent, selectedLocation: String?): Boolean {
        val selected = selectedLocation?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        return location(event).trim().equals(selected, ignoreCase = true)
    }

    fun matchesSoldFilter(event: NostrEvent, hideSold: Boolean, viewerPubkey: String?): Boolean =
        !hideSold || isOwnedListing(event, viewerPubkey) || !isSold(event)

    fun isSold(event: NostrEvent): Boolean =
        if (event.kind == KIND_LEGACY_PRODUCT) status(event) == "sold"
        else availability(event).state == AvailabilityState.SOLD_OUT

    fun stock(event: NostrEvent): Int? {
        val stockTags = event.tags.filter { it.firstOrNull() == "stock" }
        if (stockTags.isNotEmpty()) {
            if (stockTags.size != 1) return null
            val tag = stockTags.single()
            if (tag.size != 2) return null
            return tag[1].trim().toLongOrNull()
                ?.takeIf { it in 0..Int.MAX_VALUE.toLong() }
                ?.toInt()
        }
        val quantity = jsonField(event, "quantity") ?: jsonField(event, "stock") ?: return null
        return quantity.trim().toLongOrNull()
            ?.takeIf { it in 0..Int.MAX_VALUE.toLong() }
            ?.toInt()
    }

    fun availability(event: NostrEvent): Availability =
        availability(status = status(event), stock = stock(event), visibility = visibility(event))

    /** Serialize an edited legacy kind-30018 product using the JSON shape this reader accepts. */
    fun buildLegacyProductContent(
        title: String,
        summary: String,
        description: String,
        amount: String,
        currency: String,
        location: String,
        categories: List<String>,
        images: List<String>,
        status: String,
        stock: Int?,
        publishedAt: String
    ): String = buildJsonObject {
        put("name", JsonPrimitive(title))
        put("summary", JsonPrimitive(summary))
        put("description", JsonPrimitive(description))
        if (amount.isNotBlank()) {
            put("price", buildJsonObject {
                put("amount", JsonPrimitive(amount))
                if (currency.isNotBlank()) put("currency", JsonPrimitive(currency))
            })
            if (currency.isNotBlank()) put("currency", JsonPrimitive(currency))
        }
        if (location.isNotBlank()) put("location", JsonPrimitive(location))
        put("categories", buildJsonArray { categories.forEach { add(JsonPrimitive(it)) } })
        put("images", buildJsonArray { images.forEach { add(JsonPrimitive(it)) } })
        put("status", JsonPrimitive(status))
        stock?.let { put("quantity", JsonPrimitive(it)) }
        put("published_at", JsonPrimitive(publishedAt))
    }.toString()

    fun availability(status: String, stock: Int?): Availability =
        availability(status = status, stock = stock, visibility = null)

    fun availability(status: String, stock: Int?, visibility: String?): Availability {
        val normalizedStatus = normalizeMarketplaceValue(status)
        val normalizedVisibility = visibility?.trim()?.lowercase()?.replace('_', '-')
        return when {
            normalizedVisibility == "hidden" || normalizedStatus in setOf("hidden", "withdrawn", "deleted", "cancelled", "canceled") ->
                Availability(AvailabilityState.UNKNOWN, stock)
            normalizedStatus in setOf("sold", "sold_out", "out_of_stock") ->
                Availability(AvailabilityState.SOLD_OUT, stock)
            normalizedVisibility == "pre-order" || normalizedStatus == "pre_order" ->
                if (stock != null && stock > 0) Availability(AvailabilityState.AVAILABLE, stock)
                else Availability(AvailabilityState.UNKNOWN, stock)
            stock == null || stock < 0 -> Availability(AvailabilityState.UNKNOWN)
            stock == 0 -> Availability(AvailabilityState.SOLD_OUT, 0)
            else -> Availability(AvailabilityState.AVAILABLE, stock)
        }
    }

    fun publishedAt(event: NostrEvent): String =
        tag(event, "published_at")?.getOrNull(1)
            ?: jsonField(event, "published_at")
            ?: event.created_at.toString()

    fun images(event: NostrEvent): List<String> {
        val tagged = tags(event, "image")
            .mapNotNull { it.getOrNull(1)?.takeIf(String::isNotBlank) }
        val jsonImages = (jsonContent(event)?.get("images") as? JsonArray)
            ?.mapNotNull { jsonString(it)?.takeIf(String::isNotBlank) }
            .orEmpty()
        return (tagged + jsonImages + listOfNotNull(jsonField(event, "image"))).distinct()
    }

    fun categories(event: NostrEvent): List<String> {
        val tagged = tags(event, "t").mapNotNull { it.getOrNull(1)?.takeIf(String::isNotBlank) }
        val jsonCategories = (jsonContent(event)?.get("categories") as? JsonArray)
            ?.mapNotNull { jsonString(it)?.takeIf(String::isNotBlank) }
            .orEmpty()
        return (tagged + jsonCategories).distinct()
    }

    fun price(event: NostrEvent): Price? {
        val tag = tag(event, "price")
        if (tag != null) {
            val amount = tag.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
            val currency = tag.getOrNull(2)?.takeIf(String::isNotBlank) ?: return null
            return Price(amount, currency, tag.getOrNull(3)?.takeIf(String::isNotBlank))
        }
        val jsonPrice = jsonContent(event)?.get("price")
        val amount = jsonString(jsonPrice)?.takeIf(String::isNotBlank) ?: return null
        val currency = jsonField(event, "currency")
            ?: (jsonPrice as? JsonObject)?.get("currency")?.let(::jsonString)
            ?: return null
        val frequency = (jsonPrice as? JsonObject)?.get("frequency")?.let(::jsonString)
        return Price(amount, currency, frequency)
    }

    fun priceLabel(event: NostrEvent): String? {
        val value = price(event) ?: return null
        val frequency = value.frequency?.let { "/$it" }.orEmpty()
        return value.amount + " " + value.currency + frequency
    }

    fun buildTags(
        dTag: String,
        title: String,
        summary: String,
        publishedAt: String,
        amount: String,
        currency: String,
        location: String,
        category: String,
        images: List<String>,
        status: String,
        stock: Int? = null,
        format: String = "digital",
        visibility: String = "on-sale",
        productType: String = "simple",
        preservedTags: List<List<String>> = emptyList()
    ): List<List<String>> {
        require(stock == null || stock >= 0) { "Stock cannot be negative" }
        val sold = normalizeMarketplaceValue(status) in setOf("sold", "sold_out", "out_of_stock")
        val publishedStock = if (sold) 0 else stock
        val normalizedFormat = format.trim().lowercase().takeIf { it in PRODUCT_FORMATS } ?: "digital"
        val normalizedType = productType.trim().lowercase().takeIf { it in PRODUCT_TYPES } ?: "simple"
        // A sold listing cannot simultaneously advertise pre-orders or hidden inventory.
        val normalizedVisibility = if (sold) "on-sale"
            else visibility.trim().lowercase().replace('_', '-').takeIf { it in VISIBILITIES } ?: "on-sale"
        val result = mutableListOf(
            listOf("d", dTag.trim()),
            listOf("title", title.trim()),
            listOf("summary", summary.trim()),
            listOf("published_at", publishedAt),
            listOf("status", if (sold) "sold" else "active"),
            listOf("type", normalizedType, normalizedFormat),
            listOf("visibility", normalizedVisibility)
        )
        if (publishedStock != null) result.add(listOf("stock", publishedStock.toString()))
        if (location.isNotBlank()) result.add(listOf("location", location.trim()))
        if (amount.isNotBlank() && currency.isNotBlank()) {
            val normalizedCurrency = currency.trim().uppercase()
            val preservedFrequency = preservedTags.firstOrNull { tag ->
                tag.size >= 4 && tag[0] == "price" &&
                    tag[1].trim() == amount.trim() &&
                    tag[2].trim().equals(normalizedCurrency, ignoreCase = true)
            }?.take(4)
            result.add(preservedFrequency ?: listOf("price", amount.trim(), normalizedCurrency))
        }
        category.split(',', '#', '\n')
            .map { it.trim().removePrefix("#").lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { result.add(listOf("t", it)) }
        val normalizedImages = images.map(String::trim).filter(String::isNotBlank).distinct()
        val preservedImageTags = preservedTags.filter { tag ->
            tag.size > 2 && tag.firstOrNull() == "image" && tag.getOrNull(1) in normalizedImages
        }
        preservedImageTags.forEach { result.add(it) }
        val imagesWithPreservedMetadata = preservedImageTags.mapNotNull { it.getOrNull(1) }.toSet()
        normalizedImages.filterNot { it in imagesWithPreservedMetadata }
            .forEach { result.add(listOf("image", it)) }
        val regeneratedTags = setOf("d", "title", "summary", "published_at", "status", "type", "visibility", "stock", "location", "price", "t", "image", "client")
        preservedTags.filter { it.size >= 2 && it[0] !in regeneratedTags }.forEach { result.add(it) }
        result.add(listOf("client", "Postilla"))
        return result
    }
}
