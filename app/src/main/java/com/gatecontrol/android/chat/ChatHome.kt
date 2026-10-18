package com.gatecontrol.android.chat

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Who may approve a door request (Messenger C2 `approvers`). */
object ChatApprovers {
    const val PARENTS = "parents"
    const val RIGHTS = "rights"
}

/**
 * The approvers of a request: a keyword ([ChatApprovers]) or certain people
 * ([userIds], "Nur Marc").
 */
data class ChatApproverChoice(val keyword: String? = ChatApprovers.RIGHTS, val userIds: List<Long> = emptyList())

/**
 * What the last doorbell did, from the door plugin's `GET me` (Tür & Kamera
 * 1.4 `doorbell`): when and where it rang and how the chat card went
 * ([chatOk], [chatError], [fallback] = sent to the people with access
 * because there was no family group).
 */
data class ChatDoorbellStatus(
    val lastRingAt: String?,
    val source: String?,
    val chatAt: String?,
    val chatOk: Boolean?,
    val chatError: String?,
    val fallback: Boolean,
)

/** One action of `GET actions/requestable`: who could approve it ([approverIds]), [self] the caller may run it. */
data class ChatRequestableAction(val action: ChatHomeAction, val approverIds: List<Long>, val self: Boolean)

/** `GET actions/requestable` (Messenger 1.5). */
data class ChatRequestable(
    val actions: List<ChatRequestableAction>,
    /** parents | rights | list (the approvers the answer was computed for). */
    val approvers: String?,
    val approversDefault: String?,
    val canRequest: Boolean?,
    val requestTtlMin: Int?,
)

/**
 * "Haus-Aktionen" of `GET me` (Messenger 1.5): limits and the
 * administrator's defaults — read only in the app. [known] false: an older
 * messenger that sends none (the defaults below apply).
 */
data class ChatHomeInfo(
    val known: Boolean = false,
    /** Home actions work here; [reason] off (administrator) | host (core without gc.actions). */
    val available: Boolean? = null,
    val reason: String? = null,
    val requestTtlMin: Int = DEFAULT_TTL_MIN,
    val grantMaxDays: Int = DEFAULT_GRANT_DAYS,
    val approversDefault: String? = null,
    val childrenMayRequest: Boolean? = null,
    /** The Familie roles are there ("Eltern" can approve). */
    val familyRoles: Boolean = false,
    /** The caller may send door requests (a child while `childrenMayRequest` is off: no). */
    val canRequest: Boolean? = null,
    /** Last doorbell card the messenger posted. */
    val doorbellLastCardAt: String? = null,
    val offAtEndDefault: Boolean = true,
) {
    companion object {
        const val DEFAULT_TTL_MIN = 15
        const val DEFAULT_GRANT_DAYS = 7
    }
}

/** Parsers and bodies of the home features of Messenger C2–C4. Never throw. */
object ChatHomeJson {

    private fun JsonObject.child(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.prim(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive

    private fun JsonObject.text(key: String, max: Int): String? =
        prim(key)?.takeIf { it.isString }?.asString?.trim()?.take(max)?.takeIf { it.isNotEmpty() && it.none { c -> c.isISOControl() } }

    private fun JsonObject.number(key: String): Int? = prim(key)?.takeIf { it.isNumber }?.asDouble?.takeIf { it.isFinite() }?.toInt()

    private fun JsonObject.flag(key: String): Boolean? = prim(key)?.takeIf { it.isBoolean }?.asBoolean

    /** `homeActions` and `limits.requestTtlMin` / `limits.grantMaxDays` of `GET me`. */
    fun home(me: JsonObject): ChatHomeInfo {
        val h = me.child("homeActions")
        val l = me.child("limits")
        val d = ChatHomeInfo()
        val roles = h?.get("familyRoles")
        return ChatHomeInfo(
            known = h != null,
            available = h?.flag("available"),
            reason = h?.text("reason", 20),
            requestTtlMin = l?.number("requestTtlMin")?.coerceIn(1, 24 * 60) ?: d.requestTtlMin,
            grantMaxDays = l?.number("grantMaxDays")?.coerceIn(1, 365) ?: d.grantMaxDays,
            approversDefault = h?.text("approversDefault", 20)?.takeIf { it == ChatApprovers.PARENTS || it == ChatApprovers.RIGHTS },
            childrenMayRequest = h?.flag("childrenMayRequest"),
            familyRoles = roles != null && (roles.isJsonObject || (roles.isJsonPrimitive && roles.asJsonPrimitive.isBoolean && roles.asBoolean)),
            canRequest = h?.flag("canRequest"),
            doorbellLastCardAt = h?.child("doorbell")?.text("lastCardAt", 40),
            offAtEndDefault = h?.flag("offAtEndDefault") ?: true,
        )
    }

    /** Writes [h] back in the shape [home] reads (the stored profile). */
    fun write(into: JsonObject, h: ChatHomeInfo) {
        val limits = into.child("limits") ?: JsonObject().also { into.add("limits", it) }
        limits.addProperty("requestTtlMin", h.requestTtlMin)
        limits.addProperty("grantMaxDays", h.grantMaxDays)
        if (!h.known) return
        into.add("homeActions", JsonObject().apply {
            h.available?.let { addProperty("available", it) }
            h.reason?.let { addProperty("reason", it) }
            h.approversDefault?.let { addProperty("approversDefault", it) }
            h.childrenMayRequest?.let { addProperty("childrenMayRequest", it) }
            addProperty("familyRoles", h.familyRoles)
            h.canRequest?.let { addProperty("canRequest", it) }
            h.doorbellLastCardAt?.let { at -> add("doorbell", JsonObject().apply { addProperty("lastCardAt", at) }) }
            addProperty("offAtEndDefault", h.offAtEndDefault)
        })
    }

    /** `doorbell` of the door plugin's `GET me` (Tür & Kamera 1.4); null without one. */
    fun doorbell(doorMe: JsonObject?): ChatDoorbellStatus? {
        val b = doorMe?.child("doorbell") ?: return null
        val chat = b.child("chat")
        val fb = chat?.get("fallback")
        return ChatDoorbellStatus(
            lastRingAt = b.text("lastRingAt", 40),
            source = b.text("source", 60),
            chatAt = chat?.text("at", 40),
            chatOk = chat?.flag("ok"),
            chatError = chat?.text("error", 120),
            fallback = fb != null && !fb.isJsonNull && !(fb.isJsonPrimitive && fb.asJsonPrimitive.isBoolean && !fb.asBoolean),
        )
    }

    private fun JsonObject.longs(key: String): List<Long> =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong?.takeIf { it > 0 } }.orEmpty()

    /** `GET actions/requestable` → the actions with their approvers. */
    fun requestable(o: JsonObject): ChatRequestable? {
        val arr = o.get("actions")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val base = ChatJson14.homeActions(o).orEmpty().associateBy { it.plugin to it.id }
        val actions = arr.mapNotNull { e ->
            val a = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = a.text("id", 128) ?: return@mapNotNull null
            val action = base[a.text("plugin", 64) to id] ?: return@mapNotNull null
            ChatRequestableAction(action, a.longs("approverIds").distinct().take(50), a.flag("self") == true)
        }
        return ChatRequestable(
            actions = actions,
            approvers = o.text("approvers", 20),
            approversDefault = o.text("approversDefault", 20),
            canRequest = o.flag("canRequest"),
            requestTtlMin = o.number("requestTtlMin")?.coerceIn(1, 24 * 60),
        )
    }

    /** `approvers` of the query: a keyword or the ids comma separated. */
    fun approversQuery(c: ChatApproverChoice): String? = c.keyword ?: c.userIds.takeIf { it.isNotEmpty() }?.joinToString(",")

    private fun ids(list: List<Long>): JsonElement = JsonArray().apply { list.forEach { add(it) } }

    /**
     * `action` of a door request: `approvers` (C2) and, for certain people, the
     * `approverIds` older messengers understand; [note] the optional message.
     */
    fun requestBody(actionId: String, plugin: String?, approvers: ChatApproverChoice, note: String?): JsonObject = JsonObject().apply {
        addProperty("mode", ChatActionCard.MODE_REQUEST)
        add("actionId", ChatJson14.keyElement(actionId))
        plugin?.let { addProperty("plugin", it) }
        if (approvers.keyword != null) {
            addProperty("approvers", approvers.keyword)
        } else {
            add("approvers", ids(approvers.userIds))
            add("approverIds", ids(approvers.userIds))
        }
        note?.trim()?.takeIf { it.isNotEmpty() }?.let { addProperty("note", it.take(200)) }
    }

    /** `action` of "Gerät teilen": to one person until a time, switched off at the end ([offAtEnd], C3). */
    fun grantBody(actionId: String, plugin: String?, toUserId: Long, untilIso: String, offAtEnd: Boolean): JsonObject = JsonObject().apply {
        addProperty("mode", ChatActionCard.MODE_GRANT)
        add("actionId", ChatJson14.keyElement(actionId))
        plugin?.let { addProperty("plugin", it) }
        add("grant", JsonObject().apply {
            addProperty("toUserId", toUserId)
            addProperty("until", untilIso)
            if (offAtEnd) addProperty("offAtEnd", true)
        })
    }

    /** The chat that gets doorbell cards: the Familie group, else one titled "Familie". */
    fun familyGroup(conversations: List<ChatConversation>): ChatConversation? =
        conversations.firstOrNull { it.isGroup && it.managedBy?.contains("familie") == true }
            ?: conversations.firstOrNull { it.isGroup && (it.title.equals("Familie", true) || it.title.equals("Family", true)) }
}
