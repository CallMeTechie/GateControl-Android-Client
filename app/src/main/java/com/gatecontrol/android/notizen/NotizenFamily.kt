package com.gatecontrol.android.notizen

/**
 * „Wer ist dran?“, stars and the family's list (plugin 1.6.0, the apps' contract of stage 4) — pure, unit-tested. Every
 * ability is asked of `me.features` ([NFeature]) and `me.familyTasks`, never of a version: with an older plugin
 * nothing of this shows and nothing of it is sent.
 */
object NotizenTurns {
    /** The choice „Eine Person“ of „Wer ist dran?“ (`turn: null`). */
    const val ONE = "one"

    /** What a task's row says about whose turn it is. */
    sealed interface Line {
        /** „Reihum · du bist dran“. */
        data object RotateMe : Line
        /** „Reihum · Lena ist dran“ ([name] null: nobody known). */
        data class Rotate(val name: String?) : Line
        /** „Wer zuerst“. */
        data object First : Line
    }

    /** The line of an open task with a turn; null: an ordinary task, or done. */
    fun line(task: NTask, meId: Long?): Line? = when {
        task.done -> null
        task.turn == TurnWords.FIRST -> Line.First
        task.turn == TurnWords.ROTATE -> if (task.assignee?.id != null && task.assignee.id == meId) Line.RotateMe else Line.Rotate(task.assignee?.name)
        else -> null
    }

    /** `rotate` | `first` | [ONE]. */
    fun mode(turn: String?): String = NotizenJson.turn(turn) ?: ONE

    /** Whom a task of [list] may go to in turns: the people of a shared list (the owner first). */
    fun candidates(list: NList?): List<NPerson> = if (list == null || !list.shared) emptyList() else NotizenLists.people(list).filter { it.id != null }

    /** „Wer ist dran?“ is offered for a task of [list]: the feature, and at least two people to choose from (or a turn to take away). */
    fun offered(me: NMe?, list: NList?, task: NTask? = null): Boolean =
        me?.taskTurns == true && (task?.turn != null || candidates(list).size >= 2)

    /** A task's stars show: a task of the family's list while the Familie plugin counts stars. */
    fun starsShown(me: NMe?, task: NTask): Boolean = me?.taskStars == true && (task.family || (task.listId != null && task.listId == me.familyList))

    /** I may give stars to a task of [listId]: a parent, in the family's list, while stars are counted. */
    fun mayGiveStars(me: NMe?, listId: Long?): Boolean = me != null && me.taskStars && me.familyParent && listId != null && listId == me.familyList

    /** The most stars a task may have (`me.familyTasks.starsMax`). */
    fun starsMax(me: NMe?): Int = me?.familyTasks?.starsMax?.coerceIn(1, 10) ?: 10

    /**
     * Whom a parent may tick [task] off for (`complete { userId }`): the people of „wer zuerst“, else — nobody is
     * assigned — the people of the family's list. Empty: not offered (not a parent, not the family's list, done,
     * assigned to someone: the server then ticks for that person by itself).
     */
    fun tickFor(me: NMe?, task: NTask, list: NList?): List<NPerson> {
        if (me == null || me.readOnly || !me.familyParent || task.done || !task.canComplete || task.parentId != null) return emptyList()
        if (!(task.family || task.listId == me.familyList)) return emptyList()
        return when {
            task.turn == TurnWords.FIRST -> task.people
            task.assignee == null -> candidates(list)
            else -> emptyList()
        }.filter { it.id != null }
    }

    /** „Noch nicht fertig“: a parent takes back what was ticked off for someone else in the family's list. */
    fun mayReject(me: NMe?, task: NTask): Boolean =
        me != null && !me.readOnly && me.familyParent && task.done && (task.family || task.listId == me.familyList) && task.doneBy?.id != null && task.doneBy.id != me.userId

    /**
     * „Annehmen“ / „Ablehnen“ of a task's place show for me. With `placeMine` the server says so ([NPlace.canConsent],
     * while the consent is pending or declined); without it the app guesses as before: someone else set the place and
     * it still waits.
     */
    fun canConsent(place: NPlace, me: NMe): Boolean {
        if (me.readOnly || place.consent == NPlace.ACCEPTED) return false
        val says = place.canConsent
        return if (me.placeMine && says != null) says else place.consent == NPlace.PENDING && place.by != me.userId
    }

    /** The server says who may agree: others then only see the state („wartet auf Zustimmung“). */
    fun consentKnown(place: NPlace, me: NMe): Boolean = me.placeMine && place.canConsent != null

    // ── the quick entry ─────────────────────────────────────

    /** What the line says about several people and stars, as the task will be made. [turn] null: „Eine Person“. */
    data class Quick(val title: String, val people: List<QuickParse.Ref>, val turn: String?, val stars: Int?)

    /**
     * The line after [QuickParse]: read again by [TurnWords] — only with `taskTurns` (else null: all stays as it was).
     * Stars are read only where I may give them ([mayGiveStars] for the list the task goes to).
     */
    fun read(parsed: QuickParse.Parsed?, people: List<QuickParse.Ref>, me: NMe?, listId: Long?): TurnWords.Result? {
        if (parsed == null || me?.taskTurns != true) return null
        return TurnWords.apply(parsed, people, mayGiveStars(me, listId))
    }

    /**
     * [read] with what the person changed: [pick] the choice of „Wer ist dran?“ ([ONE], `rotate`, `first`; null: what
     * the line says), [off] the chips taken away (`person`, `stars`). A turn needs two people. [members]: who belongs
     * to the list the task goes to (null: not known) — as on the server a further person who does not belong to it
     * falls away silently (the first one stays: the assignee, as before).
     */
    fun effective(read: TurnWords.Result?, pick: String?, off: Set<String>, members: Set<Long>? = null): Quick? {
        if (read == null) return null
        val people = if ("person" in off) emptyList() else read.people.filterIndexed { i, p -> i == 0 || members == null || p.id in members }
        val turn = (if (pick == ONE) null else NotizenJson.turn(pick) ?: read.turn)?.takeIf { people.size >= 2 }
        return Quick(read.title, people, turn, read.stars?.takeIf { "stars" !in off })
    }

    /** The people of [list] a new task may go to in turns (null: the list is not known; not shared: nobody). */
    fun members(list: NList?): Set<Long>? = list?.let { l -> candidates(l).mapNotNull { it.id }.toSet() }

    /** Whom the new task is assigned to: the first person — nobody with „wer zuerst“. */
    fun assignee(q: Quick): Long? = if (q.turn == TurnWords.FIRST) null else q.people.firstOrNull()?.id
}

/**
 * Where the family's tasks open (contract § 9, pure): in the hub's family list once Familie says its tasks live in
 * „Notizen & Aufgaben“ (`home: notizen`) and this app's hub holds that list for me; else in the Familie plugin, as
 * before. The pattern of [ShoppingHome].
 */
object TasksHome {
    const val NOTIZEN = "notizen"

    sealed interface Target
    /** The page of the Familie plugin (its own tasks, or its copy). */
    data object Familie : Target
    /** The hub's family list [listId]. */
    data class Hub(val listId: Long) : Target

    /** Familie's answer says the tasks moved. */
    fun moved(home: String?): Boolean = home == NOTIZEN

    /** [home]: `home.home` of Familie's `GET tasks` / `GET overview` (null: an older plugin). [me]: the hub's `GET me` (null: no hub). */
    fun target(home: String?, me: NMe?): Target {
        if (!moved(home)) return Familie
        return me?.familyList?.let { Hub(it) } ?: Familie
    }

    /** The destination of the hub's family list for the app's navigation ([NotizenPush.decode]). */
    fun dest(t: Hub): String = NotizenPush.encode(NotizenPush.ListPage(t.listId))

    /**
     * After the hub was asked which places to watch (`POST places/watch`) the Familie plugin is asked again (`GET
     * places`): only then it reports the reminders that moved as not active for this phone. Once per start of the app
     * and once per move ([movedAt]); [askedFor]: what it was last asked for (null: not yet in this process).
     */
    fun askFamilyAgain(me: NMe?, askedFor: String?): String? {
        val list = me?.familyList ?: return null
        val key = "$list:" + me.familyTasks?.moved?.at.orEmpty()
        return key.takeIf { it != askedFor }
    }
}
