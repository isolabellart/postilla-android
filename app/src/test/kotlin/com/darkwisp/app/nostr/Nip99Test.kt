package com.darkwisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Nip99Test {
    private fun event(tags: List<List<String>>) = NostrEvent(
        id = "event-id",
        pubkey = "11".repeat(32),
        created_at = 1_800_000_000L,
        kind = Nip99.KIND_LISTING,
        tags = tags,
        content = "A detailed description",
        sig = "signature"
    )

    @Test
    fun `reads standard listing fields and price`() {
        val listing = event(
            listOf(
                listOf("d", "painting-01"),
                listOf("title", "Original oil painting"),
                listOf("summary", "A one-of-a-kind artwork"),
                listOf("location", "Italy"),
                listOf("price", "450", "EUR"),
                listOf("status", "active"),
                listOf("image", "https://example.com/painting.jpg"),
                listOf("t", "art")
            )
        )

        assertEquals("painting-01", Nip99.dTag(listing))
        assertEquals("Original oil painting", Nip99.title(listing))
        assertEquals("A one-of-a-kind artwork", Nip99.summary(listing))
        assertEquals("Italy", Nip99.location(listing))
        assertEquals("450 EUR", Nip99.priceLabel(listing))
        assertEquals(listOf("https://example.com/painting.jpg"), Nip99.images(listing))
        assertEquals(listOf("art"), Nip99.categories(listing))
        assertFalse(Nip99.isSold(listing))
    }

    @Test
    fun `reads the CLIFF REMAINS legacy listing payload used by the Marketplace feed`() {
        val legacy = event(listOf(listOf("d", "cliff-remains"))).copy(
            kind = Nip99.KIND_LEGACY_PRODUCT,
            content = """{"name":"CLIFF REMAINS","summary":"Oil on board - 2025","description":"Oil on board - 2025","price":{"amount":"600000","currency":"SATS"},"currency":"SATS","location":"Italy","categories":["art"],"images":["https://blossom.isolabellart.it.com/fb7a6511cb0b7dc945a3ac29f1df63860820f9d712eb9a6ef45eec09cbc574df.jpg"],"status":"active","quantity":1,"published_at":"1757668715"}"""
        )

        assertTrue(Nip99.isMarketplaceKind(legacy.kind))
        assertEquals("CLIFF REMAINS", Nip99.title(legacy))
        assertEquals("CLIFF REMAINS", Nip99.displayTitle(legacy, "Untitled"))
        assertEquals("Oil on board - 2025", Nip99.summary(legacy))
        assertEquals("Oil on board - 2025", Nip99.description(legacy))
        assertEquals("600000 SATS", Nip99.priceLabel(legacy))
        assertEquals("Italy", Nip99.location(legacy))
        assertEquals(listOf("art"), Nip99.categories(legacy))
        assertEquals(listOf("https://blossom.isolabellart.it.com/fb7a6511cb0b7dc945a3ac29f1df63860820f9d712eb9a6ef45eec09cbc574df.jpg"), Nip99.images(legacy))
        assertEquals("active", Nip99.status(legacy))
        assertEquals(1, Nip99.stock(legacy))
        assertEquals(Nip99.AvailabilityState.AVAILABLE, Nip99.availability(legacy).state)
    }

    @Test
    fun `legacy editor serializer round trips all supported product fields`() {
        val content = Nip99.buildLegacyProductContent(
            title = "Vintage camera",
            summary = "35mm camera",
            description = "Fully working",
            amount = "120",
            currency = "EUR",
            location = "Italy",
            categories = listOf("photography", "vintage"),
            images = listOf("https://example.com/camera.jpg"),
            status = "active",
            stock = 0,
            publishedAt = "2017-04-03T10:15:00Z"
        )
        val legacy = event(listOf(listOf("d", "legacy-editor"))).copy(
            kind = Nip99.KIND_LEGACY_PRODUCT,
            content = content
        )

        assertEquals("Vintage camera", Nip99.title(legacy))
        assertEquals("35mm camera", Nip99.summary(legacy))
        assertEquals("Fully working", Nip99.description(legacy))
        assertEquals("120 EUR", Nip99.priceLabel(legacy))
        assertEquals("Italy", Nip99.location(legacy))
        assertEquals(listOf("photography", "vintage"), Nip99.categories(legacy))
        assertEquals(listOf("https://example.com/camera.jpg"), Nip99.images(legacy))
        assertEquals(0, Nip99.stock(legacy))
        assertEquals("2017-04-03T10:15:00Z", Nip99.publishedAt(legacy))
        assertEquals(Nip99.AvailabilityState.SOLD_OUT, Nip99.availability(legacy).state)
    }

    @Test
    fun `reads JSON product payloads carried by external NIP99 events`() {
        val external = event(listOf(listOf("d", "external-json"))).copy(
            content = """{"name":"External product","description":"External description","images":["https://example.com/one.jpg"],"price":"45","currency":"USD"}"""
        )
        assertEquals("External product", Nip99.title(external))
        assertEquals("External description", Nip99.description(external))
        assertEquals("45 USD", Nip99.priceLabel(external))
        assertEquals(listOf("https://example.com/one.jpg"), Nip99.images(external))
    }

    @Test
    fun `reads sold status from legacy JSON while preserving explicit tag precedence`() {
        val legacySold = event(listOf(listOf("d", "legacy-sold"))).copy(
            kind = Nip99.KIND_LEGACY_PRODUCT,
            content = """{"name":"Vintage camera","status":"sold","quantity":0}"""
        )
        assertEquals("sold", Nip99.status(legacySold))
        assertTrue(Nip99.isSold(legacySold))

        val explicitlyActive = legacySold.copy(
            tags = listOf(listOf("d", "legacy-sold"), listOf("status", "active"))
        )
        assertEquals("active", Nip99.status(explicitlyActive))
        assertFalse(Nip99.isSold(explicitlyActive))

        val withdrawn = legacySold.copy(
            content = """{"name":"Vintage camera","status":"withdrawn"}"""
        )
        assertEquals("withdrawn", Nip99.status(withdrawn))
        assertFalse(Nip99.isSold(withdrawn))
        assertEquals(Nip99.AvailabilityState.UNKNOWN, Nip99.availability(withdrawn).state)
    }

    @Test
    fun `reads legacy published at timestamp from JSON`() {
        val legacy = event(listOf(listOf("d", "legacy-dated"))).copy(
            kind = Nip99.KIND_LEGACY_PRODUCT,
            created_at = 1_800_000_000L,
            content = """{"name":"Old listing","published_at":"2017-04-03T10:15:00Z"}"""
        )
        assertEquals("2017-04-03T10:15:00Z", Nip99.publishedAt(legacy))

        val tagged = legacy.copy(tags = listOf(
            listOf("d", "legacy-dated"),
            listOf("published_at", "2018-01-01")
        ))
        assertEquals("2018-01-01", Nip99.publishedAt(tagged))
    }

    @Test
    fun `malformed legacy product JSON falls back safely`() {
        val legacy = event(listOf(listOf("d", "broken-legacy"))).copy(
            kind = Nip99.KIND_LEGACY_PRODUCT,
            content = """{"name":"""
        )
        assertEquals("", Nip99.title(legacy))
        assertEquals("""{"name":""", Nip99.description(legacy))
        assertTrue(Nip99.images(legacy).isEmpty())
        assertNull(Nip99.price(legacy))
        assertNull(Nip99.stock(legacy))
    }

    @Test
    fun `buildTags emits marketplace metadata and normalizes category tags`() {
        val tags = Nip99.buildTags(
            dTag = "painting-01",
            title = " Original oil painting ",
            summary = "Unique work",
            publishedAt = "1800000000",
            amount = "450",
            currency = "eur",
            location = "Italy",
            category = "Art, #handmade\npainting",
            images = listOf("https://example.com/one.jpg", "https://example.com/one.jpg"),
            status = "sold"
        )

        assertTrue(tags.contains(listOf("d", "painting-01")))
        assertTrue(tags.contains(listOf("title", "Original oil painting")))
        assertTrue(tags.contains(listOf("price", "450", "EUR")))
        assertTrue(tags.contains(listOf("status", "sold")))
        assertTrue(tags.contains(listOf("t", "art")))
        assertTrue(tags.contains(listOf("t", "handmade")))
        assertTrue(tags.contains(listOf("t", "painting")))
        assertEquals(1, tags.count { it.firstOrNull() == "image" })
    }

    @Test
    fun `stock distinguishes available sold out and unknown listings`() {
        val available = event(listOf(listOf("d", "available"), listOf("stock", "3")))
        val soldOut = event(listOf(listOf("d", "empty"), listOf("stock", "0")))
        val unknown = event(listOf(listOf("d", "unknown")))

        assertEquals(3, Nip99.stock(available))
        assertEquals(Nip99.AvailabilityState.AVAILABLE, Nip99.availability(available).state)
        assertEquals(Nip99.AvailabilityState.SOLD_OUT, Nip99.availability(soldOut).state)
        assertEquals(Nip99.AvailabilityState.UNKNOWN, Nip99.availability(unknown).state)
    }

    @Test
    fun `malformed duplicate negative and overflowing stock are unknown`() {
        val duplicate = event(listOf(listOf("d", "duplicate"), listOf("stock", "4"), listOf("stock", "2")))
        val malformed = event(listOf(listOf("d", "malformed"), listOf("stock", "many")))
        val extraField = event(listOf(listOf("d", "extra"), listOf("stock", "2", "unexpected")))
        val negative = event(listOf(listOf("d", "negative"), listOf("stock", "-1")))
        val overflow = event(listOf(listOf("d", "overflow"), listOf("stock", "2147483648")))

        listOf(duplicate, malformed, extraField, negative, overflow).forEach { listing ->
            assertNull(Nip99.stock(listing))
            assertEquals(Nip99.AvailabilityState.UNKNOWN, Nip99.availability(listing).state)
        }
    }

    @Test
    fun `sold out availability includes zero stock for modern and legacy listings`() {
        val modern = event(listOf(listOf("d", "zero-stock"), listOf("stock", "0")))
        val legacy = event(listOf(listOf("d", "legacy-zero"))).copy(
            kind = Nip99.KIND_LEGACY_PRODUCT,
            content = """{"name":"Legacy product","quantity":0}"""
        )
        val explicitSoldOut = event(listOf(listOf("d", "sold-out"), listOf("status", "sold_out")))

        assertEquals(Nip99.AvailabilityState.SOLD_OUT, Nip99.availability(modern).state)
        assertEquals(Nip99.AvailabilityState.SOLD_OUT, Nip99.availability(legacy).state)
        assertEquals(Nip99.AvailabilityState.SOLD_OUT, Nip99.availability(explicitSoldOut).state)
    }

    @Test
    fun `explicit sold status takes precedence over positive stock`() {
        val listing = event(listOf(listOf("d", "sold"), listOf("status", "sold"), listOf("stock", "5")))
        assertEquals(Nip99.AvailabilityState.SOLD_OUT, Nip99.availability(listing).state)
    }

    @Test
    fun `buildTags publishes stock only when explicitly supplied`() {
        val withStock = Nip99.buildTags(
            dTag = "painting-stock", title = "Painting", summary = "", publishedAt = "1800000000",
            amount = "1", currency = "BTC", location = "", category = "", images = emptyList(),
            status = "active", stock = 2
        )
        val withoutStock = Nip99.buildTags(
            dTag = "painting-unknown", title = "Painting", summary = "", publishedAt = "1800000000",
            amount = "", currency = "", location = "", category = "", images = emptyList(), status = "active"
        )
        assertTrue(withStock.contains(listOf("stock", "2")))
        assertFalse(withoutStock.any { it.firstOrNull() == "stock" })
    }

    @Test
    fun `listing without title remains renderable using summary and description fallback`() {
        val withoutTitle = event(
            listOf(
                listOf("d", "external-listing"),
                listOf("summary", "An external NIP-99 listing")
            )
        )

        assertEquals("", Nip99.title(withoutTitle))
        assertEquals("An external NIP-99 listing", Nip99.displayTitle(withoutTitle, "Listing title"))
    }

    @Test
    fun `listing without title or summary uses first description line`() {
        val withoutTitle = event(listOf(listOf("d", "external-listing")))

        assertEquals("A detailed description", Nip99.displayTitle(withoutTitle, "Listing title"))
    }

    @Test
    fun `listing with no heading data uses localized caller fallback`() {
        val empty = event(listOf(listOf("d", "empty-heading"))).copy(content = "   \n  ")

        assertEquals("Listing title", Nip99.displayTitle(empty, "Listing title"))
    }

    @Test
    fun `uses the first occurrence of duplicate NIP-99 tags`() {
        val listing = event(
            listOf(
                listOf("d", "first-listing"),
                listOf("d", "second-listing"),
                listOf("title", "Original title"),
                listOf("title", "Injected title"),
                listOf("price", "450", "EUR"),
                listOf("price", "999", "USD")
            )
        )

        assertEquals("first-listing", Nip99.dTag(listing))
        assertEquals("Original title", Nip99.title(listing))
        assertEquals("450 EUR", Nip99.priceLabel(listing))
    }

    @Test
    fun `missing price is represented as null`() {
        assertNull(Nip99.price(event(listOf(listOf("d", "no-price"), listOf("title", "Free")))))
    }

    @Test
    fun `listing address tag uses the NIP-33 coordinate`() {
        val listing = event(listOf(listOf("d", "painting-01")))
        assertEquals("${Nip99.KIND_LISTING}:${listing.pubkey}:painting-01", Nip99.addressTag(listing))
    }

    @Test
    fun `listing reactions include its address coordinate`() {
        val listing = event(listOf(listOf("d", "painting-01")))
        assertTrue(Nip25.buildReactionTags(listing).contains(listOf("a", Nip99.addressTag(listing))))
    }

    @Test
    fun `naddr round trips listing coordinate`() {
        val author = "ab".repeat(32)
        val encoded = Nip19.naddrEncode(
            dTag = "painting-01",
            authorHex = author,
            kind = Nip99.KIND_LISTING,
            relays = listOf("wss://relay.example")
        )
        val decoded = Nip19.naddrDecode(encoded)

        assertEquals("painting-01", decoded.dTag)
        assertEquals(author, decoded.author)
        assertEquals(Nip99.KIND_LISTING, decoded.kind)
        assertEquals(listOf("wss://relay.example"), decoded.relays)
    }

    @Test
    fun `identifies owned NIP99 and legacy Marketplace listings case insensitively`() {
        val modern = event(listOf(listOf("d", "owned")))
        val legacy = modern.copy(kind = Nip99.KIND_LEGACY_PRODUCT)

        assertTrue(Nip99.isOwnedListing(modern, modern.pubkey))
        assertTrue(Nip99.isOwnedListing(modern, modern.pubkey.uppercase()))
        assertTrue(Nip99.isOwnedListing(legacy, modern.pubkey))
        assertFalse(Nip99.isOwnedListing(modern, "22".repeat(32)))
        assertFalse(Nip99.isOwnedListing(modern, null))
        assertFalse(Nip99.isOwnedListing(modern, ""))
    }

    @Test
    fun `identifies only supported Marketplace kinds for feed rendering`() {
        assertTrue(Nip99.isMarketplaceKind(Nip99.KIND_LISTING))
        assertTrue(Nip99.isMarketplaceKind(Nip99.KIND_LEGACY_PRODUCT))
        assertFalse(Nip99.isMarketplaceKind(1))
        assertFalse(Nip99.isMarketplaceKind(30023))
        assertFalse(Nip99.isMarketplaceKind(Nip99.KIND_DRAFT))
    }
    @Test
    fun `reads OpenMarkets type visibility and specification defaults`() {
        val legacy = event(listOf(listOf("d", "without-extension")))
        assertEquals("simple", Nip99.productType(legacy))
        assertEquals("digital", Nip99.productFormat(legacy))
        assertEquals("on-sale", Nip99.visibility(legacy))

        val physicalPreOrder = event(listOf(
            listOf("d", "physical-preorder"),
            listOf("type", "simple", "physical"),
            listOf("visibility", "pre-order"),
            listOf("status", "active"),
            listOf("stock", "0")
        ))
        assertEquals("simple", Nip99.productType(physicalPreOrder))
        assertEquals("physical", Nip99.productFormat(physicalPreOrder))
        assertEquals("pre-order", Nip99.visibility(physicalPreOrder))
        assertEquals(Nip99.AvailabilityState.UNKNOWN, Nip99.availability(physicalPreOrder).state)
        assertFalse(Nip99.isSold(physicalPreOrder))
    }

    @Test
    fun `publishes Gamma tags together with legacy NIP99 status and sold stock zero`() {
        val tags = Nip99.buildTags(
            dTag = "gamma-item", title = "Gamma item", summary = "", publishedAt = "1800000000",
            amount = "15", currency = "EUR", location = "Italy", category = "", images = emptyList(),
            status = "sold", stock = 9, format = "physical", visibility = "pre-order",
            preservedTags = listOf(
                listOf("a", "30402:2222222222222222222222222222222222222222222222222222222222222222:parent"),
                listOf("spec", "material", "oil"),
                listOf("status", "active"),
                listOf("title", "old")
            )
        )

        assertTrue(tags.contains(listOf("status", "sold")))
        assertTrue(tags.contains(listOf("type", "simple", "physical")))
        assertTrue(tags.contains(listOf("visibility", "on-sale")))
        assertTrue(tags.contains(listOf("stock", "0")))
        assertTrue(tags.contains(listOf("spec", "material", "oil")))
        assertTrue(tags.any { it.firstOrNull() == "a" })
        assertEquals(1, tags.count { it.firstOrNull() == "status" })
        assertEquals(1, tags.count { it.firstOrNull() == "title" })
    }

    @Test
    fun `hidden listings are visible to their owner but not other viewers`() {
        val hidden = event(listOf(
            listOf("d", "private-listing"),
            listOf("status", "active"),
            listOf("visibility", "hidden")
        ))
        assertEquals("hidden", Nip99.visibility(hidden))
        assertFalse(Nip99.isVisibleToViewer(hidden, "22".repeat(32)))
        assertTrue(Nip99.isVisibleToViewer(hidden, hidden.pubkey))
        assertTrue(Nip99.isVisibleToViewer(event(listOf(listOf("d", "public"))), "22".repeat(32)))
    }

    @Test
    fun `hide sold filter keeps own inventory and filters other sold listings`() {
        val sold = event(listOf(
            listOf("d", "sold-listing"),
            listOf("status", "active"),
            listOf("stock", "0")
        ))
        assertTrue(Nip99.isSold(sold))
        assertFalse(Nip99.matchesSoldFilter(sold, hideSold = true, viewerPubkey = "22".repeat(32)))
        assertTrue(Nip99.matchesSoldFilter(sold, hideSold = true, viewerPubkey = sold.pubkey))
        assertTrue(Nip99.matchesSoldFilter(sold, hideSold = false, viewerPubkey = "22".repeat(32)))
    }

    @Test
    fun `location filter matches case insensitively and ignores surrounding spaces`() {
        val listing = event(listOf(listOf("d", "location"), listOf("location", "  Genova  ")))
        assertTrue(Nip99.matchesLocation(listing, "genova"))
        assertFalse(Nip99.matchesLocation(listing, "Milano"))
        assertTrue(Nip99.matchesLocation(listing, null))
        assertTrue(Nip99.matchesLocation(listing, "  "))
    }

    @Test
    fun `buildTags preserves untouched image dimensions order and price frequency`() {
        val originalImage = listOf("image", "https://example.com/art.jpg", "1200x800", "3")
        val originalPrice = listOf("price", "12", "EUR", "month")
        val tags = Nip99.buildTags(
            dTag = "preserved-metadata", title = "Artwork", summary = "", publishedAt = "1800000000",
            amount = "12", currency = "eur", location = "", category = "",
            images = listOf("https://example.com/art.jpg"), status = "active",
            format = "physical", preservedTags = listOf(originalImage, originalPrice)
        )
        assertTrue(tags.contains(originalImage))
        assertTrue(tags.contains(originalPrice))
        assertEquals(1, tags.count { it.firstOrNull() == "image" })
        assertEquals(1, tags.count { it.firstOrNull() == "price" })
    }

}
