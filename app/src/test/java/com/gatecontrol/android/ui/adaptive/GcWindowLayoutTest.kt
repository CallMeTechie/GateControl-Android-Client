package com.gatecontrol.android.ui.adaptive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GcWindowLayoutTest {

    @Test
    fun `phone in portrait - bottom bar, one column`() {
        val l = GcWindowLayout.compute(widthDp = 411, heightDp = 891)
        assertFalse(l.useRail)
        assertFalse(l.twoPane)
    }

    @Test
    fun `phone in landscape - rail and two panes`() {
        val l = GcWindowLayout.compute(widthDp = 891, heightDp = 411)
        assertTrue(l.useRail)
        assertTrue(l.twoPane)
    }

    @Test
    fun `small tablet in portrait - rail, still one column`() {
        val l = GcWindowLayout.compute(widthDp = 600, heightDp = 960)
        assertTrue(l.useRail)
        assertFalse(l.twoPane)
    }

    @Test
    fun `tablet and unfolded foldable - two panes`() {
        assertTrue(GcWindowLayout.compute(widthDp = 800, heightDp = 1280).twoPane)
        assertTrue(GcWindowLayout.compute(widthDp = 841, heightDp = 701).twoPane)
    }

    @Test
    fun `a flat fold does not split anything`() {
        val flat = HingeBounds(isVertical = true, isSeparating = false, left = 1104f, top = 0f, right = 1104f, bottom = 1840f)
        val l = GcWindowLayout.compute(widthDp = 841, heightDp = 701, hinges = listOf(flat))
        assertNull(l.verticalHinge)
        assertNull(l.horizontalHinge)
    }

    @Test
    fun `book posture - panes split at the hinge even below the two-pane width`() {
        val hinge = HingeBounds(isVertical = true, isSeparating = true, left = 1000f, top = 0f, right = 1040f, bottom = 1800f)
        val l = GcWindowLayout.compute(widthDp = 700, heightDp = 860, hinges = listOf(hinge))
        assertTrue(l.twoPane)
        assertEquals(HingeSplit(1000f, 1040f), l.verticalHinge)
    }

    @Test
    fun `tabletop posture - horizontal hinge`() {
        val hinge = HingeBounds(isVertical = false, isSeparating = true, left = 0f, top = 1100f, right = 2200f, bottom = 1100f)
        val l = GcWindowLayout.compute(widthDp = 841, heightDp = 701, hinges = listOf(hinge))
        assertEquals(HingeSplit(1100f, 1100f), l.horizontalHinge)
        assertNull(l.verticalHinge)
    }

    @Test
    fun `first pane takes the preferred width, at most 45 percent`() {
        assertEquals(380f, GcWindowLayout.firstPaneWidthPx(availablePx = 1200f, paneStartPx = 0f, hinge = null, preferredPx = 380f))
        assertEquals(360f, GcWindowLayout.firstPaneWidthPx(availablePx = 800f, paneStartPx = 0f, hinge = null, preferredPx = 380f))
    }

    @Test
    fun `first pane ends at the hinge, measured from the pane's own start`() {
        val hinge = HingeSplit(1104f, 1104f)
        // A navigation rail of 231 px sits left of the panes.
        assertEquals(873f, GcWindowLayout.firstPaneWidthPx(availablePx = 1977f, paneStartPx = 231f, hinge = hinge, preferredPx = 997f))
    }

    @Test
    fun `a hinge outside the panes is ignored`() {
        val hinge = HingeSplit(100f, 120f)
        assertEquals(380f, GcWindowLayout.firstPaneWidthPx(availablePx = 1200f, paneStartPx = 231f, hinge = hinge, preferredPx = 380f))
    }

    @Test
    fun `book posture on a narrow foldable - bottom bar so the list keeps the left half`() {
        // 673 dp wide at 2.625 px/dp, hinge in the middle (884 px = 337 dp).
        val hinge = HingeBounds(isVertical = true, isSeparating = true, left = 884f, top = 0f, right = 884f, bottom = 2208f)
        val l = GcWindowLayout.compute(widthDp = 673, heightDp = 841, hinges = listOf(hinge), density = 2.625f)
        assertFalse(l.useRail)
        assertTrue(l.twoPane)
        assertEquals(HingeSplit(884f, 884f), l.verticalHinge)
    }

    @Test
    fun `book posture with a wide left half keeps the rail`() {
        val hinge = HingeBounds(isVertical = true, isSeparating = true, left = 1100f, top = 0f, right = 1100f, bottom = 1840f)
        val l = GcWindowLayout.compute(widthDp = 840, heightDp = 700, hinges = listOf(hinge), density = 2.625f)
        assertTrue(l.useRail) // 419 dp left of the hinge
    }

    @Test
    fun `a hinge too far left for a list is not split at`() {
        val hinge = HingeBounds(isVertical = true, isSeparating = true, left = 500f, top = 0f, right = 500f, bottom = 1800f)
        val l = GcWindowLayout.compute(widthDp = 640, heightDp = 860, hinges = listOf(hinge), density = 2.625f)
        assertNull(l.verticalHinge)
        assertFalse(l.twoPane)
    }
}
