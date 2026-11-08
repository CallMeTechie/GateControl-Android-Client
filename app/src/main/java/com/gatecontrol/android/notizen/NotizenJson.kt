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
    private fun person(o: JsonObject?): NPerson? = o?.let { NPerson(it.id("id"), it.text("name", 120)) }?.takeIf { it.id != null || it.name != null }
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
        return NPrefs(NArea.of(o.str("area")), time(o.str("evening")) ?: "18:00", sources, groups, times)
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
        )
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
        )
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
        )
    }

    fun group(o: JsonObject?): NGroup? = o?.let { g -> NGroup(g.id("id") ?: return null, g.text("name", 120) ?: return null, NArea.of(g.str("area"))) }

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

    fun attachBody(kind: String, refId: Long, mediaId: String, name: String?, nameCipher: String?) = JsonObject().apply {
        addProperty("kind", kind)
        addProperty("refId", refId)
        addProperty("mediaId", mediaId)
        name?.let { addProperty("name", it.take(255)) }
        nameCipher?.let { addProperty("nameCipher", it) }
    }

    fun prefsBody(sources: Map<String, NSourcePrefs> = emptyMap(), groups: Map<String, String?> = emptyMap(), area: String? = null, evening: String? = null) = JsonObject().apply {
        area?.let { addProperty("area", it) }
        evening?.let { addProperty("evening", it) }
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
