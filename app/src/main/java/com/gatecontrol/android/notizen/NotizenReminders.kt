package com.gatecontrol.android.notizen

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * The phone's own alarms for the reminders of tasks (plugin 1.3.0, `reminderActions`; pure, unit-tested): which alarms
 * to set from `GET reminders`, and the rule that keeps a reminder from showing twice.
 *
 * The server's push and the phone's alarm name one reminder by the same `tag` (`task:<id>:<atMs>`) and share one
 * notification (the push's id of the collapse key `task:<id>`): whichever comes first is shown, the other replaces it
 * quietly; a reminder the person decided (a button, swiped away, opened) is not shown again for [ReminderGuard.KEEP_MS].
 */
object ReminderPlan {
    /** Alarms set at a time (the soonest; the daily reload brings the next). Each takes up to two of Android's 500. */
    const val MAX_ALARMS = 60

    /** As the server: a reminder missed by more than this is not shown any more („Gestern offen geblieben“ has it). */
    const val LATE_MAX_MS = 24 * 3_600_000L

    /** Without exact alarms Android may deliver within this window after the time (longer while the phone sleeps). */
    const val WINDOW_MS = 10 * 60_000L

    /** One alarm: the reminder [tag] of the notification [key] (`task:<id>`) at [atMs]. */
    data class Alarm(val key: String, val tag: String, val atMs: Long)

    /** What to set and which earlier alarms ([Alarm.key]s) to take away. */
    data class Plan(val set: List<Alarm>, val cancel: Set<String>)

    /**
     * The alarms for [reminders] at [nowMs]: one per task (its soonest), none for a reminder seen already ([seen]) or
     * missed by more than [LATE_MAX_MS]; one whose time has passed (the phone was off) rings at once. [scheduled]: the
     * keys with an alarm set before — those no longer wanted are cancelled.
     */
    fun plan(reminders: List<NReminderEntry>, scheduled: Set<String>, seen: Map<String, ReminderGuard.Seen>, nowMs: Long, max: Int = MAX_ALARMS): Plan {
        val live = ReminderGuard.pruned(seen, nowMs)
        val set = reminders
            .asSequence()
            .filter { it.tag !in live && it.atMs >= nowMs - LATE_MAX_MS }
            .sortedBy { it.atMs }
            .distinctBy { it.collapseKey }
            .take(max)
            .map { Alarm(it.collapseKey, it.tag, maxOf(it.atMs, nowMs)) }
            .toList()
        val keys = set.map { it.key }.toSet()
        return Plan(set, scheduled - keys)
    }

    /** Android shows three buttons at a notification. */
    const val MAX_BUTTONS = 3

    /**
     * The buttons the phone's alarm shows for [r] — the server's, unchanged and in its order, as far as the phone can
     * do them: „Erledigt“, „Später“ (`snooze_*`), and „Liste öffnen“ (plugin 1.4.0) only while the reminder names the
     * list ([NReminderEntry.attachedList]).
     *
     * Plugin 1.7.0 (`driveDeadlines`): the reminder of a deadline in Drive gets „Datei öffnen“ ([openFile], its text;
     * null: the plugin does not know deadlines — nothing is added) — the server names its three buttons only. Of the
     * [MAX_BUTTONS] Android shows it is the last: „Erledigt“, the first „Später“, „Datei öffnen“; the other „Später“
     * is at the task in the app.
     */
    fun buttons(r: NReminderEntry, openFile: String? = null): List<NReminderAction> {
        val own = r.actions.filter { a ->
            a.id == "done" || ReminderGuard.untilOf(a.id) != null || (a.id == NotizenPush.ACTION_OPEN_LIST && r.attachedList != null)
        }
        if (openFile == null || NotizenPush.fileNode(r.deadline) == null) return own
        return own.take(MAX_BUTTONS - 1) + NReminderAction(NotizenPush.ACTION_OPEN_FILE, openFile)
    }

    /**
     * The person settled task [taskId] on this phone already and it only waits for a connection ([ops], the outbox):
     * ticked off, deleted, or its reminder moved with „Später“ — its alarm stays quiet.
     */
    fun settledOffline(ops: List<NotizenOutbox.Op>, taskId: Long): Boolean = ops.any { o ->
        o.id == taskId && !o.conflict && when (o.kind) {
            NotizenOutbox.TASK -> ops.last { it.kind == NotizenOutbox.TASK && it.id == taskId }.done
            NotizenOutbox.DELETE -> o.what == NotizenOutbox.TASK
            NotizenOutbox.ACTION -> o.what == NotizenOutbox.SNOOZE
            else -> false
        }
    }
}

/** Which reminders this phone has shown or the person has decided — so none shows twice (pure). */
object ReminderGuard {
    /** How long a tag is remembered. */
    const val KEEP_MS = 48 * 3_600_000L
    private const val MAX = 400

    /** The phone's own alarm shows it. */
    const val LOCAL = "local"
    /** The server's push shows it. */
    const val PUSH = "push"
    /** A button was pressed, it was swiped away or opened. */
    const val DECIDED = "decided"
    /**
     * The phone's alarm rang but did not show it (notifications off here, done meanwhile, settled offline): no second
     * alarm for it — a push of it is shown as any push.
     */
    const val SKIPPED = "skipped"

    data class Seen(val state: String, val atMs: Long)

    /** What to do with a push that carries a tag. */
    enum class Push {
        /** Show it as any push. */
        SHOW,
        /** The phone's alarm shows this reminder already: the push takes its place without a second sound. */
        REPLACE,
        /** Decided already: not shown, acknowledged as read. */
        HIDE,
    }

    fun pruned(seen: Map<String, Seen>, nowMs: Long): Map<String, Seen> =
        seen.filterValues { nowMs - it.atMs in 0 until KEEP_MS || it.atMs > nowMs }
            .entries.sortedBy { it.value.atMs }.takeLast(MAX).associate { it.key to it.value }

    /** May the phone's alarm for [tag] show? Not when the push showed it already or it was decided. */
    fun alarmShows(seen: Map<String, Seen>, tag: String, nowMs: Long): Boolean = tag !in pruned(seen, nowMs)

    fun onPush(seen: Map<String, Seen>, tag: String, nowMs: Long): Push = when (pruned(seen, nowMs)[tag]?.state) {
        DECIDED -> Push.HIDE
        LOCAL -> Push.REPLACE
        else -> Push.SHOW
    }

    /** [tag] is shown by [source] ([LOCAL] / [PUSH]) — a decided one stays decided. */
    fun shown(seen: Map<String, Seen>, tag: String, source: String, nowMs: Long): Map<String, Seen> {
        val live = pruned(seen, nowMs)
        return if (live[tag]?.state == DECIDED) live else live + (tag to Seen(source, nowMs))
    }

    fun decided(seen: Map<String, Seen>, tag: String, nowMs: Long): Map<String, Seen> = pruned(seen, nowMs) + (tag to Seen(DECIDED, nowMs))

    /** The alarm of [tag] rang without showing it ([SKIPPED]) — what is known of the tag already stays. */
    fun skipped(seen: Map<String, Seen>, tag: String, nowMs: Long): Map<String, Seen> {
        val live = pruned(seen, nowMs)
        return if (tag in live) live else live + (tag to Seen(SKIPPED, nowMs))
    }

    /**
     * [nowMs] lies in the person's quiet hours [from]–[to] (`HH:MM`, over midnight when [to] is not after [from]) in
     * [zone] (unknown or null: the phone's). The server sends a push silently then; the phone's alarm does the same.
     */
    fun inQuietHours(from: String, to: String, zone: String?, nowMs: Long): Boolean {
        val f = minutes(from) ?: return false
        val t = minutes(to) ?: return false
        if (f == t) return false
        val z = zone?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() } ?: java.time.ZoneId.systemDefault()
        val now = java.time.Instant.ofEpochMilli(nowMs).atZone(z).let { it.hour * 60 + it.minute }
        return if (f < t) now in f until t else now >= f || now < t
    }

    private fun minutes(hhmm: String): Int? = NotizenJson.time(hhmm)?.let { it.substring(0, 2).toInt() * 60 + it.substring(3).toInt() }?.takeIf { it < 24 * 60 }

    /**
     * Plugin 1.7.0, Drive 1.30.0: the phone's alarm showed the reminder of Drive's deadline [deadlineId] — kept for
     * [DRIVE_TWIN_MS], so Drive's own message of the same deadline is not shown beside it ([driveTwin]).
     */
    fun deadlineShown(shown: Map<Long, Long>, deadlineId: Long, nowMs: Long): Map<Long, Long> =
        shown.filterValues { nowMs - it in 0 until DRIVE_TWIN_MS }.entries.sortedBy { it.value }.takeLast(MAX - 1).associate { it.key to it.value } + (deadlineId to nowMs)

    /**
     * Drive's own message of deadline [deadlineId] (topic `deadline`) would be a second one: the phone's alarm of the
     * hub showed that deadline within the last [DRIVE_TWIN_MS] (the hub could not be asked at that moment, so Drive
     * sent it itself).
     */
    fun driveTwin(shown: Map<Long, Long>, deadlineId: Long?, nowMs: Long): Boolean =
        deadlineId != null && shown[deadlineId]?.let { nowMs - it in 0 until DRIVE_TWIN_MS } == true

    const val DRIVE_TWIN_MS = 24 * 3_600_000L

    /** `deadline:<n>` (`Task.source.id` of a task that reminds of Drive's deadline n) → n; null: not one. */
    fun deadlineId(source: NSourceRef?): Long? =
        source?.takeIf { it.plugin == NotizenOpen.DRIVE }?.let { Regex("^deadline:([1-9]\\d{0,14})$").matchEntire(it.id)?.groupValues?.get(1)?.toLongOrNull() }

    /** The reminder a button of the phone's alarm asks for: `done`, or „Später“ `until` of `snooze_*`; null: unknown. */
    fun untilOf(actionId: String): String? = when (actionId) {
        "snooze_1h" -> "1h"
        "snooze_eve" -> "evening"
        "snooze_tom" -> "tomorrow"
        else -> null
    }
}

/** What the phone keeps of `GET reminders` (to ring after a boot and without a connection), its alarms and [ReminderGuard]'s tags. */
data class ReminderState(
    val reminders: List<NReminderEntry> = emptyList(),
    /** The [ReminderPlan.Alarm.key]s with an alarm set. */
    val alarms: Set<String> = emptySet(),
    val seen: Map<String, ReminderGuard.Seen> = emptyMap(),
    /** Plugin 1.7.0: Drive's deadlines (their ids) the phone's alarm showed, and when ([ReminderGuard.driveTwin]). */
    val deadlines: Map<Long, Long> = emptyMap(),
) {
    fun toJson() = JsonObject().apply {
        add("reminders", JsonArray().apply {
            reminders.forEach { r ->
                add(JsonObject().apply {
                    addProperty("tag", r.tag)
                    addProperty("taskId", r.taskId)
                    r.listId?.let { addProperty("listId", it) }
                    r.area?.let { addProperty("area", it) }
                    addProperty("title", r.title)
                    addProperty("body", r.body)
                    addProperty("atMs", r.atMs)
                    addProperty("important", r.important)
                    addProperty("collapseKey", r.collapseKey)
                    add("actions", JsonArray().apply { r.actions.forEach { a -> add(JsonObject().apply { addProperty("id", a.id); addProperty("label", a.label) }) } })
                    r.attachedList?.let { l ->
                        add("attachedList", JsonObject().apply {
                            addProperty("id", l.id); addProperty("uid", l.uid); addProperty("name", l.name)
                            addProperty("open", l.open); addProperty("total", l.total)
                        })
                    }
                    r.deadline?.let { d ->
                        add("deadline", JsonObject().apply {
                            d.date?.let { addProperty("date", it) }
                            d.kind?.let { addProperty("kind", it) }
                            d.file?.let { addProperty("file", it) }
                            addProperty("daysBefore", d.daysBefore)
                            d.open?.let { o -> add("open", JsonObject().apply { addProperty("plugin", o.plugin); addProperty("route", o.route) }) }
                        })
                    }
                })
            }
        })
        add("alarms", JsonArray().apply { alarms.forEach { add(it) } })
        add("seen", JsonObject().apply { seen.forEach { (k, v) -> add(k, JsonObject().apply { addProperty("state", v.state); addProperty("at", v.atMs) }) } })
        if (deadlines.isNotEmpty()) add("deadlines", JsonObject().apply { deadlines.forEach { (k, v) -> addProperty(k.toString(), v) } })
    }

    companion object {
        private val KEY = Regex("^task:[1-9]\\d{0,14}$")
        private val STATES = setOf(ReminderGuard.LOCAL, ReminderGuard.PUSH, ReminderGuard.DECIDED, ReminderGuard.SKIPPED)

        /** Tolerant: what cannot be read is left out. */
        fun fromJson(o: JsonObject?): ReminderState {
            if (o == null) return ReminderState()
            val reminders = NotizenJson.reminders(o)?.reminders.orEmpty()
            val alarms = runCatching { o.getAsJsonArray("alarms")?.mapNotNull { e -> runCatching { e.asString }.getOrNull()?.takeIf { KEY.matches(it) } }?.toSet() }.getOrNull().orEmpty()
            val seen = runCatching {
                o.getAsJsonObject("seen")?.entrySet()?.mapNotNull { (k, v) ->
                    val s = v.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    val tag = NotizenJson.reminderTag(k) ?: return@mapNotNull null
                    val state = runCatching { s.get("state").asString }.getOrNull()?.takeIf { it in STATES } ?: return@mapNotNull null
                    tag to ReminderGuard.Seen(state, runCatching { s.get("at").asLong }.getOrNull() ?: return@mapNotNull null)
                }?.toMap()
            }.getOrNull().orEmpty()
            val deadlines = runCatching {
                o.getAsJsonObject("deadlines")?.entrySet()?.mapNotNull { (k, v) ->
                    (k.toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null) to (runCatching { v.asLong }.getOrNull() ?: return@mapNotNull null)
                }?.toMap()
            }.getOrNull().orEmpty()
            return ReminderState(reminders, alarms, seen, deadlines)
        }
    }
}

/** The card „Gestern offen geblieben“ of „Mein Tag“ (plugin 1.3.0, `yesterdayOpen`; pure, unit-tested). */
object NotizenYesterday {
    /** The card to show: the open tasks of `yesterday`; null without the feature or with nothing left to decide. */
    fun card(view: NView?, enabled: Boolean): NYesterday? {
        val y = view?.yesterday?.takeIf { enabled } ?: return null
        val items = y.items.filter { !it.done }
        return if (items.isEmpty()) null else y.copy(items = items, count = items.size)
    }

    /** The sections of „Mein Tag“ beside [card]: its tasks are in `overdue` as well (for older apps) — not twice here. */
    fun sections(view: NView, card: NYesterday?): List<NSection> {
        if (card == null) return view.sections
        val uids = card.items.map { it.uid }.toSet()
        return view.sections.map { s -> if (s.id == "overdue") s.copy(items = s.items.filterNot { it.uid in uids }) else s }
    }

    /** [view] without the tasks [ids] in the card and under „Überfällig“ (decided just now, before the server answers). */
    fun without(view: NView, ids: Set<Long>): NView {
        if (ids.isEmpty()) return view
        val gone = { e: NEntry -> e is NTask && e.id in ids }
        return view.copy(
            yesterday = view.yesterday?.let { y -> y.items.filterNot(gone).let { y.copy(items = it, count = it.size) } },
            sections = view.sections.map { s -> if (s.id == "overdue") s.copy(items = s.items.filterNot(gone)) else s },
        )
    }

    /** The tasks a button of the morning hint means: the push's [ids] still open ([open]: all areas; null: not known). */
    fun asked(ids: List<Long>, open: List<Long>?): List<Long> = when {
        open == null -> ids
        ids.isEmpty() -> open
        else -> ids.filter { it in open }
    }
}
