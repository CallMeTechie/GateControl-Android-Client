package com.gatecontrol.android.notizen

import com.gatecontrol.android.navigation.NotizenRoutes
import com.gatecontrol.android.notify.model.PushMessage
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where a tap on a push of „Notizen & Aufgaben“ leads (pure, unit-tested). Plugin 1.1.0 (README "Pushes") sends
 * `data.notizen = { kind, area, taskId | listId | notebookId | vaultId, … }`; 1.0 sent `{ task | list | notebook |
 * vault }` only — both are read:
 * - `reminder` (topic `reminders`), `assigned` (topic `shared`) → the task;
 * - `place_consent` (plugin 1.5.0, topic `shared`, „… möchte dich an einem Ort erinnern“) → the task; its buttons
 *   „Annehmen“ / „Ablehnen“ are callbacks GateControl sends to the plugin;
 * - `shared_list` → the list; `shared_notebook` → the notebook (a Tresor: its page, which unlocks first);
 * - `vault_request` (topic `vault`) → the Tresor's page with the device to approve (unlocked first);
 * - `listday` (plugin 1.2.0, topic `listday`, „Heute ist Einkaufstag“) → the list, in its area;
 * - `done_feedback` (plugin 1.6.0, topic `shared`, „… hat … erledigt“) and `reminder_done` (topic `reminders`, „Erledigt
 *   von …“ for the others of „wer zuerst“) → the task;
 * - `yesterday` (plugin 1.3.0, topic `reminders`, „Gestern offen geblieben“) → „Mein Tag“ with the card; its buttons
 *   „Erledigt“ (several tasks: `done_ask`) and „Verschieben“ (`move`) open the same, with the question or the choice.
 *
 * The destination travels as text in the notification's intent: `task:<id>`, `list:<id>`, `notebook:<id>`,
 * `tresor:<notebookId>:<vaultId>`, `vault:<vaultId>`, `yesterday:<open|done|move>[:<id>-<id>…]`, `shop:<list uid>`
 * (plugin 1.4.0: „Liste öffnen“ of a reminder whose task carries a shopping list), `file:<node>:<task id>` (plugin
 * 1.7.0: „Datei öffnen“ of the reminder of a deadline in Drive), each with `@private` / `@work` when the push names
 * the area.
 */
object NotizenPush {
    private const val TOPIC_PREFIX = "plugin:$NOTIZEN_PLUGIN:"
    const val TOPIC_REMINDERS = TOPIC_PREFIX + "reminders"
    const val TOPIC_SHARED = TOPIC_PREFIX + "shared"
    const val TOPIC_VAULT = TOPIC_PREFIX + "vault"
    const val TOPIC_LISTDAY = TOPIC_PREFIX + "listday"

    sealed interface Target { val area: String? }
    data class Task(val id: Long, override val area: String? = null) : Target
    data class ListPage(val id: Long, override val area: String? = null) : Target
    data class Notebook(val id: Long, override val area: String? = null) : Target
    /** A shared Tresor notebook: its page (unlock, then its notes). */
    data class Tresor(val notebookId: Long, val vaultId: String, override val area: String? = null) : Target
    /** A device asks for a Tresor: its page with the requests (unlock first). */
    data class VaultRequest(val vaultId: String, override val area: String? = null) : Target
    /**
     * „Gestern offen geblieben“: „Mein Tag“ with the card. [action]: [Y_OPEN] (the message was tapped), [Y_DONE] (ask
     * once „Alle N abhaken?“) or [Y_MOVE] (the choice of days) for the tasks [ids] of the message (at most 20).
     */
    data class Yesterday(val action: String, val ids: List<Long>, override val area: String? = null) : Target
    /** Plugin 1.4.0: the shopping list a reminded task carries (the button „Liste öffnen“), to tick off. */
    data class Shopping(val uid: String, override val area: String? = null) : Target

    /**
     * Plugin 1.7.0 (`driveDeadlines`): the file in Drive the deadline of the reminded task [taskId] hangs on (the button
     * „Datei öffnen“) — its page in the Drive tab; without that tab the task.
     */
    data class File(val node: Long, val taskId: Long, override val area: String? = null) : Target

    /** The button of a reminder with a list: not a callback — the app opens the list itself. */
    const val ACTION_OPEN_LIST = "open_list"

    /** The button of the reminder of a deadline in Drive (plugin 1.7.0): the server sends a portal link — the app has the file's page. */
    const val ACTION_OPEN_FILE = "open_file"

    const val Y_OPEN = "open"
    const val Y_DONE = "done"
    const val Y_MOVE = "move"
    private const val Y_MAX = 20

    fun isNotizenMessage(message: PushMessage): Boolean = message.topic.startsWith(TOPIC_PREFIX)

    /** The destination of a push of this plugin (see the class); null for other messages or one that names nothing. */
    fun destination(message: PushMessage): String? {
        if (!isNotizenMessage(message)) return null
        val n = runCatching { JsonParser.parseString(message.dataJson ?: return null).asJsonObject.get("notizen") }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return target(message.topic, n)?.let { encode(it) }
    }

    /** `data.notizen` of [topic] → what to open. */
    fun target(topic: String, n: JsonObject): Target? {
        val area = n.str("area")?.takeIf { it == NArea.PRIVATE || it == NArea.WORK }
        val task = n.id("taskId") ?: n.id("task")
        val list = n.id("listId") ?: n.id("list")
        val notebook = n.id("notebookId") ?: n.id("notebook")
        val vault = NotizenJson.vaultId(n.str("vaultId") ?: n.str("vault"))
        return when (n.str("kind")) {
            "yesterday" -> Yesterday(Y_OPEN, ids(n), area)
            // `place_consent` (plugin 1.5.0, topic `shared`): „… möchte dich erinnern“ — the task, with Annehmen / Ablehnen
            // `done_feedback` (plugin 1.6.0, topic `shared`): „Lena hat … erledigt“ — „Ansehen“ opens the task, „Noch nicht
            // fertig“ is a callback GateControl sends to the plugin; `reminder_done` (topic `reminders`, „Erledigt von
            // Lena“): takes the place of the reminder of „wer zuerst“ (the same collapse key), without buttons
            "reminder", "assigned", "place_consent", "done_feedback", "reminder_done" -> task?.let { Task(it, area) }
            "shared_list", "listday" -> list?.let { ListPage(it, area) }
            "shared_notebook" -> notebook?.let { nb -> vault?.let { Tresor(nb, it, area) } ?: Notebook(nb, area) }
            "vault_request" -> vault?.let { VaultRequest(it, area) }
            // plugin 1.0: no kind — the topic and the key say it
            else -> when (topic) {
                TOPIC_VAULT -> vault?.let { VaultRequest(it, area) }
                TOPIC_LISTDAY -> list?.let { ListPage(it, area) }
                TOPIC_REMINDERS -> task?.let { Task(it, area) }
                TOPIC_SHARED -> task?.let { Task(it, area) } ?: list?.let { ListPage(it, area) } ?: notebook?.let { Notebook(it, area) }
                else -> null
            }
        }
    }

    private fun ids(n: JsonObject): List<Long> = n.get("taskIds")?.takeIf { it.isJsonArray }?.asJsonArray
        ?.mapNotNull { e -> runCatching { e.asJsonPrimitive.takeIf { it.isNumber }?.asLong }.getOrNull()?.takeIf { it > 0 } }?.distinct()?.take(Y_MAX).orEmpty()

    /**
     * The reminder a push is about (`task:<id>:<atMs>`, plugin 1.3.0) — the name it shares with the phone's own alarm;
     * null for every other message (also the reminders of an older plugin, which carry none).
     */
    fun reminderTag(message: PushMessage): String? {
        if (message.topic != TOPIC_REMINDERS) return null
        val n = runCatching { JsonParser.parseString(message.dataJson ?: return null).asJsonObject.get("notizen") }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        if (n.str("kind") != "reminder") return null
        return NotizenJson.reminderTag(n.str("tag"))
    }

    /** The task a push `reminder_done` (plugin 1.6.0) is about: someone else did it — the phone's own alarm for it goes. */
    fun doneElsewhere(message: PushMessage): Long? {
        if (message.topic != TOPIC_REMINDERS) return null
        val n = runCatching { JsonParser.parseString(message.dataJson ?: return null).asJsonObject.get("notizen") }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        if (n.str("kind") != "reminder_done") return null
        return n.id("taskId") ?: n.id("task")
    }

    /**
     * Where „Liste öffnen“ of a reminder leads (plugin 1.4.0, `data.notizen.attachedList.uid`); null: the push carries
     * no list (GateControl's route then opens the tab, as for an app that does not know lists).
     */
    fun listDestination(message: PushMessage): String? {
        if (message.topic != TOPIC_REMINDERS) return null
        val n = runCatching { JsonParser.parseString(message.dataJson ?: return null).asJsonObject.get("notizen") }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val uid = NotizenJson.attached(n.get("attachedList")?.takeIf { it.isJsonObject }?.asJsonObject)?.uid ?: return null
        return encode(Shopping(uid, n.str("area")?.takeIf { it == NArea.PRIVATE || it == NArea.WORK }))
    }

    /** The destination of the button [actionId] of a push whose list is [listDest]: the list for „Liste öffnen“, else null. */
    fun listButton(listDest: String?, actionId: String?): String? =
        listDest?.takeIf { actionId == ACTION_OPEN_LIST && decode(it) is Shopping }

    /** The file's node in Drive of [deadline] (`open { plugin: gatecontrol-drive, route: node/<id> }`); null: none the app can open. */
    fun fileNode(deadline: NDeadline?): Long? =
        (NotizenOpen.target(deadline?.open, NotizenOpen.Have(mail = false, drive = true, familie = false, schule = false)) as? NotizenOpen.DriveFile)?.id

    /**
     * Where „Datei öffnen“ of the reminder of a deadline leads (plugin 1.7.0, `data.notizen.deadline.open`); null: the
     * push carries none (the button then opens the portal, as for an app that does not know deadlines).
     */
    fun fileDestination(message: PushMessage): String? {
        if (message.topic != TOPIC_REMINDERS) return null
        val n = runCatching { JsonParser.parseString(message.dataJson ?: return null).asJsonObject.get("notizen") }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        if (n.str("kind") != "reminder") return null
        val node = fileNode(NotizenJson.deadline(n.get("deadline")?.takeIf { it.isJsonObject }?.asJsonObject)) ?: return null
        val task = n.id("taskId") ?: n.id("task") ?: return null
        return encode(File(node, task, n.str("area")?.takeIf { it == NArea.PRIVATE || it == NArea.WORK }))
    }

    /** The destination of the button [actionId] of a push whose file is [fileDest]: the file for „Datei öffnen“, else null. */
    fun fileButton(fileDest: String?, actionId: String?): String? =
        fileDest?.takeIf { actionId == ACTION_OPEN_FILE && decode(it) is File }

    /**
     * The buttons of [message] for a notification with room for [max]: the push of a deadline brings four — „Erledigt“,
     * two „Später“ and „Datei öffnen“ (plugin 1.7.0). Android shows three: „Erledigt“, the first „Später“ and „Datei
     * öffnen“, as the phone's own alarm shows them ([ReminderPlan.buttons]). Every other message: its buttons, unchanged.
     */
    fun buttons(message: PushMessage, max: Int = ReminderPlan.MAX_BUTTONS): List<com.gatecontrol.android.notify.model.PushAction> {
        val actions = message.actions
        val file = actions.firstOrNull { it.id == ACTION_OPEN_FILE }
        if (file == null || actions.size <= max || actions.indexOf(file) < max || fileDestination(message) == null) return actions
        return actions.filter { it !== file }.take(max - 1) + file
    }

    /**
     * The destination of a button of the morning hint that opens the app: `done_ask` → the card with the question,
     * `move` → with the choice of days. null: not such a button (GateControl sends them as a portal link — the app has
     * the screen itself).
     */
    fun yesterdayButton(dest: String?, actionId: String?): String? {
        val y = decode(dest) as? Yesterday ?: return null
        return when (actionId) {
            "done_ask" -> encode(y.copy(action = Y_DONE))
            "move" -> encode(y.copy(action = Y_MOVE))
            else -> null
        }
    }

    fun encode(t: Target): String = when (t) {
        is Task -> "task:${t.id}"
        is ListPage -> "list:${t.id}"
        is Notebook -> "notebook:${t.id}"
        is Tresor -> "tresor:${t.notebookId}:${t.vaultId}"
        is VaultRequest -> "vault:${t.vaultId}"
        is Yesterday -> "yesterday:${t.action}" + if (t.ids.isEmpty()) "" else ":" + t.ids.take(Y_MAX).joinToString("-")
        is Shopping -> "shop:${t.uid}"
        is File -> "file:${t.node}:${t.taskId}"
    } + (t.area?.let { "@$it" } ?: "")

    private val YDEST = Regex("^yesterday:(open|done|move)(?::([1-9]\\d{0,14}(?:-[1-9]\\d{0,14}){0,19}))?(?:@(private|work))?$")

    private val DEST = Regex("^(task|list|notebook|tresor|vault|shop|file):([A-Za-z0-9_:-]{1,80})(?:@(private|work))?$")

    /** A destination of [destination] again; null when it is not one. */
    fun decode(dest: String?): Target? {
        YDEST.matchEntire(dest ?: return null)?.let { y ->
            val ids = y.groupValues[2].split('-').mapNotNull { it.toLongOrNull() }
            return Yesterday(y.groupValues[1], ids, y.groupValues[3].takeIf { it.isNotEmpty() })
        }
        val m = DEST.matchEntire(dest) ?: return null
        val area = m.groupValues[3].takeIf { it.isNotEmpty() }
        val rest = m.groupValues[2]
        val id = rest.toLongOrNull()?.takeIf { it > 0 }
        return when (m.groupValues[1]) {
            "task" -> id?.let { Task(it, area) }
            "list" -> id?.let { ListPage(it, area) }
            "notebook" -> id?.let { Notebook(it, area) }
            "shop" -> NotizenJson.listUid(rest)?.let { Shopping(it, area) }
            "file" -> {
                val node = rest.substringBefore(':').toLongOrNull()?.takeIf { it > 0 } ?: return null
                rest.substringAfter(':', "").toLongOrNull()?.takeIf { it > 0 }?.let { File(node, it, area) }
            }
            "tresor" -> {
                val nb = rest.substringBefore(':').toLongOrNull()?.takeIf { it > 0 } ?: return null
                NotizenJson.vaultId(rest.substringAfter(':', ""))?.let { Tresor(nb, it, area) }
            }
            else -> NotizenJson.vaultId(rest)?.let { VaultRequest(it, area) }
        }
    }

    /** One step of opening a push's page. */
    sealed interface Step
    /** The tab „Aufgaben“ (without the plugin, not licensed or not allowed: it says so itself). */
    data object Tab : Step
    data class Route(val route: String) : Step

    /**
     * The steps for [dest]: always the tab first (so Back leads to it, and on a cold start it is there), then the page.
     * [listed]: the plugin is in the app's plugin list (installed, licensed, allowed) — without it only the tab.
     */
    fun steps(dest: String?, listed: Boolean): List<Step> {
        val t = decode(dest)
        if (!listed || t == null) return listOf(Tab)
        return listOf(Tab) + when (t) {
            is Task -> listOf(Route(NotizenRoutes.task(t.id)))
            is ListPage -> listOf(Route(NotizenRoutes.view("list:${t.id}")))
            // a plain notebook: the tab's notes with that notebook (NotizenDeepLink)
            is Notebook -> emptyList()
            is Tresor -> listOf(Route(NotizenRoutes.vault(t.vaultId)))
            is VaultRequest -> listOf(Route(NotizenRoutes.vault(t.vaultId, requests = true)))
            // „Mein Tag“ of the tab, with the card (NotizenDeepLink)
            is Yesterday -> emptyList()
            is Shopping -> listOf(Route(NotizenRoutes.shopping(t.uid)))
            // without the Drive tab (else [driveFile] leads there): the task, which names the file
            is File -> listOf(Route(NotizenRoutes.task(t.taskId)))
        }
    }

    /** [dest] is „Datei öffnen“ of a deadline's reminder → the file's node in Drive (the app opens its page there). */
    fun driveFile(dest: String?): Long? = (decode(dest) as? File)?.node

    private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.id(k: String): Long? = get(k)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString.trim().toLong() }.getOrNull() }?.takeIf { it > 0 }
}

/**
 * What a push hands to the tab (the area to switch to, the notebook to show) and a short hint for the page the person
 * comes back to (`gone`: the task or list of a push is not there any more).
 */
object NotizenDeepLink {
    data class Open(val area: String?, val notebook: Long?, val yesterday: NotizenPush.Yesterday? = null)

    private val _open = MutableStateFlow<Open?>(null)
    val open: StateFlow<Open?> = _open.asStateFlow()

    private val _hint = MutableStateFlow<String?>(null)
    val hint: StateFlow<String?> = _hint.asStateFlow()

    /** Before the tab opens for [t]. */
    fun request(t: NotizenPush.Target?) {
        if (t == null) return
        _open.value = Open(t.area, (t as? NotizenPush.Notebook)?.id ?: (t as? NotizenPush.Tresor)?.notebookId, t as? NotizenPush.Yesterday)
    }

    fun take(): Open? = _open.value.also { _open.value = null }

    fun hint(code: String) { _hint.value = code }

    fun takeHint(): String? = _hint.value.also { _hint.value = null }
}
