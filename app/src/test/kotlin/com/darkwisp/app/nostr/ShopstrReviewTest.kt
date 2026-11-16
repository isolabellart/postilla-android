package com.darkwisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShopstrReviewTest {
    private val seller = "a".repeat(64)
    private val reviewer = "b".repeat(64)
    private val anotherReviewer = "c".repeat(64)

    private fun review(pubkey: String = reviewer, createdAt: Long, productD: String = "item-one",
        ratingTags: List<List<String>>): NostrEvent = NostrEvent.createUnsigned(
        pubkey, ShopstrReview.KIND, "review",
        listOf(listOf("d", "a:30402:$seller:$productD"), listOf("p", seller)) + ratingTags,
        createdAt
    )

    @Test fun `thumb and criteria use Shopstr weights`() {
        val event = review(createdAt = 1, ratingTags = listOf(
            listOf("rating", "1.0", "thumb"),
            listOf("rating", "0.5", "quality"),
            listOf("rating", "0.5", "delivery")
        ))
        val summary = ShopstrReview.summarize(listOf(event), seller)
        assertEquals(1, summary.reviewCount)
        assertEquals(3.75, summary.averageStars!!, 0.0001)
    }

    @Test fun `thumb-only review contributes its Shopstr half weight`() {
        val event = review(createdAt = 1, ratingTags = listOf(listOf("rating", "1.0", "thumb")))
        val summary = ShopstrReview.summarize(listOf(event), seller)
        assertEquals(2.5, summary.averageStars!!, 0.0001)
    }

    @Test fun `without thumb the available criteria are averaged`() {
        val event = review(createdAt = 1, ratingTags = listOf(
            listOf("rating", "0.25", "quality"), listOf("rating", "0.75", "communication")
        ))
        val summary = ShopstrReview.summarize(listOf(event), seller)
        assertEquals(1.25, summary.averageStars!!, 0.0001)
    }

    @Test fun `values are clamped and malformed values ignored`() {
        val event = review(createdAt = 1, ratingTags = listOf(
            listOf("rating", "2.0", "thumb"), listOf("rating", "-1.0", "quality"),
            listOf("rating", "bad", "delivery")
        ))
        assertEquals(2.5, ShopstrReview.summarize(listOf(event), seller).averageStars!!, 0.0001)
    }

    @Test fun `latest review per reviewer and product wins`() {
        val old = review(createdAt = 1, ratingTags = listOf(listOf("rating", "1.0", "thumb")))
        val latest = review(createdAt = 2, ratingTags = listOf(listOf("rating", "0.5", "thumb")))
        val anotherProduct = review(createdAt = 3, productD = "item-two", ratingTags = listOf(listOf("rating", "1.0", "thumb")))
        val otherPerson = review(pubkey = anotherReviewer, createdAt = 4, ratingTags = listOf(listOf("rating", "0.5", "thumb")))
        val summary = ShopstrReview.summarize(listOf(old, latest, anotherProduct, otherPerson), seller)
        assertEquals(3, summary.reviewCount)
        assertEquals(1.6666667, summary.averageStars!!, 0.0001)
    }

    @Test fun `unrated events do not count as zero stars`() {
        val summary = ShopstrReview.summarize(listOf(review(createdAt = 1, ratingTags = emptyList())), seller)
        assertEquals(0, summary.reviewCount)
        assertNull(summary.averageStars)
    }

    @Test fun `Shopstr product address in d tag is enough without p tag`() {
        val event = NostrEvent.createUnsigned(
            reviewer, ShopstrReview.KIND, "review",
            listOf(
                listOf("d", "a:30402:$seller:item-one"),
                listOf("rating", "1.0", "thumb")
            ),
            10
        )
        assertEquals(true, ShopstrReview.referencesSeller(event, seller))
        assertEquals(2.5, ShopstrReview.summarize(listOf(event), seller).averageStars!!, 0.0001)
    }

    @Test fun `Shopstr product address must belong to seller inventory when supplied`() {
        val event = NostrEvent.createUnsigned(
            reviewer, ShopstrReview.KIND, "review",
            listOf(
                listOf("d", "a:30402:$seller:other-item"),
                listOf("rating", "1.0", "thumb")
            ),
            10
        )
        assertEquals(false, ShopstrReview.referencesSeller(event, seller, listOf("30402:$seller:item-one")))
    }

    @Test fun `seller p tag remains a valid fallback without a matching product coordinate`() {
        val event = NostrEvent.createUnsigned(
            reviewer, ShopstrReview.KIND, "review",
            listOf(
                listOf("d", "a:30402:${"d".repeat(64)}:other-item"),
                listOf("p", seller),
                listOf("rating", "1.0", "thumb")
            ),
            10
        )
        assertEquals(true, ShopstrReview.referencesSeller(event, seller))
    }

    @Test fun `events for other sellers are excluded`() {
        val event = NostrEvent.createUnsigned(reviewer, ShopstrReview.KIND, "review", listOf(
            listOf("d", "a:30402:${"d".repeat(64)}:item"),
            listOf("rating", "1.0", "thumb")
        ), 1)
        val summary = ShopstrReview.summarize(listOf(event), seller)
        assertEquals(0, summary.reviewCount)
        assertNull(summary.averageStars)
    }
}
