package com.gatecontrol.android.ui.drive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The player of a streamed Drive video, kept by the view model: one per video shown, released exactly once. */
class DriveKeptPlayerTest {

    private class Fake { var released = 0 }

    private fun kept() = DriveKeptPlayer<Fake> { it.released++ }

    private val a = DriveVideoKey(1, 1, 0)
    private val b = DriveVideoKey(2, 1, 0)

    @Test
    fun `the same video gets the same player - after a fold or a turn it plays on`() {
        val kept = kept()
        var made = 0
        val first = kept.get(a) { made++; Fake() }
        assertSame(first, kept.peek(DriveVideoKey(1, 1, 0)))
        assertSame(first, kept.get(DriveVideoKey(1, 1, 0)) { made++; Fake() })
        assertEquals(1, made)
        assertEquals(0, first.released)
    }

    @Test
    fun `peek makes nothing`() {
        val kept = kept()
        assertNull(kept.peek(a))
        kept.get(a) { Fake() }
        assertNull(kept.peek(b))
    }

    @Test
    fun `another video or another try - the last player is released, once`() {
        val kept = kept()
        val first = kept.get(a) { Fake() }
        val second = kept.get(a.copy(attempt = 1)) { Fake() }
        assertEquals(1, first.released)
        // the page of the first one leaves the screen afterwards: it is not the one kept, nothing again
        assertFalse(kept.holds(first))
        kept.release(first)
        assertEquals(1, first.released)
        assertEquals(0, second.released)
        val third = kept.get(b) { Fake() }
        assertEquals(1, second.released)
        assertTrue(kept.holds(third))
    }

    @Test
    fun `released when its page goes and again with the view model - once`() {
        val kept = kept()
        val p = kept.get(a) { Fake() }
        kept.release(p)
        kept.release(p)
        kept.clear()
        assertEquals(1, p.released)
        assertNull(kept.peek(a))
    }

    @Test
    fun `the view model goes with the player still kept - released once`() {
        val kept = kept()
        val p = kept.get(a) { Fake() }
        kept.clear()
        kept.clear()
        assertEquals(1, p.released)
    }
}
