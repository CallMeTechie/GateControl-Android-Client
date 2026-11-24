package com.gatecontrol.android.tv.notizen

import com.gatecontrol.android.notizen.NArea
import com.gatecontrol.android.notizen.NEntry
import com.gatecontrol.android.notizen.NItem
import com.gatecontrol.android.notizen.NList
import com.gatecontrol.android.notizen.NNav
import com.gatecontrol.android.notizen.NNote
import com.gatecontrol.android.notizen.NNoteSummary
import com.gatecontrol.android.notizen.NNotebook
import com.gatecontrol.android.notizen.NSubtask
import com.gatecontrol.android.notizen.NTask
import com.gatecontrol.android.notizen.NTaskDetail
import com.gatecontrol.android.notizen.NView
import java.time.LocalDate

/** What the left column of Notizen chose: a smart list, a list or a notebook. */
sealed interface TvNotesPlace {
    /** The key the TV keeps the loaded answer under. */
    val key: String

    data object MyDay : TvNotesPlace {
        override val key = TvNotizenLogic.MYDAY
    }

    /** „Demnächst“: the tasks with a day (the plugin's view `planned`). */
    data object Planned : TvNotesPlace {
        override val key = TvNotizenLogic.PLANNED
    }

    data class List(val id: Long) : TvNotesPlace {
        override val key get() = "list:$id"
    }

    data class Notebook(val id: Long) : TvNotesPlace {
        override val key get() = "notebook:$id"
    }
}

/** The tasks of today for the start row: open in Mein Tag, of which overdue. */
data class TvTasksToday(val open: Int, val overdue: Int)

/** The left column: the area „Privat“ only, no Tresor notebooks (decision 14, stage 3 text 2). */
data class TvNotesNav(val myDay: Int, val planned: Int, val lists: kotlin.collections.List<NList>, val notebooks: kotlin.collections.List<NNotebook>)

/**
 * The rules of the page „Notizen“ (mockup „2 · Notizen und Aufgaben“), free of Android: only the area „Privat“, never a
 * Tresor (end-to-end encrypted notebooks and their notes), no places (the stick has no GPS); the tasks with the open
 * ones first, the counts of the start row, the day of a new task. Pure, unit-tested.
 */
object TvNotizenLogic {
    const val MYDAY = "myday"
    const val PLANNED = "planned"

    /** The only area the TV shows. */
    const val AREA = NArea.PRIVATE

    /** How long a new task's line may be (the plugin's `text`). */
    const val TEXT_MAX = 500

    /** The plugin's name of a view for [place]; null for a notebook (its notes are no view). */
    fun viewName(place: TvNotesPlace): String? = when (place) {
        TvNotesPlace.MyDay -> MYDAY
        TvNotesPlace.Planned -> PLANNED
        is TvNotesPlace.List -> "list:${place.id}"
        is TvNotesPlace.Notebook -> null
    }

    fun nav(nav: NNav): TvNotesNav = TvNotesNav(
        myDay = nav.smart[MYDAY] ?: 0,
        planned = nav.smart[PLANNED] ?: 0,
        lists = nav.lists.filter { it.area == AREA }.sortedBy { it.position },
        notebooks = nav.notebooks.filter { it.area == AREA && it.vault == null && !it.name.isNullOrBlank() }.sortedBy { it.position },
    )

    /** [place] is still there (a list or notebook may go away meanwhile); else Mein Tag. */
    fun valid(place: TvNotesPlace, nav: TvNotesNav?): TvNotesPlace = when (place) {
        is TvNotesPlace.List -> if (nav == null || nav.lists.any { it.id == place.id }) place else TvNotesPlace.MyDay
        is TvNotesPlace.Notebook -> if (nav == null || nav.notebooks.any { it.id == place.id }) place else TvNotesPlace.MyDay
        else -> place
    }

    /**
     * The rows of a view as one list: every entry once (a task can be in two sections), the sub tasks left out (they
     * show in the details), the open ones in the server's order, then the done ones.
     */
    fun entries(view: NView?): kotlin.collections.List<NEntry> {
        if (view == null) return emptyList()
        val seen = HashSet<String>()
        val all = view.sections.flatMap { it.items }.filter { e -> (e !is NTask || e.parentId == null) && seen.add(e.uid) }
        return all.filter { !it.done } + all.filter { it.done }
    }

    /** „3 heute · 1 überfällig“: the open rows of Mein Tag; null while Mein Tag was not loaded. */
    fun today(view: NView?): TvTasksToday? {
        if (view == null) return null
        val open = entries(view).filter { !it.done }
        return TvTasksToday(open.size, open.count { overdue(it) })
    }

    fun overdue(e: NEntry): Boolean = when (e) {
        is NTask -> e.overdue
        is NItem -> e.overdue
    }

    /** The notes of a notebook the TV may show (never one encrypted in a Tresor). */
    fun notes(notes: kotlin.collections.List<NNoteSummary>): kotlin.collections.List<NNoteSummary> =
        notes.filter { !it.encrypted && it.titleCipher == null }.sortedWith(compareByDescending<NNoteSummary> { it.pinned }.thenByDescending { it.updatedAt })

    /** A note to read: null when it is a Tresor's or has no text the TV can show. */
    fun readable(note: NNote?): NNote? = note?.takeIf { !it.summary.encrypted && !it.notebookVault && it.cipher == null }

    /** The task the TV may tick: an own task where I may, or an item of another plugin that says so. */
    fun canTick(e: NEntry): Boolean = when (e) {
        is NTask -> e.canComplete && e.id > 0
        is NItem -> e.canComplete
    }

    /** „Auf morgen verschieben“: own open tasks I may change. */
    fun canMove(e: NEntry): Boolean = e is NTask && !e.done && e.canEdit && e.id > 0

    /** [entry] ticked (or opened again) in the loaded [view] at once — the server's answer follows. */
    fun withDone(view: NView, uid: String, done: Boolean): NView = view.copy(
        sections = view.sections.map { s ->
            s.copy(items = s.items.map { e ->
                if (e.uid != uid) e else when (e) {
                    is NTask -> e.copy(done = done)
                    is NItem -> e.copy(done = done)
                }
            })
        },
    )

    /** A sub task ticked in the loaded details. */
    fun withSub(detail: NTaskDetail, id: Long, done: Boolean): NTaskDetail =
        detail.copy(subtasks = detail.subtasks.map { if (it.id == id) it.copy(done = done) else it })

    fun progress(subtasks: kotlin.collections.List<NSubtask>): Pair<Int, Int> = subtasks.count { it.done } to subtasks.size

    /** A new task from Mein Tag: in Mein Tag when the quick entry gives it no day or today's (as on the phone). */
    fun inMyDay(due: String?, today: LocalDate): Boolean = due == null || due == today.toString()

    /** The line of „Aufgabe hinzufügen“: trimmed, one line, null when empty. */
    fun line(text: String): String? = text.replace(Regex("\\s+"), " ").trim().take(TEXT_MAX).takeIf { it.isNotEmpty() }

    /** `YYYY-MM-DD` → the date, null for anything else. */
    fun date(v: String?): LocalDate? = v?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
