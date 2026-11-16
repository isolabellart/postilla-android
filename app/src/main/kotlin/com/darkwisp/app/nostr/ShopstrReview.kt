package com.darkwisp.app.nostr

/** Shopstr-compatible kind 31555 rating convention, not a universal Nostr standard. */
data class SellerReviewSummary(val averageStars: Double?, val reviewCount: Int)

object ShopstrReview {
    const val KIND = 31555
    private const val PRODUCT_KIND = 30402

    fun referencesSeller(
        event: NostrEvent,
        sellerPubkey: String,
        productAddresses: Collection<String> = emptyList()
    ): Boolean {
        if (event.kind != KIND || sellerPubkey.isBlank()) return false
        if (event.tags.any { it.size >= 2 && it[0] == "p" && it[1] == sellerPubkey }) return true
        val prefix = "$PRODUCT_KIND:$sellerPubkey:"
        val expected = productAddresses.toSet()
        return event.tags.any { tag ->
            if (tag.size < 2 || (tag[0] != "d" && tag[0] != "a")) false
            else {
                val coordinate = tag[1].removePrefix("a:")
                coordinate.startsWith(prefix) && (expected.isEmpty() || coordinate in expected)
            }
        }
    }

    fun summarize(
        events: Collection<NostrEvent>,
        sellerPubkey: String,
        productAddresses: Collection<String> = emptyList()
    ): SellerReviewSummary {
        if (sellerPubkey.isBlank()) return SellerReviewSummary(null, 0)
        val latest = linkedMapOf<String, NostrEvent>()
        events.asSequence().filter { referencesSeller(it, sellerPubkey, productAddresses) }.forEach { event ->
            val target = reviewTarget(event) ?: "p:$sellerPubkey"
            val identity = "${event.pubkey}:$target"
            val previous = latest[identity]
            if (previous == null || event.created_at > previous.created_at ||
                (event.created_at == previous.created_at && event.id < previous.id)
            ) latest[identity] = event
        }
        val scores = latest.values.mapNotNull(::score)
        return if (scores.isEmpty()) SellerReviewSummary(null, 0)
        else SellerReviewSummary(scores.average() * 5.0, scores.size)
    }

    private fun reviewTarget(event: NostrEvent): String? {
        val a = event.tags.firstOrNull { it.size >= 2 && it[0] == "a" }?.get(1)
        if (!a.isNullOrBlank()) return "a:$a"
        val d = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)
        if (!d.isNullOrBlank()) return when {
            d.startsWith("a:$PRODUCT_KIND:") -> d
            d.startsWith("$PRODUCT_KIND:") -> "a:$d"
            else -> "d:$d"
        }
        val e = event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
        if (!e.isNullOrBlank()) return "e:$e"
        val p = event.tags.firstOrNull { it.size >= 2 && it[0] == "p" }?.get(1)
        return p?.takeIf(String::isNotBlank)?.let { "p:$it" }
    }

    private fun score(event: NostrEvent): Double? {
        val ratings = linkedMapOf<String, Double>()
        event.tags.forEach { tag ->
            if (tag.size < 3 || tag[0] != "rating") return@forEach
            val type = tag[2].trim().lowercase()
            if (type.isBlank() || type in ratings) return@forEach
            val value = tag[1].toDoubleOrNull()?.takeIf(Double::isFinite) ?: return@forEach
            ratings[type] = value.coerceIn(0.0, 1.0)
        }
        if (ratings.isEmpty()) return null
        val thumb = ratings["thumb"]
        val remaining = ratings.filterKeys { it != "thumb" }.values
        return when {
            thumb != null && remaining.isNotEmpty() -> thumb * 0.5 + remaining.average() * 0.5
            thumb != null -> thumb
            else -> ratings.values.average()
        }
    }
}
