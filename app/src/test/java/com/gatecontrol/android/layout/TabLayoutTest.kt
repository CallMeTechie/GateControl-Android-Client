package com.gatecontrol.android.layout

import com.gatecontrol.android.network.ClientLayoutDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TabLayoutTest {

    @Test
    fun `lists are cleaned the way the server checks them`() {
        val raw = listOf("info", "tile:p/a", "info", "", "has space", "ümlaut", "x".repeat(121), "widget:p/@a", "usage")
        assertEquals(listOf("info", "tile:p/a", "widget:p/@a", "usage"), TabLayout.clean(raw))
        assertEquals(TabLayout.MAX_ENTRIES, TabLayout.clean((1..150).map { "tile:p/$it" }).size)
        assertEquals(emptyList<String>(), TabLayout.clean(null))
        assertNull(TabLayout.from(null))
        val fromServer = TabLayout.from(ClientLayoutDto(start = listOf("info", "info"), familie = null))
        assertEquals(TabLayout(start = listOf("info")), fromServer)
    }

    @Test
    fun `saving keeps another device's entries after the one they followed`() {
        val remote = listOf("tile:a/1", "widget:b/2", "widget:only/there", "info", "usage")
        val local = listOf("tile:a/1", "usage", "info")
        assertEquals(
            listOf("tile:a/1", "widget:b/2", "widget:only/there", "usage", "info"),
            TabLayout.keepForeign(local, remote, StartLayout::identity),
        )
        // A width change is the same widget, not a foreign one.
        assertEquals(listOf("widget2:b/2", "info"), TabLayout.keepForeign(listOf("widget2:b/2", "info"), listOf("widget:b/2", "info"), StartLayout::identity))
        // Foreign entries before anything known go to the front.
        assertEquals(listOf("x", "info"), TabLayout.keepForeign(listOf("info"), listOf("x", "info")))
    }

    @Test
    fun `foreign entries never push the list past the limit`() {
        val local = (1..99).map { "tile:p/$it" }
        val merged = TabLayout.keepForeign(local, listOf("tile:q/1", "tile:q/2", "tile:q/3"))
        assertEquals(TabLayout.MAX_ENTRIES, merged.size)
        assertTrue("tile:q/1" in merged)
        assertFalse("tile:q/2" in merged)
    }

    @Test
    fun `access tells shared and locked orders apart`() {
        assertFalse(LayoutAccess().shared) // older server: the device's own
        assertFalse(LayoutAccess(LayoutScope.USER, server = false).shared)
        assertTrue(LayoutAccess(LayoutScope.USER, editable = true, server = true).shared)
        assertFalse(LayoutAccess(LayoutScope.USER, editable = true, server = true).locked)
        assertTrue(LayoutAccess(LayoutScope.GLOBAL, editable = false, server = true).locked)
        assertFalse(LayoutAccess(LayoutScope.DEVICE, editable = false, server = true).locked)
        assertEquals(LayoutScope.GLOBAL, LayoutScope.parse("global"))
        assertNull(LayoutScope.parse("team"))
    }

    @Test
    fun `stored layouts survive missing fields and garbage`() {
        assertEquals(StoredTabLayout(), StoredTabLayout.decode(null))
        assertEquals(StoredTabLayout(), StoredTabLayout.decode("not json"))
        val partial = StoredTabLayout.decode("""{"layout":{"start":["info",null,"bad id"]},"access":{"editable":false}}""")
        assertEquals(listOf("info"), partial.layout.start)
        assertEquals(emptyList<String>(), partial.layout.familie)
        assertEquals(LayoutScope.DEVICE, partial.access.scope)
        val full = StoredTabLayout(TabLayout(start = listOf("usage")), access = LayoutAccess(LayoutScope.USER, true, true), pending = true, owner = "o")
        assertEquals(full, StoredTabLayout.decode(StoredTabLayout.encode(full)))
    }

    @Test
    fun `moveBy shifts one item and leaves the ends alone`() {
        assertEquals(listOf("b", "a", "c"), TabLayout.moveBy(listOf("a", "b", "c"), 0, 1))
        assertEquals(listOf("a", "b", "c"), TabLayout.moveBy(listOf("a", "b", "c"), 0, -1))
    }
}
