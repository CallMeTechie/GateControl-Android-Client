package com.gatecontrol.android.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pages beside Start kept as `page:<id>` markers in the start tab's list. */
class StartPagesTest {

    private val door = "gatecontrol-door/open:1"
    private val light = "gatecontrol-ha/light:2"
    private val car = "gatecontrol-skoda/car:1"
    private val nas = "gatecontrol-nas/ds918"

    private fun widget(device: String, wide: Boolean = false) = StartEntry.Widget(device, wide)

    @Test
    fun `the list splits at the markers and joins back`() {
        val saved = listOf("tile:$door", "info", "page:network", "page:w2", "tile:$light", "widget:$nas", "page:familie", "usage")
        val split = StartPages.split(saved)
        // A function page opens no section: what follows its marker still belongs to the page before it.
        assertEquals(listOf("tile:$door", "info"), split.start)
        assertEquals(listOf("network", "w2", "familie"), split.ids)
        assertEquals(listOf("tile:$light", "widget:$nas", "usage"), split.pages.first { it.id == "w2" }.entries)
        assertEquals(emptyList<String>(), split.pages.first { it.id == "network" }.entries)
        assertEquals(
            listOf("tile:$door", "info", "page:network", "page:w2", "tile:$light", "widget:$nas", "usage", "page:familie"),
            StartPages.join(split),
        )
        // Joined lists split the same way again.
        assertEquals(split, StartPages.split(StartPages.join(split)))
    }

    @Test
    fun `entries after a function marker before any widget page stay on Start`() {
        // What an older app writes: its own entries behind the markers it kept.
        val split = StartPages.split(listOf("tile:$door", "page:chats", "widget:$car", "info"))
        assertEquals(listOf("tile:$door", "widget:$car", "info"), split.start)
        assertEquals(listOf("chats"), split.ids)
    }

    @Test
    fun `duplicate, empty and unknown markers`() {
        val split = StartPages.split(listOf("page:", "page:vault", "page:vault", "page:future", "page:w1", "info"))
        // The first marker of an id counts, one without id goes; unknown ids stay (saving keeps them) without entries.
        assertEquals(listOf("vault", "future", "w1"), split.ids)
        assertEquals(listOf("info"), split.start)
        assertEquals(listOf("vault", "future", "w1"), StartPages.pages(StartPages.join(split)))
        // Shown: known functions that are available and widget pages from 2 on.
        assertEquals(listOf("vault"), StartPages.visible(listOf("page:vault", "page:future", "page:w1", "page:drive"), listOf("vault")))
        assertEquals(listOf("w2", "network"), StartPages.visible(listOf("page:w2", "page:network"), listOf("network", "chats")))
    }

    @Test
    fun `widget page ids`() {
        assertTrue(StartPages.isWidgets("w2"))
        assertTrue(StartPages.isWidgets("w17"))
        assertFalse(StartPages.isWidgets("w1"))
        assertFalse(StartPages.isWidgets("w"))
        assertFalse(StartPages.isWidgets("wx"))
        assertFalse(StartPages.isWidgets("chats"))
        assertEquals(3, StartPages.widgetsNumber("w3"))
        assertNull(StartPages.widgetsNumber("network"))
        assertEquals("w2", StartPages.nextWidgets(emptyList()))
        assertEquals("w3", StartPages.nextWidgets(listOf("page:w2", "page:chats")))
        // A gap is filled first.
        assertEquals("w3", StartPages.nextWidgets(listOf("page:w2", "page:w4")))
    }

    @Test
    fun `pinning appends a page, taking it off keeps a widget page's blocks on Start`() {
        val saved = listOf("tile:$door", "info", "usage")
        val pinned = StartPages.pin(saved, "network")
        assertEquals(listOf("tile:$door", "info", "usage", "page:network"), pinned)
        assertEquals(pinned, StartPages.pin(pinned, "network"))
        val withWidgets = StartPages.pin(pinned, "w2").let { StartPages.withEntries(it, "w2", listOf("widget:$nas", "tile:$light")) }
        assertEquals(listOf("tile:$door", "info", "usage", "page:network", "page:w2", "widget:$nas", "tile:$light"), withWidgets)
        assertEquals(listOf("tile:$door", "info", "usage", "page:w2", "widget:$nas", "tile:$light"), StartPages.unpin(withWidgets, "network"))
        assertEquals(listOf("tile:$door", "info", "usage", "widget:$nas", "tile:$light", "page:network"), StartPages.unpin(withWidgets, "w2"))
        // Taking off a page that is none changes nothing.
        assertEquals(saved, StartPages.unpin(saved, "chats"))
    }

    @Test
    fun `the entries of one page are replaced, the others stay`() {
        val saved = listOf("tile:$door", "page:w2", "tile:$light", "page:chats")
        assertEquals(listOf("info", "page:w2", "tile:$light", "page:chats"), StartPages.withEntries(saved, null, listOf("info")))
        assertEquals(listOf("tile:$door", "page:w2", "widget:$nas", "page:chats"), StartPages.withEntries(saved, "w2", listOf("widget:$nas")))
        // A function page has no entries, a missing page none to replace.
        assertSame(saved, StartPages.withEntries(saved, "chats", listOf("info")))
        assertSame(saved, StartPages.withEntries(saved, "w3", listOf("info")))
        assertEquals(listOf("tile:$light"), StartPages.entriesOf(saved, "w2"))
        assertEquals(listOf("tile:$door"), StartPages.entriesOf(saved, null))
    }

    @Test
    fun `pages are reordered with their entries`() {
        val saved = listOf("info", "page:network", "page:w2", "tile:$light", "page:familie")
        assertEquals(listOf("info", "page:familie", "page:w2", "tile:$light", "page:network"), StartPages.reorder(saved, listOf("familie", "w2", "network")))
        // Ids the order lacks keep theirs after the others.
        assertEquals(listOf("info", "page:w2", "tile:$light", "page:network", "page:familie"), StartPages.reorder(saved, listOf("w2")))
        val ids = listOf("network", "w2", "familie")
        assertEquals(listOf("w2", "network", "familie"), StartPages.moveBy(ids, "w2", -1))
        assertFalse(StartPages.canMove(ids, "network", -1))
        assertTrue(StartPages.canMove(ids, "network", 1))
        assertFalse(StartPages.canMove(ids, "familie", 1))
        assertEquals(listOf("w2", "familie", "network"), StartPages.move(ids, "network", "familie"))
        assertSame(ids, StartPages.move(ids, "network", "chats"))
    }

    @Test
    fun `each pin stands on the page that names it, the rest on Start`() {
        val saved = listOf("tile:$door", "widget:$car", "info", "usage", "page:network", "page:w2", "widget2:$nas", "tile:$light", "info", "page:w3")
        val pages = StartPages.merge(saved, tiles = listOf(door, light, "new/tile"), widgets = listOf(widget(car), widget(nas, wide = true), widget("new/widget")))
        assertEquals(
            listOf(StartEntry.Tile(door), StartEntry.Tile("new/tile"), widget(car), widget("new/widget"), StartEntry.Info, StartEntry.Usage),
            pages[null],
        )
        // No Info and usage card on a widget page; the width is the pin's.
        assertEquals(listOf(StartEntry.Tile(light), widget(nas, wide = true)), pages["w2"])
        // An empty widget page is there, without blocks.
        assertEquals(emptyList<StartEntry>(), pages["w3"])
        assertEquals(setOf(null, "w2", "w3"), pages.keys)
    }

    @Test
    fun `without pages Start shows what it showed before`() {
        val saved = listOf("widget:$car", "tile:$door", "usage", "info")
        val tiles = listOf(door, light)
        val widgets = listOf(widget(car))
        assertEquals(StartLayout.merge(saved, tiles, widgets), StartPages.merge(saved, tiles, widgets)[null])
        assertEquals(StartLayout.merge(emptyList(), tiles, widgets), StartPages.merge(listOf("page:network"), tiles, widgets)[null])
    }

    @Test
    fun `a pin made on a widget page joins it, the pages stay as they are`() {
        val saved = listOf("tile:$door", "info", "usage", "page:network", "page:w2", "widget:$nas")
        val synced = StartPages.syncPins(saved, tiles = listOf(door, light), widgets = listOf(widget(nas), widget(car)), target = "w2")
        assertEquals(listOf("tile:$door", "info", "usage", "page:network", "page:w2", "tile:$light", "widget:$nas", "widget:$car"), synced)
        // Pinned from Start (or a page that is gone): Start.
        assertEquals(
            listOf("tile:$door", "tile:$light", "info", "usage", "page:network", "page:w2", "widget:$nas"),
            StartPages.syncPins(saved, tiles = listOf(door, light), widgets = listOf(widget(nas)), target = null),
        )
        assertEquals(
            StartPages.syncPins(saved, listOf(door, light), listOf(widget(nas)), target = null),
            StartPages.syncPins(saved, listOf(door, light), listOf(widget(nas)), target = "w9"),
        )
        // Unpinned blocks drop out of their page; the page stays (empty).
        assertEquals(listOf("tile:$door", "info", "usage", "page:network", "page:w2"), StartPages.syncPins(saved, listOf(door), emptyList(), target = null))
    }

    @Test
    fun `markers are entries the server accepts`() {
        (StartPages.FEATURES + listOf("w2", "w999")).forEach { assertTrue(TabLayout.isValidEntry(StartPages.marker(it)), it) }
        val many = (1..120).map { "tile:p/$it" } + "page:network"
        // The start list is cut at the server's limit like before (markers count as entries).
        assertEquals(TabLayout.MAX_ENTRIES, TabLayout.clean(StartPages.pin(many, "chats")).size)
    }

    @Test
    fun `an older app skips the markers and keeps them when it saves`() {
        val remote = listOf("tile:$door", "info", "page:network", "page:w2", "widget:$nas", "usage")
        // Older apps: no block for a marker, every pin of the device back on Start.
        StartPages.FEATURES.forEach { assertNull(StartLayout.decode(StartPages.marker(it))) }
        assertNull(StartLayout.decode("page:w2"))
        assertEquals(
            listOf(StartEntry.Tile(door), StartEntry.Info, widget(nas), StartEntry.Usage),
            StartLayout.merge(remote, listOf(door), listOf(widget(nas))),
        )
        // Their saving (encode of what they show, then keepForeign over the server's copy) keeps the markers.
        val oldLocal = StartLayout.encodeAll(StartLayout.merge(remote, listOf(door), listOf(widget(nas))))
        val pushed = TabLayout.keepForeign(oldLocal, remote, StartLayout::identity)
        assertEquals(remote, pushed)
        assertEquals(listOf("network", "w2"), StartPages.pages(pushed))
    }

    @Test
    fun `saving here keeps another device's blocks but not a page taken off here`() {
        val remote = listOf("tile:$door", "page:network", "page:w2", "widget:other/phone", "tile:$light")
        val local = listOf("tile:$door", "page:w2", "tile:$light")
        // "network" was taken off here: it must not come back from the server's copy.
        val pushed = StartPages.keepForeign(local, remote)
        assertEquals(listOf("tile:$door", "page:w2", "widget:other/phone", "tile:$light"), pushed)
        // The other device's widget stays on its page.
        assertEquals(listOf("widget:other/phone", "tile:$light"), StartPages.entriesOf(pushed, "w2"))
        // A widget page taken off here: its foreign blocks fall back to the page before it.
        val gone = StartPages.keepForeign(listOf("tile:$door", "tile:$light"), remote)
        assertEquals(emptyList<String>(), StartPages.pages(gone))
        assertTrue("widget:other/phone" in StartPages.entriesOf(gone, null))
    }
}
