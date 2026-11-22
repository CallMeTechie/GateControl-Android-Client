package com.gatecontrol.android.tv.pair

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The server's answers of pair/start, pair/poll and enroll (fields as in the server's routes/api/client/pair.js). */
class PairAnswersTest {

    private val server = "https://gate.example.com"
    private val id = "AbCdEfGhIjKlMnOpQrSt_-"
    private val secret = "A".repeat(43)
    private val now = 1_790_000_000_000L
    private val serverLink = "gatecontrol://tv-pair?url=https%3A%2F%2Fgate.example.com&id=$id"

    private fun start(
        status: Int = 201,
        pairingId: String? = id,
        pollSecret: String? = secret,
        expiresAt: Long? = now + 600_000L,
        interval: Int? = 5,
        link: String? = serverLink,
        errorBody: String? = null,
    ) = PairAnswers.start(status, server, pairingId, pollSecret, "K7Q4-MZAB", expiresAt, interval, link, errorBody, now)

    @Test
    fun `a started pairing`() {
        assertEquals(PairEvent.Started(PairCode(id, secret, "K7Q4-MZAB", serverLink, now + 600_000L, 5_000L)), start())
    }

    @Test
    fun `the QR link is the server's only when it fits, else the TV builds it`() {
        val built = "gatecontrol://tv-pair?url=https%3A%2F%2Fgate.example.com&id=$id"
        // the server knows itself under another name (APP_BASE_URL): the QR code holds the address the TV uses
        val foreign = start(link = "gatecontrol://tv-pair?url=https%3A%2F%2Fother.example.com&id=$id") as PairEvent.Started
        assertEquals(built, foreign.code.link)
        val missing = start(link = null) as PairEvent.Started
        assertEquals(built, missing.code.link)
    }

    @Test
    fun `end and interval are kept sane`() {
        assertEquals(now + PairAnswers.TTL_MS, (start(expiresAt = null) as PairEvent.Started).code.expiresAt)
        // the TV's clock is off (an end in the past or far away): the server's 10 minutes from now
        assertEquals(now + PairAnswers.TTL_MS, (start(expiresAt = now - 1) as PairEvent.Started).code.expiresAt)
        assertEquals(now + PairAnswers.TTL_MS, (start(expiresAt = now + 86_400_000L) as PairEvent.Started).code.expiresAt)
        assertEquals(5_000L, (start(interval = null) as PairEvent.Started).code.intervalMs)
        assertEquals(PairingMachine.MIN_INTERVAL_MS, (start(interval = 0) as PairEvent.Started).code.intervalMs)
    }

    @Test
    fun `start errors`() {
        assertEquals(PairEvent.StartFailed(PairError.NOT_SUPPORTED), start(status = 404))
        assertEquals(PairEvent.StartFailed(PairError.RATE_LIMITED), start(status = 429))
        assertEquals(PairEvent.StartFailed(PairError.BUSY), start(status = 503, errorBody = """{"ok":false,"error":"busy"}"""))
        assertEquals(PairEvent.StartFailed(PairError.NETWORK), start(status = 502))
        assertEquals(PairEvent.StartFailed(PairError.INVALID_ANSWER), start(pairingId = "short"))
        assertEquals(PairEvent.StartFailed(PairError.INVALID_ANSWER), start(pollSecret = null))
    }

    @Test
    fun `poll answers`() {
        assertEquals(PairEvent.PollPending(id, 5_000L), PairAnswers.poll(id, 200, "pending", 5, null, null, null))
        assertEquals(
            PairEvent.PollApproved(id, "AB12-CD34-EF56-7890", "Wohnzimmer"),
            PairAnswers.poll(id, 200, "approved", null, "AB12-CD34-EF56-7890", "Wohnzimmer", null),
        )
        assertEquals(PairEvent.PollApproved(id, "AB12-CD34-EF56-7890", null), PairAnswers.poll(id, 200, "approved", null, "AB12-CD34-EF56-7890", " ", null))
        assertEquals(PairEvent.PollFailed(id, PairError.INVALID_ANSWER), PairAnswers.poll(id, 200, "approved", null, null, null, null))
        assertEquals(PairEvent.PollExpired(id), PairAnswers.poll(id, 400, null, null, null, null, """{"ok":false,"error":"invalid_or_expired"}"""))
        assertEquals(PairEvent.PollSlowDown(id, 5_000L), PairAnswers.poll(id, 429, null, null, null, null, """{"ok":false,"error":"slow_down","interval":5}"""))
        assertEquals(PairEvent.PollSlowDown(id, null), PairAnswers.poll(id, 429, null, null, null, null, """{"ok":false,"error":"rate_limited"}"""))
        assertEquals(PairEvent.PollFailed(id, PairError.NOT_ALLOWED), PairAnswers.poll(id, 403, null, null, null, null, """{"ok":false,"error":"enroll_not_allowed"}"""))
        assertEquals(PairEvent.PollFailed(id, PairError.USER_DISABLED), PairAnswers.poll(id, 403, null, null, null, null, """{"ok":false,"error":"user_disabled"}"""))
        assertEquals(PairEvent.PollFailed(id, PairError.LIMIT_REACHED), PairAnswers.poll(id, 403, null, null, null, null, """{"ok":false,"error":"limit_reached"}"""))
        assertEquals(PairEvent.PollUnreachable(id), PairAnswers.poll(id, 502, null, null, null, null, "<html>Bad Gateway</html>"))
        assertEquals(PairEvent.PollUnreachable(id), PairAnswers.poll(id, 500, null, null, null, null, """{"ok":false,"error":"failed"}"""))
    }

    @Test
    fun `enroll errors`() {
        assertEquals(PairError.INVALID_SETUP_CODE, PairAnswers.enrollError("invalid_or_expired"))
        assertEquals(PairError.LIMIT_REACHED, PairAnswers.enrollError("limit_reached"))
        assertEquals(PairError.USER_DISABLED, PairAnswers.enrollError("user_disabled"))
        assertEquals(PairError.NOT_ALLOWED, PairAnswers.enrollError("no_valid_scopes"))
        assertEquals(PairError.ENROLL_FAILED, PairAnswers.enrollError(null))
    }
}
