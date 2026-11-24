package com.gatecontrol.android.familie

/** One line of the dates agenda: a Familie date, an Alltag appointment or a waste collection. */
sealed interface AgendaEntry {
    val date: String
    val time: String?

    data class Own(val event: FamilieEvent) : AgendaEntry {
        override val date get() = event.date
        override val time get() = event.time.takeIf { !event.allDay }
    }

    data class Alltag(val event: AlltagEvent) : AgendaEntry {
        override val date get() = event.date
        override val time get() = event.time.takeIf { !event.allDay }
    }

    data class Waste(val waste: AlltagWaste) : AgendaEntry {
        override val date get() = waste.date
        override val time: String? get() = null
    }
}

object Agenda {
    /** Days the dates screen shows ahead (Alltag answers at most 62). */
    const val DAYS = 60

    /**
     * Merges the Familie dates and the Alltag calendar into one agenda:
     * by day, all-day entries first, then by time; [from]..[to] inclusive.
     */
    fun merge(own: List<FamilieEvent>, alltag: AlltagPayload?, from: String, to: String): List<AgendaEntry> {
        val entries = own.map { AgendaEntry.Own(it) } +
            alltag?.events.orEmpty().map { AgendaEntry.Alltag(it) } +
            alltag?.waste.orEmpty().map { AgendaEntry.Waste(it) }
        return entries
            .filter { it.date in from..to }
            .distinctBy {
                when (it) {
                    is AgendaEntry.Own -> "o:${it.event.id}:${it.date}"
                    is AgendaEntry.Alltag -> "a:${it.event.id}:${it.date}"
                    is AgendaEntry.Waste -> "w:${it.date}:${it.waste.kinds.joinToString()}"
                }
            }
            .sortedWith(compareBy<AgendaEntry>({ it.date }, { it.time ?: "" }))
    }
}
