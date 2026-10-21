package com.gatecontrol.android.chat

import com.gatecontrol.android.door.DoorPush
import com.gatecontrol.android.familie.FamilieDest
import com.gatecontrol.android.familie.push.FamiliePush
import com.gatecontrol.android.familie.school.SchoolPush
import com.gatecontrol.android.familie.school.SchoolView
import com.gatecontrol.android.notify.model.PushMessage
import com.gatecontrol.android.push.PushNavTarget

/**
 * The chat list's sections (Messenger 1.6): under "Angeheftet" the
 * GateControl entry and the chats the server pins to the list
 * (`listPinned`, the family group), under "Chats" the rest. Pure,
 * unit-tested.
 */
object ChatListSections {

    data class Split(val pinned: List<ChatConversation>, val chats: List<ChatConversation>)

    /**
     * Splits [visible] (already sorted, filtered and searched) into the two
     * sections, each keeping its order. While searching or picking a chat for
     * something shared there are no sections: pinned chats simply appear among
     * the results.
     */
    fun split(visible: List<ChatConversation>, sectioned: Boolean): Split =
        if (!sectioned) {
            Split(emptyList(), visible)
        } else {
            val (pinned, chats) = visible.partition { it.listPinned }
            Split(pinned, chats)
        }
}

/**
 * Where a card's link or route button leads (`link { label, route }` of
 * plugin and notice cards). The app's routes as for notifications
 * ([PushNavTarget.forRoute]); a plugin route may name a screen after a `/`:
 * `plg-gatecontrol-schule[/messages[/<id>]]` opens the school messages (on
 * that message), `plg-gatecontrol-familie/<screen>` a screen of the Familie
 * tab. Unknown screens fall back to the plugin's route. Pure, unit-tested.
 */
object ChatCardLinks {

    /**
     * [notice] = the notification a notice card carries (Messenger 1.6): its
     * topic and `data` lead where a tap on that push would (the Schule
     * message of "Ganze Nachricht lesen", a ring of the door, a Familie screen).
     */
    fun target(route: String, notice: ChatCardNotice? = null): PushNavTarget {
        if (notice != null) fromNotice(route, notice)?.let { return it }
        val base = route.substringBefore('/')
        val path = route.substringAfter('/', "").trim('/')
        return when (base) {
            SchoolPush.ROUTE -> PushNavTarget.Familie(school(path).key)
            PushNavTarget.FAMILIE_ROUTE -> PushNavTarget.Familie(path.takeIf { it.isNotEmpty() && FamilieDest.parse(it) != null } ?: "home")
            else -> PushNavTarget.forRoute(base, null)
        }
    }

    /** The push's own deep-link logic on the card's notification (as PushNotifier / PushOpenActivity do). */
    private fun fromNotice(route: String, n: ChatCardNotice): PushNavTarget? {
        val topic = n.topic ?: return null
        val message = PushMessage(
            seq = 0L, id = 0L, eventId = "", topic = topic, priority = n.priority ?: "normal",
            title = "", body = "", createdAt = null, expiresAt = null, collapseKey = null, silent = true,
            route = route.substringBefore('/'), actions = emptyList(), dataJson = n.dataJson,
        )
        val familie = runCatching { FamiliePush.parse(message)?.let { FamiliePush.destination(it).key } ?: SchoolPush.destination(message)?.key }.getOrNull()
        if (familie != null) return PushNavTarget.Familie(familie)
        val door = runCatching { DoorPush.destination(message)?.key }.getOrNull() ?: return null
        return PushNavTarget.Door(door)
    }

    /** A Schule card names a message (its link "Ganze Nachricht lesen"): the tab "Nachrichten", on that message when given. */
    private fun school(path: String): FamilieDest {
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return FamilieDest.School(view = SchoolView.MESSAGES)
        // `messages/<id>`: a card names its message (WebUntis ids are numbers, not a child's).
        if (parts.size == 2 && parts[0] == "messages") {
            return FamilieDest.School(view = SchoolView.MESSAGES, messageId = parts[1].takeIf { FamilieDest.School.MESSAGE_ID_RE.matches(it) })
        }
        // Else a full screen key of the Familie tab (`messages/<child>/<id>`, `absences/…`, a timetable day).
        return FamilieDest.parse("school/$path") ?: FamilieDest.School(view = SchoolView.MESSAGES)
    }
}
