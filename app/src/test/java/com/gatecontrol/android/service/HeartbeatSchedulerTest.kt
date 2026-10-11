package com.gatecontrol.android.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HeartbeatSchedulerTest {

    private val minute = 60_000L

    @Test
    fun `interval is 60 s without push control and 15 min with it`() {
        assertEquals(60_000L, HeartbeatScheduler.intervalMs(pushLive = false, succeeded = true))
        assertEquals(15 * 60_000L, HeartbeatScheduler.intervalMs(pushLive = true, succeeded = true))
        // A failed or skipped heartbeat is retried soon, push or not.
        assertEquals(60_000L, HeartbeatScheduler.intervalMs(pushLive = true, succeeded = false))
        assertEquals(1_000L + 15 * minute, HeartbeatScheduler.nextDueAt(1_000L, pushLive = true, succeeded = true))
        assertEquals(1_000L + minute, HeartbeatScheduler.nextDueAt(1_000L, pushLive = false, succeeded = true))
    }

    /** Runs the scheduler on virtual time (until the test ends); returns the times (ms) of each heartbeat. */
    private fun TestScope.start(live: MutableStateFlow<Boolean>, result: () -> Boolean = { true }): MutableList<Long> {
        val beats = mutableListOf<Long>()
        backgroundScope.launch {
            HeartbeatScheduler(live, clock = { testScheduler.currentTime }).run {
                beats += testScheduler.currentTime
                result()
            }
        }
        return beats
    }

    @Test
    fun `first heartbeat right away, then every minute without push`() = runTest {
        val beats = start(MutableStateFlow(false))
        runCurrent()
        advanceTimeBy(3 * minute + 1)
        assertEquals(listOf(0L, minute, 2 * minute, 3 * minute), beats)
    }

    @Test
    fun `with push control live the heartbeat runs every 15 minutes`() = runTest {
        val beats = start(MutableStateFlow(true))
        runCurrent()
        advanceTimeBy(31 * minute)
        assertEquals(listOf(0L, 15 * minute, 30 * minute), beats)
    }

    @Test
    fun `push dropping brings the next heartbeat forward`() = runTest {
        val live = MutableStateFlow(true)
        val beats = start(live)
        runCurrent()
        advanceTimeBy(5 * minute)
        assertEquals(listOf(0L), beats)
        // Push goes down 5 min after the last heartbeat: 60 s are long over → right away.
        live.value = false
        runCurrent()
        assertEquals(listOf(0L, 5 * minute), beats)
        advanceTimeBy(minute + 1)
        assertEquals(listOf(0L, 5 * minute, 6 * minute), beats)
    }

    @Test
    fun `push coming up stretches the pending wait`() = runTest {
        val live = MutableStateFlow(false)
        val beats = start(live)
        runCurrent()
        advanceTimeBy(30_000)
        live.value = true
        advanceTimeBy(14 * minute)
        assertEquals(listOf(0L), beats)
        advanceTimeBy(minute)
        assertEquals(listOf(0L, 15 * minute), beats)
    }

    @Test
    fun `a failed heartbeat is retried after a minute even with push`() = runTest {
        var ok = false
        val beats = start(MutableStateFlow(true)) { ok }
        runCurrent()
        advanceTimeBy(minute + 1)
        assertEquals(listOf(0L, minute), beats)
        ok = true
        // The retry at 1 min was still failing (ok was read before) — next one 1 min later succeeds.
        advanceTimeBy(minute)
        assertEquals(listOf(0L, minute, 2 * minute), beats)
        advanceTimeBy(15 * minute)
        assertEquals(listOf(0L, minute, 2 * minute, 17 * minute), beats)
    }
}
