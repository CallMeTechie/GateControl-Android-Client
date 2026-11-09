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
 * - `shared_list` → the list; `shared_notebook` → the notebook (a Tresor: its page, which unlocks first);
 * - `vault_request` (topic `vault`) → the Tresor's page with the device to approve (unlocked first);
 * - `listday` (plugin 1.2.0, topic `listday`, „Heute ist Einkaufstag“) → the list, in its area.
 *
 * The destination travels as text in the notification's intent: `task:<id>`, `list:<id>`, `notebook:<id>`,
 * `tresor:<notebookId>:<vaultId>`, `vault:<vaultId>`, each with `@private` / `@work` when the push names the area.
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
            "reminder", "assigned" -> task?.let { Task(it, area) }
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

    fun encode(t: Target): String = when (t) {
        is Task -> "task:${t.id}"
        is ListPage -> "list:${t.id}"
        is Notebook -> "notebook:${t.id}"
        is Tresor -> "tresor:${t.notebookId}:${t.vaultId}"
        is VaultRequest -> "vault:${t.vaultId}"
    } + (t.area?.let { "@$it" } ?: "")

    private val DEST = Regex("^(task|list|notebook|tresor|vault):([A-Za-z0-9_:-]{1,80})(?:@(private|work))?$")

    /** A destination of [destination] again; null when it is not one. */
    fun decode(dest: String?): Target? {
        val m = DEST.matchEntire(dest ?: return null) ?: return null
        val area = m.groupValues[3].takeIf { it.isNotEmpty() }
        val rest = m.groupValues[2]
        val id = rest.toLongOrNull()?.takeIf { it > 0 }
        return when (m.groupValues[1]) {
            "task" -> id?.let { Task(it, area) }
            "list" -> id?.let { ListPage(it, area) }
            "notebook" -> id?.let { Notebook(it, area) }
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
        }
    }

    private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.id(k: String): Long? = get(k)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString.trim().toLong() }.getOrNull() }?.takeIf { it > 0 }
}

/**
 * What a push hands to the tab (the area to switch to, the notebook to show) and a short hint for the page the person
 * comes back to (`gone`: the task or list of a push is not there any more).
 */
object NotizenDeepLink {
    data class Open(val area: String?, val notebook: Long?)

    private val _open = MutableStateFlow<Open?>(null)
    val open: StateFlow<Open?> = _open.asStateFlow()

    private val _hint = MutableStateFlow<String?>(null)
    val hint: StateFlow<String?> = _hint.asStateFlow()

    /** Before the tab opens for [t]. */
    fun request(t: NotizenPush.Target?) {
        if (t == null) return
        _open.value = Open(t.area, (t as? NotizenPush.Notebook)?.id ?: (t as? NotizenPush.Tresor)?.notebookId)
    }

    fun take(): Open? = _open.value.also { _open.value = null }

    fun hint(code: String) { _hint.value = code }

    fun takeHint(): String? = _hint.value.also { _hint.value = null }
}
