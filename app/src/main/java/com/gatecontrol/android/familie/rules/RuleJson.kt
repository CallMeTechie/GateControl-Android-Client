package com.gatecontrol.android.familie.rules

import com.gatecontrol.android.familie.FamilieJson.arr
import com.gatecontrol.android.familie.FamilieJson.bool
import com.gatecontrol.android.familie.FamilieJson.clean
import com.gatecontrol.android.familie.FamilieJson.dbl
import com.gatecontrol.android.familie.FamilieJson.el
import com.gatecontrol.android.familie.FamilieJson.int
import com.gatecontrol.android.familie.FamilieJson.ints
import com.gatecontrol.android.familie.FamilieJson.obj
import com.gatecontrol.android.familie.FamilieJson.objects
import com.gatecontrol.android.familie.FamilieJson.stamp
import com.gatecontrol.android.familie.FamilieJson.str
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject

/**
 * Parsers and bodies of the rules API (contract B1). Lenient towards the
 * plugin's older presence rules (`name`, `trigger` as a word with
 * `userId` / `placeId` beside it): what is missing gets its default, what is
 * malformed is dropped — never an exception.
 */
object RuleJson {

    private val HHMM_RE = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
    private val ACTION_ID_RE = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*/[A-Za-z0-9:._-]{1,64}$")
    private val PLUGIN_RE = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
    private val QUICK_ID_RE = Regex("^[A-Za-z0-9:._-]{1,64}$")
    private val FIELD_RE = Regex("^(?:value|badge|reading:[A-Za-z0-9_.:-]{1,64})$")
    private val TOKEN_RE = Regex("^[A-Za-z0-9_:.-]{1,64}$")
    private val DATE_RE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private const val TITLE_MAX = 80
    private const val TEXT_MAX = 500
    private const val MAX_ITEMS = 200

    internal fun time(value: String?): String? = value?.trim()?.take(5)?.takeIf { HHMM_RE.matches(it) }

    private fun token(value: String?): String? = value?.trim()?.takeIf { TOKEN_RE.matches(it) }

    // ─── rules ───────────────────────────────────────────────────────

    /** `GET rules` → rules, protocol and what the caller may create. */
    fun payload(o: JsonObject): RulesPayload? {
        val arr = o.arr("rules") ?: return null
        return RulesPayload(
            rules = arr.objects().mapNotNull(::rule).distinctBy { it.id }.take(MAX_ITEMS),
            log = o.arr("log")?.objects()?.mapNotNull(::logEntry)?.take(MAX_ITEMS).orEmpty(),
            can = can(o.obj("can")),
            open = o.arr("open")?.objects()?.mapNotNull(::open)?.take(MAX_ITEMS).orEmpty(),
            vacation = o.obj("vacation")?.let(::vacation),
        )
    }

    /** A place of `rules/options` → `places[]`, or of `POST presence/places` → `place`. */
    fun place(p: JsonObject): RuleOptionPlace? {
        val id = p.int("id") ?: return null
        return RuleOptionPlace(id, clean(p.str("name") ?: p.str("label"), 60).ifBlank { "#$id" })
    }

    /** `POST presence/places`. */
    fun placeBody(name: String, lat: Double, lon: Double, radius: Int): JsonObject = JsonObject().apply {
        addProperty("name", name.trim().take(40))
        addProperty("lat", lat)
        addProperty("lon", lon)
        addProperty("radius", radius)
    }

    /** `vacation` (1.11). */
    fun vacation(o: JsonObject): RuleVacation = RuleVacation(
        on = o.bool("on") == true,
        until = o.str("until")?.trim()?.takeIf { DATE_RE.matches(it) },
        since = stamp(o.str("since")),
        paused = (o.int("paused") ?: 0).coerceAtLeast(0),
    )

    /** `PUT rules/vacation`: [until] the last day or null (until switched off). */
    fun vacationBody(on: Boolean, until: String?): JsonObject = JsonObject().apply {
        addProperty("on", on)
        if (on && until != null) addProperty("until", until) else add("until", JsonNull.INSTANCE)
    }

    /** `POST rules/open/<id>/done | snooze`. */
    fun openBody(key: String): JsonObject = JsonObject().apply { addProperty("key", key) }

    /** `open[]`: a reminder that fired and is not done yet. */
    fun open(o: JsonObject): RuleOpen? {
        val ruleId = o.int("ruleId") ?: return null
        return RuleOpen(
            ruleId = ruleId,
            title = clean(o.str("title"), TITLE_MAX),
            key = o.str("key")?.let { clean(it, 64) }?.takeIf { it.isNotBlank() },
            snoozed = o.str("state") == "snoozed",
            since = stamp(o.str("since")),
            actionIds = o.arr("actionIds")?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString?.takeIf { ACTION_ID_RE.matches(it) } }?.distinct()?.take(20).orEmpty(),
            userId = o.int("userId"),
            snoozeUntil = stamp(o.str("snoozeUntil")),
        )
    }

    fun can(o: JsonObject?): RuleCan = RuleCan(house = o?.bool("house") == true, personal = o?.bool("personal") == true)

    fun rule(o: JsonObject): Rule? {
        val id = o.int("id") ?: return null
        val trigger = trigger(o) ?: return null
        val last = o.el("lastResult")
        val lastOk = when {
            last == null -> null
            last.isJsonObject -> last.asJsonObject.bool("ok")
            last.isJsonPrimitive && last.asJsonPrimitive.isBoolean -> last.asBoolean
            last.isJsonPrimitive -> when (last.asString) {
                "ok", "fired" -> true
                "failed", "error" -> false
                else -> null
            }
            else -> null
        }
        return Rule(
            id = id,
            title = clean(o.str("title") ?: o.str("name"), TITLE_MAX),
            scope = if (o.str("scope") == RuleScope.PERSONAL) RuleScope.PERSONAL else RuleScope.HOUSE,
            active = o.bool("active") != false,
            trigger = trigger,
            conditions = conditions(o.obj("conditions")),
            actions = o.arr("actions")?.objects()?.mapNotNull(::action).orEmpty(),
            lastRunAt = stamp(o.str("lastRunAt")) ?: last?.takeIf { it.isJsonObject }?.asJsonObject?.str("at")?.let(::stamp),
            lastOk = lastOk,
            ownerId = o.int("ownerId"),
            ownerName = o.str("ownerName")?.let { clean(it, 60) }?.takeIf { it.isNotBlank() },
            kind = token(o.str("kind")),
            editable = o.bool("editable") != false,
            pausable = o.bool("pausable") != false,
        )
    }

    /** `trigger` as an object (B1) or as a word with its fields on the rule (presence rules). */
    fun trigger(o: JsonObject): RuleTrigger? {
        val t = o.obj("trigger")
        val type = token(t?.str("type") ?: o.str("trigger")) ?: return null
        val src = t ?: o
        val schedule = o.obj("schedule")
        val weekdays = (src.ints("weekdays").ifEmpty { schedule?.ints("weekdays").orEmpty() }).filter { it in 1..7 }.distinct().sorted()
        val school = type == RuleTriggerType.SCHOOL_CHANGE
        return RuleTrigger(
            type = type,
            weekdays = weekdays,
            time = time(src.str("time") ?: schedule?.str("time")),
            userId = src.int("userId") ?: o.int("userId"),
            placeId = src.int("placeId") ?: o.int("placeId"),
            placeName = (src.str("placeName") ?: o.str("placeName"))?.let { clean(it, 60) }?.takeIf { it.isNotBlank() },
            graceMinutes = (src.int("graceMinutes") ?: o.int("graceMinutes"))?.coerceIn(0, 120),
            kinds = if (school) schoolKinds(src) else emptyList(),
            days = if (school) src.str("days")?.takeIf { it in RuleSchoolDays.ALL } ?: RuleSchoolDays.ANY else null,
        )
    }

    /** `kinds` of a school_change trigger: the known ones in their order; none (or only unknown) → cancelled. */
    private fun schoolKinds(t: JsonObject): List<String> {
        val given = t.arr("kinds")?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }.orEmpty().toSet()
        return RuleSchoolKind.ALL.filter { it in given }.ifEmpty { listOf(RuleSchoolKind.CANCELLED) }
    }

    fun conditions(o: JsonObject?): RuleConditions {
        if (o == null) return RuleConditions()
        val t = o.obj("time")
        val from = time(t?.str("from"))
        val to = time(t?.str("to"))
        val cal = o.obj("calendar")
        return RuleConditions(
            time = if (from != null && to != null && from != to) RuleTimeWindow(from, to) else null,
            afterSunset = o.bool("afterSunset") == true,
            device = o.arr("device")?.objects()?.mapNotNull(::deviceCondition)?.take(10).orEmpty(),
            calendar = cal?.let(::calendarCondition),
        )
    }

    private fun deviceCondition(o: JsonObject): DeviceCondition? {
        val actionId = o.str("actionId")?.takeIf { ACTION_ID_RE.matches(it) } ?: return null
        val field = o.str("field")?.takeIf { FIELD_RE.matches(it) } ?: "value"
        val op = o.str("op")?.takeIf { it in RuleOp.ALL } ?: return null
        val value = o.dbl("value") ?: return null
        return DeviceCondition(
            actionId, field, op, value,
            label = o.str("label")?.let { clean(it, 40) }?.takeIf { it.isNotBlank() },
            unit = o.str("unit")?.let { clean(it, 12) }?.takeIf { it.isNotBlank() },
        )
    }

    private fun calendarCondition(o: JsonObject): CalendarCondition = CalendarCondition(
        dayOffset = if (o.int("dayOffset") == 1) 1 else 0,
        sources = o.arr("sources")?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString?.let(::token) }?.distinct()?.take(20).orEmpty(),
        match = clean(o.str("match"), 80),
        memberIds = o.ints("memberIds").distinct().take(50),
    )

    private fun recipients(o: JsonObject): RuleRecipients? {
        val e = o.el("to") ?: return null
        return when {
            e.isJsonArray -> RuleRecipients(userIds = o.ints("to").distinct().take(50))
            e.isJsonPrimitive -> token(e.asString)?.let { RuleRecipients(keyword = it) }
            else -> null
        }
    }

    fun action(o: JsonObject): RuleAction? {
        val type = token(o.str("type")) ?: return null
        val text = o.str("text")?.let { clean(it, TEXT_MAX) }?.takeIf { it.isNotBlank() }
        return when (type) {
            RuleActionType.QUICK_ACTION -> {
                // Familie 1.9 names it `actionId` ('<plugin>/<id>'); older rules `plugin` + `id`.
                val full = o.str("actionId")?.takeIf { ACTION_ID_RE.matches(it) }
                val plugin = full?.substringBefore('/') ?: o.str("plugin")?.takeIf { PLUGIN_RE.matches(it) } ?: return null
                val id = full?.substringAfter('/') ?: o.str("id")?.takeIf { QUICK_ID_RE.matches(it) } ?: return null
                RuleAction(
                    type = type,
                    plugin = plugin,
                    id = id,
                    title = o.str("title")?.let { clean(it, TITLE_MAX) }?.takeIf { it.isNotBlank() },
                    state = o.str("state")?.takeIf { it == "on" || it == "off" },
                    available = o.bool("available"),
                    needsTarget = o.bool("needsTarget") == true,
                    kind = o.str("kind")?.takeIf { it == "toggle" || it == "button" },
                    verb = o.str("verb")?.let { clean(it, 40) }?.takeIf { it.isNotBlank() },
                )
            }
            RuleActionType.NOTIFY, RuleActionType.MESSENGER -> RuleAction(
                type = type,
                to = recipients(o),
                text = text,
                facts = o.bool("facts") == true,
                buttons = o.arr("buttons")?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { b -> b.length <= 140 } }
                    ?.distinct()?.take(3).orEmpty(),
            )
            // A newer action type: kept so a save does not lose it.
            else -> RuleAction(type = type, text = text)
        }
    }

    fun logEntry(o: JsonObject): RuleLogEntry? {
        val name = clean(o.str("ruleTitle") ?: o.str("ruleName") ?: o.str("title") ?: o.str("name"), TITLE_MAX)
        val at = stamp(o.str("at"))
        if (name.isBlank() && at == null) return null
        return RuleLogEntry(
            ruleId = o.int("ruleId"),
            ruleName = name,
            at = at,
            ok = o.bool("ok") != false,
            // Familie 1.9: `skipped` (a condition was not met); `fired: false` of earlier drafts too.
            fired = o.bool("skipped") != true && o.bool("fired") != false,
            text = (o.str("text") ?: o.str("summary"))?.let { clean(it, 200) }?.takeIf { it.isNotBlank() },
        )
    }

    // ─── options / test ──────────────────────────────────────────────

    fun options(o: JsonObject): RuleOptions {
        val triggers = o.arr("triggers")?.mapNotNull { e ->
            when {
                e.isJsonPrimitive -> token(e.asString)
                // `{ type, group, available }`: one that is not available here (module off) is left out.
                e.isJsonObject -> e.asJsonObject.takeIf { it.bool("available") != false }?.let { t -> token(t.str("type") ?: t.str("id")) }
                else -> null
            }
        }?.distinct().orEmpty()
        val features = o.obj("features")
        return RuleOptions(
            triggers = triggers.ifEmpty { RuleTriggerType.ALL },
            devices = o.arr("devices")?.objects()?.mapNotNull(::optionDevice)?.distinctBy { it.actionId }?.take(MAX_ITEMS).orEmpty(),
            calendars = o.arr("calendars")?.objects()?.mapNotNull { c ->
                val source = token(c.str("source")) ?: return@mapNotNull null
                RuleOptionCalendar(source, clean(c.str("label"), 60).ifBlank { source })
            }?.distinctBy { it.source }.orEmpty(),
            places = o.arr("places")?.objects()?.mapNotNull(::place)?.distinctBy { it.id }.orEmpty(),
            people = o.arr("people")?.objects()?.mapNotNull { p ->
                val id = p.int("userId") ?: p.int("id") ?: return@mapNotNull null
                RuleOptionPerson(id, clean(p.str("name"), 60).ifBlank { "#$id" }, child = p.str("role") == "child")
            }?.distinctBy { it.userId }.orEmpty(),
            can = can(o.obj("can")),
            messenger = o.obj("messenger")?.bool("available") ?: o.bool("messenger") ?: true,
            actions = if (o.bool("actionsAvailable") == false) emptyList() else o.arr("actions")?.objects()?.mapNotNull { a ->
                val id = a.str("actionId")?.takeIf { ACTION_ID_RE.matches(it) } ?: return@mapNotNull null
                RuleOptionAction(
                    actionId = id,
                    title = clean(a.str("title") ?: a.str("label"), 60).ifBlank { id.substringAfter('/') },
                    subtitle = listOfNotNull(a.str("pluginName"), a.str("subtitle")).map { clean(it, 60) }.filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null },
                    icon = a.str("icon")?.takeIf { it.length <= 4000 },
                    kind = a.str("kind")?.takeIf { it == "toggle" || it == "button" },
                    deviceId = a.obj("device")?.str("id")?.takeIf { it.isNotBlank() && it.length <= 120 },
                )
            }?.distinctBy { it.actionId }?.take(MAX_ITEMS).orEmpty(),
            deviceValues = features?.bool("deviceValues") != false,
            buttons = features?.bool("buttons") != false,
            school = o.obj("school")?.let { sc ->
                RuleOptionSchool(
                    students = sc.arr("students")?.objects()?.mapNotNull { st ->
                        val id = st.int("userId") ?: return@mapNotNull null
                        RuleOptionStudent(id, clean(st.str("name"), 60).ifBlank { "#$id" })
                    }?.distinctBy { it.userId }?.take(50).orEmpty(),
                )
            },
        )
    }

    private fun optionDevice(o: JsonObject): RuleOptionDevice? {
        val actionId = o.str("actionId")?.takeIf { ACTION_ID_RE.matches(it) } ?: return null
        return RuleOptionDevice(
            actionId = actionId,
            label = clean(o.str("label"), 60).ifBlank { actionId.substringAfter('/') },
            icon = o.str("icon")?.takeIf { it.length <= 64 },
            kind = o.str("kind")?.takeIf { it == "toggle" || it == "button" },
            fields = o.arr("fields")?.objects()?.mapNotNull { f ->
                val id = f.str("id")?.takeIf { FIELD_RE.matches(it) } ?: return@mapNotNull null
                RuleOptionField(
                    id = id,
                    label = clean(f.str("label"), 40).ifBlank { id.substringAfter(':') },
                    unit = f.str("unit")?.let { clean(it, 12) }?.takeIf { it.isNotBlank() },
                    current = f.str("current")?.let { clean(it, 40) }?.takeIf { it.isNotBlank() },
                )
            }?.distinctBy { it.id }?.take(20).orEmpty(),
        )
    }

    fun testResult(o: JsonObject): RuleTestResult? {
        val would = o.bool("wouldFire") ?: return null
        return RuleTestResult(
            wouldFire = would,
            details = o.arr("details")?.objects()?.mapNotNull { d ->
                val text = clean(d.str("text"), 200).ifBlank { return@mapNotNull null }
                RuleTestDetail(token(d.str("kind")) ?: "", d.bool("ok"), text)
            }?.take(20).orEmpty(),
        )
    }

    // ─── bodies ──────────────────────────────────────────────────────

    /**
     * Body of `POST rules` / `PATCH rules/<id>` / `POST rules/test`: the whole
     * rule; [withScope] false for a change (the plugin keeps the scope).
     */
    fun body(rule: Rule, withScope: Boolean = true): JsonObject = JsonObject().apply {
        addProperty("title", rule.title)
        if (withScope) addProperty("scope", rule.scope)
        addProperty("active", rule.active)
        add("trigger", triggerBody(rule.trigger))
        add("conditions", conditionsBody(rule.conditions))
        add("actions", JsonArray().apply { rule.actions.forEach { add(actionBody(it)) } })
    }

    fun activeBody(active: Boolean): JsonObject = JsonObject().apply { addProperty("active", active) }

    private fun triggerBody(t: RuleTrigger): JsonObject = JsonObject().apply {
        addProperty("type", t.type)
        when (t.type) {
            RuleTriggerType.SCHEDULE -> {
                add("weekdays", JsonArray().apply { t.weekdays.forEach { add(it) } })
                addProperty("time", t.time)
            }
            in RuleTriggerType.PERSON -> t.userId?.let { addProperty("userId", it) }
            in RuleTriggerType.PLACE -> {
                t.placeId?.let { addProperty("placeId", it) }
                if (t.userId == null) add("userId", JsonNull.INSTANCE) else addProperty("userId", t.userId)
            }
            RuleTriggerType.ALL_AWAY -> t.graceMinutes?.let { addProperty("graceMinutes", it) }
            // Familie 1.12: userId null = any child; at least one kind
            RuleTriggerType.SCHOOL_CHANGE -> {
                if (t.userId == null) add("userId", JsonNull.INSTANCE) else addProperty("userId", t.userId)
                add("kinds", JsonArray().apply { t.kinds.forEach { add(it) } })
                addProperty("days", t.days ?: RuleSchoolDays.ANY)
            }
        }
    }

    private fun conditionsBody(c: RuleConditions): JsonObject = JsonObject().apply {
        val t = c.time
        if (t == null) add("time", JsonNull.INSTANCE) else add("time", JsonObject().apply {
            addProperty("from", t.from)
            addProperty("to", t.to)
        })
        addProperty("afterSunset", c.afterSunset)
        if (c.device.isNotEmpty()) {
            add("device", JsonArray().apply {
                c.device.forEach { d ->
                    add(JsonObject().apply {
                        addProperty("actionId", d.actionId)
                        addProperty("field", d.field)
                        addProperty("op", d.op)
                        addProperty("value", d.value)
                    })
                }
            })
        }
        c.calendar?.let { cal ->
            add("calendar", JsonObject().apply {
                addProperty("dayOffset", cal.dayOffset)
                add("sources", JsonArray().apply { cal.sources.forEach { add(it) } })
                addProperty("match", cal.match)
                if (cal.memberIds.isNotEmpty()) add("memberIds", JsonArray().apply { cal.memberIds.forEach { add(it) } })
            })
        }
    }

    private fun actionBody(a: RuleAction): JsonObject = JsonObject().apply {
        addProperty("type", a.type)
        when (a.type) {
            // Familie 1.9: the quick action as `{ actionId }`, a toggle with its target `state`.
            RuleActionType.QUICK_ACTION -> {
                addProperty("actionId", a.actionId)
                a.title?.let { addProperty("title", it) }
                a.state?.let { addProperty("state", it) }
            }
            else -> {
                val to = a.to
                when {
                    to == null -> Unit
                    to.keyword != null -> addProperty("to", to.keyword)
                    else -> add("to", JsonArray().apply { to.userIds.forEach { add(it) } })
                }
                a.text?.let { addProperty("text", it) }
                if (a.type == RuleActionType.NOTIFY) {
                    if (a.facts) addProperty("facts", true)
                    if (a.buttons.isNotEmpty()) add("buttons", JsonArray().apply { a.buttons.forEach { add(it) } })
                }
            }
        }
    }
}
