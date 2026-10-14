package com.gatecontrol.android.familie

import com.gatecontrol.android.familie.emergency.EmergencyCard
import com.gatecontrol.android.familie.emergency.EmergencyContact
import com.gatecontrol.android.familie.emergency.EmergencyInsurance
import com.gatecontrol.android.familie.emergency.EmergencyMember
import com.gatecontrol.android.familie.emergency.EmergencyMemberDraft
import com.gatecontrol.android.familie.rides.DriverChoice
import com.gatecontrol.android.familie.rides.OverviewRides
import com.gatecontrol.android.familie.rides.Ride
import com.gatecontrol.android.familie.rides.RideDraft
import com.gatecontrol.android.familie.rides.RideDriver
import com.gatecontrol.android.familie.rides.RidePerson
import com.gatecontrol.android.familie.rides.RideRule
import com.gatecontrol.android.familie.shopping.ShoppingItem
import com.gatecontrol.android.familie.shopping.ShoppingList
import com.gatecontrol.android.familie.shopping.ShoppingOp
import com.gatecontrol.android.familie.shopping.ShoppingSnapshot
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * Turns the Familie plugin's JSON (contract B3) into [FamilieModels] and
 * builds the request bodies. Lenient where SQLite leaks through (0/1 for
 * booleans, numbers as text), strict where it matters: an entry without
 * its id or required fields is dropped, texts are plain and capped.
 */
object FamilieJson {

    private val DATE_RE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val TIME_RE = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    private val COLOR_RE = Regex("^#[0-9A-Fa-f]{6}$")
    /** Client ids (shopping): UUIDs; the server accepts `[A-Za-z0-9][A-Za-z0-9_-]{7,63}`. */
    private val UUID_RE = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")
    private val CONTROL_RE = Regex("[\\u0000-\\u001F\\u007F]")

    // ─── Primitive helpers ───────────────────────────────────────────

    internal fun JsonObject.el(key: String): JsonElement? = get(key)?.takeIf { !it.isJsonNull }

    internal fun JsonObject.obj(key: String): JsonObject? = el(key)?.takeIf { it.isJsonObject }?.asJsonObject

    internal fun JsonObject.arr(key: String): JsonArray? = el(key)?.takeIf { it.isJsonArray }?.asJsonArray

    internal fun JsonObject.str(key: String): String? {
        val p = el(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        return if (p.isString || p.isNumber) p.asString else null
    }

    internal fun JsonObject.int(key: String): Int? {
        val p = el(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        return when {
            p.isNumber -> p.asDouble.takeIf { it % 1.0 == 0.0 && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()
            p.isString -> p.asString.trim().toIntOrNull()
            else -> null
        }
    }

    internal fun JsonObject.dbl(key: String): Double? {
        val p = el(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        val v = when {
            p.isNumber -> p.asDouble
            p.isString -> p.asString.trim().toDoubleOrNull()
            else -> null
        }
        return v?.takeIf { it.isFinite() }
    }

    internal fun JsonObject.bool(key: String): Boolean? {
        val p = el(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        return when {
            p.isBoolean -> p.asBoolean
            p.isNumber -> p.asDouble != 0.0
            p.isString -> when (p.asString.lowercase()) {
                "true", "1" -> true
                "false", "0" -> false
                else -> null
            }
            else -> null
        }
    }

    internal fun JsonObject.ints(key: String): List<Int> =
        arr(key)?.mapNotNull { e ->
            e.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.let { p ->
                when {
                    p.isNumber -> p.asDouble.takeIf { d -> d % 1.0 == 0.0 }?.toInt()
                    p.isString -> p.asString.toIntOrNull()
                    else -> null
                }
            }
        }.orEmpty()

    internal fun JsonArray.objects(): List<JsonObject> = filter { it.isJsonObject }.map { it.asJsonObject }

    /** Plain text: control characters out, trimmed, capped. */
    fun clean(value: String?, max: Int): String =
        value?.let { CONTROL_RE.replace(it, " ").trim().take(max) }.orEmpty()

    internal fun date(value: String?): String? = value?.takeIf { DATE_RE.matches(it) }

    internal fun time(value: String?): String? = value?.take(5)?.takeIf { TIME_RE.matches(it) }

    internal fun color(value: String?): String? = value?.takeIf { COLOR_RE.matches(it) }

    /** ISO timestamps are only compared and formatted; anything sane-looking passes. */
    internal fun stamp(value: String?): String? = value?.trim()?.take(40)?.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c in "-:.+ " } }

    // ─── Error answers ───────────────────────────────────────────────

    /** `retryAfter` (seconds) of a too_soon / daily_limit answer. */
    fun retryAfter(o: JsonObject?): Int? = o?.int("retryAfter")?.takeIf { it in 0..86_400 }

    /** The open request a refused `POST locate/<child>` points to. */
    fun refusedRequestId(o: JsonObject?): Int? = o?.int("requestId")?.takeIf { it > 0 }

    private val FIELD_RE = Regex("^[A-Za-z][A-Za-z0-9_.]{0,59}$")

    /** The refused input of an `invalid` answer (`field`, e.g. `contacts.0.phone`). */
    fun errorField(o: JsonObject?): String? = o?.str("field")?.takeIf { FIELD_RE.matches(it) }

    /** Who drives an `already_taken` ride (`driver: {userId, name} | {label}`). */
    fun errorDriver(o: JsonObject?): String? = o?.obj("driver")?.let { d ->
        clean(d.str("name") ?: d.str("label"), 60).takeIf { it.isNotBlank() }
    }

    /** `{ ok:false, code, error }` → code (or null). */
    fun errorCode(o: JsonObject?): String? = o?.str("code")?.takeIf { it.length <= 40 && it.all { c -> c.isLetterOrDigit() || c == '_' } }

    // ─── me ──────────────────────────────────────────────────────────

    fun profile(o: JsonObject): FamilieProfile? {
        val me = o.obj("me") ?: return null
        val userId = me.int("userId") ?: return null
        val role = FamilieRole.fromWire(me.str("role")) ?: return null
        val m = o.obj("modules")
        val modules = FamilieModules(
            shopping = m?.bool("shopping") == true,
            tasks = m?.bool("tasks") == true,
            stars = m?.bool("stars") == true,
            dates = m?.bool("dates") == true,
            locate = m?.bool("locate") == true,
            arrivalSos = m?.bool("arrival_sos") == true,
            places = m?.bool("places") == true,
            rides = m?.bool("rides") == true,
            emergency = m?.bool("emergency") == true,
            wall = m?.bool("wall") == true,
            allowance = m?.bool("allowance") == true,
            wishes = m?.bool("wishes") == true,
            expenses = m?.bool("expenses") == true,
            board = m?.bool("board") == true,
            meals = m?.bool("meals") == true,
            presence = m?.bool("presence") == true,
        )
        return FamilieProfile(
            me = FamilieMe(
                userId = userId,
                name = clean(me.str("name"), 60).ifBlank { "#$userId" },
                role = role,
                age = me.int("age")?.takeIf { it in 0..150 },
                locateMode = if (role == FamilieRole.CHILD) LocateMode.fromWire(me.str("locateMode")) else null,
                color = color(me.str("color")),
            ),
            modules = modules,
            members = o.arr("members")?.objects()?.mapNotNull(::member).orEmpty().distinctBy { it.userId },
            serverTime = stamp(o.str("serverTime")),
        )
    }

    private fun member(o: JsonObject): FamilieMember? {
        val id = o.int("userId") ?: return null
        val role = FamilieRole.fromWire(o.str("role")) ?: return null
        return FamilieMember(
            userId = id,
            name = clean(o.str("name"), 60).ifBlank { "#$id" },
            role = role,
            age = o.int("age")?.takeIf { it in 0..150 },
            color = color(o.str("color")),
            locateMode = LocateMode.fromWire(o.str("locateMode")),
            locatable = o.bool("locatable"),
        )
    }

    // ─── overview ────────────────────────────────────────────────────

    fun overview(o: JsonObject): FamilieOverview {
        val shopping = o.obj("shopping")
        val tasks = o.obj("tasks")
        val stars = o.obj("stars")
        val dates = o.obj("dates")
        val locate = o.obj("locate")
        return FamilieOverview(
            shoppingLists = shopping?.let { s ->
                s.arr("lists")?.objects()?.mapNotNull { l ->
                    val id = l.str("id")?.takeIf { UUID_RE.matches(it) } ?: return@mapNotNull null
                    OverviewList(id, clean(l.str("name"), 40).ifBlank { "–" }, (l.int("open") ?: 0).coerceAtLeast(0))
                }.orEmpty()
            },
            myTasks = tasks?.let { t -> t.arr("mine")?.objects()?.mapNotNull(::task).orEmpty() },
            openTasks = (tasks?.int("open") ?: 0).coerceAtLeast(0),
            stars = stars?.let { OverviewStars((it.int("week") ?: 0).coerceAtLeast(0), (it.int("goal") ?: 0).coerceAtLeast(0)) },
            nextDates = dates?.let { d -> d.arr("next")?.objects()?.mapNotNull(::event).orEmpty() },
            lastLocates = locate?.let { l ->
                l.arr("last")?.objects()?.mapNotNull { r ->
                    val child = r.int("childId") ?: return@mapNotNull null
                    OverviewLocate(child, clean(r.str("status"), 20), stamp(r.str("at")), r.int("requestId"))
                }.orEmpty()
            },
            going = shopping?.arr("going")?.objects()?.mapNotNull { g ->
                val user = g.int("userId") ?: return@mapNotNull null
                OverviewGoing(user, g.str("listId")?.takeIf { UUID_RE.matches(it) }, stamp(g.str("at")))
            }.orEmpty(),
            arrivals = o.arr("arrivals")?.objects()?.mapNotNull { a ->
                val user = a.int("userId") ?: return@mapNotNull null
                OverviewArrival(user, a.str("label")?.let { clean(it, 60) }?.takeIf { it.isNotBlank() }, stamp(a.str("at")))
            },
            rides = o.obj("rides")?.let { r ->
                OverviewRides(
                    next = r.arr("next")?.objects()?.mapNotNull(::ride).orEmpty().take(5),
                    open = (r.int("open") ?: 0).coerceAtLeast(0),
                )
            },
            allowance = FamilieJson12.overviewAllowance(o.obj("allowance")),
            meals = FamilieJson12.overviewMeals(o.obj("meals")),
            board = FamilieJson12.overviewBoard(o.obj("board")),
            presence = FamilieJson12.overviewPresence(o.el("presence")),
        )
    }

    // ─── shopping ────────────────────────────────────────────────────

    fun shopping(o: JsonObject): ShoppingSnapshot = ShoppingSnapshot(
        lists = o.arr("lists")?.objects()?.mapNotNull(::shoppingList).orEmpty(),
        items = o.arr("items")?.objects()?.mapNotNull(::shoppingItem).orEmpty(),
        serverTime = stamp(o.str("serverTime")),
    )

    private fun shoppingList(o: JsonObject): ShoppingList? {
        val id = o.str("id")?.takeIf { UUID_RE.matches(it) } ?: return null
        return ShoppingList(
            id = id,
            name = clean(o.str("name"), 40).ifBlank { "–" },
            icon = o.str("icon")?.let { clean(it, 40) }?.takeIf { it.isNotBlank() },
            sort = o.int("sort") ?: 0,
            updatedAt = stamp(o.str("updatedAt")),
            deleted = o.bool("deleted") == true,
        )
    }

    private fun shoppingItem(o: JsonObject): ShoppingItem? {
        val id = o.str("id")?.takeIf { UUID_RE.matches(it) } ?: return null
        val listId = o.str("listId")?.takeIf { UUID_RE.matches(it) } ?: return null
        val deleted = o.bool("deleted") == true
        val text = clean(o.str("text"), 120)
        if (text.isBlank() && !deleted) return null
        return ShoppingItem(
            id = id,
            listId = listId,
            text = text,
            qty = o.str("qty")?.let { clean(it, 40) }?.takeIf { it.isNotBlank() },
            checked = o.bool("checked") == true,
            checkedBy = o.str("checkedBy")?.let { clean(it, 60) }?.takeIf { it.isNotBlank() },
            updatedAt = stamp(o.str("updatedAt")),
            deleted = deleted,
        )
    }

    /** `{ ops: [...] }` — null fields are left out. */
    fun opsBody(ops: List<ShoppingOp>): JsonObject = JsonObject().apply {
        add("ops", JsonArray().apply {
            ops.forEach { op ->
                add(JsonObject().apply {
                    addProperty("op", op.op)
                    op.id?.let { addProperty("id", it) }
                    op.listId?.let { addProperty("listId", it) }
                    op.name?.let { addProperty("name", it) }
                    op.icon?.let { addProperty("icon", it) }
                    op.text?.let { addProperty("text", it) }
                    op.qty?.let { addProperty("qty", it) }
                    op.checked?.let { addProperty("checked", it) }
                })
            }
        })
    }

    data class OpsResult(val applied: Int, val rejected: List<Pair<Int, String>>, val serverTime: String?)

    fun opsResult(o: JsonObject): OpsResult = OpsResult(
        applied = o.int("applied") ?: 0,
        rejected = o.arr("rejected")?.objects()?.mapNotNull { r ->
            val i = r.int("index") ?: return@mapNotNull null
            i to (errorCode(JsonObject().apply { r.el("code")?.let { add("code", it) } }) ?: "rejected")
        }.orEmpty(),
        serverTime = stamp(o.str("serverTime")),
    )

    // ─── tasks ───────────────────────────────────────────────────────

    fun task(o: JsonObject): FamilieTask? {
        val id = o.int("id") ?: return null
        val title = clean(o.str("title"), 120)
        if (title.isBlank()) return null
        val last = o.obj("lastDone")
        return FamilieTask(
            id = id,
            title = title,
            mode = o.str("mode")?.takeIf { it in FamilieTask.MODES } ?: FamilieTask.MODE_FIXED,
            assignees = o.ints("assignees").distinct(),
            current = o.int("current"),
            repeat = o.str("repeat")?.takeIf { it in FamilieTask.REPEATS } ?: "none",
            weekdays = o.ints("weekdays").filter { it in 1..7 }.distinct().sorted(),
            dueTime = time(o.str("dueTime")),
            dueDate = date(o.str("dueDate")),
            stars = (o.int("stars") ?: 0).coerceIn(0, 10),
            reminder = o.bool("reminder") == true,
            doneToday = o.bool("doneToday") == true,
            lastDone = last?.let { TaskLastDone(it.int("by"), stamp(it.str("at"))) },
        )
    }

    fun tasks(o: JsonObject): TasksPayload {
        val stars = o.obj("stars")
        return TasksPayload(
            tasks = o.arr("tasks")?.objects()?.mapNotNull(::task).orEmpty(),
            stars = stars?.let { StarsBoard(userMap(it.obj("week")), userMap(it.obj("goal"))) },
        )
    }

    private fun userMap(o: JsonObject?): Map<Int, Int> =
        o?.entrySet()?.mapNotNull { (k, _) ->
            val id = k.toIntOrNull() ?: return@mapNotNull null
            val n = o.int(k) ?: return@mapNotNull null
            id to n.coerceAtLeast(0)
        }?.toMap().orEmpty()

    fun taskBody(d: TaskDraft): JsonObject = JsonObject().apply {
        addProperty("title", d.title.trim().take(120))
        addProperty("mode", d.mode)
        add("assignees", JsonArray().apply { d.assignees.forEach { add(it) } })
        addProperty("repeat", d.repeat)
        add("weekdays", JsonArray().apply { d.weekdays.forEach { add(it) } })
        add("dueTime", d.dueTime?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        add("dueDate", d.dueDate?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        addProperty("stars", d.stars.coerceIn(0, 10))
        addProperty("reminder", d.reminder)
    }

    // ─── dates ───────────────────────────────────────────────────────

    fun event(o: JsonObject): FamilieEvent? {
        val id = o.str("id")?.takeIf { it.length <= 40 && it.isNotBlank() } ?: return null
        val title = clean(o.str("title"), 120)
        val date = date(o.str("date")) ?: return null
        if (title.isBlank()) return null
        return FamilieEvent(
            id = id,
            title = title,
            date = date,
            time = time(o.str("time")),
            endDate = date(o.str("endDate")),
            allDay = o.bool("allDay") ?: (o.str("time") == null),
            repeat = if (o.str("repeat") == "yearly") "yearly" else "none",
            memberIds = o.ints("memberIds").distinct(),
            kind = if (o.str("kind") == FamilieEvent.KIND_BIRTHDAY) FamilieEvent.KIND_BIRTHDAY else FamilieEvent.KIND_EVENT,
            remindDays = o.int("remindDays")?.takeIf { it in 0..14 },
            note = clean(o.str("note"), 500),
        )
    }

    /** `GET dates` → occurrences (key `dates`, or `events` / `occurrences`). */
    fun events(o: JsonObject): List<FamilieEvent> =
        (o.arr("dates") ?: o.arr("events") ?: o.arr("occurrences"))?.objects()?.mapNotNull(::event).orEmpty()

    fun eventBody(d: EventDraft): JsonObject = JsonObject().apply {
        addProperty("title", d.title.trim().take(120))
        addProperty("date", d.date)
        add("time", d.time?.takeIf { !d.allDay }?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        add("endDate", d.endDate?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        addProperty("allDay", d.allDay)
        addProperty("repeat", d.repeat)
        add("memberIds", JsonArray().apply { d.memberIds.forEach { add(it) } })
        addProperty("kind", d.kind)
        add("remindDays", d.remindDays?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        addProperty("note", d.note.trim().take(500))
    }

    fun alltag(o: JsonObject): AlltagPayload = AlltagPayload(
        events = o.arr("events")?.objects()?.mapNotNull { e ->
            val id = e.str("id")?.takeIf { it.isNotBlank() && it.length <= 64 } ?: return@mapNotNull null
            val date = date(e.str("date")) ?: return@mapNotNull null
            val title = clean(e.str("title"), 120).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val cal = e.obj("calendar")
            AlltagEvent(
                id = id,
                title = title,
                date = date,
                time = time(e.str("time")),
                endTime = time(e.str("endTime")),
                allDay = e.bool("allDay") ?: (e.str("time") == null),
                calendarName = cal?.str("name")?.let { clean(it, 40) }?.takeIf { it.isNotBlank() },
                calendarColor = color(cal?.str("color")),
            )
        }.orEmpty(),
        waste = o.arr("waste")?.objects()?.mapNotNull { w ->
            val date = date(w.str("date")) ?: return@mapNotNull null
            val kinds = w.arr("kinds")?.mapNotNull { k -> k.takeIf { it.isJsonPrimitive }?.asString?.let { clean(it, 40) }?.takeIf { it.isNotBlank() } }.orEmpty()
            if (kinds.isEmpty()) null else AlltagWaste(date, kinds, color(w.str("color")))
        }.orEmpty(),
    )

    // ─── locate ──────────────────────────────────────────────────────

    fun locateRequest(o: JsonObject): LocateRequest? {
        val id = o.int("id") ?: return null
        val child = o.int("childId") ?: return null
        val lat = o.dbl("lat")?.takeIf { it in -90.0..90.0 }
        val lon = o.dbl("lon")?.takeIf { it in -180.0..180.0 }
        return LocateRequest(
            id = id,
            childId = child,
            requestedBy = o.int("requestedBy"),
            mode = LocateMode.fromWire(o.str("mode")),
            status = o.str("status")?.takeIf { it.length <= 20 } ?: LocateRequest.PENDING,
            createdAt = stamp(o.str("createdAt")),
            expiresAt = stamp(o.str("expiresAt")),
            lat = if (lon != null) lat else null,
            lon = if (lat != null) lon else null,
            accuracy = o.dbl("accuracy")?.takeIf { it >= 0 },
            at = stamp(o.str("at")),
            battery = o.int("battery")?.takeIf { it in 0..100 },
            code = (o.str("code") ?: o.str("reason"))?.let { clean(it, 40) }?.takeIf { it.isNotBlank() },
            requestedByName = o.str("requestedByName")?.let { clean(it, 60) }?.takeIf { it.isNotBlank() },
            childName = o.str("childName")?.let { clean(it, 60) }?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * The request in an answer of `POST locate/<child>` or `GET
     * locate/requests/<id>`: `request` object, else the top level.
     */
    fun requestIn(o: JsonObject): LocateRequest? = o.obj("request")?.let(::locateRequest) ?: locateRequest(o)

    fun locateRequests(o: JsonObject): List<LocateRequest> =
        o.arr("requests")?.objects()?.mapNotNull(::locateRequest).orEmpty()

    fun answerBody(lat: Double, lon: Double, accuracy: Double, at: String, battery: Int?): JsonObject = JsonObject().apply {
        addProperty("lat", lat)
        addProperty("lon", lon)
        addProperty("accuracy", accuracy)
        addProperty("at", at)
        battery?.takeIf { it in 0..100 }?.let { addProperty("battery", it) }
    }

    fun positionBody(lat: Double?, lon: Double?, accuracy: Double?, extra: Map<String, String> = emptyMap()): JsonObject = JsonObject().apply {
        extra.forEach { (k, v) -> addProperty(k, v) }
        if (lat != null && lon != null) {
            addProperty("lat", lat)
            addProperty("lon", lon)
            accuracy?.let { addProperty("accuracy", it) }
        }
    }

    /** `POST sos` → `sosId` (or `id`). */
    fun sosId(o: JsonObject): Int? = o.int("sosId") ?: o.obj("sos")?.int("id") ?: o.int("id")

    // ─── places ──────────────────────────────────────────────────────

    fun place(o: JsonObject): FamiliePlace? {
        val id = o.int("id") ?: return null
        val lat = o.dbl("lat")?.takeIf { it in -90.0..90.0 } ?: return null
        val lon = o.dbl("lon")?.takeIf { it in -180.0..180.0 } ?: return null
        val owner = o.int("ownerId") ?: return null
        val title = clean(o.str("title"), 80)
        if (title.isBlank()) return null
        val action = o.str("action")?.takeIf { it in listOf(FamiliePlace.ACTION_NOTE, FamiliePlace.ACTION_LIST, FamiliePlace.ACTION_TELL) }
            ?: FamiliePlace.ACTION_NOTE
        return FamiliePlace(
            id = id,
            ownerId = owner,
            targetUserId = o.int("targetUserId") ?: owner,
            title = title,
            note = clean(o.str("note"), 300),
            lat = lat,
            lon = lon,
            radius = (o.int("radius") ?: 150).coerceIn(FamiliePlace.MIN_RADIUS, FamiliePlace.MAX_RADIUS),
            trigger = if (o.str("trigger") == FamiliePlace.TRIGGER_EXIT) FamiliePlace.TRIGGER_EXIT else FamiliePlace.TRIGGER_ENTER,
            action = action,
            listId = o.str("listId")?.takeIf { UUID_RE.matches(it) },
            tellUserIds = o.ints("tellUserIds").distinct(),
            repeat = if (o.str("repeat") == "once") "once" else "always",
            active = o.bool("active") != false,
            consent = o.str("consent")?.takeIf {
                it in listOf(FamiliePlace.CONSENT_ACCEPTED, FamiliePlace.CONSENT_PENDING, FamiliePlace.CONSENT_DECLINED)
            } ?: FamiliePlace.CONSENT_ACCEPTED,
            ownerName = o.str("ownerName")?.let { clean(it, 60) }?.takeIf { it.isNotBlank() },
            targetName = o.str("targetName")?.let { clean(it, 60) }?.takeIf { it.isNotBlank() },
        )
    }

    fun places(o: JsonObject): List<FamiliePlace> = o.arr("places")?.objects()?.mapNotNull(::place).orEmpty()

    fun placeBody(d: PlaceDraft): JsonObject = JsonObject().apply {
        addProperty("title", d.title.trim().take(80))
        addProperty("note", d.note.trim().take(300))
        addProperty("lat", d.lat)
        addProperty("lon", d.lon)
        addProperty("radius", d.radius.coerceIn(FamiliePlace.MIN_RADIUS, FamiliePlace.MAX_RADIUS))
        addProperty("trigger", d.trigger)
        addProperty("action", d.action)
        add("listId", d.listId?.takeIf { d.action == FamiliePlace.ACTION_LIST }?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        add("tellUserIds", JsonArray().apply { if (d.action == FamiliePlace.ACTION_TELL) d.tellUserIds.forEach { add(it) } })
        addProperty("repeat", d.repeat)
        addProperty("active", d.active)
        d.targetUserId?.let { addProperty("targetUserId", it) }
    }

    // ─── rides (1.1) ─────────────────────────────────────────────────

    /** `"12"` or `"<seriesId>:<YYYY-MM-DD>"`. */
    val RIDE_ID_RE = Regex("^[1-9]\\d{0,9}(:\\d{4}-\\d{2}-\\d{2})?$")

    private fun person(o: JsonObject?): RidePerson? {
        val id = o?.int("userId") ?: return null
        return RidePerson(id, clean(o.str("name"), 60).ifBlank { "#$id" })
    }

    private fun driver(o: JsonObject?): RideDriver? {
        if (o == null) return null
        o.int("userId")?.let { return RideDriver.Member(it, clean(o.str("name"), 60).ifBlank { "#$it" }) }
        return clean(o.str("label"), 60).takeIf { it.isNotBlank() }?.let { RideDriver.Label(it) }
    }

    fun ride(o: JsonObject): Ride? {
        val id = o.str("id")?.takeIf { RIDE_ID_RE.matches(it) } ?: return null
        val date = date(o.str("date")) ?: return null
        val time = time(o.str("time")) ?: return null
        val title = clean(o.str("title"), 80)
        if (title.isBlank()) return null
        val lat = o.dbl("lat")?.takeIf { it in -90.0..90.0 }
        val lon = o.dbl("lon")?.takeIf { it in -180.0..180.0 }
        return Ride(
            id = id,
            seriesId = o.int("seriesId")?.takeIf { it > 0 },
            date = date,
            time = time,
            title = title,
            place = clean(o.str("place"), 80),
            lat = if (lon != null) lat else null,
            lon = if (lat != null) lon else null,
            childIds = o.ints("childIds").distinct(),
            status = o.str("status")?.takeIf { it in Ride.STATUSES } ?: Ride.STATUS_OPEN,
            driver = driver(o.obj("driver")),
            requestedBy = person(o.obj("requestedBy")),
            declined = o.arr("declined")?.objects()?.mapNotNull(::person).orEmpty().distinctBy { it.userId },
            askedAt = stamp(o.str("askedAt")),
            remindMinutes = (o.int("remindMinutes") ?: Ride.DEFAULT_REMIND).coerceIn(0, Ride.MAX_REMIND),
            note = clean(o.str("note"), 200),
            rule = o.obj("repeat")?.let { rep ->
                val days = rep.ints("weekdays").filter { it in 1..7 }.distinct().sorted()
                if (days.isEmpty()) null else RideRule(days, date(rep.str("startDate")), date(rep.str("endDate")))
            },
            startAt = stamp(o.str("startAt")),
        )
    }

    fun rides(o: JsonObject): List<Ride> =
        o.arr("rides")?.objects()?.mapNotNull(::ride).orEmpty().sortedWith(compareBy({ it.date }, { it.time }))

    /** The ride in a create / change answer (`ride`, or the top level), else null. */
    fun rideIn(o: JsonObject): Ride? = o.obj("ride")?.let(::ride) ?: ride(o)

    /** `{userId}` | `{label}` | `{clear:true}` (`POST rides/<id>/driver`). */
    fun driverBody(d: DriverChoice): JsonObject = JsonObject().apply {
        when (d) {
            is DriverChoice.Member -> addProperty("userId", d.userId)
            is DriverChoice.Label -> addProperty("label", d.label.trim().take(60))
            DriverChoice.None -> addProperty("clear", true)
        }
    }

    /**
     * `POST rides` / `PATCH rides/<id>`. [create] adds what only a new ride
     * takes (driver, repeat, ask); a change sets the driver through its own
     * call — except for the whole [series] ([withDriver]: `driver` object or
     * null). A change of the whole series keeps the series' dates.
     */
    fun rideBody(d: RideDraft, create: Boolean = true, series: Boolean = false, withDriver: Boolean = false): JsonObject = JsonObject().apply {
        if (!series || create) addProperty("date", d.date)
        addProperty("time", d.time)
        addProperty("title", d.title.trim().take(80))
        addProperty("place", d.place.trim().take(80))
        if (d.lat != null && d.lon != null) {
            addProperty("lat", d.lat)
            addProperty("lon", d.lon)
        } else if (!create) {
            add("lat", JsonNull.INSTANCE)
            add("lon", JsonNull.INSTANCE)
        }
        add("childIds", JsonArray().apply { d.childIds.distinct().forEach { add(it) } })
        addProperty("remindMinutes", d.remindMinutes.coerceIn(0, Ride.MAX_REMIND))
        addProperty("note", d.note.trim().take(200))
        val repeat = d.repeat
        if (repeat != null && repeat.weekdays.isNotEmpty()) {
            add("repeat", JsonObject().apply {
                add("weekdays", JsonArray().apply { repeat.weekdays.filter { it in 1..7 }.distinct().sorted().forEach { add(it) } })
                add("endDate", repeat.endDate?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
            })
        }
        if (create) {
            if (d.driver !is DriverChoice.None) add("driver", driverBody(d.driver))
            d.ask?.let { addProperty("ask", it) }
        } else if (withDriver) {
            add("driver", if (d.driver is DriverChoice.None) JsonNull.INSTANCE else driverBody(d.driver))
        }
    }

    // ─── emergency card (1.1) ────────────────────────────────────────

    private val BLOOD_RE = Regex("^(A|B|AB|0|O)[+-]$")

    private fun bloodType(value: String?): String? {
        val v = value?.trim()?.uppercase()?.replace(" ", "")?.replace("RH", "") ?: return null
        if (!BLOOD_RE.matches(v)) return null
        return if (v.startsWith("O")) "0" + v.substring(1) else v
    }

    private fun contact(o: JsonObject): EmergencyContact? {
        val phone = o.str("phone")?.trim()?.takeIf { EmergencyContact.PHONE_RE.matches(it) } ?: return null
        val label = clean(o.str("label"), EmergencyContact.MAX_LABEL)
        return EmergencyContact(label.ifBlank { phone }, phone, clean(o.str("note"), EmergencyContact.MAX_NOTE))
    }

    private fun contacts(o: JsonObject, max: Int): List<EmergencyContact> =
        o.arr("contacts")?.objects()?.mapNotNull(::contact).orEmpty().take(max)

    private fun emergencyMember(o: JsonObject): EmergencyMember? {
        val id = o.int("userId") ?: return null
        val insurance = o.obj("insurance")?.let { i ->
            val name = clean(i.str("name"), EmergencyInsurance.MAX_NAME)
            val number = clean(i.str("number"), EmergencyInsurance.MAX_NUMBER)
            if (name.isBlank() && number.isBlank()) null else EmergencyInsurance(name, number)
        }
        val updatedBy = o.el("updatedBy")
        return EmergencyMember(
            userId = id,
            name = clean(o.str("name"), 60).ifBlank { "#$id" },
            birthdate = date(o.str("birthdate")),
            age = o.int("age")?.takeIf { it in 0..150 },
            bloodType = bloodType(o.str("bloodType")),
            weightKg = o.dbl("weightKg")?.takeIf { it in EmergencyMember.MIN_WEIGHT..EmergencyMember.MAX_WEIGHT },
            allergies = clean(o.str("allergies"), EmergencyMember.MAX_TEXT),
            medications = clean(o.str("medications"), EmergencyMember.MAX_TEXT),
            conditions = clean(o.str("conditions"), EmergencyMember.MAX_TEXT),
            notes = clean(o.str("notes"), EmergencyMember.MAX_NOTES),
            insurance = insurance,
            contacts = contacts(o, EmergencyMember.MAX_CONTACTS),
            updatedAt = stamp(o.str("updatedAt")),
            // A user id, or { userId, name } — both are accepted.
            updatedBy = if (updatedBy?.isJsonObject == true) updatedBy.asJsonObject.int("userId") else o.int("updatedBy"),
            updatedByName = (if (updatedBy?.isJsonObject == true) updatedBy.asJsonObject.str("name") else o.str("updatedByName"))
                ?.let { clean(it, 60) }?.takeIf { it.isNotBlank() },
        )
    }

    fun emergency(o: JsonObject): EmergencyCard = EmergencyCard(
        members = o.arr("members")?.objects()?.mapNotNull(::emergencyMember).orEmpty().distinctBy { it.userId },
        contacts = contacts(o, EmergencyCard.MAX_FAMILY_CONTACTS),
        updatedAt = stamp(o.str("updatedAt")),
    )

    private fun contactsArray(list: List<EmergencyContact>, max: Int): JsonArray = JsonArray().apply {
        list.mapNotNull { c ->
            val phone = EmergencyContact.cleanPhone(c.phone).takeIf { p -> p.any { it.isDigit() } } ?: return@mapNotNull null
            c.copy(label = c.label.trim().take(EmergencyContact.MAX_LABEL), phone = phone, note = c.note.trim().take(EmergencyContact.MAX_NOTE))
        }.take(max).forEach { c ->
            add(JsonObject().apply {
                addProperty("label", c.label)
                addProperty("phone", c.phone)
                addProperty("note", c.note)
            })
        }
    }

    /** `PUT emergency/members/<userId>` (the name and birthdate come from the member list). */
    fun emergencyMemberBody(d: EmergencyMemberDraft): JsonObject = JsonObject().apply {
        add("bloodType", bloodType(d.bloodType)?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        add("weightKg", EmergencyMember.weight(d.weightKg)?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        addProperty("allergies", d.allergies.trim().take(EmergencyMember.MAX_TEXT))
        addProperty("medications", d.medications.trim().take(EmergencyMember.MAX_TEXT))
        addProperty("conditions", d.conditions.trim().take(EmergencyMember.MAX_TEXT))
        addProperty("notes", d.notes.trim().take(EmergencyMember.MAX_NOTES))
        val ins = d.insurance?.let { EmergencyInsurance(it.name.trim().take(EmergencyInsurance.MAX_NAME), it.number.trim().take(EmergencyInsurance.MAX_NUMBER)) }
            ?.takeIf { it.name.isNotBlank() || it.number.isNotBlank() }
        add(
            "insurance",
            ins?.let {
                JsonObject().apply {
                    addProperty("name", it.name)
                    addProperty("number", it.number)
                }
            } ?: JsonNull.INSTANCE,
        )
        add("contacts", contactsArray(d.contacts, EmergencyMember.MAX_CONTACTS))
    }

    /** `PUT emergency/contacts`. */
    fun emergencyContactsBody(list: List<EmergencyContact>): JsonObject = JsonObject().apply {
        add("contacts", contactsArray(list, EmergencyCard.MAX_FAMILY_CONTACTS))
    }
}
