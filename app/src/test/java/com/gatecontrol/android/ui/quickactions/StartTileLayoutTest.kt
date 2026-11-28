package com.gatecontrol.android.ui.quickactions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StartTileLayoutTest {

    // dp at density 1, gap 10, a tile at most 84 wide
    private fun columns(width: Int) = StartTileGrid.columns(width, 10, 84)

    @Test
    fun `phones keep five in a row, narrow ones with smaller tiles`() {
        assertEquals(5, columns(380)) // 412 dp less 2 × 16
        assertEquals(5, columns(328)) // 360 dp
        assertEquals(5, columns(288)) // 320 dp
        assertEquals(68, StartTileGrid.tileWidth(380, 10, 5))
        assertEquals(57, StartTileGrid.tileWidth(328, 10, 5))
    }

    @Test
    fun `the row never comes out wider than the space`() {
        for (width in 200..1400) {
            val n = columns(width)
            val tile = StartTileGrid.tileWidth(width, 10, n)
            assertTrue(n >= StartTileGrid.MIN_PER_ROW)
            assertTrue(tile * n + 10 * (n - 1) <= width, "width $width")
        }
    }

    @Test
    fun `wide screens fit more, no tile wider than the most`() {
        assertEquals(9, columns(768))
        for (width in 470..1400) {
            assertTrue(StartTileGrid.tileWidth(width, 10, columns(width)) <= 84, "width $width")
        }
    }

    @Test
    fun `a line may end at a space or after a hyphen, not inside a word`() {
        assertFalse(TileLabelFit.breaksInsideWord("Haustür öffnen", "Haustür ".length))
        assertFalse(TileLabelFit.breaksInsideWord("Haustür öffnen", "Haustür".length))
        assertFalse(TileLabelFit.breaksInsideWord("Auf-/Zu", "Auf-".length))
        assertTrue(TileLabelFit.breaksInsideWord("Aufgesperrt", "Aufge".length))
        assertTrue(TileLabelFit.splitsWord("Aufgesperrt", listOf(5)))
        assertFalse(TileLabelFit.splitsWord("Aufgesperrt", emptyList()))
    }

    @Test
    fun `sizes step down in halves to the smallest`() {
        assertEquals(listOf(11f, 10.5f, 10f, 9.5f, 9f), TileLabelFit.sizes(11f, 9f))
        assertEquals(listOf(9f), TileLabelFit.sizes(8f, 9f))
    }
}
