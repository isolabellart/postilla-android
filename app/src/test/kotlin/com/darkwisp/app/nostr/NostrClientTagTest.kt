package com.darkwisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Test

class NostrClientTagTest {
    @Test
    fun clientTagIdentifiesPostilla() {
        assertEquals(listOf("client", "Postilla"), NostrClientTag.tag)
    }
}
