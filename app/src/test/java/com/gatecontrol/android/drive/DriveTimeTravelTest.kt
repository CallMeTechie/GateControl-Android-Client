package com.gatecontrol.android.drive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DriveTimeTravelTest {

    private val folder = DNode(5, "mine:7", 1, "Docs", true, 0, null, null, 1, null, null, null)
    private val now = 1_800_000_000_000L
    private fun preview(n: Int) = DTimeTravelPreview("at", null, n, 0, 0, 0, emptyList(), false)
    private val result = DTimeTravelResult("at", "2026-10-28T09:00:00Z", 1, 0, 2, 0, 0)

    @Test
    fun `a time in the future is refused`() {
        val t = DriveTimeTravel(folder).pick(now + 1, now)
        assertEquals(DriveTimeTravel.Step.PICK, t.step)
        assertEquals(DriveTimeTravel.FUTURE, t.error)
        assertNull(t.atMs)
    }

    @Test
    fun `pick, preview, run, undo once`() {
        var t = DriveTimeTravel(folder).pick(now - 60_000, now)
        assertEquals(DriveTimeTravel.Step.PREVIEWING, t.step)
        assertEquals(java.time.Instant.ofEpochMilli(now - 60_000).toString(), t.atIso)
        assertFalse(t.canRun)
        t = t.previewed(DResult.Ok(preview(3)))
        assertEquals(DriveTimeTravel.Step.PREVIEW, t.step)
        assertTrue(t.canRun)
        t = t.running()
        assertEquals(DriveTimeTravel.Step.RUNNING, t.step)
        // a second run while one runs changes nothing
        assertEquals(t, t.running())
        t = t.ran(DResult.Ok(result))
        assertEquals(DriveTimeTravel.Step.DONE, t.step)
        assertTrue(t.canUndo)
        t = t.undoing()
        assertEquals(DriveTimeTravel.Step.UNDOING, t.step)
        t = t.undid(DResult.Ok(result.copy(before = "later")))
        assertEquals(DriveTimeTravel.Step.DONE, t.step)
        assertTrue(t.undone)
        assertFalse(t.canUndo)
    }

    @Test
    fun `nothing to do cannot run`() {
        val t = DriveTimeTravel(folder).pick(now - 1, now).previewed(DResult.Ok(preview(0)))
        assertFalse(t.canRun)
        assertEquals(t, t.running())
    }

    @Test
    fun `failures go back a step with their code`() {
        val failed = DriveTimeTravel(folder).pick(now - 1, now).previewed(DResult.Failed("forbidden", 403))
        assertEquals(DriveTimeTravel.Step.PICK, failed.step)
        assertEquals("forbidden", failed.error)
        val ran = DriveTimeTravel(folder).pick(now - 1, now).previewed(DResult.Ok(preview(1))).running().ran(DResult.Offline)
        assertEquals(DriveTimeTravel.Step.PREVIEW, ran.step)
        assertEquals("offline", ran.error)
        // a late preview after the person picked again is ignored
        val stale = DriveTimeTravel(folder).pick(now - 1, now).previewed(DResult.Ok(preview(1)))
        assertEquals(stale, stale.previewed(DResult.Ok(preview(5))))
    }

    @Test
    fun `refusals of the time are named`() {
        val body = com.google.gson.JsonParser.parseString("""{"ok":false,"code":"invalid","field":"at","ops":2500,"max":2000}""").asJsonObject
        val many = DriveTimeTravel(folder).pick(now - 1, now).previewed(DResult.Failed("invalid", 400, body))
        assertEquals("${DriveTimeTravel.TOO_MANY}:2000", many.error)
        val future = com.google.gson.JsonParser.parseString("""{"ok":false,"code":"invalid","field":"at"}""").asJsonObject
        assertEquals(DriveTimeTravel.FUTURE, DriveTimeTravel(folder).pick(now - 1, now).previewed(DResult.Failed("invalid", 400, future)).error)
    }

    @Test
    fun `no undo without before, and a failed undo keeps the result`() {
        val t = DriveTimeTravel(folder).pick(now - 1, now).previewed(DResult.Ok(preview(1))).running().ran(DResult.Ok(result.copy(before = null)))
        assertFalse(t.canUndo)
        assertEquals(t, t.undoing())
        val u = DriveTimeTravel(folder).pick(now - 1, now).previewed(DResult.Ok(preview(1))).running().ran(DResult.Ok(result)).undoing().undid(DResult.Failed("device_locked", 423))
        assertEquals(DriveTimeTravel.Step.DONE, u.step)
        assertEquals("device_locked", u.error)
        assertEquals(result, u.result)
    }
}
