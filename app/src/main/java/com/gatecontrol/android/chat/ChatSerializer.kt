package com.gatecontrol.android.chat

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject

/**
 * Parsed models back into the server's JSON shape, for the Room cache:
 * `ChatJson.x(ChatSerializer.x(m)) == m`. Only validated values are ever
 * stored. Pure, unit-tested (round trip).
 */
object ChatSerializer {

    private fun JsonObject.put(key: String, value: String?) = if (value == null) add(key, JsonNull.INSTANCE) else addProperty(key, value)

    private fun JsonObject.put(key: String, value: Number?) = if (value == null) add(key, JsonNull.INSTANCE) else addProperty(key, value)

    private fun ids(list: List<Long>) = JsonArray().apply { list.forEach { add(it) } }

    fun person(p: ChatPerson): JsonObject = JsonObject().apply {
        addProperty("userId", p.userId)
        put("name", p.name)
        put("avatarUrl", p.avatarUrl)
        put("initials", p.initials)
        put("colorHex", p.colorHex)
        addProperty("gone", p.gone)
        p.role?.let { addProperty("role", it) }
        p.online?.let { addProperty("online", it) }
        p.lastSeenAt?.let { addProperty("lastSeenAt", it) }
        if (p.blocked) addProperty("blocked", true)
    }

    fun message(m: ChatMessage): JsonObject = JsonObject().apply {
        addProperty("id", m.id)
        addProperty("conversationId", m.conversationId)
        put("senderId", m.senderId)
        put("clientId", m.clientId)
        addProperty("kind", m.kind)
        put("via", m.via)
        addProperty("mine", m.mine)
        addProperty("createdAt", m.createdAt)
        put("editedAt", m.editedAt)
        put("deletedAt", m.deletedAt)
        put("expiresAt", m.expiresAt)
        addProperty("deleted", m.deleted)
        addProperty("undecryptable", m.undecryptable)
        put("text", m.text)
        add("replyTo", m.replyTo?.let { r ->
            JsonObject().apply {
                addProperty("id", r.id)
                put("senderId", r.senderId)
                put("kind", r.kind)
                put("text", r.text)
                addProperty("deleted", r.deleted)
                addProperty("missing", r.missing)
            }
        } ?: JsonNull.INSTANCE)
        add("media", m.media?.let { md ->
            JsonObject().apply {
                addProperty("id", md.id)
                put("mime", md.mime)
                put("bytes", md.bytes)
                put("thumbId", md.thumbId)
            }
        } ?: JsonNull.INSTANCE)
        add("meta", m.meta?.let { mt ->
            JsonObject().apply {
                mt.durationMs?.let { addProperty("durationMs", it) }
                if (mt.waveform.isNotEmpty()) add("waveform", JsonArray().apply { mt.waveform.forEach { add(it) } })
                mt.w?.let { addProperty("w", it) }
                mt.h?.let { addProperty("h", it) }
                mt.fileName?.let { addProperty("fileName", it) }
            }
        } ?: JsonNull.INSTANCE)
        add("location", m.location?.let { l ->
            JsonObject().apply {
                addProperty("lat", l.lat)
                addProperty("lon", l.lon)
                put("label", l.label)
                l.accuracy?.let { addProperty("accuracy", it) }
            }
        } ?: JsonNull.INSTANCE)
        add("system", m.system?.let { s ->
            JsonObject().apply {
                addProperty("event", s.event)
                put("by", s.by)
                s.title?.let { addProperty("title", it) }
                if (s.userIds.isNotEmpty()) add("userIds", ids(s.userIds))
                s.userId?.let { addProperty("userId", it) }
                s.seconds?.let { addProperty("seconds", it) }
                s.label?.let { addProperty("label", it) }
                s.messageId?.let { addProperty("messageId", it) }
                s.answer?.let { addProperty("answer", it) }
                s.guests?.let { addProperty("guests", it) }
                s.otherUserId?.let { addProperty("asUserId", it) }
                s.ok?.let { addProperty("ok", it) }
            }
        } ?: JsonNull.INSTANCE)
        add("reactions", ChatJsonPatch.reactions(JsonObject(), m.reactions).get("reactions"))
        put("status", m.status)
        m.video?.let { v ->
            add("video", JsonObject().apply {
                v.durationMs?.let { addProperty("durationMs", it) }
                v.width?.let { addProperty("width", it) }
                v.height?.let { addProperty("height", it) }
            })
        }
        m.poll?.let { add("poll", ChatJson14.writePoll(it)) }
        m.event?.let { add("event", ChatJson14.writeEventInfo(it)) }
        m.arrival?.let { add("arrival", ChatJson14.writeArrival(it)) }
        m.action?.let { add("action", ChatJson14.writeAction(it)) }
        m.card?.let { add("card", ChatJson14.writeCard(it)) }
        m.replyStory?.let { add("replyStory", ChatJson14.writeReplyStory(it)) }
    }

    fun conversation(c: ChatConversation): JsonObject = JsonObject().apply {
        addProperty("id", c.id)
        addProperty("kind", c.kind)
        put("title", c.title)
        add("peer", c.peer?.let { person(it) } ?: JsonNull.INSTANCE)
        put("avatarUrl", c.avatarUrl)
        put("avatarMediaId", c.avatarMediaId)
        put("managedBy", c.managedBy)
        addProperty("readonly", c.readonly)
        addProperty("disappearingS", c.disappearingS)
        put("createdAt", c.createdAt)
        put("updatedAt", c.updatedAt)
        put("lastMessageId", c.lastMessageId)
        add("members", JsonArray().apply { c.members.forEach { add(person(it)) } })
        add("me", JsonObject().apply {
            put("role", c.me.role)
            put("mutedUntil", c.me.mutedUntil)
            addProperty("notify", c.me.notify)
            put("lastReadId", c.me.lastReadId)
            put("lastDeliveredId", c.me.lastDeliveredId)
        })
        addProperty("muted", c.muted)
        addProperty("unread", c.unread)
        add("lastMessage", c.lastMessage?.let { message(it) } ?: JsonNull.INSTANCE)
        add("typing", ids(c.typing))
        put("readUpTo", c.readUpTo)
        put("deliveredUpTo", c.deliveredUpTo)
        add("receipts", JsonArray().apply {
            c.receipts.forEach { r ->
                add(JsonObject().apply {
                    addProperty("userId", r.userId)
                    put("lastReadId", r.lastReadId)
                    put("lastDeliveredId", r.lastDeliveredId)
                })
            }
        })
        add("can", JsonObject().apply {
            addProperty("send", c.can.send)
            addProperty("editInfo", c.can.editInfo)
            addProperty("manageMembers", c.can.manageMembers)
            addProperty("leave", c.can.leave)
            addProperty("setDisappearing", c.can.setDisappearing)
            addProperty("pin", c.can.pin)
        })
        if (c.archived) addProperty("archived", true)
        if (c.pinned.isNotEmpty()) add("pinned", ids(c.pinned))
        c.nextEvent?.let { add("nextEvent", ChatJson14.writeNextEvent(it)) }
    }

    fun profile(p: ChatProfile): JsonObject = JsonObject().apply {
        add("user", person(p.user).apply {
            remove("userId")
            addProperty("id", p.user.userId)
        })
        add("prefs", JsonObject().apply {
            addProperty("readReceipts", p.prefs.readReceipts)
            addProperty("typing", p.prefs.typing)
            addProperty("preview", p.prefs.preview)
            ChatJson14.writePrefs14(this, p.prefs14)
        })
        p.status?.let { add("status", ChatJson14.writeStatus(it)) }
        add("integrations", JsonObject().apply {
            add("familie", JsonObject().apply {
                addProperty("lists", p.familie.lists)
                addProperty("calendar", p.familie.calendar)
                addProperty("places", p.familie.places)
            })
        })
        p.licensed?.let { l -> add("license", JsonObject().apply { addProperty("licensed", l) }) }
        add("features", JsonObject().apply {
            val f = p.features
            addProperty("direct", f.direct)
            addProperty("groups", f.groups)
            addProperty("voice", f.voice)
            addProperty("files", f.files)
            addProperty("location", f.location)
            addProperty("reactions", f.reactions)
            addProperty("edit", f.edit)
            addProperty("search", f.search)
            addProperty("disappearing", f.disappearing)
            f.tokens.forEach { addProperty(it, true) }
        })
        add("limits", JsonObject().apply {
            val l = p.limits
            put("maxBytes", l.maxBytes)
            add("types", JsonArray().apply { l.types.forEach { add(it) } })
            addProperty("quotaFull", l.quotaFull)
            addProperty("editWindowS", l.editWindowS)
            addProperty("maxVoiceS", l.maxVoiceS)
            addProperty("textMax", l.textMax)
            addProperty("thumbMaxBytes", l.thumbMaxBytes)
            addProperty("thumbMaxSide", l.thumbMaxSide)
            addProperty("imageMaxSide", l.imageMaxSide)
            addProperty("groupMax", l.groupMax)
            addProperty("titleMax", l.titleMax)
            addProperty("pageMax", l.pageMax)
            addProperty("typingS", l.typingS)
            addProperty("typingEveryS", l.typingEveryS)
            addProperty("activeS", l.activeS)
            add("disappearingS", JsonArray().apply { l.disappearingS.forEach { add(it) } })
            ChatJson14.writeUsage(this, l.usage)
        })
        ChatHomeJson.write(this, p.home)
        put("serverTime", p.serverTime)
    }
}
