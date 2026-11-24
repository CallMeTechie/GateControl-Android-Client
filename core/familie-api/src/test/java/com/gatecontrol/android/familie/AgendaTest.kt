package com.gatecontrol.android.familie

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgendaTest {

    private fun own(id: String, title: String, date: String, time: String? = null) =
        FamilieEvent(id, title, date, time, null, time == null, "none", emptyList(), FamilieEvent.KIND_EVENT, null, "")

    @Test
    fun `familie, alltag and waste merge by day, all-day first, within the range`() {
        val alltag = AlltagPayload(
            events = listOf(AlltagEvent("a1", "Zahnarzt", "2026-11-25", "10:15", null, false, "Familie", null)),
            waste = listOf(AlltagWaste("2026-11-25", listOf("Gelbe Tonne"), null), AlltagWaste("2026-11-25", listOf("Gelbe Tonne"), null)),
        )
        val entries = Agenda.merge(
            listOf(own("1", "Elternabend", "2026-11-24", "18:30"), own("2", "Später", "2026-12-24"), own("3", "Schwimmkurs", "2026-11-24", "16:00")),
            alltag,
            "2026-11-24",
            "2026-11-30",
        )
        val titles = entries.map {
            when (it) {
                is AgendaEntry.Own -> it.event.title
                is AgendaEntry.Alltag -> it.event.title
                is AgendaEntry.Waste -> it.waste.kinds.single()
            }
        }
        assertEquals(listOf("Schwimmkurs", "Elternabend", "Gelbe Tonne", "Zahnarzt"), titles)
    }
}
