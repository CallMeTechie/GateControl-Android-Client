package com.gatecontrol.android.ui.drive

import android.content.Context
import com.gatecontrol.android.drive.DArea
import com.gatecontrol.android.drive.DCrumb
import com.gatecontrol.android.drive.DFolder
import com.gatecontrol.android.drive.DMe
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DOp
import com.gatecontrol.android.drive.DOpCheck
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveBulk
import com.gatecontrol.android.drive.DriveLive
import com.gatecontrol.android.drive.DriveOps
import com.gatecontrol.android.drive.DriveTransfers
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * "Hierher verschieben" (Drive 1.12.2): once the check is through and the question about taken names answered, "Ziel
 * wählen" closes and the selection ends at once; the job shows as "wird vorbereitet" with its items waiting until the
 * server answers (an older Drive after up to 5 s). A start the server refuses: the placeholder goes, the message comes,
 * the selection is back. The same for "Löschen".
 */
class DriveBulkActionsTest {

    private val opId = "o".repeat(22)
    private val events = MutableSharedFlow<DOp>(extraBufferCapacity = 16)
    private val area = DArea("a1", "mine", "Meine Dateien", "manage", null, 1L, false)
    private val me = DMe(1, "Anna", "family", true, null, listOf(area), 0, null, null, emptyMap(), features = setOf(DMe.FEATURE_BULK))
    private val live = mockk<DriveLive>(relaxed = true).also {
        every { it.ops } returns events
        every { it.me } returns MutableStateFlow(me)
    }
    private val api = mockk<DriveApi>()
    private val context = mockk<Context>(relaxed = true)

    private fun node(id: Long, name: String, parent: Long = 2L, dir: Boolean = false) =
        DNode(id, "a1", parent, name, dir, if (dir) 0 else 100, null, null, 1, null, null, null)

    private val here = node(2, "Dokumente", parent = 1L, dir = true)
    private val files = listOf(node(10, "a.pdf"), node(11, "b.pdf"), node(12, "c.pdf"))
    private val dest = node(3, "Rechnungen", parent = 1L, dir = true)
    private val folderHere = DFolder(here, listOf(DCrumb(1, "Meine Dateien")), "manage", files, files.size, null)
    private val folderDest = DFolder(dest, listOf(DCrumb(1, "Meine Dateien")), "manage", emptyList(), 0, null)

    private fun op(state: String, done: Int) = DOp(opId, "move", state, 3, done, 300, done * 100L, items = 3, targetName = "Rechnungen")

    private fun TestScope.actions(ops: DriveOps) = DriveBulkActions(
        context, backgroundScope, api, ops, mockk<DriveTransfers>(relaxed = true), live,
        items = { files }, here = { folderHere },
    )

    private fun TestScope.ops() = DriveOps(api, live, backgroundScope) { testScheduler.currentTime }

    private fun TestScope.chooseRechnungen(bulk: DriveBulkActions) {
        coEvery { api.areas() } returns DResult.Ok(listOf(area))
        coEvery { api.folder(2L, any(), any(), any(), any()) } returns DResult.Ok(folderHere)
        coEvery { api.folder(3L, any(), any(), any(), any()) } returns DResult.Ok(folderDest)
        coEvery { api.opCheck("move", any(), 3L) } returns DResult.Ok(DOpCheck(3, 0, 300, emptyList(), emptyList(), items = listOf(10L, 11L, 12L)))
        files.forEach { bulk.toggle(it) }
        bulk.openTarget(DriveBulk.Mode.MOVE)
        runCurrent()
        bulk.browse(3L)
        runCurrent()
    }

    @Test
    fun `Hierher verschieben - the target closes and the selection ends before the server answers, the job shows at once`() = runTest {
        val answer = CompletableDeferred<DResult<DOp>>()
        coEvery { api.startOp("move", listOf(10L, 11L, 12L), 3L, null) } coAnswers { answer.await() }
        coEvery { api.op(opId) } returns DResult.Ok(op("running", 1))
        val ops = ops()
        val bulk = actions(ops)
        runCurrent()
        chooseRechnungen(bulk)
        bulk.confirmTarget()
        runCurrent()
        // the server has not answered (an older Drive works up to 5 s first)
        assertNull(bulk.target.value, "Ziel wählen is closed")
        assertTrue(bulk.selected.value.isEmpty(), "the selection ended")
        val job = ops.running.value.single()
        assertTrue(job.pending, "wird vorbereitet")
        assertEquals("move", job.op.kind)
        assertEquals("Rechnungen", job.target)
        assertEquals(listOf("a.pdf", "b.pdf", "c.pdf"), job.op.files!!.map { it.name })
        assertTrue(job.op.files!!.all { it.state == "waiting" })
        answer.complete(DResult.Ok(op("running", 1)))
        runCurrent()
        val now = ops.running.value.single()
        assertEquals(opId, now.op.id)
        assertFalse(now.pending)
        events.emit(op("done", 3).copy(undoUntil = "2099-01-01T00:00:00Z"))
        runCurrent()
        val snack = bulk.snack.value!!
        assertEquals(listOf("move", "Rechnungen", opId), listOf(snack.kind, snack.target, snack.undoId))
        assertEquals(3, snack.count)
    }

    @Test
    fun `a start the server refuses - the placeholder goes, the message comes, the selection is back`() = runTest {
        coEvery { api.startOp(any(), any(), any(), any()) } returns DResult.Failed("job_limit", 409)
        val ops = ops()
        val bulk = actions(ops)
        runCurrent()
        chooseRechnungen(bulk)
        bulk.confirmTarget()
        runCurrent()
        assertTrue(ops.jobs.value.isEmpty(), "the plus again")
        assertEquals("job_limit", bulk.message.value)
        assertEquals(setOf(10L, 11L, 12L), bulk.selected.value)
        assertNull(bulk.target.value)
    }

    @Test
    fun `Loeschen - the trash shows at once too`() = runTest {
        val answer = CompletableDeferred<DResult<DOp>>()
        coEvery { api.startOp("trash", listOf(10L, 11L), null, null) } coAnswers { answer.await() }
        val ops = ops()
        val bulk = actions(ops)
        runCurrent()
        bulk.toggle(files[0])
        bulk.toggle(files[1])
        bulk.delete()
        runCurrent()
        assertTrue(bulk.selected.value.isEmpty())
        val job = ops.running.value.single()
        assertTrue(job.pending)
        assertEquals(listOf("a.pdf", "b.pdf"), job.op.files!!.map { it.name })
        answer.complete(DResult.Ok(DOp(opId, "trash", "done", 2, 2, 200, 200, items = 2)))
        runCurrent()
        assertTrue(ops.running.value.isEmpty())
        assertEquals("trash", bulk.snack.value!!.kind)
        // refused: the selection is back
        coEvery { api.startOp("trash", listOf(12L), null, null) } returns DResult.Offline
        bulk.toggle(files[2])
        bulk.delete()
        runCurrent()
        assertTrue(ops.running.value.isEmpty())
        assertEquals(setOf(12L), bulk.selected.value)
    }
}
