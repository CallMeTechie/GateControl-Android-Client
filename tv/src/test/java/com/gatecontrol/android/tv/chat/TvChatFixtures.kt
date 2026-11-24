package com.gatecontrol.android.tv.chat

import com.gatecontrol.android.chat.ChatConversation
import com.gatecontrol.android.chat.ChatJson
import com.gatecontrol.android.chat.ChatMessage
import com.google.gson.JsonParser

/** Chats and messages as the messenger answers them (names made up). */
internal object TvChatFixtures {

    fun conversation(
        id: Long,
        title: String,
        kind: String = "group",
        unread: Int = 0,
        managedBy: String? = null,
        listPinned: Boolean = false,
        bridge: String? = null,
        canSend: Boolean = true,
        lastMessage: String? = null,
        at: String = "2026-11-24T18:00:00Z",
        archived: Boolean = false,
    ): ChatConversation = ChatJson.conversation(
        JsonParser.parseString(
            """{"id":$id,"kind":"$kind","title":"$title","unread":$unread,"updatedAt":"$at","archived":$archived,
               "members":[{"userId":1,"name":"Ich"},{"userId":2,"name":"Mila"}],
               ${managedBy?.let { "\"managedBy\":\"$it\"," } ?: ""}
               "listPinned":$listPinned,
               "can":{"send":$canSend,"react":true},
               ${bridge?.let { "\"bridge\":$it," } ?: ""}
               ${lastMessage?.let { "\"lastMessage\":$it," } ?: ""}
               "me":{"role":"member"}}""",
        ).asJsonObject,
    )!!

    fun bridge(network: String = "whatsapp", label: String = "WhatsApp", state: String = "ok", closed: Boolean = false) =
        """{"network":"$network","label":"$label","state":"$state","closed":$closed,"supports":["image"]}"""

    fun message(
        id: Long,
        conversationId: Long = 5,
        text: String? = "Hallo",
        kind: String = "text",
        mine: Boolean = false,
        senderId: Long? = if (mine) 1 else 2,
        clientId: String? = null,
        status: String? = null,
        extra: String = "",
    ): ChatMessage = ChatJson.message(JsonParser.parseString(messageJson(id, conversationId, text, kind, mine, senderId, clientId, status, extra)).asJsonObject)!!

    fun messageJson(
        id: Long,
        conversationId: Long = 5,
        text: String? = "Hallo",
        kind: String = "text",
        mine: Boolean = false,
        senderId: Long? = if (mine) 1 else 2,
        clientId: String? = null,
        status: String? = null,
        extra: String = "",
    ): String = """{"id":$id,"conversationId":$conversationId,"kind":"$kind","mine":$mine,"createdAt":"2026-11-24T18:0${id % 10}:00Z"
        ${senderId?.let { ",\"senderId\":$it" } ?: ""}${text?.let { ",\"text\":\"$it\"" } ?: ""}${clientId?.let { ",\"clientId\":\"$it\"" } ?: ""}
        ${status?.let { ",\"status\":\"$it\"" } ?: ""}$extra}"""
}
