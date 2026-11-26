package com.gatecontrol.android.media.player

import androidx.media3.datasource.cache.CacheSpan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File

/** The space of „mitladen“: what goes first when it is full, the size of a file from the server's answer. */
class PlayAheadTest {

    private val mb = 1L shl 20

    private fun span(key: String, at: Long, length: Long = 2 * mb) = CacheSpan(key, at, length, 0, File("x"))

    @Test
    fun `room - first another file, then furthest behind, then furthest ahead, never right at the player`() {
        val other = span("old", 0)
        val behind = span("f", 0)
        val behind2 = span("f", 2 * mb)
        val near = span("f", 98 * mb)
        val at = span("f", 100 * mb)
        val ahead = span("f", 200 * mb)
        val farAhead = span("f", 400 * mb)
        val all = listOf(near, at, ahead, farAhead, behind2, behind, other)
        assertEquals(other, PlayheadEvictor.victim(all, "f", 100 * mb))
        assertEquals(behind, PlayheadEvictor.victim(all - other, "f", 100 * mb))
        assertEquals(farAhead, PlayheadEvictor.victim(listOf(near, at, ahead, farAhead), "f", 100 * mb))
        assertNull(PlayheadEvictor.victim(listOf(near, at), "f", 100 * mb))
        // nothing read yet: from the start
        assertEquals(behind, PlayheadEvictor.victim(listOf(ahead, behind), "f", -1))
    }

    @Test
    fun `the size of the file from Content-Range`() {
        assertEquals(52_428_800, PlayAheadHeaders.total(mapOf("content-range" to listOf("bytes 0-1023/52428800"))))
        assertEquals(10L, PlayAheadHeaders.total(mapOf("Content-Range" to listOf("bytes 2-3/10"))))
        assertNull(PlayAheadHeaders.total(mapOf("content-range" to listOf("bytes 0-1023/*"))))
        assertNull(PlayAheadHeaders.total(emptyMap()))
    }
}
