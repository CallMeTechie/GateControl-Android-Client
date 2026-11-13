package com.gatecontrol.android.ui.start

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** When a page's prompt may come by itself ([PageArrivalEffect]): on the page the pager rests on, in front, once. */
class PageArrivalTest {

    @Test
    fun `only the page the pager rests on is settled`() {
        assertTrue(startPageSettled(index = 2, settledPage = 2))
        assertFalse(startPageSettled(index = 2, settledPage = 1))
        assertFalse(startPageSettled(index = 0, settledPage = 1))
    }

    @Test
    fun `a page composed beside the shown one does not run`() {
        assertEquals(PageArrival(done = false, run = false), PageArrival.next(done = false, settled = false, resumed = true, enabled = true))
    }

    @Test
    fun `the page in front runs once`() {
        val first = PageArrival.next(done = false, settled = true, resumed = true, enabled = true)
        assertEquals(PageArrival(done = true, run = true), first)
        assertEquals(PageArrival(done = true, run = false), PageArrival.next(first.done, settled = true, resumed = true, enabled = true))
    }

    @Test
    fun `not in the background, and coming back does not ask again`() {
        assertEquals(PageArrival(done = false, run = false), PageArrival.next(done = false, settled = true, resumed = false, enabled = true))
        // asked, cancelled, app left and opened again
        assertEquals(PageArrival(done = true, run = false), PageArrival.next(done = true, settled = true, resumed = false, enabled = true))
        assertEquals(PageArrival(done = true, run = false), PageArrival.next(done = true, settled = true, resumed = true, enabled = true))
    }

    @Test
    fun `leaving the page arms it for the next visit`() {
        val away = PageArrival.next(done = true, settled = false, resumed = true, enabled = true)
        assertEquals(PageArrival(done = false, run = false), away)
        assertTrue(PageArrival.next(away.done, settled = true, resumed = true, enabled = true).run)
    }

    @Test
    fun `nothing to ask for stays quiet and is not counted as asked`() {
        assertEquals(PageArrival(done = false, run = false), PageArrival.next(done = false, settled = true, resumed = true, enabled = false))
    }
}
