package com.gatecontrol.android.gatedrop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/** Waiting for the other device of a direct transfer ends as soon as it gave up. */
class GateDropWaitTest {

    @Test
    fun `done - the bytes`() = runTest {
        val done = CompletableDeferred<Long>()
        launch { done.complete(42) }
        assertEquals(GdWait.Done(42), GateDropWait.await(done, 120_000) { GdTransferStatus(GdTransferStatus.OPEN) })
    }

    @Test
    fun `the other device reports a failure - ends after the next poll, not after the timeout`() = runTest {
        val done = CompletableDeferred<Long>()
        var polls = 0
        val w = GateDropWait.await(done, 120_000, pollMs = 2_000) {
            polls++
            if (polls < 2) GdTransferStatus(GdTransferStatus.OPEN) else GdTransferStatus(GdTransferStatus.FAILED, peerOk = false, peerError = "unreachable")
        }
        assertEquals(GdWait.PeerGaveUp("unreachable"), w)
        assertTrue(currentTime <= 4_000, "after ${currentTime} ms")
    }

    @Test
    fun `an expired ticket ends the wait too, older plugins without a reason`() = runTest {
        val w = GateDropWait.await(CompletableDeferred(), 120_000) { GdTransferStatus(GdTransferStatus.EXPIRED) }
        assertEquals(GdWait.PeerGaveUp("expired"), w)
    }

    @Test
    fun `poll errors are ignored, the timeout still ends it`() = runTest {
        val w = GateDropWait.await(CompletableDeferred(), 10_000) { throw IOException("offline") }
        assertEquals(GdWait.TimedOut, w)
        assertEquals(10_000, currentTime)
    }

    @Test
    fun `a broken listener`() = runTest {
        val done = CompletableDeferred<Long>()
        done.completeExceptionally(IOException("listener stopped"))
        assertEquals(GdWait.Broken, GateDropWait.await(done, 10_000) { null })
    }
}
