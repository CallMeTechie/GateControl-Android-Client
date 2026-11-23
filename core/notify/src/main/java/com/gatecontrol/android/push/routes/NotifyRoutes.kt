package com.gatecontrol.android.push.routes

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Where a kind of notification (a push topic) appears (core 1.191,
 * `GET/PUT /api/v1/client/push/routes`): the pinned chat "GateControl"
 * (the inbox, default), the family chat (for the whole household) or an
 * own notice chat of its source.
 */
enum class NotifyDest(val wire: String) {
    INBOX("inbox"),
    OWN("own"),
    FAMILY("family"),
    ;

    companion object {
        fun of(wire: String?): NotifyDest? = entries.firstOrNull { it.wire == wire }
    }
}

/** One topic: [dest] as the server has it; [locked] = always GateControl; [familySetBy] who chose the family chat. */
data class NotifyRouteTopic(
    val id: String,
    val label: String,
    val dest: NotifyDest,
    val locked: Boolean,
    val familySetBy: String?,
)

/** The topics of one source (`core` or `plugin:<id>`), [label] its name ("Schule"). */
data class NotifyRouteGroup(val source: String, val label: String, val topics: List<NotifyRouteTopic>)

/**
 * The own chat of a source that a change to "own" made sure of (core 1.192,
 * `chat` of the PUT answer): [created] = just created, false = an existing
 * one is used. [conversationId] null when the server sent none usable.
 */
data class NotifyRouteChat(
    val source: String?,
    val label: String,
    val conversationId: Long?,
    val created: Boolean,
)

/**
 * The answer: [sink] = a plugin delivers notifications into chats (the
 * messenger) — without it everything stays in GateControl; [canFamily] =
 * this person may choose the family chat (admins and parents).
 */
data class NotifyRoutes(val sink: Boolean, val canFamily: Boolean, val groups: List<NotifyRouteGroup>) {

    fun topic(id: String): NotifyRouteTopic? = groups.firstNotNullOfOrNull { g -> g.topics.firstOrNull { it.id == id } }

    fun groupOf(topicId: String): NotifyRouteGroup? = groups.firstOrNull { g -> g.topics.any { it.id == topicId } }

    /** [topicId] now goes to [dest] (the change shown before the server answered). */
    fun with(topicId: String, dest: NotifyDest): NotifyRoutes = copy(
        groups = groups.map { g ->
            g.copy(topics = g.topics.map { t -> if (t.id == topicId) t.copy(dest = dest, familySetBy = if (dest == NotifyDest.FAMILY) t.familySetBy else null) else t })
        },
    )

    /** Where [t] really ends up: without a sink everything stays in GateControl. */
    fun effective(t: NotifyRouteTopic): NotifyDest = if (!sink || t.locked) NotifyDest.INBOX else t.dest

    /**
     * The choices for [t]: GateControl always; the family chat for those who
     * may set it (or shown, not changeable, while it is set); the own chat
     * with a sink.
     */
    fun options(t: NotifyRouteTopic): List<NotifyDest> = when {
        t.locked || !sink -> listOf(NotifyDest.INBOX)
        else -> listOfNotNull(
            NotifyDest.INBOX,
            NotifyDest.FAMILY.takeIf { canFamily || t.dest == NotifyDest.FAMILY },
            NotifyDest.OWN,
        )
    }

    /** [t] can be changed by this person (not locked, a sink, and no family choice of someone else they may not lift). */
    fun editable(t: NotifyRouteTopic): Boolean = !t.locked && sink && (t.dest != NotifyDest.FAMILY || canFamily)
}

/**
 * Parses the routes answer from the Gson tree (R8-safe, no reflection).
 * Everything the server sends is untrusted: ids checked, labels plain and
 * capped; a malformed topic or group is dropped, a malformed answer null.
 * Pure, unit-tested.
 */
object NotifyRoutesJson {

    /** `security`, `admin_notice`, … and `plugin:<id>:<topic>` (as the push topics). */
    private val TOPIC_RE = Regex("^(?:[a-z][a-z0-9_]{0,31}|plugin:[a-z0-9]+(?:-[a-z0-9]+)*:[a-z][a-z0-9_-]{0,31})$")
    private val SOURCE_RE = Regex("^(?:core|plugin:[a-z0-9]+(?:-[a-z0-9]+)*)$")
    private val CONTROL_RE = Regex("[\\u0000-\\u001F\\u007F]")
    private val ERROR_RE = Regex("^[a-z][a-z0-9_]{0,39}$")
    private val ID_RE = Regex("^[0-9]{1,18}$")
    private const val MAX_GROUPS = 64
    private const val MAX_TOPICS = 64

    /** Topics that always stay in GateControl, whatever the server says. */
    val ALWAYS_INBOX: Set<String> = setOf("security", "admin_notice")

    fun parse(raw: String?): NotifyRoutes? = raw?.let { s ->
        runCatching { JsonParser.parseString(s) }.getOrNull()?.objOrNull()?.let { parse(it) }
    }

    fun parse(o: JsonObject?): NotifyRoutes? {
        if (o == null) return null
        val groupsEl = o.get("groups")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val seen = HashSet<String>()
        val groups = groupsEl.mapNotNull { e ->
            val g = e.objOrNull() ?: return@mapNotNull null
            val source = g.str("source")?.takeIf { SOURCE_RE.matches(it) } ?: return@mapNotNull null
            val label = line(g.str("label"), 60) ?: if (source == "core") "GateControl" else source.removePrefix("plugin:")
            val topics = g.get("topics")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { te ->
                val t = te.objOrNull() ?: return@mapNotNull null
                val id = t.str("id")?.takeIf { TOPIC_RE.matches(it) && seen.add(it) } ?: return@mapNotNull null
                val locked = t.flag("locked") == true || id in ALWAYS_INBOX
                NotifyRouteTopic(
                    id = id,
                    label = line(t.str("label"), 80) ?: id.substringAfterLast(':'),
                    dest = if (locked) NotifyDest.INBOX else NotifyDest.of(t.str("dest")) ?: NotifyDest.INBOX,
                    locked = locked,
                    familySetBy = line(t.str("familySetBy"), 80),
                )
            }?.take(MAX_TOPICS).orEmpty()
            if (topics.isEmpty()) null else NotifyRouteGroup(source, label, topics)
        }.take(MAX_GROUPS)
        return NotifyRoutes(sink = o.flag("sink") == true, canFamily = o.flag("canFamily") == true, groups = groups)
    }

    /**
     * `chat` of the PUT answer (core 1.192): `{ source, label, conversationId, created }`
     * or null — missing for inbox / family, on older servers (1.191) or when it failed.
     */
    fun chat(o: JsonObject?): NotifyRouteChat? {
        val c = o?.get("chat")?.objOrNull() ?: return null
        val source = c.str("source")?.takeIf { SOURCE_RE.matches(it) }
        val label = line(c.str("label"), 60)
            ?: source?.let { if (it == "core") "GateControl" else it.removePrefix("plugin:") }
            ?: return null
        val id = c.get("conversationId")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.let { p ->
            when {
                p.isString -> p.asString.trim().takeIf { ID_RE.matches(it) }?.toLongOrNull()
                p.isNumber -> p.asString.takeIf { ID_RE.matches(it) }?.toLongOrNull()
                else -> null
            }
        }?.takeIf { it > 0 }
        return NotifyRouteChat(source = source, label = label, conversationId = id, created = c.flag("created") == true)
    }

    /** Body of the PUT. */
    fun body(topic: String, dest: NotifyDest): JsonObject = JsonObject().apply {
        addProperty("topic", topic)
        addProperty("dest", dest.wire)
    }

    /** `{ error }` (or `code`) of a refusal: `family_not_allowed`, `locked`, `no_sink`, … — null when none. */
    fun errorCode(raw: String?): String? {
        val o = raw?.let { runCatching { JsonParser.parseString(it) }.getOrNull()?.objOrNull() } ?: return null
        return (o.str("error") ?: o.str("code"))?.trim()?.lowercase()?.takeIf { ERROR_RE.matches(it) }
    }

    private fun JsonElement.objOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.flag(key: String): Boolean? = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.let { p ->
        when {
            p.isBoolean -> p.asBoolean
            p.isNumber -> p.asDouble != 0.0
            else -> null
        }
    }

    private fun line(value: String?, max: Int): String? = value?.let { CONTROL_RE.replace(it, " ").trim().take(max).takeIf { s -> s.isNotBlank() } }
}
