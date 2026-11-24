package com.gatecontrol.android.familie

import com.gatecontrol.android.familie.shopping.ShoppingCompleted
import com.gatecontrol.android.familie.shopping.ShoppingItem
import com.gatecontrol.android.familie.shopping.ShoppingList
import com.gatecontrol.android.familie.shopping.ShoppingOp
import com.gatecontrol.android.familie.shopping.ShoppingOpsResult
import com.gatecontrol.android.familie.shopping.ShoppingSnapshot
import com.gatecontrol.android.familie.shopping.ShoppingTaskRef
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * The JSON of the Familie and Alltag plugins shared by the phone app and
 * GateControl TV: the lenient primitive readers, the error answers, the
 * shopping lists, the dates and the Alltag calendar. Lenient where SQLite
 * leaks through (0/1 for booleans, numbers as text), strict where it
 * matters: an entry without its id or required fields is dropped, texts are
 * plain and capped. The phone app's `FamilieJson` adds the rest of the
 * contract on top.
 */
open class FamilieJsonBase {

    private val DATE_RE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val TIME_RE = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    private val COLOR_RE = Regex("^#[0-9A-Fa-f]{6}$")
    /** Client ids (shopping): UUIDs; the server accepts `[A-Za-z0-9][A-Za-z0-9_-]{7,63}`. */
    val UUID_RE = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")
    private val CONTROL_RE = Regex("[\\u0000-\\u001F\\u007F]")

    // ─── Primitive helpers ───────────────────────────────────────────

    fun JsonObject.el(key: String): JsonElement? = get(key)?.takeIf { !it.isJsonNull }

    fun JsonObject.obj(key: String): JsonObject? = el(key)?.takeIf { it.isJsonObject }?.asJsonObject

    fun JsonObject.arr(key: String): JsonArray? = el(key)?.takeIf { it.isJsonArray }?.asJsonArray

    fun JsonObject.str(key: String): String? {
        val p = el(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        return if (p.isString || p.isNumber) p.asString else null
    }

    fun JsonObject.int(key: String): Int? {
        val p = el(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        return when {
            p.isNumber -> p.asDouble.takeIf { it % 1.0 == 0.0 && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()
            p.isString -> p.asString.trim().toIntOrNull()
            else -> null
        }
    }

    fun JsonObject.dbl(key: String): Double? {
        val p = el(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        val v = when {
            p.isNumber -> p.asDouble
            p.isString -> p.asString.trim().toDoubleOrNull()
            else -> null
        }
        return v?.takeIf { it.isFinite() }
    }

    fun JsonObject.bool(key: String): Boolean? {
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

    fun JsonObject.ints(key: String): List<Int> =
        arr(key)?.mapNotNull { e ->
            e.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.let { p ->
                when {
                    p.isNumber -> p.asDouble.takeIf { d -> d % 1.0 == 0.0 }?.toInt()
                    p.isString -> p.asString.toIntOrNull()
                    else -> null
                }
            }
        }.orEmpty()

    fun JsonArray.objects(): List<JsonObject> = filter { it.isJsonObject }.map { it.asJsonObject }

    /** Plain text: control characters out, trimmed, capped. */
    fun clean(value: String?, max: Int): String =
        value?.let { CONTROL_RE.replace(it, " ").trim().take(max) }.orEmpty()

    fun date(value: String?): String? = value?.takeIf { DATE_RE.matches(it) }

    fun time(value: String?): String? = value?.take(5)?.takeIf { TIME_RE.matches(it) }

    fun color(value: String?): String? = value?.takeIf { COLOR_RE.matches(it) }

    /** ISO timestamps are only compared and formatted; anything sane-looking passes. */
    fun stamp(value: String?): String? = value?.trim()?.take(40)?.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c in "-:.+ " } }

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

    // ─── shopping ────────────────────────────────────────────────────

    fun shopping(o: JsonObject): ShoppingSnapshot = ShoppingSnapshot(
        lists = o.arr("lists")?.objects()?.mapNotNull(::shoppingList).orEmpty(),
        items = o.arr("items")?.objects()?.mapNotNull(::shoppingItem).orEmpty(),
        serverTime = stamp(o.str("serverTime")),
        home = o.obj("home")?.str("home")?.takeIf { it == "notizen" || it == "familie" },
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
            assigneeId = o.obj("assignee")?.let { a -> a.int("userId") ?: a.int("id") } ?: o.int("assignee") ?: o.int("assigneeId"),
            assigneeName = o.obj("assignee")?.str("name")?.let { clean(it, 60) }?.takeIf { it.isNotBlank() },
            assignedBy = o.obj("assignee")?.int("by") ?: o.int("assignedBy"),
            assigneeState = o.obj("assignee")?.str("state")?.takeIf { it == "open" || it == "taken" || it == "declined" },
            dueAt = stamp(o.str("dueAt")),
            // „Notizen & Aufgaben“ 1.4.0: the same lists there carry who may do what and the tasks they hang on
            listNo = o.el("listId")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong?.takeIf { it > 0 },
            area = o.str("area")?.takeIf { it == "private" || it == "work" },
            canEdit = o.bool("canEdit"),
            canManage = o.bool("canManage"),
            family = o.bool("family") == true,
            shared = o.bool("shared") == true,
            ownerName = o.obj("owner")?.str("name")?.let { clean(it, 60) }?.takeIf { it.isNotBlank() },
            memberNames = o.arr("members")?.objects()?.mapNotNull { m -> m.str("name")?.let { clean(it, 60) }?.takeIf { it.isNotBlank() } }?.take(50),
            tasks = o.arr("tasks")?.objects()?.mapNotNull { t ->
                val id = t.el("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong?.takeIf { it > 0 } ?: return@mapNotNull null
                ShoppingTaskRef(id, clean(t.str("title"), 200).ifBlank { return@mapNotNull null })
            }?.take(5),
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
                    op.area?.let { addProperty("area", it) }
                    op.family?.let { addProperty("family", it) }
                })
            }
        })
    }

    fun opsResult(o: JsonObject): ShoppingOpsResult = ShoppingOpsResult(
        applied = o.int("applied") ?: 0,
        rejected = o.arr("rejected")?.objects()?.mapNotNull { r ->
            val i = r.int("index") ?: return@mapNotNull null
            i to (errorCode(JsonObject().apply { r.el("code")?.let { add("code", it) } }) ?: "rejected")
        }.orEmpty(),
        serverTime = stamp(o.str("serverTime")),
        completed = o.arr("completed")?.objects()?.mapNotNull { c ->
            val task = c.el("taskId")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong?.takeIf { it > 0 } ?: return@mapNotNull null
            val list = c.str("listId")?.takeIf { UUID_RE.matches(it) } ?: return@mapNotNull null
            ShoppingCompleted(task, clean(c.str("title"), 200), list)
        }?.take(20).orEmpty(),
    )

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
}

/** The shared parsers on their own (GateControl TV, the core calls). */
object FamilieHomeJson : FamilieJsonBase()
