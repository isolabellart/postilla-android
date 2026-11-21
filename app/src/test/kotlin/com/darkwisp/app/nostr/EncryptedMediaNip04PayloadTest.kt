package com.darkwisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EncryptedMediaNip04PayloadTest {
    private val metadata = EncryptedMedia.EncryptedFileMetadata(
        fileUrl = "https://cdn.example/image.enc",
        mimeType = "image/jpeg",
        algorithm = EncryptedMedia.ALGORITHM,
        keyHex = "a".repeat(64),
        nonceHex = "b".repeat(24),
        encryptedHash = "c".repeat(64),
        originalHash = "d".repeat(64),
        size = 12345L,
        dimensions = "1200x800",
        thumbhash = null,
        blurhash = null
    )

    @Test
    fun roundTripsCaptionAndEncryptedImageMetadata() {
        val encoded = EncryptedMedia.encodeNip04Content("A small memory", metadata)
        val (text, decoded) = EncryptedMedia.decodeNip04Content(encoded)

        assertEquals("A small memory", text)
        assertEquals(metadata, decoded)
    }

    @Test
    fun roundTripsImageWithoutCaption() {
        val encoded = EncryptedMedia.encodeNip04Content("", metadata)
        val (text, decoded) = EncryptedMedia.decodeNip04Content(encoded)

        assertEquals("", text)
        assertEquals(metadata, decoded)
    }

    @Test
    fun leavesOrdinaryNip04TextUntouched() {
        val original = "A normal legacy message"
        val (text, decoded) = EncryptedMedia.decodeNip04Content(original)

        assertEquals(original, text)
        assertNull(decoded)
        assertEquals(original, EncryptedMedia.encodeNip04Content(original, null))
    }

    @Test
    fun rejectsMalformedOrUntrustedMediaMetadata() {
        val malformed = "Caption\n\n[postilla-media-v1:not-valid-base64]"
        val (text, decoded) = EncryptedMedia.decodeNip04Content(malformed)

        assertEquals(malformed, text)
        assertNull(decoded)
    }
}
