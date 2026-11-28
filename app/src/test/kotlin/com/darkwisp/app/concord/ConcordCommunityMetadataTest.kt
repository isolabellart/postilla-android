package com.darkwisp.app.concord

import com.vitorpamplona.quartz.concord.cord04Roles.ConcordJson
import com.vitorpamplona.quartz.concord.cord04Roles.MetadataEntity
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcordCommunityMetadataTest {
    @Test
    fun metadataEditPreservesTimerAndFieldsUnknownToThisClient() {
        val originalContent = """
            {
              "name": "Old group name",
              "description": "Original description",
              "relays": ["wss://relay.example"],
              "message_expiration": 2592000,
              "custom": {"rules": "be kind", "welcome": "hello"},
              "av_brokers": ["wss://broker.example"]
            }
        """.trimIndent()
        val previous = ConcordJson.instance.decodeFromString(MetadataEntity.serializer(), originalContent)
        assertNotNull(previous)

        val updated = previous!!.copy(
            name = "Postilla community",
            description = "Updated from Postilla",
        )
        val encoded = ConcordJson.encodePreserving(
            MetadataEntity.serializer(),
            updated,
            originalContent,
        )
        val actual = ConcordJson.instance.parseToJsonElement(encoded).jsonObject
        val original = ConcordJson.instance.parseToJsonElement(originalContent).jsonObject

        assertEquals(JsonPrimitive("Postilla community"), actual["name"])
        assertEquals(JsonPrimitive("Updated from Postilla"), actual["description"])
        assertEquals(original["message_expiration"], actual["message_expiration"])
        assertEquals(original["custom"], actual["custom"])
        assertEquals(original["av_brokers"], actual["av_brokers"])
        assertTrue(actual.containsKey("relays"))
    }

    @Test
    fun metadataEditCanClearDescriptionWithoutDroppingOtherFields() {
        val originalContent = """{"name":"Group","description":"Old","message_expiration":3600,"custom":{"theme":"dark"}}"""
        val previous = ConcordJson.instance.decodeFromString(MetadataEntity.serializer(), originalContent)!!
        val encoded = ConcordJson.encodePreserving(
            MetadataEntity.serializer(),
            previous.copy(description = null),
            originalContent,
        )
        val actual = ConcordJson.instance.parseToJsonElement(encoded).jsonObject
        assertTrue(!actual.containsKey("description"))
        assertEquals(JsonPrimitive(3600), actual["message_expiration"])
        assertEquals(
            ConcordJson.instance.parseToJsonElement("""{"theme":"dark"}"""),
            actual["custom"],
        )
    }
}
