package com.gatecontrol.android.notify

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class PushRetryPolicyTest {

    private val policy = PushRetryPolicy(Random(42))

    private fun after(outcome: StreamOutcome, attempt: Int = 0) =
        assertInstanceOf(RetryDecision.After::class.java, policy.decide(outcome, attempt))

    @Test
    fun `backoff doubles from 1 s and is capped at 5 min, with jitter in the upper half`() {
        for (attempt in 0..30) {
            val cap = minOf(1_000L shl minOf(attempt, 20), 300_000L)
            repeat(20) {
                val d = policy.backoffMs(attempt)
                assertTrue(d in cap / 2..cap, "attempt $attempt: $d not in ${cap / 2}..$cap")
            }
        }
        assertTrue(policy.backoffMs(0) <= 1_000L)
        assertTrue(policy.backoffMs(12) >= 150_000L)
    }

    @Test
    fun `network errors back off with the attempt count`() {
        val d = after(StreamOutcome.NetworkError("SocketTimeoutException"), attempt = 4)
        assertEquals(WaitReason.BACKOFF, d.reason)
        assertTrue(d.delayMs in 8_000L..16_000L)
    }

    @Test
    fun `a stream that was open long enough restarts the backoff`() {
        val d = after(StreamOutcome.Ended(openForMs = 3_600_000L, sawHello = true), attempt = 9)
        assertTrue(d.delayMs <= 1_000L)
        val short = after(StreamOutcome.Ended(openForMs = 2_000L, sawHello = true), attempt = 9)
        assertTrue(short.delayMs >= 150_000L)
    }

    @Test
    fun `404 and push_disabled retry hourly`() {
        val old = after(StreamOutcome.HttpError(404, null))
        assertEquals(WaitReason.SERVER_UNSUPPORTED, old.reason)
        assertTrue(old.delayMs in 3_600_000L..3_960_000L)
        val off = after(StreamOutcome.HttpError(503, "push_disabled"))
        assertEquals(WaitReason.PUSH_DISABLED, off.reason)
        assertTrue(off.delayMs >= 3_600_000L)
    }

    @Test
    fun `too_many_streams backs off normally`() {
        val d = after(StreamOutcome.HttpError(503, "too_many_streams"), attempt = 2)
        assertEquals(WaitReason.SERVER_BUSY, d.reason)
        assertTrue(d.delayMs in 2_000L..4_000L)
    }

    @Test
    fun `429 waits at least 15 minutes, longer when the server says so`() {
        val d = after(StreamOutcome.HttpError(429, "rate_limited"))
        assertEquals(WaitReason.RATE_LIMITED, d.reason)
        assertTrue(d.delayMs >= 15 * 60_000L)
        val longer = after(StreamOutcome.HttpError(429, "rate_limited", retryAfterS = 1800))
        assertTrue(longer.delayMs >= 30 * 60_000L)
    }

    @Test
    fun `direct_not_allowed waits for the VPN or an hour`() {
        val d = after(StreamOutcome.HttpError(403, "direct_not_allowed"))
        assertEquals(WaitReason.DIRECT_NOT_ALLOWED, d.reason)
        assertTrue(d.delayMs >= 3_600_000L)
    }

    @Test
    fun `401 and 403 stop until the setup changes`() {
        for (outcome in listOf(
            StreamOutcome.HttpError(401, null),
            StreamOutcome.HttpError(403, "token_required"),
            StreamOutcome.HttpError(403, "scope_required"),
        )) {
            assertEquals(RetryDecision.Blocked(BlockReason.AUTH), policy.decide(outcome, 0))
        }
        assertEquals(
            RetryDecision.Blocked(BlockReason.MACHINE_BINDING),
            policy.decide(StreamOutcome.HttpError(403, "Token is bound to a different machine", bindingRejected = true), 0),
        )
    }

    @Test
    fun `a kick reconnects immediately, 5xx backs off`() {
        assertEquals(0L, after(StreamOutcome.Kicked).delayMs)
        assertEquals(WaitReason.BACKOFF, after(StreamOutcome.HttpError(502, null)).reason)
        assertEquals(WaitReason.BACKOFF, after(StreamOutcome.Stale).reason)
        assertEquals(WaitReason.BACKOFF, after(StreamOutcome.NotAStream).reason)
    }
}
