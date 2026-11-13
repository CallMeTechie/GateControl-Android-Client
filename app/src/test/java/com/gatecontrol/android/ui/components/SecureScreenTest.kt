package com.gatecontrol.android.ui.components

import com.gatecontrol.android.ui.start.startPageVisible
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecureScreenTest {

    /** A window as the holds see it: its flag and every change made to it. */
    private class Win(var secure: Boolean = false) {
        val changes = mutableListOf<Boolean>()
    }

    private fun holds() = SecureHolds<Win>(wasSecure = { it.secure }, set = { w, on -> w.secure = on; w.changes += on })

    @Test
    fun `the flag is set with the first screen and taken away with the last`() {
        val holds = holds()
        val window = Win()
        holds.acquire(window)
        assertTrue(window.secure)
        holds.release(window)
        assertFalse(window.secure)
        assertEquals(listOf(true, false), window.changes)
        assertEquals(0, holds.count(window))
    }

    @Test
    fun `two screens at once - the one going does not take the flag from the one staying`() {
        // a navigation: the next screen is composed before the last one is disposed
        val holds = holds()
        val window = Win()
        holds.acquire(window)
        holds.acquire(window)
        assertEquals(2, holds.count(window))
        holds.release(window)
        assertTrue(window.secure, "one screen still asks for it")
        assertEquals(listOf(true), window.changes, "set once, not again per screen")
        holds.release(window)
        assertFalse(window.secure)
    }

    @Test
    fun `a viewer over its page - closing the viewer leaves the page secure, in either order`() {
        val holds = holds()
        val window = Win()
        holds.acquire(window) // the page
        holds.acquire(window) // the viewer
        holds.release(window) // the page first
        assertTrue(window.secure)
        holds.acquire(window)
        holds.release(window)
        assertTrue(window.secure)
        holds.release(window)
        assertFalse(window.secure)
    }

    @Test
    fun `a dialog has a window of its own - each window is counted by itself`() {
        val holds = holds()
        val activity = Win()
        val dialog = Win()
        holds.acquire(dialog)
        assertTrue(dialog.secure)
        assertFalse(activity.secure, "the page under the dialog did not ask")
        holds.acquire(activity)
        holds.release(dialog)
        assertFalse(dialog.secure)
        assertTrue(activity.secure, "the dialog closing does not open the page under it")
        holds.release(activity)
        assertFalse(activity.secure)
    }

    @Test
    fun `a window secure by itself stays so`() {
        val holds = holds()
        val window = Win(secure = true)
        holds.acquire(window)
        holds.release(window)
        assertTrue(window.secure)
        assertTrue(window.changes.isEmpty())
    }

    @Test
    fun `a release nobody asked for changes nothing`() {
        val holds = holds()
        val window = Win()
        holds.release(window)
        assertEquals(0, holds.count(window))
        assertTrue(window.changes.isEmpty())
        holds.acquire(window)
        holds.release(window)
        holds.release(window)
        assertEquals(listOf(true, false), window.changes)
        holds.acquire(window)
        assertTrue(window.secure, "counted from zero again")
    }

    @Test
    fun `a page beside Start shows from its first part until it is gone`() {
        // at rest on page 1: only that page shows, the ones composed beside it do not
        assertTrue(startPageVisible(index = 1, currentPage = 1, offsetFraction = 0f))
        assertFalse(startPageVisible(index = 0, currentPage = 1, offsetFraction = 0f))
        assertFalse(startPageVisible(index = 2, currentPage = 1, offsetFraction = 0f))
        // dragged a little towards page 2: both show, page 0 does not
        assertTrue(startPageVisible(index = 2, currentPage = 1, offsetFraction = 0.02f))
        assertTrue(startPageVisible(index = 1, currentPage = 1, offsetFraction = 0.02f))
        assertFalse(startPageVisible(index = 0, currentPage = 1, offsetFraction = 0.02f))
        // past the middle the pager counts from page 2: page 1 still shows until it is all gone
        assertTrue(startPageVisible(index = 1, currentPage = 2, offsetFraction = -0.4f))
        assertTrue(startPageVisible(index = 1, currentPage = 2, offsetFraction = -0.01f))
        assertFalse(startPageVisible(index = 1, currentPage = 2, offsetFraction = 0f))
        // dragged back towards page 0
        assertTrue(startPageVisible(index = 0, currentPage = 1, offsetFraction = -0.3f))
        assertFalse(startPageVisible(index = 2, currentPage = 1, offsetFraction = -0.3f))
        // a jump over several pages (a tap on a dot): the pages far away never show
        assertFalse(startPageVisible(index = 1, currentPage = 4, offsetFraction = -0.5f))
    }

    @Test
    fun `the flag is held only by a screen that asks for it and shows`() {
        assertTrue(secureScreenActive(wanted = true, pageVisible = true))
        // the chats composed beside the shown page, "Bildschirmfotos verhindern" on
        assertFalse(secureScreenActive(wanted = true, pageVisible = false))
        // the chats shown, the setting off
        assertFalse(secureScreenActive(wanted = false, pageVisible = true))
        assertFalse(secureScreenActive(wanted = false, pageVisible = false))
    }

    @Test
    fun `swiping from the chats to the vault - the flag never drops on the way`() {
        // both pages hold the same window; each follows its own visibility
        val holds = holds()
        val window = Win()
        var chats = false
        var vault = false
        fun at(page: Int, offset: Float) {
            val c = secureScreenActive(true, startPageVisible(1, page, offset))
            val v = secureScreenActive(true, startPageVisible(2, page, offset))
            // as Compose: the effects of a frame are disposed first, then started
            if (chats && !c) holds.release(window)
            if (vault && !v) holds.release(window)
            if (!chats && c) holds.acquire(window)
            if (!vault && v) holds.acquire(window)
            chats = c
            vault = v
        }
        at(0, 0f)
        assertFalse(window.secure, "on Start, both composed ahead at most")
        at(0, 0.1f)
        assertTrue(window.secure, "the chats come in")
        at(1, 0f)
        at(1, 0.3f)
        at(2, -0.3f)
        at(2, 0f)
        assertTrue(window.secure)
        assertEquals(listOf(true), window.changes, "set once, never dropped between the two pages")
        at(2, 0.4f)
        at(3, 0f)
        assertFalse(window.secure, "the vault is gone")
    }
}
