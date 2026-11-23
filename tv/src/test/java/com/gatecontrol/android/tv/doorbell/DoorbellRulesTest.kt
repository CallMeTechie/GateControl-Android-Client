package com.gatecontrol.android.tv.doorbell

import com.gatecontrol.android.door.DoorAction
import com.gatecontrol.android.door.DoorBlocked
import com.gatecontrol.android.door.DoorCamera
import com.gatecontrol.android.door.DoorCameraPrefs
import com.gatecontrol.android.door.DoorLock
import com.gatecontrol.android.door.DoorLockState
import com.gatecontrol.android.door.DoorOverview
import com.gatecontrol.android.door.DoorPerson
import com.gatecontrol.android.door.DoorPushInfo
import com.gatecontrol.android.door.DoorRight
import com.gatecontrol.android.door.DoorRing
import com.gatecontrol.android.door.HomeCamera
import com.gatecontrol.android.door.HomeCameraSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DoorbellRulesTest {

    private val ding = DoorPushInfo(DoorPushInfo.DING, eventId = 7, cameraId = 3, lockId = null)
    private val motion = ding.copy(type = DoorPushInfo.MOTION)

    private fun decide(
        info: DoorPushInfo = ding,
        settings: DoorbellSettings = DoorbellSettings(),
        minute: Int = 12 * 60,
        front: Boolean = false,
        overlays: Boolean = false,
    ) = DoorbellRules.decide(info, settings, minute, front, overlays)

    @Test
    fun `quiet hours over midnight and within a day`() {
        // 22:00–07:00
        assertTrue(DoorbellRules.inQuietHours(22 * 60, 22 * 60, 7 * 60))
        assertTrue(DoorbellRules.inQuietHours(23 * 60 + 59, 22 * 60, 7 * 60))
        assertTrue(DoorbellRules.inQuietHours(0, 22 * 60, 7 * 60))
        assertTrue(DoorbellRules.inQuietHours(6 * 60 + 59, 22 * 60, 7 * 60))
        assertFalse(DoorbellRules.inQuietHours(7 * 60, 22 * 60, 7 * 60))
        assertFalse(DoorbellRules.inQuietHours(21 * 60 + 59, 22 * 60, 7 * 60))
        // 13:00–15:00
        assertTrue(DoorbellRules.inQuietHours(14 * 60, 13 * 60, 15 * 60))
        assertFalse(DoorbellRules.inQuietHours(15 * 60, 13 * 60, 15 * 60))
        assertFalse(DoorbellRules.inQuietHours(12 * 60, 13 * 60, 15 * 60))
        // same start and end: no quiet hours
        assertFalse(DoorbellRules.inQuietHours(10, 600, 600))
    }

    @Test
    fun `quiet hours keep the overlay away, only when switched on`() {
        val quiet = DoorbellSettings(quiet = true)
        assertEquals(DoorbellShow.QUIET, decide(settings = quiet, minute = 23 * 60, front = true))
        assertEquals(DoorbellShow.WINDOW, decide(settings = quiet, minute = 12 * 60, front = true))
        assertEquals(DoorbellShow.WINDOW, decide(settings = DoorbellSettings(quiet = false), minute = 23 * 60, front = true))
    }

    @Test
    fun `motion only when wanted, other door pushes never`() {
        assertEquals(DoorbellShow.NONE, decide(info = motion, front = true))
        assertEquals(DoorbellShow.WINDOW, decide(info = motion, settings = DoorbellSettings(motion = true), front = true))
        assertEquals(DoorbellShow.NONE, decide(info = ding.copy(type = "auto_unlocked"), front = true))
        assertEquals(DoorbellShow.NONE, decide(info = ding.copy(type = DoorPushInfo.HOME_CAMERA_EVENT), front = true))
    }

    @Test
    fun `the window over another app only with the overlay right`() {
        assertEquals(DoorbellShow.WINDOW, decide(front = true))
        assertEquals(DoorbellShow.WINDOW, decide(front = false, overlays = true))
        assertEquals(DoorbellShow.NOTICE, decide(front = false, overlays = false))
        assertEquals(DoorbellShow.FULLSCREEN, decide(settings = DoorbellSettings(mode = DoorbellMode.FULLSCREEN)))
        assertEquals(DoorbellShow.NOTICE, decide(settings = DoorbellSettings(mode = DoorbellMode.NOTICE), front = true))
    }

    @Test
    fun `modes keep their stored names, unknown is the window`() {
        for (m in DoorbellMode.entries) assertEquals(m, DoorbellMode.fromWire(m.wire))
        assertEquals(DoorbellMode.WINDOW, DoorbellMode.fromWire(null))
        assertEquals(DoorbellMode.WINDOW, DoorbellMode.fromWire("other"))
    }

    private fun lock(id: Long, right: String = DoorRight.OPEN, actions: List<String> = DoorAction.ALL, blocked: String? = null) = DoorLock(
        id = id, name = "Haustür $id", type = DoorLock.TYPE_SMARTLOCK, right = right, actions = actions, blocked = blocked,
        confirm = emptyList(), doorSensor = false,
        state = DoorLockState("locked", null, batteryCritical = false, batteryPercent = 80, batteryCharging = false, reachable = true, updatedAt = null),
    )

    private fun camera(id: Long, lockId: Long?) = DoorCamera(id, "Klingel", "doorbell", true, lockId, true, null, DoorCameraPrefs(ding = true, motion = false), null)

    private fun overview(locks: List<DoorLock>, cameras: List<DoorCamera>, canAct: Boolean = true) = DoorOverview(
        me = DoorPerson(1, "Person", canAct, autoUnlock = false, window = null, windowOpen = true),
        locks = locks,
        cameras = cameras,
        ring = DoorRing(connected = true, broken = false),
        serverTime = null,
    )

    @Test
    fun `the open button belongs to the ringing camera's lock and the person's right`() {
        val o = overview(listOf(lock(1), lock(2)), listOf(camera(3, lockId = 2)))
        assertEquals(2L, DoorbellRules.openLock(ding, o)?.id)
        assertEquals(DoorAction.OPEN, DoorbellRules.openAction(lock(2)))
        // only lock/unlock right: unlocking is the "open"
        assertEquals(DoorAction.UNLOCK, DoorbellRules.openAction(lock(2, right = DoorRight.LOCK)))
        // view only: no button
        assertNull(DoorbellRules.openLock(ding, overview(listOf(lock(2, right = DoorRight.VIEW)), listOf(camera(3, 2)))))
        // outside the time window: no button
        assertNull(DoorbellRules.openLock(ding, overview(listOf(lock(2, blocked = DoorBlocked.OUTSIDE_WINDOW)), listOf(camera(3, 2)))))
        // a kiosk / read-only person: no button
        assertNull(DoorbellRules.openLock(ding, overview(listOf(lock(2)), listOf(camera(3, 2)), canAct = false)))
        // no camera link: the only lock, never a guess among several
        assertEquals(5L, DoorbellRules.openLock(ding, overview(listOf(lock(5)), emptyList()))?.id)
        assertNull(DoorbellRules.openLock(ding, overview(listOf(lock(5), lock(6)), emptyList())))
        assertNull(DoorbellRules.openLock(ding, null))
    }

    private fun home(id: String) = HomeCamera(id, "Kamera $id", null, null, "ok", true, "h264", HomeCameraSettings("motion", emptyList(), null, "main", false), null, null)

    @Test
    fun `the chosen live camera only while the person still sees it`() {
        val cams = listOf(home("c000000000000001"), home("c000000000000002"))
        assertEquals("c000000000000002", DoorbellRules.camera(DoorbellSettings(cameraId = "c000000000000002"), cams)?.id)
        assertNull(DoorbellRules.camera(DoorbellSettings(cameraId = "c00000000000000f"), cams))
        assertNull(DoorbellRules.camera(DoorbellSettings(cameraId = null), cams))
    }

    @Test
    fun `the home camera assigned to the doorbell comes first, the setting is the fallback`() {
        val cams = listOf(home("c000000000000001"), home("c000000000000002"))
        val chosen = DoorbellSettings(cameraId = "c000000000000002")
        // the push carries it (Door 1.9): that one, whatever the setting says
        val pushed = ding.copy(homeCameraId = "c00000000000000a")
        assertEquals("c00000000000000a", DoorbellRules.liveCamera(pushed, null, chosen, cams))
        assertEquals("c00000000000000a", DoorbellRules.liveCamera(pushed, null, chosen, null))
        // the push without it: GET me of the ringing camera (3)
        val o = overview(emptyList(), listOf(camera(3, null).copy(homeCameraId = "c00000000000000b")))
        assertEquals("c00000000000000b", DoorbellRules.assignedCamera(ding, o))
        assertEquals("c00000000000000b", DoorbellRules.liveCamera(ding, o, chosen, cams))
        // another camera rang: its assignment does not count
        assertNull(DoorbellRules.assignedCamera(ding.copy(cameraId = 4), o))
        // nothing assigned (older Door): the camera of the settings while the person sees it
        val none = overview(emptyList(), listOf(camera(3, null)))
        assertNull(DoorbellRules.assignedCamera(ding, none))
        assertEquals("c000000000000002", DoorbellRules.liveCamera(ding, none, chosen, cams))
        assertEquals("c000000000000002", DoorbellRules.liveCamera(ding, null, chosen, cams))
        // nothing set either, or the list not readable: the doorbell's own picture
        assertNull(DoorbellRules.liveCamera(ding, none, DoorbellSettings(), cams))
        assertNull(DoorbellRules.liveCamera(ding, none, chosen, null))
    }
}
