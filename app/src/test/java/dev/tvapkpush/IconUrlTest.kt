package dev.tvapkpush

import org.junit.Assert.*
import org.junit.Test

class IconUrlTest {
    @Test fun googleArtworkUsesSmallThumbnails() {
        assertEquals("https://play-lh.googleusercontent.com/icon=s192", IconUrl.thumbnail("https://play-lh.googleusercontent.com/icon"))
        assertEquals("https://lh3.googleusercontent.com/icon=s192", IconUrl.thumbnail("https://lh3.googleusercontent.com/icon=w480-h960-rw"))
        assertEquals("https://play-lh.googleusercontent.com/icon=s192", IconUrl.thumbnail("https://play-lh.googleusercontent.com/icon=s96"))
    }

    @Test fun signedAndUnrelatedUrlsStayUnchanged() {
        listOf("https://cdn.example.com/icon.png", "https://play-lh.googleusercontent.com/icon?token=fixture",
            "https://googleusercontent.com.example.com/icon").forEach { assertEquals(it, IconUrl.thumbnail(it)) }
    }

    @Test fun unsupportedSourcesAreRejected() {
        listOf("", "invalid", "http://cdn.example.com/icon.png", "file:///private/icon.png").forEach { assertNull(IconUrl.thumbnail(it)) }
    }
}
