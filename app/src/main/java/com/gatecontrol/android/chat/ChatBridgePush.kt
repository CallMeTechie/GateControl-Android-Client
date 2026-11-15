package com.gatecontrol.android.chat

import com.gatecontrol.android.notify.model.PushMessage
import com.google.gson.JsonParser

/**
 * Messenger 1.17: the pushes of the topic `bridge` („Verknüpfte Dienste“) — today the warning that the phone with
 * WhatsApp was not seen for days (`data: { deepLink: "bridges", bridge: "whatsapp" }`). A tap opens „Verknüpfte
 * Dienste“ in the chat settings. No buttons; the channel and the topic's switch are those of every plugin topic.
 * Pure, unit-tested.
 */
object ChatBridgePush {
    const val TOPIC = "plugin:$MESSENGER_PLUGIN:bridge"
    const val DEEP_LINK = "bridges"

    /** [DEEP_LINK] for a push of the messenger that leads to „Verknüpfte Dienste“; null for any other. */
    fun destination(message: PushMessage): String? {
        if (!message.topic.startsWith("plugin:$MESSENGER_PLUGIN:")) return null
        if (message.topic == TOPIC) return DEEP_LINK
        val link = message.dataJson?.let { raw ->
            runCatching { JsonParser.parseString(raw).asJsonObject.get("deepLink")?.takeIf { it.isJsonPrimitive }?.asString }.getOrNull()
        }
        return DEEP_LINK.takeIf { link == it }
    }
}
