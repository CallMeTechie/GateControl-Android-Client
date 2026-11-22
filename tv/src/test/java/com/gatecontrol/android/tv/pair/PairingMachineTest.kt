package com.gatecontrol.android.tv.pair

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** The pairing of GateControl TV: server, display code, polling, a code that runs out, the phone's approval. */
class PairingMachineTest {

    private val server = "https://gate.example.com"
    private val t0 = 1_790_000_000_000L
    private val code = PairCode(
        pairingId = "AbCdEfGhIjKlMnOpQrSt_-",
        pollSecret = "s".repeat(43),
        code = "K7Q4-MZAB",
        link = "gatecontrol://tv-pair?url=https%3A%2F%2Fgate.example.com&id=AbCdEfGhIjKlMnOpQrSt_-",
        expiresAt = t0 + 600_000L,
        intervalMs = 5_000L,
    )
    private val waiting = PairState.Waiting(server, code, nextPollAt = t0 + 5_000L)

    private fun reduce(state: PairState, event: PairEvent, now: Long = t0) = PairingMachine.reduce(state, event, now)

    @Test
    fun `start state`() {
        assertEquals(PairState.Paired, PairingMachine.initial(paired = true, server = server))
        assertEquals(PairState.Starting(server), PairingMachine.initial(paired = false, server = server))
        assertEquals(PairState.NeedServer(), PairingMachine.initial(paired = false, server = null))
    }

    @Test
    fun `the server is typed in once`() {
        assertEquals(PairState.Starting(server), reduce(PairState.NeedServer(), PairEvent.ServerEntered(" gate.example.com ")))
        assertEquals(PairState.Starting(server), reduce(PairState.NeedServer(), PairEvent.ServerEntered("https://gate.example.com/")))
        assertEquals(
            PairState.Starting("https://gate.example.com:8443"),
            reduce(PairState.NeedServer(), PairEvent.ServerEntered("gate.example.com:8443")),
        )
        assertEquals(PairState.NeedServer(ServerInputError.HTTPS_ONLY), reduce(PairState.NeedServer(), PairEvent.ServerEntered("http://gate.example.com")))
        assertEquals(PairState.NeedServer(ServerInputError.INVALID), reduce(PairState.NeedServer(), PairEvent.ServerEntered("gatecontrol")))
        assertEquals(PairState.NeedServer(ServerInputError.INVALID), reduce(PairState.NeedServer(), PairEvent.ServerEntered("")))
    }

    @Test
    fun `a code is shown and polled after the interval`() {
        assertEquals(waiting, reduce(PairState.Starting(server), PairEvent.Started(code)))
        val later = t0 + 5_000L
        assertEquals(
            PairState.Waiting(server, code, nextPollAt = later + 5_000L),
            reduce(waiting, PairEvent.PollPending(code.pairingId, 5_000L), later),
        )
        // the server asks for a longer interval
        assertEquals(
            PairState.Waiting(server, code.copy(intervalMs = 8_000L), nextPollAt = later + 8_000L),
            reduce(waiting, PairEvent.PollPending(code.pairingId, 8_000L), later),
        )
        // never faster than the minimum, whatever an answer says
        assertEquals(
            PairState.Waiting(server, code.copy(intervalMs = PairingMachine.MIN_INTERVAL_MS), nextPollAt = later + PairingMachine.MIN_INTERVAL_MS),
            reduce(waiting, PairEvent.PollPending(code.pairingId, 0L), later),
        )
    }

    @Test
    fun `slow_down makes the interval longer`() {
        val s = reduce(waiting, PairEvent.PollSlowDown(code.pairingId, 5_000L), t0 + 5_000L) as PairState.Waiting
        assertEquals(10_000L, s.code.intervalMs)
        assertEquals(t0 + 15_000L, s.nextPollAt)
        val s2 = reduce(s.copy(code = s.code.copy(intervalMs = 58_000L)), PairEvent.PollSlowDown(code.pairingId, null), t0 + 6_000L) as PairState.Waiting
        assertEquals(PairingMachine.MAX_INTERVAL_MS, s2.code.intervalMs)
    }

    @Test
    fun `no answer only means the next poll`() {
        assertEquals(
            waiting.copy(nextPollAt = t0 + 10_000L),
            reduce(waiting, PairEvent.PollUnreachable(code.pairingId), t0 + 5_000L),
        )
    }

    @Test
    fun `after ten minutes a new code comes without a word`() {
        assertSame(waiting, reduce(waiting, PairEvent.Tick, t0 + 599_999L))
        assertEquals(PairState.Starting(server), reduce(waiting, PairEvent.Tick, t0 + 600_000L))
        // a poll answer after the end too
        assertEquals(PairState.Starting(server), reduce(waiting, PairEvent.PollPending(code.pairingId, 5_000L), t0 + 600_001L))
        // the server forgot it (restart, locked after wrong codes): a new one as well
        assertEquals(PairState.Starting(server), reduce(waiting, PairEvent.PollExpired(code.pairingId), t0 + 10_000L))
    }

    @Test
    fun `the phone approved - the setup code is redeemed`() {
        val approved = reduce(waiting, PairEvent.PollApproved(code.pairingId, "AB12-CD34-EF56-7890", "Wohnzimmer"), t0 + 20_000L)
        assertEquals(PairState.Enrolling(server, "AB12-CD34-EF56-7890", "Wohnzimmer"), approved)
        // approved in the last moment still counts
        assertEquals(
            PairState.Enrolling(server, "AB12-CD34-EF56-7890", null),
            reduce(waiting, PairEvent.PollApproved(code.pairingId, "AB12-CD34-EF56-7890", null), t0 + 600_500L),
        )
        assertEquals(PairState.Paired, reduce(approved, PairEvent.Enrolled))
        assertEquals(PairState.Failed(server, PairError.LIMIT_REACHED), reduce(approved, PairEvent.EnrollFailed(PairError.LIMIT_REACHED)))
    }

    @Test
    fun `answers of an older code change nothing`() {
        val other = "ZzZzZzZzZzZzZzZzZzZz_-"
        assertSame(waiting, reduce(waiting, PairEvent.PollApproved(other, "AB12-CD34-EF56-7890", null)))
        assertSame(waiting, reduce(waiting, PairEvent.PollExpired(other)))
        assertSame(waiting, reduce(waiting, PairEvent.PollFailed(other, PairError.NOT_ALLOWED)))
        // and an answer in the wrong state is ignored
        assertEquals(PairState.Starting(server), reduce(PairState.Starting(server), PairEvent.PollPending(code.pairingId, 5_000L)))
        assertSame(PairState.Paired, reduce(PairState.Paired, PairEvent.Tick))
    }

    @Test
    fun `refusals stop with the reason, a new code on request`() {
        val failed = reduce(waiting, PairEvent.PollFailed(code.pairingId, PairError.NOT_ALLOWED), t0 + 5_000L)
        assertEquals(PairState.Failed(server, PairError.NOT_ALLOWED), failed)
        assertEquals(PairState.Starting(server), reduce(failed, PairEvent.Retry))
        assertEquals(PairState.Failed(server, PairError.NOT_SUPPORTED), reduce(PairState.Starting(server), PairEvent.StartFailed(PairError.NOT_SUPPORTED)))
    }

    @Test
    fun `another server, or the setup code from the portal`() {
        assertEquals(PairState.NeedServer(), reduce(waiting, PairEvent.ChangeServer))
        assertEquals(PairState.NeedServer(), reduce(PairState.Failed(server, PairError.NETWORK), PairEvent.ChangeServer))
        assertEquals(
            PairState.Enrolling("https://other.example.com", "AB12-CD34-EF56-7890", null),
            reduce(waiting, PairEvent.ManualSetup("other.example.com", "ab12 cd34 ef56 7890")),
        )
        // invalid input leaves everything as it is
        assertSame(waiting, reduce(waiting, PairEvent.ManualSetup("other.example.com", "K7Q4MZAB")))
        assertSame(waiting, reduce(waiting, PairEvent.ManualSetup("http://other.example.com", "AB12CD34EF567890")))
        // not while a setup code is redeemed, and not once paired
        val enrolling = PairState.Enrolling(server, "AB12-CD34-EF56-7890", null)
        assertSame(enrolling, reduce(enrolling, PairEvent.ChangeServer))
        assertSame(PairState.Paired, reduce(PairState.Paired, PairEvent.ManualSetup("other.example.com", "AB12CD34EF567890")))
    }

    @Test
    fun `secrets stay out of the log`() {
        val text = code.toString() + PairState.Enrolling(server, "AB12-CD34-EF56-7890", null) +
            PairEvent.PollApproved(code.pairingId, "AB12-CD34-EF56-7890", null) + PairEvent.ManualSetup("x", "AB12CD34EF567890")
        assertEquals(false, text.contains("sss"))
        assertEquals(false, text.contains("AB12"))
    }
}
