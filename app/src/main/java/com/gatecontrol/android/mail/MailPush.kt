package com.gatecontrol.android.mail

import com.gatecontrol.android.notify.model.PushMessage
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Where a tap on a Mail push leads (pure, unit-tested). Topic `mail` (title = sender, text = subject) carries
 * `data.mail = { messageId, accountId }` and opens the mail; `mail_failed` carries `{ outboxId }` and opens the
 * outbox. Their channel is called "Mail" whatever the server names it.
 */
object MailPush {
    private const val TOPIC_PREFIX = "plugin:$MAIL_PLUGIN:"
    const val TOPIC_MAIL = TOPIC_PREFIX + "mail"
    const val TOPIC_FAILED = TOPIC_PREFIX + "mail_failed"

    fun isMailMessage(message: PushMessage): Boolean = message.topic.startsWith(TOPIC_PREFIX)

    /** Whether a notification channel (by its topic) is Mail's own: shown as "Mail". */
    fun isMailTopic(topic: String): Boolean = topic == TOPIC_MAIL

    /** `message:<id>` | `outbox:<id>` | `outbox`; null for other messages. */
    fun destination(message: PushMessage): String? {
        if (!isMailMessage(message)) return null
        val mail = message.dataJson?.let { raw -> runCatching { JsonParser.parseString(raw).asJsonObject.get("mail") }.getOrNull() }
            ?.takeIf { it.isJsonObject }?.asJsonObject
        return when (message.topic) {
            TOPIC_MAIL -> mail?.id("messageId")?.let { "message:$it" }
            TOPIC_FAILED -> mail?.id("outboxId")?.let { "outbox:$it" } ?: "outbox"
            else -> null
        }
    }

    private fun JsonObject.id(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString.trim().toLong() }.getOrNull() }?.takeIf { it > 0 }

    /** A destination of [destination] → the mail's id, or null. */
    fun messageOf(dest: String): Long? = dest.takeIf { it.startsWith("message:") }?.removePrefix("message:")?.toLongOrNull()?.takeIf { it > 0 }

    /** Whether a destination opens the outbox. */
    fun isOutbox(dest: String): Boolean = dest == "outbox" || dest.startsWith("outbox:")
}
