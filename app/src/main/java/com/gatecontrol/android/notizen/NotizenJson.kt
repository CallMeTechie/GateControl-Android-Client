package com.gatecontrol.android.notizen

import com.gatecontrol.android.drive.DVaultMyDevice
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject

/**
 * The answers of the plugin's API (README "API") → the app's models, and the bodies the app sends. Pure and tolerant:
 * a bad field drops the item (not the list), unknown fields are ignored; never reflection.
 */
object NotizenJson {
    const val CODE_INVALID = "invalid_answer"
    private val CODE = Regex("^[a-z_]{1,40}$")
    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val TIME = Regex("^\\d{2}:\\d{2}$")
    private val PLUGIN = Regex("^gatecontrol-[a-z]{1,40}$")
    private val ROUTE = Regex("^[A-Za-z0-9/._:=-]{0,200}$")
    private val VAULT_ID = Regex("^[A-Za-z0-9_-]{22}$")
    private val B64 = Regex("^[A-Za-z0-9_-]+$")
    val COLORS = listOf("yellow", "green", "blue", "pink", "grey", "orange", "purple", "red", "teal")

    // ── helpers ─────────────────────────────────────────────

    internal fun JsonObject.el(k: String): JsonElement? = get(k)?.takeIf { !it.isJsonNull }
    internal fun JsonObject.obj(k: String): JsonObject? = el(k)?.takeIf { it.isJsonObject }?.asJsonObject
    internal fun JsonObject.arr(k: String): JsonArray? = el(k)?.takeIf { it.isJsonArray }?.asJsonArray
    internal fun JsonObject.str(k: String): String? = el(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    internal fun JsonObject.num(k: String): Double? = el(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
    internal fun JsonObject.long(k: String): Long? = num(k)?.takeIf { it % 1.0 == 0.0 }?.toLong()
    internal fun JsonObject.int(k: String): Int? = long(k)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    internal fun JsonObject.bool(k: String): Boolean? = el(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
    private fun JsonObject.id(k: String): Long? = long(k)?.takeIf { it > 0 }
    private fun JsonObject.text(k: String, max: Int): String? = str(k)?.take(max)
    private fun <T> JsonArray?.each(f: (JsonObject) -> T?): List<T> = this?.mapNotNull { e -> (e as? JsonObject)?.let { runCatching { f(it) }.getOrNull() } }.orEmpty()

    /** A 2xx answer that still says no (`{ ok: false, code }`). */
    fun isRefusal(o: JsonObject): Boolean = o.bool("ok") == false

    fun failureCode(http: Int, body: JsonObject?): String =
        (body?.str("code") ?: body?.obj("error")?.str("code"))?.takeIf { CODE.matches(it) } ?: "http_$http"

    /** The `reason` of a refusal (`login`, `read_only`, `off` …). */
    fun reason(body: JsonObject?): String? = body?.str("reason")?.take(40)

    fun date(v: String?): String? = v?.takeIf { DATE.matches(it) }
    fun time(v: String?): String? = v?.takeIf { TIME.matches(it) }

    private fun due(o: JsonObject?): NDue? = o?.let { d -> date(d.str("date"))?.let { NDue(it, time(d.str("time"))) } }
    private fun person(o: JsonObject?): NPerson? = o?.let { NPerson(it.id("id"), it.text("name", 120), role(it.str("role")), it.long("since")?.takeIf { s -> s > 0 }) }?.takeIf { it.id != null || it.name != null }

    /** A member's or my role: `owner`, `write`, `read` (else null). */
    fun role(v: String?): String? = v?.takeIf { it in ROLES }
    private val ROLES = setOf("owner", "write", "read")
    private val ICON = Regex("^[a-z]{1,20}$")

    /** ISO week days 1..7, each once, in order. */
    fun days(a: JsonArray?): List<Int> = a?.mapNotNull { e -> runCatching { e.asJsonPrimitive.takeIf { it.isNumber }?.asDouble }.getOrNull()?.takeIf { it % 1.0 == 0.0 }?.toInt()?.takeIf { it in 1..7 } }?.distinct()?.sorted().orEmpty()
    private fun progress(o: JsonObject?): NProgress? = o?.let { p ->
        val t = p.int("total") ?: return null
        NProgress((p.int("done") ?: 0).coerceIn(0, t.coerceAtLeast(0)), t.coerceAtLeast(0))
    }
    fun color(v: String?): String? = v?.takeIf { it in COLORS }
    fun vaultId(v: String?): String? = v?.takeIf { VAULT_ID.matches(it) }
    private fun cipher(v: String?, max: Int = 1_400_000): String? = v?.takeIf { it.length in 1..max && B64.matches(it) }

    // ── me, prefs ───────────────────────────────────────────

    fun prefs(o: JsonObject?): NPrefs {
        if (o == null) return NPrefs()
        val sources = o.obj("sources")?.entrySet()?.mapNotNull { (k, v) ->
            val s = (v as? JsonObject) ?: return@mapNotNull null
            if (!PLUGIN.matches(k)) return@mapNotNull null
            k to NSourcePrefs(s.bool("on"), s.str("kids")?.takeIf { it in setOf("all", "mine", "none") }, s.bool("shoppingInMyDay"), s.bool("flagged"), time(s.str("evening")))
        }?.toMap().orEmpty()
        val groups = o.obj("groups")?.entrySet()?.mapNotNull { (k, v) ->
            val s = runCatching { v.asString }.getOrNull()?.takeIf { it in setOf("private", "work", "hide") } ?: return@mapNotNull null
            k.take(140) to s
        }?.toMap().orEmpty()
        val times = o.obj("times")?.entrySet()?.mapNotNull { (k, v) -> runCatching { v.asString }.getOrNull()?.let(::time)?.let { k to it } }?.toMap().orEmpty()
        val myday = o.obj("myday")
        val notify = o.obj("notify")
        return NPrefs(
            NArea.of(o.str("area")),
            time(o.str("evening")) ?: "18:00",
            sources,
            groups,
            times,
            myday = NMyDayPrefs(myday?.bool("listdays") ?: true, myday?.bool("showDone") ?: true),
            startArea = o.str("startArea")?.takeIf { it in setOf("last", NArea.PRIVATE, NArea.WORK) } ?: "last",
            notify = NNotifyPrefs(notify?.bool("reminders") ?: true, notify?.bool("shared") ?: true, notify?.bool("listday") ?: true, notify?.bool("yesterday") ?: true),
            yesterdayAt = time(o.str("yesterdayAt")) ?: "07:30",
            calendarTasks = o.obj("calendar")?.bool("tasks") ?: true,
        )
    }

    fun me(o: JsonObject): NMe? {
        val user = o.obj("user") ?: return null
        val imports = o.obj("imports")
        return NMe(
            userId = user.id("id") ?: return null,
            userName = user.text("name", 120),
            lang = if (o.str("lang") == "en") "en" else "de",
            today = date(o.str("today")) ?: return null,
            time = time(o.str("time")),
            timezone = o.text("timezone", 80),
            features = o.arr("features")?.mapNotNull { runCatching { it.asString.take(40) }.getOrNull() }?.toSet().orEmpty(),
            prefs = prefs(o.obj("prefs")),
            sharing = o.str("sharing")?.takeIf { it in setOf("all", "groups", "off") } ?: "off",
            vaults = o.bool("vaults") ?: false,
            imports = NImports(imports?.bool("mstodo") ?: false, imports?.bool("mstodoSync") ?: false, imports?.bool("dsnote") ?: false),
            attachMB = o.int("attachMB")?.coerceIn(1, 100) ?: 25,
            readOnly = o.bool("readOnly") ?: false,
            listIcons = o.arr("listIcons")?.mapNotNull { runCatching { it.asString }.getOrNull()?.takeIf { ICON.matches(it) } }?.distinct()?.take(40).orEmpty(),
            calendar = o.obj("calendar")?.let { c -> NCalendarInfo(c.bool("available") ?: false, c.bool("enabled") ?: false, c.bool("mine") ?: false, c.text("name", 80)) },
        )
    }

    // ── reminders, „Gestern offen geblieben“, the calendar (plugin 1.3.0) ──

    private val TAG = Regex("^task:[1-9]\\d{0,14}:\\d{1,16}$")
    private val COLLAPSE = Regex("^task:[1-9]\\d{0,14}$")
    private val ACTION_ID = Regex("^[a-z0-9_]{1,32}$")

    /** `task:<id>:<atMs>` — the name of one reminder; anything else is not one. */
    fun reminderTag(v: String?): String? = v?.takeIf { TAG.matches(it) }

    private fun reminder(o: JsonObject?): NReminder? = o?.let { r ->
        NReminder(reminderTag(r.str("tag")) ?: return null, r.text("at", 40), r.long("atMs")?.takeIf { it > 0 } ?: return null, r.bool("mine") ?: false, r.bool("snoozed") ?: false)
    }

    private fun taskCalendar(o: JsonObject?): NTaskCalendar? = o?.let { c ->
        NTaskCalendar(c.str("state")?.takeIf { it == "synced" || it == "pending" } ?: return null, c.id("eventId"), c.id("calendarId"), c.bool("mine") ?: false)
    }

    /** `GET yesterday`, or `yesterday` of „Mein Tag“. */
    fun yesterday(o: JsonObject?): NYesterday? {
        if (o == null) return null
        val items = o.arr("items").each(::task)
        return NYesterday(
            day = date(o.str("day")),
            title = o.text("title", 120) ?: "",
            count = items.size,
            items = items,
            moveOptions = o.arr("moveOptions").each { m ->
                NMoveOption(m.str("to")?.takeIf { it == "tomorrow" || it == "weekend" } ?: return@each null, date(m.str("date")), m.text("label", 60) ?: return@each null)
            },
        )
    }

    fun yesterdayResult(o: JsonObject): NYesterdayResult = NYesterdayResult(
        changed = o.int("changed")?.coerceAtLeast(0) ?: 0,
        failed = o.arr("failed").each { it.id("id") },
        yesterday = yesterday(o.obj("yesterday")),
    )

    /** `POST tasks/<id>/snooze` → whether the reminder moved (false: pressed twice, done, another reminder by now). */
    fun snoozeAnswer(o: JsonObject): Boolean? = o.bool("snoozed")

    /** `GET reminders`: a reminder without a usable tag, time or task is dropped (not the list). */
    fun reminders(o: JsonObject): NReminderFeed? {
        val list = o.arr("reminders") ?: return null
        return NReminderFeed(
            nowMs = o.long("nowMs") ?: 0,
            enabled = o.bool("enabled") ?: true,
            reminders = list.each { r ->
                val taskId = r.id("taskId") ?: return@each null
                val atMs = r.long("atMs")?.takeIf { it > 0 } ?: return@each null
                val tag = reminderTag(r.str("tag"))?.takeIf { it == "task:$taskId:$atMs" } ?: return@each null
                NReminderEntry(
                    tag = tag,
                    taskId = taskId,
                    listId = r.id("listId"),
                    area = r.str("area")?.takeIf { it == NArea.PRIVATE || it == NArea.WORK },
                    title = r.text("title", 200) ?: return@each null,
                    body = r.text("body", 1000) ?: "",
                    atMs = atMs,
                    important = r.bool("important") ?: false,
                    collapseKey = r.str("collapseKey")?.takeIf { COLLAPSE.matches(it) } ?: "task:$taskId",
                    actions = r.arr("actions").each { a -> NReminderAction(a.str("id")?.takeIf { ACTION_ID.matches(it) } ?: return@each null, a.text("label", 40) ?: return@each null) }.take(3),
                )
            }.take(200),
        )
    }

    /** `POST tasks/<id>/snooze { until, tag? }`. */
    fun snoozeBody(until: String, tag: String?) = JsonObject().apply {
        addProperty("until", until)
        reminderTag(tag)?.let { addProperty("tag", it) }
    }

    /** `POST tasks/<id>/move { to }`: `today` | `tomorrow` | `weekend` | a date. */
    fun moveBody(to: String) = JsonObject().apply { addProperty("to", to) }

    /** A target of „Verschieben“ the server knows; null: not one. */
    fun moveTarget(to: String?): String? = to?.takeIf { it == "today" || it == "tomorrow" || it == "weekend" || DATE.matches(it) }

    /** `POST yesterday { action, to?, ids?, area? }`. */
    fun yesterdayBody(action: String, to: String? = null, ids: List<Long>? = null, area: String? = null) = JsonObject().apply {
        addProperty("action", action)
        to?.let { addProperty("to", it) }
        ids?.let { l -> add("ids", JsonArray().apply { l.forEach { add(it) } }) }
        area?.let { addProperty("area", it) }
    }

    // ── tasks and items ─────────────────────────────────────

    fun task(o: JsonObject?): NTask? {
        if (o == null) return null
        return NTask(
            id = o.id("id") ?: return null,
            listId = o.id("listId"),
            parentId = o.id("parentId"),
            title = o.text("title", 500) ?: return null,
            hasNote = o.bool("hasNote") ?: false,
            important = o.bool("important") ?: false,
            done = o.bool("done") ?: false,
            doneAt = o.text("doneAt", 40),
            due = due(o.obj("due")),
            overdue = o.bool("overdue") ?: false,
            remindAt = o.text("remindAt", 40),
            repeat = NRepeat.fromJson(o.el("repeat")),
            repeatText = o.text("repeatText", 120),
            assignee = person(o.obj("assignee")),
            myDay = o.bool("myDay") ?: false,
            progress = progress(o.obj("progress")),
            attachments = o.int("attachments")?.coerceAtLeast(0) ?: 0,
            source = o.obj("source")?.let { s -> s.str("plugin")?.takeIf { PLUGIN.matches(it) }?.let { p -> s.text("id", 200)?.let { NSourceRef(p, it) } } },
            noteId = o.id("noteId"),
            position = o.long("position") ?: 0,
            updatedAt = o.long("updatedAt") ?: 0,
            canComplete = o.bool("canComplete") ?: true,
            canEdit = o.bool("canEdit") ?: true,
            reminder = reminder(o.obj("reminder")),
            snoozed = o.bool("snoozed") ?: false,
            missedReminder = o.bool("missedReminder") ?: false,
            calendar = taskCalendar(o.obj("calendar")),
        )
    }

    fun item(o: JsonObject?): NItem? {
        if (o == null) return null
        val src = o.obj("source") ?: return null
        val plugin = src.str("plugin")?.takeIf { PLUGIN.matches(it) } ?: return null
        val open = src.obj("open")?.let { op ->
            val p = op.str("plugin")?.takeIf { PLUGIN.matches(it) } ?: return@let null
            val r = op.str("route")?.takeIf { ROUTE.matches(it) } ?: ""
            NOpen(p, r)
        }
        val group = o.obj("group")
        val meta = o.obj("meta")?.entrySet()?.mapNotNull { (k, v) ->
            if (!v.isJsonPrimitive || k.length > 40) return@mapNotNull null
            val p = v.asJsonPrimitive
            k to when {
                p.isBoolean -> p.asBoolean.toString()
                p.isNumber -> p.asNumber.let { n -> if (n.toDouble() % 1.0 == 0.0) n.toLong().toString() else n.toString() }
                else -> p.asString.take(120)
            }
        }?.toMap().orEmpty()
        return NItem(
            id = o.id("id") ?: return null,
            kind = o.text("kind", 32) ?: "task",
            title = o.text("title", 500) ?: return null,
            subtitle = o.text("subtitle", 200),
            plugin = plugin,
            sourceLabel = src.text("label", 80) ?: plugin.removePrefix("gatecontrol-"),
            open = open,
            due = due(o.obj("due")),
            show = due(o.obj("show")),
            allDay = o.bool("allDay") ?: false,
            lead = o.text("lead", 20),
            overdue = o.bool("overdue") ?: false,
            area = NArea.of(o.str("area")),
            important = o.bool("important") ?: false,
            done = o.bool("done") ?: false,
            doneAt = o.long("doneAt"),
            canComplete = o.bool("canComplete") ?: false,
            assignee = person(o.obj("assignee")),
            progress = progress(o.obj("progress")),
            groupId = group?.text("id", 80),
            groupLabel = group?.text("label", 80),
            meta = meta,
            updatedAt = o.long("updatedAt") ?: 0,
        )
    }

    /** A row of a section: `type` says which. */
    fun entry(o: JsonObject?): NEntry? = when (o?.str("type")) {
        "task" -> task(o)
        "source" -> item(o)
        else -> null
    }

    fun view(o: JsonObject): NView? {
        val sections = o.arr("sections") ?: return null
        return NView(
            view = o.text("view", 40) ?: "",
            title = o.text("title", 120) ?: "",
            sections = sections.each { s ->
                val items = s.arr("items").each(::entry)
                NSection(s.text("id", 100) ?: return@each null, s.text("title", 120) ?: "", s.int("count") ?: items.size, items, s.bool("collapsed") ?: false)
            },
            list = list(o.obj("list")),
            plugin = o.str("plugin")?.takeIf { PLUGIN.matches(it) },
            listdays = o.arr("listdays").each(::listDay),
            yesterday = yesterday(o.obj("yesterday")),
        )
    }

    /** A card of `listdays` (plugin 1.2.0). */
    fun listDay(o: JsonObject): NListDay? {
        val l = list(o.obj("list")) ?: return null
        val items = o.arr("items").each(::task).take(10)
        val open = o.int("open")?.coerceAtLeast(0) ?: l.open
        return NListDay(l, o.text("title", 120) ?: l.name, open, o.int("assignedToMe")?.coerceIn(0, open) ?: 0, items, o.int("more")?.coerceAtLeast(0) ?: (open - items.size).coerceAtLeast(0))
    }

    fun taskAnswer(o: JsonObject): NTask? = task(o.obj("task"))

    /** `POST tasks/<id>/complete` → the task and the next one of a repeat. */
    fun completeAnswer(o: JsonObject): Pair<NTask, NTask?>? = task(o.obj("task"))?.let { it to task(o.obj("next")) }

    fun attachment(o: JsonObject?): NAttachment? {
        if (o == null) return null
        return NAttachment(
            id = o.id("id") ?: return null,
            kind = o.text("kind", 10) ?: "note",
            refId = o.id("refId") ?: 0,
            name = o.text("name", 255),
            nameCipher = cipher(o.str("nameCipher"), 2000),
            mime = o.text("mime", 120),
            bytes = o.long("bytes") ?: 0,
            encrypted = o.bool("encrypted") ?: false,
            hasText = o.bool("hasText") ?: false,
            url = o.str("url")?.takeIf { it.startsWith("/plugin-media/") && it.length < 2000 && !it.contains("..") },
        )
    }

    fun taskDetail(o: JsonObject): NTaskDetail? {
        val t = o.obj("task") ?: return null
        val task = task(t) ?: return null
        val list = t.obj("list")
        return NTaskDetail(
            task = task,
            note = t.text("note", 20_000),
            subtasks = t.arr("subtasks").each { s -> NSubtask(s.id("id") ?: return@each null, s.text("title", 500) ?: "", s.bool("done") ?: false) },
            listName = list?.text("name", 120),
            listArea = list?.str("area"),
            attachmentList = t.arr("attachmentList").each(::attachment),
            sourceItem = item(t.obj("sourceItem")),
        )
    }

    fun itemAnswer(o: JsonObject): Pair<NItem, Long?>? = item(o.obj("item"))?.let { it to o.id("taskId") }

    // ── lists, notebooks, nav ───────────────────────────────

    fun list(o: JsonObject?): NList? {
        if (o == null) return null
        return NList(
            id = o.id("id") ?: return null,
            name = o.text("name", 120) ?: return null,
            area = NArea.of(o.str("area")),
            color = color(o.str("color")),
            isDefault = o.bool("isDefault") ?: false,
            owner = person(o.obj("owner")),
            role = o.text("role", 20),
            groupId = o.id("groupId"),
            position = o.long("position") ?: 0,
            shared = o.bool("shared") ?: false,
            members = o.arr("members").each { person(it) },
            open = o.int("open")?.coerceAtLeast(0) ?: 0,
            ext = o.obj("ext")?.text("kind", 20),
            icon = o.str("icon")?.takeIf { ICON.matches(it) },
            canEdit = o.bool("canEdit") ?: (o.str("role") != "read"),
            days = days(o.arr("days")),
            remind = time(o.str("remind")),
            mydayOff = o.bool("mydayOff") ?: false,
            dayToday = o.bool("dayToday") ?: false,
        )
    }

    fun group(o: JsonObject?): NGroup? = o?.let { g ->
        NGroup(g.id("id") ?: return null, g.text("name", 120) ?: return null, NArea.of(g.str("area")), g.long("position") ?: 0, g.int("open")?.coerceAtLeast(0))
    }

    fun groupAnswer(o: JsonObject): NGroup? = group(o.obj("group"))

    private fun ref(o: JsonObject?): NRef? = o?.let { r -> NRef(r.id("id") ?: return null, r.text("name", 120) ?: "") }

    /** `GET lists/<id>/usage`. */
    fun listUsage(o: JsonObject): NListUsage? {
        val open = o.int("open") ?: return null
        return NListUsage(open.coerceAtLeast(0), o.int("done")?.coerceAtLeast(0) ?: 0, o.arr("members").each { person(it) }, o.bool("isDefault") ?: false, o.bool("canDelete") ?: false, ref(o.obj("target")))
    }

    /** `GET notebooks/<id>/usage`. */
    fun notebookUsage(o: JsonObject): NNotebookUsage? {
        val notes = o.int("notes") ?: return null
        return NNotebookUsage(
            notes.coerceAtLeast(0),
            o.int("withImages")?.coerceAtLeast(0) ?: 0,
            o.int("withFiles")?.coerceAtLeast(0) ?: 0,
            o.arr("members").each { person(it) },
            o.bool("isDefault") ?: false,
            o.bool("canDelete") ?: false,
            o.bool("vault") ?: false,
            ref(o.obj("target")),
        )
    }

    /** The answer of `DELETE lists/<id>` / `DELETE notebooks/<id>` (1.1: `{ ok }` or `{ ok, left }`). */
    fun deleted(o: JsonObject): NDeleted = NDeleted(
        left = o.bool("left") ?: false,
        moved = o.int("moved")?.coerceAtLeast(0) ?: 0,
        removed = o.int("removed")?.coerceAtLeast(0) ?: 0,
        target = ref(o.obj("target")),
        restoreUntil = o.long("restoreUntil")?.takeIf { it > 0 },
    )

    fun notebook(o: JsonObject?): NNotebook? {
        if (o == null) return null
        val v = o.obj("vault")
        val b = o.obj("bound")
        return NNotebook(
            id = o.id("id") ?: return null,
            name = o.text("name", 120),
            area = NArea.of(o.str("area")),
            color = color(o.str("color")),
            isDefault = o.bool("isDefault") ?: false,
            role = o.text("role", 20),
            owner = person(o.obj("owner")),
            members = o.arr("members").each { person(it) },
            shared = o.bool("shared") ?: false,
            vault = v?.let { x -> vaultId(x.str("id"))?.let { NVaultRef(it, cipher(x.str("nameCipher"), 2000), x.int("keyVersion")?.coerceAtLeast(1) ?: 1, x.bool("needsRotation") ?: false) } },
            bound = b?.let { x -> x.str("plugin")?.let { p -> NBound(p, x.text("key", 32) ?: "", x.text("kind", 20), x.text("label", 80)) } },
            count = o.int("count")?.coerceAtLeast(0) ?: 0,
            position = o.long("position") ?: 0,
            canEdit = o.bool("canEdit") ?: (o.str("role") != "read"),
        )
    }

    private fun tags(a: JsonArray?): List<NTag> = a.each { t -> NTag(t.text("tag", 40) ?: return@each null, t.int("count") ?: 0) }

    fun nav(o: JsonObject): NNav? {
        val smart = o.obj("smart") ?: return null
        val notes = o.obj("notes")
        return NNav(
            area = NArea.of(o.str("area")),
            smart = listOf("myday", "important", "planned", "assigned").associateWith { smart.int(it)?.coerceAtLeast(0) ?: 0 },
            sources = o.arr("sources").each { s ->
                NNavSource(s.str("plugin")?.takeIf { PLUGIN.matches(it) } ?: return@each null, s.text("label", 80) ?: "", s.text("hint", 200), s.int("open") ?: 0)
            },
            lists = o.arr("lists").each(::list),
            groups = o.arr("groups").each(::group),
            notesTotal = notes?.int("total") ?: 0,
            notebooks = notes?.arr("notebooks").each(::notebook),
            tags = tags(notes?.arr("tags")),
        )
    }

    fun listAnswer(o: JsonObject): NList? = list(o.obj("list"))
    fun notebookAnswer(o: JsonObject): NNotebook? = notebook(o.obj("notebook"))
    fun notebooks(o: JsonObject): List<NNotebook>? = o.arr("notebooks")?.each(::notebook)

    // ── notes ───────────────────────────────────────────────

    fun noteSummary(o: JsonObject?): NNoteSummary? {
        if (o == null) return null
        return NNoteSummary(
            id = o.id("id") ?: return null,
            notebookId = o.id("notebookId") ?: return null,
            title = o.text("title", 300),
            snippet = o.text("snippet", 400),
            titleCipher = cipher(o.str("titleCipher"), 2000),
            keyVersion = o.int("keyVersion"),
            encrypted = o.bool("encrypted") ?: false,
            pinned = o.bool("pinned") ?: false,
            color = color(o.str("color")),
            due = date(o.str("due")),
            expiresAt = o.long("expiresAt"),
            check = progress(o.obj("check")),
            images = o.int("images") ?: 0,
            files = o.int("files") ?: 0,
            tags = o.arr("tags")?.mapNotNull { runCatching { it.asString.take(40) }.getOrNull() }.orEmpty(),
            ext = o.text("ext", 20),
            createdAt = o.long("createdAt") ?: 0,
            updatedAt = o.long("updatedAt") ?: 0,
            rev = o.long("rev") ?: 0,
        )
    }

    fun notesPage(o: JsonObject): NNotesPage? = o.arr("notes")?.let { NNotesPage(it.each(::noteSummary), o.text("next", 60)) }

    fun note(o: JsonObject): NNote? {
        val n = o.obj("note") ?: return null
        val s = noteSummary(n) ?: return null
        val nb = n.obj("notebook")
        return NNote(
            summary = s,
            body = n.str("body"),
            cipher = cipher(n.str("cipher")),
            notebookName = nb?.text("name", 120),
            notebookVault = nb?.bool("vault") ?: false,
            attachments = n.arr("attachments").each(::attachment),
            tasks = n.arr("tasks").each { t -> NNoteTask(t.id("id") ?: return@each null, t.text("title", 500) ?: "", due(t.obj("due")), t.bool("done") ?: false) },
        )
    }

    // ── sources, people, search ─────────────────────────────

    fun sources(o: JsonObject): List<NSourceStatus>? = o.arr("sources")?.each { s ->
        NSourceStatus(
            plugin = s.str("plugin")?.takeIf { PLUGIN.matches(it) } ?: return@each null,
            label = s.text("label", 80) ?: "",
            hint = s.text("hint", 200),
            enabled = s.bool("enabled") ?: false,
            available = s.bool("available") ?: false,
            lastSync = s.long("lastSync"),
            error = s.text("error", 80),
            groups = s.arr("groups").each { g -> NSrcGroup(g.text("id", 80) ?: return@each null, g.text("label", 80) ?: "", g.int("count") ?: 0) },
        )
    }

    fun people(o: JsonObject): List<NPersonPick>? = o.arr("people")?.each { p ->
        NPersonPick(p.id("id") ?: return@each null, p.text("name", 120) ?: return@each null, p.text("initials", 4), p.str("colorHex")?.takeIf { Regex("^#[0-9a-fA-F]{6}$").matches(it) }, p.bool("me") ?: false)
    }

    fun search(o: JsonObject): NSearchResult = NSearchResult(o.arr("tasks").each(::task), o.arr("notes").each(::noteSummary), o.arr("items").each(::item))

    // ── moving over ─────────────────────────────────────────

    fun msAccounts(o: JsonObject): NMsAccounts = NMsAccounts(
        o.bool("available") ?: false,
        o.text("reason", 40),
        o.arr("accounts").each { a -> NMsAccount(a.id("id") ?: return@each null, a.text("email", 200) ?: return@each null, a.text("label", 120), a.bool("tasks") ?: false) },
    )

    fun msConsent(o: JsonObject): NMsConsent = NMsConsent(
        deviceId = o.text("deviceId", 64),
        userCode = o.text("userCode", 40),
        verificationUri = o.str("verificationUri")?.takeIf { it.startsWith("https://") && it.length < 300 },
        expiresIn = o.int("expiresIn") ?: 900,
        interval = o.int("interval")?.coerceIn(1, 60) ?: 5,
        status = o.text("status", 20),
    )

    fun msPreview(o: JsonObject): NMsPreview? {
        val lists = o.arr("lists") ?: return null
        val t = o.obj("totals")
        return NMsPreview(
            lists.each { l -> NMsList(l.text("id", 300) ?: return@each null, l.text("name", 200) ?: "To Do", l.int("open") ?: 0, l.int("done") ?: 0, l.int("attachments") ?: 0, NArea.of(l.str("area")), l.bool("include") ?: true) },
            t?.int("lists") ?: 0, t?.int("open") ?: 0, t?.int("attachments") ?: 0,
        )
    }

    fun msLinks(o: JsonObject): List<NMsLink> = o.arr("links").each { l -> NMsLink(l.id("id") ?: return@each null, l.text("listName", 120) ?: "", l.bool("twoWay") ?: false, l.text("error", 80)) }

    fun job(o: JsonObject?): NJob? {
        val j = o?.obj("job") ?: return null
        return NJob(
            j.id("id") ?: return null,
            j.text("kind", 20) ?: "",
            j.text("state", 20) ?: "running",
            j.obj("stats")?.entrySet()?.mapNotNull { (k, v) -> runCatching { k to v.asInt }.getOrNull() }?.toMap().orEmpty(),
            j.text("error", 80),
        )
    }

    // ── Tresor ──────────────────────────────────────────────

    fun vault(o: JsonObject?): NVault? {
        if (o == null) return null
        return NVault(
            id = vaultId(o.str("id")) ?: return null,
            notebookId = o.id("notebookId") ?: return null,
            area = NArea.of(o.str("area")),
            nameCipher = cipher(o.str("nameCipher"), 2000),
            keyVersion = o.int("keyVersion")?.takeIf { it > 0 } ?: return null,
            recoveryPub = o.str("recoveryPub")?.takeIf { it.length == 43 && B64.matches(it) },
            needsRotation = o.bool("needsRotation") ?: false,
            myDevices = o.arr("myDevices").each { d -> DVaultMyDevice(vaultId(d.str("deviceId")) ?: return@each null, d.text("state", 20) ?: "pending", d.text("name", 80)) },
            pending = o.int("pending")?.coerceAtLeast(0) ?: 0,
        )
    }

    fun vaultAnswer(o: JsonObject): NVault? = vault(o.obj("vault"))

    // ── bodies ──────────────────────────────────────────────

    private fun JsonObject.put(k: String, v: Any?) {
        when (v) {
            null -> add(k, JsonNull.INSTANCE)
            is String -> addProperty(k, v)
            is Number -> addProperty(k, v)
            is Boolean -> addProperty(k, v)
            is JsonElement -> add(k, v)
            else -> addProperty(k, v.toString())
        }
    }

    fun dueBody(d: NDue?): JsonElement = d?.let { JsonObject().apply { addProperty("date", it.date); it.time?.let { t -> addProperty("time", t) } } } ?: JsonNull.INSTANCE

    /** `POST tasks` of the quick entry: the line as the person wrote it, the server reads it the same way. */
    fun quickTaskBody(text: String, area: String, listId: Long?, myDay: Boolean, createList: Boolean) = JsonObject().apply {
        addProperty("text", text.take(1000))
        addProperty("area", area)
        listId?.let { addProperty("listId", it) }
        if (myDay) addProperty("myDay", true)
        if (createList) addProperty("createList", true)
    }

    /** `POST tasks` with a plain title (a sub task with [parentId], a line of a share). */
    fun taskBody(title: String, area: String? = null, listId: Long? = null, parentId: Long? = null, note: String? = null) = JsonObject().apply {
        addProperty("title", title.take(500))
        area?.let { addProperty("area", it) }
        listId?.let { addProperty("listId", it) }
        parentId?.let { addProperty("parentId", it) }
        note?.let { addProperty("note", it.take(20_000)) }
    }

    /** `PATCH tasks/<id>`: only the fields given (`null` values clear: due, remindAt, repeat, assigneeId). */
    fun patch(vararg fields: Pair<String, Any?>) = JsonObject().apply { fields.forEach { (k, v) -> put(k, v) } }

    fun doneBody(done: Boolean) = JsonObject().apply { addProperty("done", done) }

    fun noteBody(notebookId: Long?, area: String, title: String?, body: String?) = JsonObject().apply {
        notebookId?.let { addProperty("notebookId", it) }
        addProperty("area", area)
        title?.let { addProperty("title", it.take(300)) }
        body?.let { addProperty("body", it) }
    }

    fun vaultNoteBody(notebookId: Long, titleCipher: String, cipher: String, keyVersion: Int) = JsonObject().apply {
        addProperty("notebookId", notebookId)
        addProperty("titleCipher", titleCipher)
        addProperty("cipher", cipher)
        addProperty("keyVersion", keyVersion)
    }

    fun membersBody(ids: List<Long>) = JsonObject().apply { add("members", JsonArray().apply { ids.forEach { add(it) } }) }

    /**
     * `PUT …/members` with the roles (plugin 1.2.0, `shareRoles`): `[{ id, role }]`; without [roles] the ids only
     * (1.1: everyone may change).
     */
    fun membersBody(members: List<Pair<Long, String>>, roles: Boolean): JsonObject = JsonObject().apply {
        add("members", JsonArray().apply {
            members.forEach { (id, role) ->
                if (roles) add(JsonObject().apply { addProperty("id", id); addProperty("role", if (role == "read") "read" else "write") }) else add(id)
            }
        })
    }

    /** `PATCH lists/<id>` of „Liste bearbeiten“: only what changed. */
    fun listPatch(
        name: String? = null,
        color: String? = null,
        clearColor: Boolean = false,
        icon: String? = null,
        clearIcon: Boolean = false,
        days: List<Int>? = null,
        remind: String? = null,
        clearRemind: Boolean = false,
        groupId: Long? = null,
        clearGroup: Boolean = false,
        mydayOff: Boolean? = null,
    ) = JsonObject().apply {
        name?.let { addProperty("name", it.trim().take(80)) }
        if (clearColor) add("color", JsonNull.INSTANCE) else color?.let { addProperty("color", it) }
        if (clearIcon) add("icon", JsonNull.INSTANCE) else icon?.let { addProperty("icon", it) }
        days?.let { d -> add("days", JsonArray().apply { d.filter { it in 1..7 }.distinct().sorted().forEach { add(it) } }) }
        if (clearRemind) add("remind", JsonNull.INSTANCE) else remind?.let { addProperty("remind", it) }
        if (clearGroup) add("groupId", JsonNull.INSTANCE) else groupId?.let { addProperty("groupId", it) }
        mydayOff?.let { addProperty("mydayOff", it) }
    }

    /** `POST groups` (with `lists`: plugin 1.2.0, `groupEdit`). */
    fun groupBody(name: String, area: String, lists: List<Long>) = JsonObject().apply {
        addProperty("name", name.trim().take(80))
        addProperty("area", area)
        if (lists.isNotEmpty()) add("lists", JsonArray().apply { lists.forEach { add(it) } })
    }

    fun attachBody(kind: String, refId: Long, mediaId: String, name: String?, nameCipher: String?) = JsonObject().apply {
        addProperty("kind", kind)
        addProperty("refId", refId)
        addProperty("mediaId", mediaId)
        name?.let { addProperty("name", it.take(255)) }
        nameCipher?.let { addProperty("nameCipher", it) }
    }

    fun prefsBody(
        sources: Map<String, NSourcePrefs> = emptyMap(),
        groups: Map<String, String?> = emptyMap(),
        area: String? = null,
        evening: String? = null,
        times: Map<String, String> = emptyMap(),
        myday: Map<String, Boolean> = emptyMap(),
        notify: Map<String, Boolean> = emptyMap(),
        startArea: String? = null,
        yesterdayAt: String? = null,
        calendarTasks: Boolean? = null,
    ) = JsonObject().apply {
        time(yesterdayAt)?.let { addProperty("yesterdayAt", it) }
        calendarTasks?.let { add("calendar", JsonObject().apply { addProperty("tasks", it) }) }
        area?.let { addProperty("area", it) }
        evening?.let { addProperty("evening", it) }
        startArea?.let { addProperty("startArea", it) }
        if (times.isNotEmpty()) add("times", JsonObject().apply { times.forEach { (k, v) -> time(v)?.let { addProperty(k, it) } } })
        if (myday.isNotEmpty()) add("myday", JsonObject().apply { myday.forEach { (k, v) -> addProperty(k, v) } })
        if (notify.isNotEmpty()) add("notify", JsonObject().apply { notify.forEach { (k, v) -> addProperty(k, v) } })
        if (sources.isNotEmpty()) add("sources", JsonObject().apply {
            sources.forEach { (p, s) ->
                add(p, JsonObject().apply {
                    s.on?.let { addProperty("on", it) }
                    s.kids?.let { addProperty("kids", it) }
                    s.shoppingInMyDay?.let { addProperty("shoppingInMyDay", it) }
                    s.flagged?.let { addProperty("flagged", it) }
                    s.evening?.let { addProperty("evening", it) }
                })
            }
        })
        if (groups.isNotEmpty()) add("groups", JsonObject().apply { groups.forEach { (k, v) -> put(k, v) } })
    }

    fun msStartBody(accountId: Long, lists: List<NMsList>, doneDays: Int, twoWay: Boolean) = JsonObject().apply {
        addProperty("accountId", accountId)
        add("lists", JsonArray().apply {
            lists.forEach { l -> add(JsonObject().apply { addProperty("id", l.id); addProperty("name", l.name); addProperty("area", l.area); addProperty("include", l.include) }) }
        })
        addProperty("doneDays", doneDays)
        addProperty("twoWay", twoWay)
    }
}
