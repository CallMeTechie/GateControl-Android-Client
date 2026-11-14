package com.gatecontrol.android.notizen

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject

/**
 * What the app keeps to send once it is online again (pure, unit-tested), in the order it was done:
 * - ticks of a task or of an item of another plugin (`complete { done }` sets a state — sent twice, it does the same);
 * - with plugin 1.1.0 (`offlineEdits` in `GET me`): new tasks, sub tasks, lists, notebooks and notes ([CREATE], with a
 *   `clientId` the server knows again when it comes twice, and a negative id on the phone until the server's id is
 *   known: [State.ids]), changes ([PATCH] — tasks with `base`, the values the phone saw; notes with `rev`) and deletes.
 *
 * A change the server refuses with `409` stays as a [Op.conflict] until the person picks „Meine behalten“ or „Die andere
 * laden“. Without `offlineEdits` (an older plugin would make a create sent twice twice) only ticks wait.
 */
object NotizenOutbox {
    data class Op(
        val kind: String,
        /** The object: a server id, or a negative id of something made offline. */
        val id: Long,
        val done: Boolean,
        val at: Long,
        /** [CREATE], [PATCH], [DELETE]: [TASK], [LIST], [NOTEBOOK] or [NOTE]. */
        val what: String? = null,
        /** The request's body ([CREATE] with `clientId`; [PATCH] with `base` or `rev`). */
        val body: JsonObject? = null,
        val clientId: String? = null,
        /** Refused with 409 in the background: waits for the person. */
        val conflict: Boolean = false,
        /** Tells this op apart while it is being sent. */
        val uid: String = "",
    )

    /** The outbox as it is kept: what waits, local id → server id, the next local id. */
    data class State(val ops: List<Op> = emptyList(), val ids: Map<Long, Long> = emptyMap(), val next: Long = -1)

    /** A tick of a task ([Op.kind]) — and a task ([Op.what]). */
    const val TASK = "task"
    const val ITEM = "item"
    const val CREATE = "create"
    const val PATCH = "patch"
    const val DELETE = "delete"
    /** Plugin 1.3.0: a `POST tasks/<id>/<what>` that may be sent again ([Op.what]: [SNOOZE] with its tag, [MOVE]). */
    const val ACTION = "action"
    const val SNOOZE = "snooze"
    const val MOVE = "move"
    const val LIST = "list"
    const val NOTEBOOK = "notebook"
    const val NOTE = "note"

    const val MAX = 500
    private const val MAX_IDS = 500
    private val KINDS = setOf(TASK, ITEM, CREATE, PATCH, DELETE, ACTION)
    private val ACTIONS = setOf(SNOOZE, MOVE)
    private val WHATS = setOf(TASK, LIST, NOTEBOOK, NOTE)
    private val CLIENT_ID = Regex("^[A-Za-z0-9_-]{8,64}$")

    /** Fields of a create that name another object (negative: made offline as well). */
    private val REFS = mapOf("listId" to LIST, "parentId" to TASK, "notebookId" to NOTEBOOK)

    fun isTick(op: Op) = op.kind == TASK || op.kind == ITEM

    /** The object an op is about: (`task`, 5), (`item`, 5), (`note`, -2) … */
    fun objectOf(op: Op): Pair<String, Long> = (if (isTick(op)) op.kind else op.what.orEmpty()) to op.id

    // ── kept on the phone ───────────────────────────────────

    fun fromJson(e: JsonElement): Op? {
        val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val kind = o.str("kind")?.takeIf { it in KINDS } ?: return null
        val id = o.get("id")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() } ?: return null
        val at = o.get("at")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() } ?: 0
        if (kind == TASK || kind == ITEM) {
            if (id <= 0) return null
            val done = o.get("done")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() } ?: return null
            return Op(kind, id, done, at, uid = o.str("uid").orEmpty())
        }
        if (id == 0L) return null
        if (kind == ACTION) {
            val what = o.str("what")?.takeIf { it in ACTIONS } ?: return null
            val body = o.get("body")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            return Op(kind, id, false, at, what, body, uid = o.str("uid").orEmpty())
        }
        val what = o.str("what")?.takeIf { it in WHATS } ?: return null
        val body = o.get("body")?.takeIf { it.isJsonObject }?.asJsonObject
        if (kind != DELETE && body == null) return null
        val clientId = o.str("clientId")?.takeIf { CLIENT_ID.matches(it) }
        if (kind == CREATE && (clientId == null || id >= 0)) return null
        return Op(kind, id, false, at, what, body, clientId, o.get("conflict")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false, o.str("uid").orEmpty())
    }

    fun toJson(op: Op) = JsonObject().apply {
        addProperty("kind", op.kind)
        addProperty("id", op.id)
        addProperty("done", op.done)
        addProperty("at", op.at)
        op.what?.let { addProperty("what", it) }
        op.body?.let { add("body", it) }
        op.clientId?.let { addProperty("clientId", it) }
        if (op.conflict) addProperty("conflict", true)
        if (op.uid.isNotEmpty()) addProperty("uid", op.uid)
    }

    fun stateFromJson(o: JsonObject): State {
        val ops = o.get("ops")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { fromJson(it) }.orEmpty()
        val ids = o.get("ids")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.mapNotNull { (k, v) ->
            val local = k.toLongOrNull()?.takeIf { it < 0 } ?: return@mapNotNull null
            val server = runCatching { v.asLong }.getOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            local to server
        }?.toMap().orEmpty()
        val lowest = (ops.map { it.id } + ids.keys).filter { it < 0 }.minOrNull() ?: 0
        val next = o.get("next")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }?.takeIf { it < 0 } ?: -1
        return State(ops, ids, minOf(next, lowest - 1))
    }

    fun stateToJson(s: State) = JsonObject().apply {
        add("ops", JsonArray().apply { s.ops.forEach { add(toJson(it)) } })
        add("ids", JsonObject().apply { s.ids.forEach { (k, v) -> addProperty(k.toString(), v) } })
        addProperty("next", s.next)
    }

    // ── adding ──────────────────────────────────────────────

    /**
     * [op] added. A tick replaces an earlier one of the same thing. A change of something made offline goes into its
     * create; a change of the same thing as a change still waiting joins it (the earlier `base` and `rev` stay: what
     * the phone saw first). A delete of something made offline takes it and everything made in it away (nothing to
     * send). [frozen] is the op being sent right now: it is not changed. At most [MAX] wait.
     */
    fun add(ops: List<Op>, op: Op, frozen: String? = null): List<Op> {
        val key = objectOf(op)
        val free = { o: Op -> frozen == null || o.uid != frozen }
        val out: List<Op> = when (op.kind) {
            TASK, ITEM -> ops.filterNot { isTick(it) && objectOf(it) == key } + op
            PATCH -> {
                val c = ops.indexOfFirst { it.kind == CREATE && objectOf(it) == key && free(it) }
                val p = ops.indexOfLast { it.kind == PATCH && objectOf(it) == key && !it.conflict && free(it) }
                when {
                    c >= 0 -> ops.toMutableList().also { it[c] = ops[c].copy(body = intoCreate(ops[c].body, op.body)) }
                    p >= 0 -> ops.toMutableList().also { it[p] = joined(ops[p], op) }
                    else -> ops + op
                }
            }
            DELETE -> {
                val made = ops.any { it.kind == CREATE && objectOf(it) == key && free(it) }
                val gone = dependents(ops, key)
                val rest = ops.filterNot { objectOf(it) in gone && free(it) }
                if (made) rest else rest + op
            }
            CREATE -> ops + op
            // a later „Verschieben“ or „Später“ of the same task takes the place of one still waiting
            ACTION -> ops.filterNot { it.kind == ACTION && objectOf(it) == key && free(it) } + op
            else -> ops
        }
        return out.takeLast(MAX)
    }

    /** [key] and what was made offline in it (sub tasks of a task, tasks of a list, notes of a notebook), over and over. */
    fun dependents(ops: List<Op>, key: Pair<String, Long>): Set<Pair<String, Long>> {
        val out = mutableSetOf(key)
        var grew = true
        while (grew) {
            grew = false
            for (o in ops) {
                if (o.kind != CREATE) continue
                val k = objectOf(o)
                if (k in out) continue
                if (refsOf(o).any { it in out }) { out.add(k); grew = true }
            }
        }
        return out
    }

    private fun refsOf(op: Op): List<Pair<String, Long>> = REFS.mapNotNull { (f, what) -> op.body?.longOf(f)?.let { what to it } }

    private fun intoCreate(create: JsonObject?, patch: JsonObject?): JsonObject {
        val b = create?.deepCopy() ?: JsonObject()
        patch?.entrySet()?.forEach { (k, v) -> if (k != "base" && k != "rev") b.add(k, v.deepCopy()) }
        return b
    }

    private fun joined(old: Op, new: Op): Op {
        val b = old.body?.deepCopy() ?: JsonObject()
        new.body?.entrySet()?.forEach { (k, v) -> if (k != "base" && k != "rev") b.add(k, v.deepCopy()) }
        val base = JsonObject()
        new.body?.get("base")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (k, v) -> base.add(k, v.deepCopy()) }
        old.body?.get("base")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (k, v) -> base.add(k, v.deepCopy()) }
        if (base.size() > 0) b.add("base", base) else b.remove("base")
        (old.body?.get("rev") ?: new.body?.get("rev"))?.let { b.add("rev", it.deepCopy()) }
        return old.copy(body = b)
    }

    // ── sending ─────────────────────────────────────────────

    /** The server id of [op]'s object; null while something made offline has none yet. */
    fun target(op: Op, ids: Map<Long, Long>): Long? = if (op.id > 0) op.id else ids[op.id]

    /** [op]'s body with ids of things made offline replaced by the server's; null while one is not known. */
    fun prepared(op: Op, ids: Map<Long, Long>): JsonObject? {
        val b = op.body?.deepCopy() ?: JsonObject()
        for (f in REFS.keys) {
            val v = b.longOf(f) ?: continue
            if (v < 0) b.addProperty(f, ids[v] ?: return null)
        }
        return b
    }

    /** A create came through: [local] is [server] from now on — in the map and in what still waits. */
    fun mapped(s: State, local: Long, server: Long): State {
        val ops = s.ops.map { o ->
            val body = o.body?.let { b ->
                if (REFS.keys.none { b.longOf(it) == local }) b else b.deepCopy().apply { REFS.keys.forEach { f -> if (longOf(f) == local) addProperty(f, server) } }
            }
            o.copy(id = if (o.id == local) server else o.id, body = body)
        }
        val ids = (s.ids + (local to server)).entries.toList().takeLast(MAX_IDS).associate { it.key to it.value }
        return s.copy(ops = ops, ids = ids)
    }

    /** What a refusal of the server means for [op]: a conflict waits for the person, anything else is given up. */
    fun conflictOf(op: Op, http: Int, code: String?): Boolean =
        op.kind == PATCH && http == 409 && (code == "conflict" || code == "stale_key")

    /** „Meine behalten“: the change without `base` and `rev` (the phone's version wins); [body] instead (a Tresor note sealed again). */
    fun keepMine(op: Op, body: JsonObject? = null): Op =
        op.copy(conflict = false, body = (body ?: op.body?.deepCopy() ?: JsonObject()).apply { remove("base"); remove("rev") })

    // ── what the person sees meanwhile ──────────────────────

    /** The state a waiting tick gives [uid] (`t:<id>` / `s:<id>`); null: none waits. */
    fun pending(ops: List<Op>, uid: String): Boolean? {
        val kind = if (uid.startsWith("t:")) TASK else if (uid.startsWith("s:")) ITEM else return null
        val id = uid.substring(2).toLongOrNull() ?: return null
        return ops.lastOrNull { it.kind == kind && it.id == id }?.done
    }

    /** A task made offline (a row; its sub tasks as [NSubtask]s of [detail]). */
    fun taskOf(op: Op): NTask? {
        if (op.kind != CREATE || op.what != TASK) return null
        val b = op.body ?: return null
        val title = b.strOf("title") ?: b.strOf("text") ?: return null
        val due = dueOf(b.get("due"))
        return NTask(
            id = op.id, listId = b.longOf("listId"), parentId = b.longOf("parentId"), title = title, hasNote = !b.strOf("note").isNullOrBlank(),
            important = b.boolOf("important") ?: false, done = false, doneAt = null, due = due, overdue = false, remindAt = remindOf(b.get("remindAt")),
            repeat = NRepeat.fromJson(b.get("repeat")?.takeIf { !it.isJsonNull }), repeatText = null, assignee = b.longOf("assigneeId")?.let { NPerson(it, null) },
            myDay = b.boolOf("myDay") ?: false, progress = null, attachments = 0, source = null, noteId = null, position = Long.MIN_VALUE, updatedAt = op.at,
            pending = true,
        )
    }

    /** [t] with a waiting change laid over. */
    fun applyTask(t: NTask, b: JsonObject?, conflict: Boolean = false): NTask {
        if (b == null) return t.copy(pending = true, conflict = t.conflict || conflict)
        var x = t
        b.strOf("title")?.let { x = x.copy(title = it) }
        if (b.has("note")) x = x.copy(hasNote = !b.strOf("note").isNullOrBlank())
        b.boolOf("important")?.let { x = x.copy(important = it) }
        if (b.has("due")) x = x.copy(due = dueOf(b.get("due")))
        if (b.has("remindAt")) x = x.copy(remindAt = remindOf(b.get("remindAt")))
        if (b.has("repeat")) x = x.copy(repeat = NRepeat.fromJson(b.get("repeat")?.takeIf { !it.isJsonNull }), repeatText = null)
        if (b.has("assigneeId")) x = x.copy(assignee = b.longOf("assigneeId")?.let { NPerson(it, null) })
        b.longOf("listId")?.let { x = x.copy(listId = it) }
        b.boolOf("myDay")?.let { x = x.copy(myDay = it) }
        return x.copy(pending = true, conflict = x.conflict || conflict)
    }

    private fun entryOver(e: NEntry, ops: List<Op>): NEntry = when (e) {
        is NItem -> pending(ops, e.uid)?.let { e.copy(done = it) } ?: e
        is NTask -> taskOver(e, ops)
    }

    private fun taskOver(t: NTask, ops: List<Op>): NTask {
        var x = t
        for (o in ops) {
            if (o.id != t.id) continue
            when {
                o.kind == TASK -> x = x.copy(done = o.done, pending = true)
                o.kind == PATCH && o.what == TASK -> x = applyTask(x, o.body, o.conflict)
            }
        }
        return x
    }

    private fun deletedOf(ops: List<Op>, what: String): Set<Long> = ops.filter { it.kind == DELETE && it.what == what }.map { it.id }.toSet()

    /**
     * A view as the person sees it: the waiting ticks, changes and deletes laid over the server's copy, the tasks made
     * offline in their list, in „Mein Tag“ (in it or due [today]) and in „Wichtig“.
     */
    fun overlay(view: NView, ops: List<Op>, today: String? = null): NView {
        if (ops.isEmpty()) return view
        val deleted = deletedOf(ops, TASK)
        val listId = if (view.view.startsWith("list:")) view.view.removePrefix("list:").toLongOrNull() else null
        val made = ops.mapNotNull { taskOf(it) }.filter { it.parentId == null && it.id !in deleted }
        val adds = made.filter { t ->
            when {
                listId != null -> t.listId == listId
                view.view == "myday" -> t.myDay || (today != null && t.due?.date == today)
                view.view == "important" -> t.important
                else -> false
            }
        }
        // „Heute“ / „Verschieben“ still waiting (plugin 1.3.0): the task is no longer overdue nor in the card of yesterday
        val moved = ops.filter { it.kind == ACTION && it.what == MOVE }.map { it.id }.toSet()
        var sections = view.sections.map { s ->
            s.copy(items = s.items.filterNot { it is NTask && (it.id in deleted || (s.id == "overdue" && it.id in moved)) }.map { entryOver(it, ops) })
        }
        val yesterday = view.yesterday?.let { y ->
            val left = y.items.filterNot { it.id in deleted || it.id in moved }.map { taskOver(it, ops) }.filterNot { it.done }
            y.copy(items = left, count = left.size)
        }
        if (adds.isNotEmpty()) {
            val into = when {
                listId != null -> "open"
                view.view == "myday" -> "today"
                else -> sections.firstOrNull()?.id ?: view.view
            }
            sections = if (sections.any { it.id == into }) {
                sections.map { s -> if (s.id == into) s.copy(items = adds + s.items.filterNot { e -> adds.any { it.uid == e.uid } }, count = s.count + adds.size) else s }
            } else {
                listOf(NSection(into, "", adds.size, adds, false)) + sections
            }
        }
        return view.copy(sections = sections, yesterday = yesterday)
    }

    /** A task's page as the person sees it: its waiting changes and ticks, sub tasks made, ticked or deleted offline. */
    fun overlayDetail(d: NTaskDetail, ops: List<Op>): NTaskDetail {
        if (ops.isEmpty()) return d
        val deleted = deletedOf(ops, TASK)
        val note = ops.filter { it.kind == PATCH && it.what == TASK && it.id == d.task.id && it.body?.has("note") == true }.lastOrNull()?.body?.strOf("note")
        val subs = d.subtasks.filterNot { it.id in deleted }.map { s ->
            val tick = ops.lastOrNull { it.kind == TASK && it.id == s.id }
            val title = ops.lastOrNull { it.kind == PATCH && it.what == TASK && it.id == s.id }?.body?.strOf("title")
            if (tick == null && title == null) s else s.copy(done = tick?.done ?: s.done, title = title ?: s.title, pending = true)
        } + madeSubtasks(d.task.id, ops, deleted)
        return d.copy(task = taskOver(d.task, ops), note = note ?: d.note, subtasks = subs)
    }

    private fun madeSubtasks(parent: Long, ops: List<Op>, deleted: Set<Long>): List<NSubtask> =
        ops.mapNotNull { taskOf(it) }.filter { it.parentId == parent && it.id !in deleted }.map { t ->
            NSubtask(t.id, t.title, ops.lastOrNull { it.kind == TASK && it.id == t.id }?.done ?: false, pending = true)
        }

    /** The page of a task made offline (null: it is not waiting any more). */
    fun localDetail(id: Long, ops: List<Op>): NTaskDetail? {
        val op = ops.firstOrNull { it.kind == CREATE && it.what == TASK && it.id == id } ?: return null
        val t = taskOf(op) ?: return null
        val list = t.listId?.let { l -> ops.firstOrNull { it.kind == CREATE && it.what == LIST && it.id == l }?.body?.strOf("name") }
        val d = NTaskDetail(t, op.body?.strOf("note"), madeSubtasks(id, ops, deletedOf(ops, TASK)), list, op.body?.strOf("area"), emptyList(), null)
        return d.copy(task = taskOver(t, ops.filter { it.kind == TASK }))
    }

    /** A list made offline. */
    fun localList(op: Op, ops: List<Op>): NList? {
        if (op.kind != CREATE || op.what != LIST) return null
        val b = op.body ?: return null
        val open = ops.mapNotNull { taskOf(it) }.count { it.listId == op.id && it.parentId == null }
        return NList(op.id, b.strOf("name") ?: return null, NArea.of(b.strOf("area")), NotizenJson.color(b.strOf("color")), false, null, "owner", null, Long.MAX_VALUE, false, emptyList(), open, null, pending = true)
    }

    private fun notebookOf(op: Op): NNotebook? {
        if (op.kind != CREATE || op.what != NOTEBOOK) return null
        val b = op.body ?: return null
        return NNotebook(op.id, b.strOf("name") ?: return null, NArea.of(b.strOf("area")), NotizenJson.color(b.strOf("color")), false, "owner", null, emptyList(), false, null, null, 0, Long.MAX_VALUE, pending = true)
    }

    /** The menu with the lists and notebooks made offline. */
    fun overlayNav(nav: NNav, ops: List<Op>): NNav {
        val lists = ops.mapNotNull { localList(it, ops) }.filter { it.area == nav.area && nav.lists.none { l -> l.id == it.id } }
        val nbs = ops.mapNotNull { notebookOf(it) }.filter { it.area == nav.area && nav.notebooks.none { n -> n.id == it.id } }
        if (lists.isEmpty() && nbs.isEmpty()) return nav
        return nav.copy(lists = nav.lists + lists, notebooks = nav.notebooks + nbs)
    }

    /** The page of a list made offline (`list:<negative id>`). */
    fun localView(name: String, ops: List<Op>): NView? {
        val id = name.removePrefix("list:").toLongOrNull()?.takeIf { name.startsWith("list:") && it < 0 } ?: return null
        val list = ops.firstOrNull { it.kind == CREATE && it.what == LIST && it.id == id }?.let { localList(it, ops) } ?: return null
        val v = NView(name, list.name, listOf(NSection("open", "", 0, emptyList(), false), NSection("done", "", 0, emptyList(), true)), list)
        return overlay(v, ops)
    }

    private fun snippetOf(body: String?): String? = body?.replace("\n", " · ")?.take(240)?.takeIf { it.isNotBlank() }

    /** A note made offline. */
    fun noteOf(op: Op): NNote? {
        if (op.kind != CREATE || op.what != NOTE) return null
        val b = op.body ?: return null
        val tc = b.strOf("titleCipher")
        val s = NNoteSummary(
            id = op.id, notebookId = b.longOf("notebookId") ?: 0, title = if (tc != null) null else b.strOf("title").orEmpty(), snippet = if (tc != null) null else snippetOf(b.strOf("body")),
            titleCipher = tc, keyVersion = b.longOf("keyVersion")?.toInt(), encrypted = tc != null, pinned = b.boolOf("pinned") ?: false,
            color = NotizenJson.color(b.strOf("color")), due = null, expiresAt = null, check = null, images = 0, files = 0, tags = emptyList(), ext = null,
            createdAt = op.at, updatedAt = op.at, rev = 0, pending = true,
        )
        return NNote(s, if (tc != null) null else b.strOf("body").orEmpty(), b.strOf("cipher"), null, tc != null, emptyList(), emptyList())
    }

    /** [s] with a waiting change laid over. */
    fun applyNote(s: NNoteSummary, b: JsonObject?, conflict: Boolean): NNoteSummary {
        var x = s
        if (b != null) {
            b.strOf("title")?.let { x = x.copy(title = it) }
            if (b.has("body")) x = x.copy(snippet = snippetOf(b.strOf("body")))
            b.strOf("titleCipher")?.let { x = x.copy(titleCipher = it) }
            b.longOf("keyVersion")?.let { x = x.copy(keyVersion = it.toInt()) }
            b.boolOf("pinned")?.let { x = x.copy(pinned = it) }
            if (b.has("color")) x = x.copy(color = NotizenJson.color(b.strOf("color")))
            b.longOf("notebookId")?.let { x = x.copy(notebookId = it) }
        }
        return x.copy(pending = true, conflict = x.conflict || conflict, updatedAt = maxOf(x.updatedAt, s.updatedAt))
    }

    /** Notes of a page as the person sees them: changes and deletes laid over, the notes made offline first. */
    fun overlayNotes(notes: List<NNoteSummary>, ops: List<Op>, area: String?, notebookId: Long?, tag: String?, pinned: Boolean): List<NNoteSummary> {
        if (ops.isEmpty()) return notes
        val deleted = deletedOf(ops, NOTE)
        val shown = notes.filterNot { it.id in deleted }.map { n ->
            ops.filter { it.kind == PATCH && it.what == NOTE && it.id == n.id }.fold(n) { acc, o -> applyNote(acc, o.body, o.conflict) }
        }
        if (tag != null) return shown
        val made = ops.filter { it.kind == CREATE && it.what == NOTE && it.id !in deleted }.filter { o ->
            val b = o.body ?: return@filter false
            val nb = b.longOf("notebookId")
            (if (notebookId != null) nb == notebookId else (b.strOf("area") ?: area) == area) && (!pinned || b.boolOf("pinned") == true)
        }.mapNotNull { noteOf(it)?.summary }
        return made.reversed() + shown
    }

    /** A note's page as the person sees it: its waiting change laid over. */
    fun overlayNote(n: NNote, ops: List<Op>): NNote {
        val mine = ops.filter { it.kind == PATCH && it.what == NOTE && it.id == n.summary.id }
        if (mine.isEmpty()) return n
        var body = n.body
        var cipher = n.cipher
        for (o in mine) {
            o.body?.strOf("body")?.let { body = it }
            o.body?.strOf("cipher")?.let { cipher = it }
        }
        return n.copy(summary = mine.fold(n.summary) { acc, o -> applyNote(acc, o.body, o.conflict) }, body = body, cipher = cipher)
    }

    /** The change of [what] [id] the server refused, waiting for the person. */
    fun conflictFor(ops: List<Op>, what: String, id: Long): Op? = ops.firstOrNull { it.conflict && it.what == what && it.id == id }

    /** `base` of `PATCH tasks/<id>`: the values the phone saw of the [fields] it changes. */
    fun taskBase(d: NTaskDetail, fields: Collection<String>): JsonObject = JsonObject().apply {
        for (f in fields) when (f) {
            "title" -> addProperty(f, d.task.title)
            "note" -> addProperty(f, d.note.orEmpty())
            "important" -> addProperty(f, d.task.important)
            "due" -> add(f, NotizenJson.dueBody(d.task.due))
            "remindAt" -> d.task.remindAt?.let { addProperty(f, it) } ?: add(f, JsonNull.INSTANCE)
            "repeat" -> add(f, d.task.repeat?.let { NRepeat.toJson(it) } ?: JsonNull.INSTANCE)
            "assigneeId" -> d.task.assignee?.id?.let { addProperty(f, it) } ?: add(f, JsonNull.INSTANCE)
            "listId" -> d.task.listId?.takeIf { it > 0 }?.let { addProperty(f, it) }
            "myDay" -> addProperty(f, d.task.myDay)
        }
    }

    private fun dueOf(e: JsonElement?): NDue? = e?.takeIf { it.isJsonObject }?.asJsonObject?.let { o -> NotizenJson.date(o.strOf("date"))?.let { NDue(it, NotizenJson.time(o.strOf("time"))) } }

    /** A reminder as sent (`{ date, time }` or ISO) → the ISO form the rows read ([NotizenFormat.remindParts]). */
    private fun remindOf(e: JsonElement?): String? = when {
        e == null || e.isJsonNull -> null
        e.isJsonPrimitive -> runCatching { e.asString }.getOrNull()
        else -> dueOf(e)?.let { d -> d.time?.let { "${d.date}T$it:00" } }
    }

    private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.strOf(k: String): String? = str(k)
    private fun JsonObject.longOf(k: String): Long? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.let { runCatching { it.asLong }.getOrNull() }
    private fun JsonObject.boolOf(k: String): Boolean? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
}
