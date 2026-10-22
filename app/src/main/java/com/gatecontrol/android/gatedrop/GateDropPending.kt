package com.gatecontrol.android.gatedrop

import kotlinx.coroutines.sync.Mutex

/** Outcome of one poll of `pending`. */
sealed interface GdPollOutcome {
    /** [processed] = handed to the receive path, [acked] = only acknowledged. */
    data class Done(val processed: List<String>, val acked: List<String>) : GdPollOutcome

    /** Another poll is running. */
    data object Busy : GdPollOutcome

    /** Polled successfully a moment ago. */
    data object TooSoon : GdPollOutcome

    /** 404: a plugin before GateDrop 1.1 (no `pending`); the pushes stay the only path. */
    data object Unsupported : GdPollOutcome

    /** 409 `no_device`: this login has no GateDrop device. */
    data object NoDevice : GdPollOutcome

    data object Offline : GdPollOutcome

    data class Failed(val code: String) : GdPollOutcome
}

/**
 * Fetches what was sent to this device while it was offline: `GET pending`
 * (GateDrop 1.1) lists the sends waiting for it, oldest first. Each one not
 * handled yet goes the way of a delivery push ([Host.process]); one that is
 * on the device already only gets its acknowledgement. Never two polls at
 * once; a poll right after a successful one is skipped unless forced. Pure
 * (no Android), unit-tested.
 */
class GateDropPendingPoller(
    private val host: Host,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
) {
    interface Host {
        suspend fun fetchPending(): GdResult<List<GdEntry>>

        fun isHandled(sendId: String): Boolean

        /** What this device keeps of the send (over the server or received directly). */
        fun local(sendId: String): GdReceived?

        fun markHandled(sendId: String)

        /** Receive [send] as a `{ send }` push would (notification, auto-save, acknowledgement). */
        suspend fun process(send: GdEntry)

        /** `POST sends/<id>/opened`; true when it went through. */
        suspend fun ack(sendId: String): Boolean
    }

    private val running = Mutex()
    @Volatile private var lastAnswerAt = 0L

    suspend fun poll(force: Boolean = false): GdPollOutcome {
        if (!running.tryLock()) return GdPollOutcome.Busy
        try {
            if (!force && lastAnswerAt > 0 && clock() - lastAnswerAt < minIntervalMs) return GdPollOutcome.TooSoon
            val sends = when (val r = host.fetchPending()) {
                is GdResult.Ok -> r.value
                is GdResult.Failed -> {
                    lastAnswerAt = clock()
                    return when {
                        r.http == 404 -> GdPollOutcome.Unsupported
                        r.code == CODE_NO_DEVICE -> GdPollOutcome.NoDevice
                        else -> GdPollOutcome.Failed(r.code)
                    }
                }
                GdResult.Offline, GdResult.NotConfigured -> return GdPollOutcome.Offline
            }
            lastAnswerAt = clock()
            val processed = mutableListOf<String>()
            val acked = mutableListOf<String>()
            for (send in sends) {
                when (GateDropLogic.pendingAction(send, host.isHandled(send.id), host.local(send.id))) {
                    GdPendingAction.PROCESS -> {
                        host.process(send)
                        processed += send.id
                    }
                    GdPendingAction.ACK -> {
                        host.markHandled(send.id)
                        if (host.ack(send.id)) acked += send.id
                    }
                    GdPendingAction.SKIP -> Unit
                }
            }
            return GdPollOutcome.Done(processed, acked)
        } finally {
            running.unlock()
        }
    }

    companion object {
        const val CODE_NO_DEVICE = "no_device"
        /** Triggers come in bursts (start, stream, network): one answer per half minute is enough. */
        const val MIN_INTERVAL_MS = 30_000L
    }
}
