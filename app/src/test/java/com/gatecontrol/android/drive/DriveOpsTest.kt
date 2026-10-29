package com.gatecontrol.android.drive

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The end of a job on several items (Drive 1.12): told by the live event `op`, else found by asking; a late answer or
 * event never makes an ended job run again; a job Drive 1.12.0 left running with all its files done ends here.
 */
class DriveOpsTest {

    private val id = "j".repeat(22)
    private val events = MutableSharedFlow<DOp>(extraBufferCapacity = 16)
    private val api = mockk<DriveApi>()
    private val live = mockk<DriveLive>(relaxed = true).also { every { it.ops } returns events }

    private fun op(state: String, done: Int, files: List<DOpFile>? = null, undoUntil: String? = null) = DOp(
        id = id, kind = "move", state = state, total = 5, done = done, bytesTotal = 9_600_000, bytesDone = done * 1_920_000L,
        items = 5, label = "PXL_20261020.jpg", targetName = "FEC", files = files, undoUntil = undoUntil,
    )

    private fun file(seq: Int, state: String) = DOpFile(seq = seq, nodeId = seq.toLong(), name = "f$seq", size = 10, isDir = false, state = state)

    private fun TestScope.ops(scope: CoroutineScope = backgroundScope) = DriveOps(api, live, scope) { testScheduler.currentTime }

    @Test
    fun `the last live event ends the job`() = runTest {
        coEvery { api.startOp("move", any(), 7L, null) } returns DResult.Ok(op("running", 2))
        coEvery { api.op(id) } returns DResult.Ok(op("running", 2))
        val ops = ops()
        runCurrent()
        ops.start("move", listOf(1, 2, 3, 4, 5), 7L, null, "PXL_20261020.jpg", "FEC")
        val end = async { ops.await(id) }
        runCurrent()
        assertEquals(1, ops.running.value.size)
        events.emit(op("done", 5))
        runCurrent()
        assertEquals("done", end.await().op.state)
        assertTrue(ops.running.value.isEmpty(), "the ring becomes the plus again")
    }

    @Test
    fun `without events the app asks and finds the end`() = runTest {
        coEvery { api.startOp(any(), any(), any(), any()) } returns DResult.Ok(op("running", 2))
        var asked = 0
        coEvery { api.op(id) } answers { asked++; DResult.Ok(if (asked < 2) op("running", 4) else op("done", 5)) }
        val ops = ops()
        ops.start("move", listOf(1, 2, 3, 4, 5), 7L, null, null, null)
        val end = async { ops.await(id) }
        advanceTimeBy(DriveOps.POLL_MS * 3 + 1)
        assertEquals("done", end.await().op.state)
        assertEquals(5, end.await().op.done)
    }

    @Test
    fun `Drive 1_12_0 left the job running with 5 of 5 files - the app stops it and shows it as done`() = runTest {
        // the server's answer: running, done == total — for good
        coEvery { api.startOp(any(), any(), any(), any()) } returns DResult.Ok(op("running", 5))
        coEvery { api.op(id) } returns DResult.Ok(op("running", 5))
        coEvery { api.cancelOp(id) } returns DResult.Ok(op("cancelled", 5, undoUntil = "2026-10-29T12:15:00Z"))
        val ops = ops()
        ops.start("move", listOf(1, 2, 3, 4, 5), 7L, null, null, "FEC")
        val end = async { ops.await(id) }
        advanceTimeBy(DriveBulk.STALL_MS - DriveOps.POLL_MS)
        assertFalse(end.isCompleted, "not before it stood still a while")
        advanceTimeBy(DriveBulk.STALL_MS + DriveOps.POLL_MS * 2)
        runCurrent()
        val job = end.await()
        assertEquals("done", job.op.state)
        assertEquals("2026-10-29T12:15:00Z", job.op.undoUntil, "Rückgängig works")
        coVerify(exactly = 1) { api.cancelOp(id) }
        assertTrue(ops.running.value.isEmpty())
    }

    @Test
    fun `the end told before the answer of the start stays the end`() = runTest {
        val ops = ops()
        runCurrent()
        coEvery { api.startOp(any(), any(), any(), any()) } coAnswers {
            // the job ran to its end while the answer was on its way
            events.emit(op("done", 5))
            yield()
            DResult.Ok(op("running", 4))
        }
        ops.start("move", listOf(1, 2, 3, 4, 5), 7L, null, null, null)
        runCurrent()
        assertEquals("done", ops.jobs.value.getValue(id).op.state)
        assertTrue(ops.running.value.isEmpty())
        // a late event of before does not bring it back
        events.emit(op("running", 4))
        runCurrent()
        assertEquals("done", ops.jobs.value.getValue(id).op.state)
    }

    @Test
    fun `the items of the live event are merged with those asked for`() = runTest {
        coEvery { api.startOp(any(), any(), any(), any()) } returns DResult.Ok(op("running", 1))
        coEvery { api.op(id) } returns DResult.Ok(op("running", 1, files = (1..5).map { file(it, if (it == 1) "done" else if (it == 2) "running" else "waiting") }))
        val ops = ops()
        runCurrent()
        ops.start("move", listOf(1, 2, 3, 4, 5), 7L, null, null, null)
        advanceTimeBy(DriveOps.POLL_MS + 1)
        runCurrent()
        assertEquals(5, ops.running.value.single().op.files!!.size)
        events.emit(op("running", 3, files = listOf(file(2, "done"), file(3, "done"), file(4, "running"))))
        runCurrent()
        val files = ops.running.value.single().op.files!!
        assertEquals(listOf("done", "done", "done", "running", "waiting"), files.map { it.state })
        // an event without items (Drive 1.12.0) keeps them
        events.emit(op("running", 4))
        runCurrent()
        assertEquals(5, ops.running.value.single().op.files!!.size)
    }

    // ─── shown at once (Drive 1.12.2: the answer comes at once; an older Drive answers after up to 5 s) ───

    private val chosen = (1..5).map { DOpFile(seq = it, nodeId = it.toLong(), name = "f$it", size = 10, isDir = false, state = "waiting") }

    @Test
    fun `a job shows at once - the placeholder with its items waiting until the answer puts the job in its place`() = runTest {
        val answer = CompletableDeferred<DResult<DOp>>()
        coEvery { api.startOp("move", any(), 7L, null) } coAnswers { answer.await() }
        coEvery { api.op(id) } returns DResult.Ok(op("running", 1))
        val ops = ops()
        runCurrent()
        val pending = ops.prepare("move", 5, "f1", "FEC", chosen)
        runCurrent()
        val job = ops.running.value.single()
        assertTrue(job.pending, "the ring instead of the plus at once")
        assertEquals(listOf("waiting"), job.op.files!!.map { it.state }.distinct(), "the items from the selection, waiting")
        assertEquals(5, job.count)
        assertEquals("FEC", job.target)
        val started = async { ops.start("move", listOf(1, 2, 3, 4, 5), 7L, null, "f1", "FEC", pending) }
        // an older Drive: the answer takes a while — the placeholder stays meanwhile
        advanceTimeBy(5_000)
        assertTrue(ops.running.value.single().pending)
        answer.complete(DResult.Ok(op("running", 1, files = listOf(file(1, "done"), file(2, "running")))))
        runCurrent()
        assertTrue(started.await() is DResult.Ok)
        val now = ops.running.value.single()
        assertEquals(id, now.op.id, "replaced by the server's job")
        assertFalse(now.pending)
        assertEquals(listOf("done", "running"), now.op.files!!.map { it.state }, "the server's items, not mixed with the placeholder's")
        assertFalse(ops.jobs.value.containsKey(pending))
        events.emit(op("done", 5))
        runCurrent()
        assertTrue(ops.running.value.isEmpty())
    }

    @Test
    fun `a start the server refuses takes the placeholder away`() = runTest {
        coEvery { api.startOp(any(), any(), any(), any()) } returns DResult.Failed("job_limit", 409)
        val ops = ops()
        val pending = ops.prepare("trash", 5, "f1", null, chosen)
        val r = ops.start("trash", listOf(1, 2, 3, 4, 5), null, null, "f1", null, pending)
        assertEquals("job_limit", (r as DResult.Failed).code)
        assertTrue(ops.jobs.value.isEmpty(), "the plus again")
    }

    @Test
    fun `Abbrechen while it is being prepared - the job stops as soon as the server answers`() = runTest {
        val answer = CompletableDeferred<DResult<DOp>>()
        coEvery { api.startOp(any(), any(), any(), any()) } coAnswers { answer.await() }
        coEvery { api.cancelOp(id) } returns DResult.Ok(op("cancelled", 1, undoUntil = "2026-10-29T12:15:00Z"))
        val ops = ops()
        runCurrent()
        val pending = ops.prepare("move", 5, "f1", "FEC", chosen)
        val started = async { ops.start("move", listOf(1, 2, 3, 4, 5), 7L, null, "f1", "FEC", pending) }
        runCurrent()
        ops.cancel(pending)
        coVerify(exactly = 0) { api.cancelOp(any()) }
        answer.complete(DResult.Ok(op("running", 1)))
        started.await()
        runCurrent()
        coVerify(exactly = 1) { api.cancelOp(id) }
        assertEquals("cancelled", ops.jobs.value.getValue(id).op.state)
        assertTrue(ops.running.value.isEmpty())
    }

    @Test
    fun `the start goes on when the screen that asked is gone`() = runTest {
        val answer = CompletableDeferred<DResult<DOp>>()
        coEvery { api.startOp(any(), any(), any(), any()) } coAnswers { answer.await() }
        coEvery { api.op(id) } returns DResult.Ok(op("running", 1))
        val ops = ops()
        runCurrent()
        val pending = ops.prepare("move", 5, "f1", "FEC", chosen)
        val screen = launch { ops.start("move", listOf(1, 2, 3, 4, 5), 7L, null, "f1", "FEC", pending) }
        runCurrent()
        screen.cancel()
        answer.complete(DResult.Ok(op("running", 1)))
        runCurrent()
        assertEquals(id, ops.running.value.single().op.id, "the job is followed, the placeholder gone")
    }

    @Test
    fun `the undo shows at once too`() = runTest {
        val answer = CompletableDeferred<DResult<DOp>>()
        val undoId = "u".repeat(22)
        coEvery { api.undoOp(id) } coAnswers { answer.await() }
        coEvery { api.op(undoId) } returns DResult.Ok(op("running", 0).copy(id = undoId, kind = "undo"))
        val ops = ops()
        runCurrent()
        val r = async { ops.undo(id, 5) }
        runCurrent()
        val shown = ops.running.value.single()
        assertTrue(shown.pending)
        assertEquals("undo", shown.op.kind)
        assertEquals(5, shown.count)
        answer.complete(DResult.Ok(op("done", 5).copy(id = undoId, kind = "undo")))
        assertTrue(r.await() is DResult.Ok)
        assertTrue(ops.running.value.isEmpty())
        assertEquals("done", ops.jobs.value.getValue(undoId).op.state)
        // refused: nothing stays
        coEvery { api.undoOp("x".repeat(22)) } returns DResult.Failed("conflict", 409)
        ops.undo("x".repeat(22), 2)
        assertTrue(ops.running.value.isEmpty())
    }

    @Test
    fun `the items of a selection as a placeholder's`() {
        val nodes = listOf(
            DNode(11, "a1", 3, "Fotos", true, 0, null, null, 1, null, null, null),
            DNode(12, "a1", 3, "Brief.pdf", false, 2048, "application/pdf", "m", 1, null, null, null),
        )
        val files = DriveBulk.waitingFiles(nodes, nameOf = { it.name.uppercase() })
        assertEquals(listOf(1, 2), files.map { it.seq })
        assertEquals(listOf("FOTOS", "BRIEF.PDF"), files.map { it.name })
        assertEquals(listOf(0L, 2048L), files.map { it.size })
        assertTrue(files.all { it.state == "waiting" })
        assertEquals(1, DriveBulk.waitingFiles(nodes, max = 1).size)
    }
}
