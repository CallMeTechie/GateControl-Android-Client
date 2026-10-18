package com.gatecontrol.android.layout

import com.gatecontrol.android.layout.StartLayout.isHalf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StartLayoutTest {

    private val door = "gatecontrol-door/open:1"
    private val home = "gatecontrol-presence/home"
    private val car = "gatecontrol-skoda/car:1"
    private val clima = "gatecontrol-clima/ac:2"
    private val nas = "gatecontrol-nas/ds918"

    private fun widget(device: String, wide: Boolean = false) = StartEntry.Widget(device, wide)

    @Test
    fun `entries encode and decode, the width in the prefix`() {
        val all = listOf(StartEntry.Tile(door), widget(car), widget(clima, wide = true), StartEntry.Info, StartEntry.Usage)
        val raw = StartLayout.encodeAll(all)
        assertEquals(listOf("tile:$door", "widget:$car", "widget2:$clima", "info", "usage"), raw)
        assertEquals(all, raw.map { StartLayout.decode(it) })
        assertNull(StartLayout.decode("tile:"))
        assertNull(StartLayout.decode("whatever"))
        assertEquals("widget:$clima", StartLayout.identity("widget2:$clima"))
        assertEquals(mapOf(car to false, clima to true), StartLayout.widths(raw))
        // Every entry fits the server's rule for ids.
        assertTrue(raw.all { TabLayout.isValidEntry(it) })
    }

    @Test
    fun `without a stored order tiles come first, then widgets, Info and usage card`() {
        val merged = StartLayout.merge(emptyList(), listOf(door, home), listOf(widget(car), widget(clima)))
        assertEquals(
            listOf(StartEntry.Tile(door), StartEntry.Tile(home), widget(car), widget(clima), StartEntry.Info, StartEntry.Usage),
            merged,
        )
    }

    @Test
    fun `the stored order wins, the width is the pin's, blocks this device lacks are skipped`() {
        val saved = listOf("tile:$home", "usage", "widget2:$clima", "tile:gone/x", "info", "widget:$car", "widget:other/dev", "bogus")
        val merged = StartLayout.merge(saved, listOf(door, home), listOf(widget(car), widget(clima)))
        assertEquals(
            listOf(StartEntry.Tile(home), StartEntry.Tile(door), StartEntry.Usage, widget(clima, wide = false), StartEntry.Info, widget(car)),
            merged,
        )
    }

    @Test
    fun `a newly pinned widget joins after the last widget, else before the cards`() {
        val afterWidgets = StartLayout.merge(listOf("info", "widget:$car", "usage"), emptyList(), listOf(widget(car), widget(nas)))
        assertEquals(listOf(StartEntry.Info, widget(car), widget(nas), StartEntry.Usage), afterWidgets)
        val noWidgetYet = StartLayout.merge(listOf("usage", "info"), emptyList(), listOf(widget(nas)))
        assertEquals(listOf(widget(nas), StartEntry.Usage, StartEntry.Info), noWidgetYet)
    }

    @Test
    fun `moving keeps tiles among tiles and cards among cards`() {
        val entries = StartLayout.merge(emptyList(), listOf(door, home), listOf(widget(car), widget(clima)))
        val moved = StartLayout.move(entries, "info", "widget:$car")
        assertEquals(listOf("tile:$door", "tile:$home", "info", "widget:$car", "widget:$clima", "usage"), moved.map { it.key })
        assertSame(entries, StartLayout.move(entries, "tile:$door", "widget:$car"))
        assertSame(entries, StartLayout.move(entries, "h:tiles", "info"))
        val tiles = StartLayout.move(entries, "tile:$home", "tile:$door")
        assertEquals(listOf("tile:$home", "tile:$door"), tiles.take(2).map { it.key })
    }

    @Test
    fun `move up and down stop at the edge of the group`() {
        val entries = StartLayout.merge(emptyList(), listOf(door), listOf(widget(car)))
        assertFalse(StartLayout.canMove(entries, "tile:$door", 1)) // the next block is a card
        assertFalse(StartLayout.canMove(entries, "widget:$car", -1))
        assertTrue(StartLayout.canMove(entries, "widget:$car", 1))
        assertEquals(listOf("tile:$door", "info", "widget:$car", "usage"), StartLayout.moveBy(entries, "widget:$car", 1).map { it.key })
        assertFalse(StartLayout.canMove(entries, "usage", 1))
    }

    @Test
    fun `double tap switches a widget's width`() {
        val entries = listOf(widget(car), widget(clima))
        assertEquals(listOf(widget(car), widget(clima, wide = true)), StartLayout.toggleWide(entries, clima))
    }

    @Test
    fun `half widgets pair up in rows, everything else has a row of its own`() {
        val cards = listOf(widget(car), widget(clima), widget(nas), StartEntry.Info, widget("a/b", wide = true), widget("c/d"), widget("e/f"), StartEntry.Usage)
        val rows = StartLayout.rows(cards)
        assertEquals(
            listOf(
                listOf(widget(car), widget(clima)),
                listOf(widget(nas)),
                listOf(StartEntry.Info),
                listOf(widget("a/b", wide = true)),
                listOf(widget("c/d"), widget("e/f")),
                listOf(StartEntry.Usage),
            ),
            rows,
        )
        assertTrue(widget(car).isHalf)
        assertFalse(StartEntry.Info.isHalf)
    }

    @Test
    fun `ordered sorts by the given keys and keeps the rest in place after them`() {
        val items = listOf("a", "b", "c", "d")
        assertEquals(listOf("c", "a", "b", "d"), StartLayout.ordered(items, listOf("c", "a", "x")) { it })
        assertEquals(items, StartLayout.ordered(items, emptyList()) { it })
    }
}
