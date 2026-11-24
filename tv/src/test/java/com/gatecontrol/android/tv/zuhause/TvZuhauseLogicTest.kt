package com.gatecontrol.android.tv.zuhause

import com.gatecontrol.android.familie.AgendaEntry
import com.gatecontrol.android.familie.AlltagEvent
import com.gatecontrol.android.familie.AlltagPayload
import com.gatecontrol.android.familie.AlltagWaste
import com.gatecontrol.android.familie.FamilieEvent
import com.gatecontrol.android.familie.shopping.ShoppingItem
import com.gatecontrol.android.familie.shopping.ShoppingList
import com.gatecontrol.android.familie.shopping.ShoppingSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class TvZuhauseLogicTest {

    private val today = LocalDate.parse("2026-11-24")

    private fun own(id: String, title: String, date: String, time: String? = null) =
        FamilieEvent(id, title, date, time, null, time == null, "none", emptyList(), FamilieEvent.KIND_EVENT, null, "")

    @Test
    fun `waste shows each bin once with its next collection, in order`() {
        val waste = listOf(
            AlltagWaste("2026-11-20", listOf("Biotonne"), null),
            AlltagWaste("2026-11-25", listOf("Gelbe Tonne"), "#E6C229"),
            AlltagWaste("2026-11-27", listOf("Restmüll", "Biotonne"), "#123456"),
            AlltagWaste("2026-12-04", listOf("Biotonne"), null),
            AlltagWaste("2026-12-11", listOf("restmüll"), null),
            AlltagWaste("2026-12-01", listOf("Papier"), null),
        )
        val bins = TvZuhauseLogic.waste(waste, today, hour = 9)
        assertEquals(listOf("Gelbe Tonne", "Biotonne", "Restmüll", "Papier"), bins.map { it.kind })
        assertEquals(LocalDate.parse("2026-11-27"), bins[1].date)
        assertEquals(7, bins[1].everyDays)
        assertEquals(14, bins[2].everyDays)
        assertNull(bins[3].everyDays)
        // the plugin's colour only when the date has this one bin
        assertEquals("#E6C229", bins[0].color)
        assertEquals("#7A5230", bins[1].color)
        assertEquals("#6B6F76", bins[2].color)
        assertEquals("#2F6FD0", bins[3].color)
    }

    @Test
    fun `a bin is highlighted from the evening before and on its day`() {
        val tomorrow = today.plusDays(1)
        assertFalse(TvZuhauseLogic.soon(tomorrow, today, hour = 16))
        assertTrue(TvZuhauseLogic.soon(tomorrow, today, hour = 17))
        assertTrue(TvZuhauseLogic.soon(today, today, hour = 7))
        assertFalse(TvZuhauseLogic.soon(today.plusDays(2), today, hour = 22))
        assertTrue(TvZuhauseLogic.waste(listOf(AlltagWaste("2026-11-25", listOf("Gelbe Tonne"), null)), today, 20).single().soon)
    }

    @Test
    fun `the calendar groups seven days of familie and alltag`() {
        val alltag = AlltagPayload(
            events = listOf(AlltagEvent("a1", "Zahnarzt", "2026-11-25", "10:15", null, false, "Familie", null)),
            waste = listOf(AlltagWaste("2026-11-25", listOf("Gelbe Tonne"), null), AlltagWaste("2026-12-02", listOf("Papier"), null)),
        )
        val days = TvZuhauseLogic.calendar(
            listOf(own("1", "Elternabend", "2026-11-24", "18:30"), own("2", "Schwimmkurs", "2026-11-24", "16:00"), own("3", "Später", "2026-12-01")),
            alltag,
            today,
        )
        assertEquals(listOf("2026-11-24", "2026-11-25"), days.map { it.date.toString() })
        assertEquals(2, days[0].entries.size)
        assertTrue(days[1].entries.first() is AgendaEntry.Waste)
        assertEquals(LocalDate.parse("2026-11-30"), TvZuhauseLogic.calendarEnd(today))
    }

    @Test
    fun `the next date skips what is over and the waste`() {
        val days = TvZuhauseLogic.calendar(
            listOf(own("1", "Schwimmkurs", "2026-11-24", "16:00"), own("2", "Elternabend", "2026-11-24", "18:30"), own("3", "Ausflug", "2026-11-26")),
            AlltagPayload(emptyList(), listOf(AlltagWaste("2026-11-25", listOf("Gelbe Tonne"), null))),
            today,
        )
        fun title(e: AgendaEntry?) = (e as? AgendaEntry.Own)?.event?.title
        assertEquals("Schwimmkurs", title(TvZuhauseLogic.nextEntry(days, today, LocalTime.of(9, 0))))
        assertEquals("Elternabend", title(TvZuhauseLogic.nextEntry(days, today, LocalTime.of(16, 1))))
        assertEquals("Ausflug", title(TvZuhauseLogic.nextEntry(days, today, LocalTime.of(20, 0))))
        assertNull(TvZuhauseLogic.nextEntry(emptyList(), today, LocalTime.NOON))
    }

    @Test
    fun `the list shown is the chosen one, else the fullest`() {
        val view = ShoppingSnapshot(
            lists = listOf(ShoppingList("l1", "Drogerie", sort = 1), ShoppingList("l2", "Wocheneinkauf", sort = 2), ShoppingList("l3", "Alt", deleted = true)),
            items = listOf(
                ShoppingItem("i1", "l2", "Milch"),
                ShoppingItem("i2", "l2", "Brot", checked = true, checkedBy = "Papa"),
                ShoppingItem("i3", "l2", "Äpfel"),
                ShoppingItem("i4", "l1", "Seife"),
            ),
        )
        assertEquals(listOf("l1", "l2"), TvZuhauseLogic.lists(view).map { it.id })
        assertEquals("l2", TvZuhauseLogic.chosenList(view, null)?.id)
        assertEquals("l1", TvZuhauseLogic.chosenList(view, "l1")?.id)
        assertEquals("l2", TvZuhauseLogic.chosenList(view, "l3")?.id)
        assertEquals(listOf("Milch", "Äpfel", "Brot"), TvZuhauseLogic.items(view, "l2").map { it.text })
        assertEquals(2, TvZuhauseLogic.open(view, "l2"))
        // nothing open anywhere: the first list
        assertEquals("l1", TvZuhauseLogic.chosenList(view.copy(items = emptyList()), null)?.id)
        assertNull(TvZuhauseLogic.chosenList(ShoppingSnapshot(), null))
    }

    @Test
    fun `typed or spoken entries become one clean line`() {
        assertEquals("Butter und Eier", TvZuhauseLogic.entryText("  Butter   und\nEier "))
        assertNull(TvZuhauseLogic.entryText("  \n "))
        assertEquals(120, TvZuhauseLogic.entryText("x".repeat(300))?.length)
        assertEquals("Papa", TvZuhauseLogic.checkedByName(ShoppingItem("i", "l", "Brot", checked = true, checkedBy = "Papa")))
        assertNull(TvZuhauseLogic.checkedByName(ShoppingItem("i", "l", "Brot", checked = true, checkedBy = "5")))
    }
}
