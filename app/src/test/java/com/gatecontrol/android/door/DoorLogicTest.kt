package com.gatecontrol.android.door

import com.gatecontrol.android.door.DoorLogic.Step
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Which buttons a lock card shows and what has to happen before an action runs. */
class DoorLogicTest {

    private fun lock(
        right: String = DoorRight.OPEN,
        actions: List<String> = DoorAction.ALL,
        confirm: List<String> = listOf(DoorAction.UNLOCK, DoorAction.OPEN),
        blocked: String? = null,
        type: String = DoorLock.TYPE_SMARTLOCK,
        state: String = "locked",
        reachable: Boolean = true,
    ) = DoorLock(
        id = 1, name = "Haustür", type = type, right = right, actions = actions, blocked = blocked, confirm = confirm,
        doorSensor = false, state = DoorLockState(state, null, false, 50, false, reachable, null),
    )

    @Test
    fun `lock runs straight away, unlock and open are confirmed and need the device unlock`() {
        val l = lock()
        assertEquals(listOf(Step.RUN), DoorLogic.steps(l, DoorAction.LOCK))
        assertEquals(listOf(Step.CONFIRM, Step.DEVICE_AUTH, Step.RUN), DoorLogic.steps(l, DoorAction.UNLOCK))
        assertEquals(listOf(Step.CONFIRM, Step.DEVICE_AUTH, Step.RUN), DoorLogic.steps(l, DoorAction.OPEN))
    }

    @Test
    fun `device auth is required for unlock and open even without a confirm`() {
        val l = lock(confirm = emptyList())
        assertEquals(listOf(Step.DEVICE_AUTH, Step.RUN), DoorLogic.steps(l, DoorAction.UNLOCK))
        assertEquals(listOf(Step.DEVICE_AUTH, Step.RUN), DoorLogic.steps(l, DoorAction.OPEN))
        assertTrue(DoorLogic.needsDeviceAuth(DoorAction.UNLOCK))
        assertTrue(DoorLogic.needsDeviceAuth(DoorAction.OPEN))
        assertFalse(DoorLogic.needsDeviceAuth(DoorAction.LOCK))
    }

    @Test
    fun `a confirm the plugin asks for lock is honoured too`() {
        assertEquals(listOf(Step.CONFIRM, Step.RUN), DoorLogic.steps(lock(confirm = listOf(DoorAction.LOCK)), DoorAction.LOCK))
    }

    @Test
    fun `actions the lock does not offer or the right does not allow never run`() {
        assertTrue(DoorLogic.steps(lock(actions = listOf(DoorAction.LOCK)), DoorAction.OPEN).isEmpty())
        // A server that lists "open" for a lock right is not trusted for it.
        assertTrue(DoorLogic.steps(lock(right = DoorRight.LOCK), DoorAction.OPEN).isEmpty())
        assertTrue(DoorLogic.steps(lock(right = DoorRight.VIEW), DoorAction.LOCK).isEmpty())
        assertTrue(DoorLogic.steps(lock(), "explode").isEmpty())
    }

    @Test
    fun `buttons follow the offered actions and are disabled with a reason while blocked`() {
        val free = DoorLogic.buttons(lock(), canAct = true)
        assertEquals(DoorAction.ALL, free.map { it.action })
        assertTrue(free.all { it.enabled && it.blockedReason == null })

        val window = DoorLogic.buttons(lock(blocked = DoorBlocked.OUTSIDE_WINDOW), canAct = true)
        assertTrue(window.none { it.enabled })
        assertEquals(DoorBlocked.OUTSIDE_WINDOW, window.first().blockedReason)

        val kiosk = DoorLogic.buttons(lock(), canAct = false)
        assertTrue(kiosk.none { it.enabled })
        assertEquals(DoorBlocked.READ_ONLY, kiosk.first().blockedReason)

        assertEquals(listOf(DoorAction.LOCK, DoorAction.UNLOCK), DoorLogic.buttons(lock(right = DoorRight.LOCK), true).map { it.action })
        assertTrue(DoorLogic.buttons(lock(right = DoorRight.VIEW), true).isEmpty())
    }

    @Test
    fun `opener open is the buzzer`() {
        val opener = lock(type = DoorLock.TYPE_OPENER, actions = listOf(DoorAction.OPEN), state = "online")
        assertTrue(DoorLogic.isBuzzer(opener, DoorAction.OPEN))
        assertFalse(DoorLogic.isBuzzer(lock(), DoorAction.OPEN))
        assertEquals(DoorLogic.StateKind.READY, DoorLogic.stateKind(opener))
    }

    @Test
    fun `event detail offers open only with the open right`() {
        assertTrue(DoorLogic.canOpenFromEvent(lock()))
        assertFalse(DoorLogic.canOpenFromEvent(lock(right = DoorRight.LOCK, actions = listOf(DoorAction.LOCK, DoorAction.UNLOCK))))
        assertFalse(DoorLogic.canOpenFromEvent(lock(actions = listOf(DoorAction.LOCK))))
        assertFalse(DoorLogic.canOpenFromEvent(null))
    }

    @Test
    fun `state kinds`() {
        assertEquals(DoorLogic.StateKind.LOCKED, DoorLogic.stateKind(lock(state = "locked")))
        assertEquals(DoorLogic.StateKind.UNLOCKED, DoorLogic.stateKind(lock(state = "unlocked")))
        assertEquals(DoorLogic.StateKind.OPEN, DoorLogic.stateKind(lock(state = "unlatched")))
        assertEquals(DoorLogic.StateKind.MOVING, DoorLogic.stateKind(lock(state = "unlocking")))
        assertEquals(DoorLogic.StateKind.PROBLEM, DoorLogic.stateKind(lock(state = "jammed")))
        assertEquals(DoorLogic.StateKind.UNKNOWN, DoorLogic.stateKind(lock(state = "unknown")))
        assertEquals(DoorLogic.StateKind.UNREACHABLE, DoorLogic.stateKind(lock(state = "locked", reachable = false)))
    }

    @Test
    fun `battery and the countdown of a new picture`() {
        assertTrue(DoorLogic.batteryLow(DoorLockState("locked", null, true, null, false, true, null)))
        assertTrue(DoorLogic.batteryLow(DoorLockState("locked", null, false, 15, false, true, null)))
        assertFalse(DoorLogic.batteryLow(DoorLockState("locked", null, false, 80, false, true, null)))
        assertEquals(0, DoorLogic.secondsLeft(1_000, 2_000))
        assertEquals(12, DoorLogic.secondsLeft(13_000, 1_000))
        assertEquals(1, DoorLogic.secondsLeft(1_001, 1_000))
    }

    @Test
    fun `history filters map to the kind query`() {
        assertEquals(null, DoorLogic.HistoryFilter.ALL.kind)
        assertEquals("action", DoorLogic.HistoryFilter.ACTIONS.kind)
        assertEquals("ding", DoorLogic.HistoryFilter.RINGS.kind)
    }
}
