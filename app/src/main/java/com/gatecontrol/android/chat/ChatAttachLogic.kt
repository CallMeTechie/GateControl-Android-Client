package com.gatecontrol.android.chat

/*
 * Messenger 1.27: what the plus menu of a chat offers and where each entry goes. Pure, unit-tested; the screen draws
 * [ChatAttachPlan] and sends to the conversation it names.
 *
 * - Gallery, camera and files follow the way of the text (a chat over a bridge: what the service takes).
 * - What only GateControl can (poll, event, live arrival, task, shopping list, home actions) goes to the person's
 *   GateControl way — offered while there is one; with the text over a bridge the entry carries GateControl's logo.
 * - The location goes over the way of the text when that way takes it (inside GateControl, or a bridge with
 *   `supports: location`, [ChatBridgeChat.TAKES_LOCATION]), without a logo; else with the logo over GateControl; with
 *   neither not at all.
 */

/** Where an entry of the plus menu goes: [conversationId], with GateControl's logo when [badge]. */
data class ChatAttachRoute(val conversationId: Long, val badge: Boolean)

data class ChatAttachPlan(
    val gallery: Boolean,
    val camera: Boolean,
    val files: Boolean,
    /** The location; null: not offered. */
    val location: ChatAttachRoute?,
    /** Poll, event, live arrival, task, shopping list, home actions; null: none of them (no GateControl way). */
    val gateControl: ChatAttachRoute?,
    /** „Beim Ankommen senden“ (Messenger 1.27). */
    val placeMessage: Boolean,
)

object ChatAttachLogic {
    /**
     * [chatId] the chat on screen (a person's lead), [person] its person (null: a chat of its own), [composer] the
     * conversation the text goes to, [features] of `GET me`, [camera] the phone has one.
     */
    fun plan(chatId: Long, person: ChatMergedPerson?, composer: ChatConversation?, features: ChatFeatures?, camera: Boolean): ChatAttachPlan {
        val textOwn = composer?.bridge == null
        val takes = { what: String -> ChatBridgeChat.takes(composer, what) }
        val textId = composer?.id ?: chatId
        // the GateControl way: of a person its way, else this chat when it is inside GateControl
        val gcId = if (person != null) ChatPeople.gcWay(person) else chatId.takeIf { textOwn }
        val badge = !textOwn
        val gc = gcId?.let { ChatAttachRoute(it, badge) }
        val locationOn = features?.location != false
        val location = when {
            !locationOn -> null
            textOwn || takes(ChatBridgeChat.TAKES_LOCATION) -> ChatAttachRoute(textId, false)
            gc != null -> gc
            else -> null
        }
        return ChatAttachPlan(
            gallery = textOwn || takes(ChatBridgeChat.TAKES_IMAGE) || takes(ChatBridgeChat.TAKES_VIDEO),
            camera = (textOwn || takes(ChatBridgeChat.TAKES_IMAGE)) && camera,
            files = features?.files != false && (textOwn || takes(ChatBridgeChat.TAKES_FILE)),
            location = location,
            gateControl = gc,
            placeMessage = ChatPlaceMessages.on(features) && composer?.let { it.can.send && !it.readonly && ChatBridgeChat.readOnly(it) == null } != false,
        )
    }

    /**
     * Before Messenger 1.27 (no `features.placeMessages`): the menu as before — only what the way of the text can,
     * nothing over another way.
     */
    fun legacy(chatId: Long, composer: ChatConversation?, features: ChatFeatures?, camera: Boolean): ChatAttachPlan {
        val own = composer?.bridge == null
        val p = plan(chatId, null, composer, features, camera)
        return p.copy(
            location = if (own && features?.location != false) ChatAttachRoute(composer?.id ?: chatId, false) else null,
            gateControl = if (own) ChatAttachRoute(chatId, false) else null,
            placeMessage = false,
        )
    }
}
