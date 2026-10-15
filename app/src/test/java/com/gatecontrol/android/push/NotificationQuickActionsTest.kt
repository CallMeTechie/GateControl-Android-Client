package com.gatecontrol.android.push

import com.gatecontrol.android.notify.model.PushAction
import com.gatecontrol.android.notify.model.PushActionType
import com.gatecontrol.android.notify.model.PushMessage
import com.gatecontrol.android.push.NotificationQuickActions.Decision
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** Notification buttons of plugin quick actions (core 1.169) and the checks before one runs. */
class NotificationQuickActionsTest {

    private val openDoor = PushAction("1:open", "Tür öffnen", PushActionType.QUICK_ACTION, plugin = "gatecontrol-door", confirm = true, auth = PushAction.AUTH_DEVICE)
    private val relock = PushAction("1:lock", "Wieder zusperren", PushActionType.QUICK_ACTION, plugin = "gatecontrol-door")
    private val view = PushAction("open", "Ansehen", PushActionType.OPEN_APP_ROUTE, target = "plg-gatecontrol-door")
    private val mute = PushAction("mute_1h", "1 h stumm", PushActionType.MUTE_1H)
    private val seen = PushAction("seen", "Gesehen", PushActionType.ACK)

    private fun msg(actions: List<PushAction>, expiresAt: String? = null) = PushMessage(
        seq = 1, id = 1, eventId = "e", topic = "plugin:gatecontrol-door:doorbell", priority = "high", title = "Es klingelt", body = "",
        createdAt = null, expiresAt = expiresAt, collapseKey = "ring:4", silent = false, route = null, actions = actions,
    )

    @Test
    fun `doorbell buttons - open door needs the device unlock, then seen and view`() {
        val plan = NotificationQuickActions.plan(listOf(openDoor, view), seen)
        assertEquals(listOf("1:open", "seen", "open"), plan.map { it.action.id })
        assertEquals(listOf(ButtonKind.QUICK_ACTION, ButtonKind.SEEN, ButtonKind.OPEN), plan.map { it.kind })
        assertEquals(listOf(true, false, false), plan.map { it.authRequired })
    }

    @Test
    fun `quick action without auth runs without unlock, background and open buttons never need it`() {
        val plan = NotificationQuickActions.plan(listOf(relock, view, mute), seen)
        assertEquals(3, plan.size)
        assertEquals(ButtonKind.QUICK_ACTION, plan[0].kind)
        assertFalse(plan[0].authRequired)
        assertTrue(plan.none { it.authRequired })
        val background = NotificationQuickActions.plan(listOf(mute), seen)
        assertEquals(listOf(ButtonKind.BACKGROUND, ButtonKind.SEEN), background.map { it.kind })
    }

    @Test
    fun `a quick_action without a plugin is not wired as one`() {
        val stray = PushAction("1:open", "Öffnen", PushActionType.QUICK_ACTION, auth = PushAction.AUTH_DEVICE)
        assertFalse(stray.isQuickAction)
        // Its button still asks for the unlock; the receiver then refuses it.
        val plan = NotificationQuickActions.plan(listOf(stray), seen)
        assertEquals(ButtonKind.QUICK_ACTION, plan[0].kind)
        assertTrue(plan[0].authRequired)
        assertEquals(Decision.UNKNOWN_ACTION, NotificationQuickActions.decide(msg(listOf(stray)), null, "1:open", 0, deviceLocked = false))
    }

    @Test
    fun `only the message's own quick action runs`() {
        val m = msg(listOf(openDoor, view))
        assertEquals(Decision.RUN, NotificationQuickActions.decide(m, "gatecontrol-door", "1:open", 0, deviceLocked = false))
        assertEquals(Decision.UNKNOWN_ACTION, NotificationQuickActions.decide(m, "gatecontrol-door", "2:open", 0, deviceLocked = false))
        assertEquals(Decision.UNKNOWN_ACTION, NotificationQuickActions.decide(m, "other-plugin", "1:open", 0, deviceLocked = false))
        // "open" is the view button's id, not a quick action.
        assertEquals(Decision.UNKNOWN_ACTION, NotificationQuickActions.decide(m, "gatecontrol-door", "open", 0, deviceLocked = false))
        assertEquals(Decision.UNKNOWN_MESSAGE, NotificationQuickActions.decide(null, "gatecontrol-door", "1:open", 0, deviceLocked = false))
    }

    @Test
    fun `an expired message runs nothing`() {
        val m = msg(listOf(openDoor), expiresAt = "2026-10-14T09:00:00Z")
        val before = Instant.parse("2026-10-14T08:59:59Z").toEpochMilli()
        val after = Instant.parse("2026-10-14T09:00:00Z").toEpochMilli()
        assertEquals(Decision.RUN, NotificationQuickActions.decide(m, "gatecontrol-door", "1:open", before, deviceLocked = false))
        assertEquals(Decision.EXPIRED, NotificationQuickActions.decide(m, "gatecontrol-door", "1:open", after, deviceLocked = false))
    }

    @Test
    fun `auth device on a locked device is refused, without auth it runs`() {
        val m = msg(listOf(openDoor, relock))
        assertEquals(Decision.LOCKED, NotificationQuickActions.decide(m, "gatecontrol-door", "1:open", 0, deviceLocked = true))
        assertEquals(Decision.RUN, NotificationQuickActions.decide(m, "gatecontrol-door", "1:lock", 0, deviceLocked = true))
    }
}
