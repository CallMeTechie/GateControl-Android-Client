package com.gatecontrol.android.door

/**
 * Decisions of the door tab, kept pure for the tests: which buttons a lock
 * card shows and whether they work, what has to happen before an action
 * runs (confirmation, device authentication) and how a state reads.
 */
object DoorLogic {

    /** What the app does, in order, before it sends an action. */
    enum class Step { CONFIRM, DEVICE_AUTH, RUN }

    /** A button of a lock card. [enabled] false: shown disabled with [blockedReason]. */
    data class LockButton(val action: String, val enabled: Boolean, val blockedReason: String?)

    /** Rough state of a lock for icon and colour. */
    enum class StateKind { LOCKED, UNLOCKED, OPEN, MOVING, READY, PROBLEM, UNKNOWN, UNREACHABLE }

    /** Unlocking and opening (unlatch, buzzer) always need the device unlock first. */
    fun needsDeviceAuth(action: String): Boolean = action == DoorAction.UNLOCK || action == DoorAction.OPEN

    /** The plugin asks to confirm [action] on [lock] (`confirm` list). */
    fun needsConfirm(lock: DoorLock, action: String): Boolean = action in lock.confirm

    /**
     * Steps before [action] runs: a confirm dialog when the plugin asks for
     * it, device authentication for unlock/open, then the call. An action
     * the lock does not offer has no steps at all (never sent).
     */
    fun steps(lock: DoorLock, action: String): List<Step> {
        if (action !in lock.actions || !allowedByRight(lock.right, action)) return emptyList()
        return buildList {
            if (needsConfirm(lock, action)) add(Step.CONFIRM)
            if (needsDeviceAuth(action)) add(Step.DEVICE_AUTH)
            add(Step.RUN)
        }
    }

    /** What a right allows (the server checks again; this only keeps stray buttons away). */
    fun allowedByRight(right: String, action: String): Boolean = when (action) {
        DoorAction.LOCK, DoorAction.UNLOCK -> right == DoorRight.LOCK || right == DoorRight.OPEN
        DoorAction.OPEN -> right == DoorRight.OPEN
        else -> false
    }

    /**
     * Buttons of a lock card: one per offered action the right allows;
     * disabled (with the reason) while the lock is `blocked` or the person
     * may not act at all ([canAct] false: kiosk / read only).
     */
    fun buttons(lock: DoorLock, canAct: Boolean): List<LockButton> =
        lock.actions.filter { allowedByRight(lock.right, it) }.map { action ->
            val reason = lock.blocked ?: if (!canAct) DoorBlocked.READ_ONLY else null
            LockButton(action, enabled = reason == null, blockedReason = reason)
        }

    fun stateKind(lock: DoorLock): StateKind {
        if (!lock.state.reachable) return StateKind.UNREACHABLE
        return when (lock.state.lock) {
            "locked" -> StateKind.LOCKED
            "unlocked" -> StateKind.UNLOCKED
            "unlatched", "open" -> StateKind.OPEN
            "locking", "unlocking", "unlatching", "opening", "boot_run" -> StateKind.MOVING
            "online", "rto_active" -> StateKind.READY
            "jammed", "uncalibrated", "untrained" -> StateKind.PROBLEM
            else -> StateKind.UNKNOWN
        }
    }

    /** Battery worth showing: critical, or a known level ≤ 20 %. */
    fun batteryLow(state: DoorLockState): Boolean =
        state.batteryCritical || (state.batteryPercent != null && state.batteryPercent <= 20)

    /** `open` of an opener is the buzzer ("Summer"). */
    fun isBuzzer(lock: DoorLock, action: String): Boolean = lock.isOpener && action == DoorAction.OPEN

    /** The event detail's "Tür öffnen" / "Summer": only with the `open` right and the action offered. */
    fun canOpenFromEvent(lock: DoorLock?): Boolean =
        lock != null && DoorAction.OPEN in lock.actions && lock.right == DoorRight.OPEN

    /** Filter of the history list → `kind` query (null = all). */
    enum class HistoryFilter(val kind: String?) {
        ALL(null),
        ACTIONS(DoorHistoryItem.KIND_ACTION),
        RINGS(DoorHistoryItem.KIND_DING),
    }

    /** Seconds until "Neues Bild" works again ([retryUntilMs] from `retryAfter`), 0 = now. */
    fun secondsLeft(retryUntilMs: Long, nowMs: Long): Int =
        if (retryUntilMs <= nowMs) 0 else ((retryUntilMs - nowMs + 999) / 1000).toInt()

    /** Interval of `GET me` while the tab is visible. */
    const val POLL_MS = 10_000L
}
