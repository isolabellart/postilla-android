package com.darkwisp.app.viewmodel

import com.darkwisp.app.nostr.Nip19
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

    @Test
    fun `decodes the supplied Marketplace naddr coordinate and relay hints`() {
        val address = Nip19.naddrDecode(
            "naddr1qqvhw6r9wfjj6argv5kkgcte94jkuern94ckjertw9hqz8thwden5te0wfjkccte9ej8wcty0f5k2umrd9sk5v339ecxcqglwaehxw309ahx7um5wghx26tww4hxg7nhv9h856t89eehqctrv5q3samnwvaz7tm9wdcx2mrgduhxw6tjd9hx7tn0wfnsyg85mdf8p0vervtmag0x6q6lgh0w8y53ns55wja6cyp5953rca8q6qpsgqqqwmpq3lex2c"
        )

        assertEquals(30402, address.kind)
        assertEquals("where-the-day-ends-qidkqn", address.dTag)
        assertEquals(
            "f4db5270bd991b17bea1e6d035f45dee392919c29474bbac10342d223c74e0d0",
            address.author
        )
        assertEquals(
            listOf(
                "wss://relay.dwadziesciaj21.pl",
                "wss://nostr.einundzwanzig.space",
                "wss://espelho.girino.org"
            ),
            address.relays
        )
    }
}
