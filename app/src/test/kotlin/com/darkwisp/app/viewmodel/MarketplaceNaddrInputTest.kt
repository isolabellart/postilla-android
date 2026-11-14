package com.darkwisp.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarketplaceNaddrInputTest {
    @Test
    fun `accepts a bare naddr and trims surrounding whitespace`() {
        assertEquals("naddr1example", normalizeMarketplaceNaddrInput("  naddr1example  "))
    }

    @Test
    fun `accepts a NIP21 nostr URI`() {
        assertEquals("naddr1example", normalizeMarketplaceNaddrInput("nostr:naddr1example"))
    }

    @Test
    fun `normalizes uppercase bech32 input`() {
        assertEquals("naddr1example", normalizeMarketplaceNaddrInput("NADDR1EXAMPLE"))
    }

    @Test
    fun `leaves ordinary Marketplace text search alone`() {
        assertNull(normalizeMarketplaceNaddrInput("watercolor landscape"))
    }

    @Test
    fun `returns idle for an empty query`() {
        assertNull(normalizeMarketplaceNaddrInput("   "))
    }
}
