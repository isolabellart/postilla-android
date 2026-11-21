package com.darkwisp.app.repo

import org.junit.Assert.assertTrue
import org.junit.Test

class NostrWineApiClientTest {
    @Test
    fun malformedAndUnexpectedResponsesReturnEmptyLists() {
        assertTrue(NostrWineApiClient.parseEvents("""{"error":"rate limited"}""").isEmpty())
        assertTrue(NostrWineApiClient.parseEvents("not-json").isEmpty())
        assertTrue(NostrWineApiClient.parseEvents("""{"data":[]}""").isEmpty())
    }
}
