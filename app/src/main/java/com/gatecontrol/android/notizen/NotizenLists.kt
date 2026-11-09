package com.gatecontrol.android.notizen

/**
 * What the side menu, the menu of a list (a long press) and a shared list's filter show (pure, unit-tested). Every
 * ability of plugin 1.2.0 is asked of `me.features` ([NFeature]); against plugin 1.1.x what needs it is left out.
 */
object NotizenLists {

    // ── the side menu ───────────────────────────────────────

    /** A row of „Meine Listen“: a group (a folder that opens and closes) or a list (in a group: [depth] 1). */
    sealed interface Row {
        val key: String
        data class Group(val group: NGroup, val open: Int, val expanded: Boolean, val lists: Int) : Row {
            override val key: String get() = "g${group.id}"
        }
        data class ListRow(val list: NList, val depth: Int) : Row {
            override val key: String get() = "l${list.id}"
        }
    }

    /** The standard list „Aufgaben“ of the area (its own row among the fixed views). */
    fun defaultList(nav: NNav?): NList? = nav?.lists?.firstOrNull { it.isDefault && it.area == nav.area } ?: nav?.lists?.firstOrNull { it.isDefault }

    /** The open tasks of a group: the server's count (1.2.0) or the sum of its lists. */
    fun groupOpen(group: NGroup, lists: List<NList>): Int = group.open ?: lists.filter { it.groupId == group.id }.sumOf { it.open }

    /**
     * „Meine Listen“ without the standard list: the lists without a group and the groups in their order (by position,
     * then name), each group followed by its lists when it is not [collapsed]. A list whose group is not known stands
     * without one.
     */
    fun rows(nav: NNav?, collapsed: Set<Long>): List<Row> {
        if (nav == null) return emptyList()
        val lists = nav.lists.filterNot { it.isDefault }
        val groups = nav.groups.sortedWith(compareBy({ it.position }, { it.name.lowercase() }))
        val known = groups.map { it.id }.toSet()
        val order = compareBy<NList>({ it.position }, { it.name.lowercase() })
        val out = mutableListOf<Row>()
        lists.filter { it.groupId == null || it.groupId !in known }.sortedWith(order).forEach { out += Row.ListRow(it, 0) }
        groups.forEach { g ->
            val inside = lists.filter { it.groupId == g.id }.sortedWith(order)
            val open = g.id !in collapsed
            out += Row.Group(g, groupOpen(g, nav.lists), open, inside.size)
            if (open) inside.forEach { out += Row.ListRow(it, 1) }
        }
        return out
    }

    /** How a list is shared, for its mark in the menu. */
    enum class Shared { NO, BY_ME, WITH_ME }

    fun shared(list: NList): Shared = when {
        !list.shared -> Shared.NO
        list.isOwner -> Shared.BY_ME
        else -> Shared.WITH_ME
    }

    /** The owner and the members of a list (the people a task may be assigned to), the owner first. */
    fun people(list: NList): List<NPerson> {
        val out = mutableListOf<NPerson>()
        list.owner?.let { out += it }
        list.members.forEach { m -> if (out.none { it.id != null && it.id == m.id }) out += m }
        return out
    }

    /** The names of the others (not me) who see a list: „Anna, Tom“. */
    fun others(list: NList, meId: Long?): List<String> = people(list).filter { it.id == null || it.id != meId }.mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }

    // ── the menu of a list ──────────────────────────────────

    enum class Action { EDIT, SHARE, MOVE, DAYS, DELETE, MYDAY, LEAVE }

    /**
     * The menu of a list (a long press): the owner edits, shares (when the admin lets people share), moves it to a
     * group, sets the list day (1.2.0) and deletes it (not the standard list); a member moves it to a group of their
     * own, switches „In ‚Mein Tag‘ zeigen“ for themselves (1.2.0) and leaves it.
     */
    fun actions(list: NList, me: NMe?): List<Action> {
        // made offline and not on the server yet: nothing to change there
        if (me == null || me.readOnly || list.pending || list.id <= 0) return emptyList()
        val days = me.has(NFeature.LIST_DAYS)
        return if (list.isOwner) {
            buildList {
                add(Action.EDIT)
                if (me.sharing != "off" && !list.isDefault) add(Action.SHARE)
                if (!list.isDefault) add(Action.MOVE)
                if (days) add(Action.DAYS)
                if (!list.isDefault) add(Action.DELETE)
            }
        } else {
            buildList {
                add(Action.MOVE)
                if (days) add(Action.MYDAY)
                add(Action.LEAVE)
            }
        }
    }

    /** May I add tasks to this list here (the field at its end)? */
    fun canAdd(list: NList?, me: NMe?): Boolean = me != null && !me.readOnly && (list == null || list.canEdit)

    /** May I tick this task off? In a list I may only view: only when it is assigned to me (the server says so). */
    fun canTick(task: NTask, list: NList?, meId: Long?): Boolean = when {
        !task.canComplete -> false
        list == null || list.canEdit -> true
        else -> task.assignee?.id != null && task.assignee.id == meId
    }

    // ── the filter of a shared list ─────────────────────────

    sealed interface Filter {
        data object All : Filter
        data object Mine : Filter
        data object Nobody : Filter
        data class Person(val id: Long, val name: String) : Filter
    }

    fun matches(task: NTask, filter: Filter, meId: Long?): Boolean = when (filter) {
        Filter.All -> true
        Filter.Mine -> task.assignee?.id != null && task.assignee.id == meId
        Filter.Nobody -> task.assignee?.id == null
        is Filter.Person -> task.assignee?.id == filter.id
    }

    /**
     * The chips above a shared list with their counts of open tasks: „Alle“, „Mir zugewiesen“, each other person who
     * has tasks, „Niemandem“. Not shared: none.
     */
    fun filters(list: NList?, tasks: List<NTask>, meId: Long?): List<Pair<Filter, Int>> {
        if (list == null || !list.shared) return emptyList()
        val open = tasks.filter { !it.done }
        val count = { f: Filter -> open.count { matches(it, f, meId) } }
        val persons = people(list).filter { it.id != null && it.id != meId }.mapNotNull { p -> p.id?.let { Filter.Person(it, p.name ?: "") } }
        return buildList {
            add(Filter.All to open.size)
            add(Filter.Mine to count(Filter.Mine))
            persons.forEach { p -> count(p).takeIf { it > 0 }?.let { add(p to it) } }
            add(Filter.Nobody to count(Filter.Nobody))
        }
    }

    /** The initials of a name for its little round picture: „Anna Weber“ → „A“. */
    fun initial(name: String?): String = name?.trim()?.firstOrNull()?.uppercase() ?: "?"

    // ── notebooks ───────────────────────────────────────────

    enum class NotebookAction { EDIT, SHARE, DELETE, LEAVE }

    /** The menu of a notebook: the owner edits, shares and deletes it (not „Notizen“, not one of a plugin); a member leaves it. */
    fun notebookActions(nb: NNotebook, me: NMe?): List<NotebookAction> {
        if (me == null || me.readOnly || nb.pending || nb.id <= 0) return emptyList()
        if (nb.bound != null) return if (nb.isOwner) listOf(NotebookAction.EDIT) else emptyList()
        return if (nb.isOwner) {
            buildList {
                add(NotebookAction.EDIT)
                if (me.sharing != "off" && !nb.isDefault) add(NotebookAction.SHARE)
                if (!nb.isDefault) add(NotebookAction.DELETE)
            }
        } else {
            listOf(NotebookAction.LEAVE)
        }
    }

    /** A Tresor is deleted only after its name was typed (the server does not know it); case and spaces do not matter. */
    fun nameConfirmed(typed: String, name: String?): Boolean =
        name != null && name.isNotBlank() && typed.trim().equals(name.trim(), ignoreCase = true)

    // ── sharing ─────────────────────────────────────────────

    /**
     * The people one may still add to a list or notebook: not me, not the owner, not already in, matching [query]
     * (any part of the name, no case).
     */
    fun candidates(people: List<NPersonPick>, members: List<NPerson>, ownerId: Long?, query: String): List<NPersonPick> {
        val inside = members.mapNotNull { it.id }.toSet() + listOfNotNull(ownerId)
        val q = query.trim().lowercase()
        return people.filter { !it.me && it.id !in inside && (q.isEmpty() || it.name.lowercase().contains(q)) }
    }

    /** The members after a change, as `PUT …/members` wants them (id → role; 1.1 knows only `write`). */
    fun membersAfter(members: List<NPerson>, add: Long? = null, remove: Long? = null, role: Pair<Long, String>? = null): List<Pair<Long, String>> {
        val out = members.mapNotNull { m -> m.id?.let { it to (m.role ?: "write") } }.filter { it.first != remove }.toMutableList()
        if (add != null && out.none { it.first == add }) out += add to "write"
        if (role != null) out.replaceAll { if (it.first == role.first) it.first to role.second else it }
        return out
    }

    // ── list days ───────────────────────────────────────────

    /** The default time of the morning message. */
    const val REMIND_DEFAULT = "09:00"
}
