package com.gatecontrol.android.drive

import com.gatecontrol.android.drive.backup.DriveCameraUploads
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Drive 1.10 (`photosFolder`): the Photos tab's filter "Nur Kamera-Uploads" and the camera uploads' folder. */
class DrivePhotosCameraTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private fun me(vararg features: String) =
        DMe(7, "Ma", "family", true, null, emptyList(), 0, null, null, emptyMap(), features = features.toSet())

    @Test
    fun `the filter parameters of photos, months and memories`() {
        assertEquals(mapOf("limit" to "120"), DriveJson.photosQuery(limit = 120))
        assertEquals(mapOf("limit" to "120", "camera" to "1"), DriveJson.photosQuery(limit = 120, camera = true))
        assertEquals(mapOf("before" to "2026-10-01T00:00:00Z", "limit" to "200", "camera" to "1"), DriveJson.photosQuery(before = "2026-10-01T00:00:00Z", limit = 500, camera = true))
        assertEquals(mapOf("camera" to "1"), DriveJson.photosQuery(camera = true))
        assertEquals(emptyMap<String, String>(), DriveJson.photosQuery())
        assertEquals(mapOf("date" to "2026-10-28", "camera" to "1"), DriveJson.photosQuery(camera = true, date = "2026-10-28"))
        assertEquals(mapOf("area" to "mine:7"), DriveJson.photosQuery(area = "mine:7"))
        // an invalid area: no request
        assertNull(DriveJson.photosQuery(area = "bad area"))
    }

    @Test
    fun `the filter is sent only while Drive knows the camera uploads`() {
        assertTrue(DriveLogic.cameraFilter(true, me(DMe.FEATURE_PHOTOS, DMe.FEATURE_PHOTOS_FOLDER)))
        assertFalse(DriveLogic.cameraFilter(false, me(DMe.FEATURE_PHOTOS, DMe.FEATURE_PHOTOS_FOLDER)))
        assertFalse(DriveLogic.cameraFilter(true, me(DMe.FEATURE_PHOTOS)))
        assertFalse(DriveLogic.cameraFilter(true, null))
    }

    @Test
    fun `the camera uploads' folder`() {
        val f = DriveJson.cameraFolder(json("""{"ok":true,"folder":{"id":41,"path":"Bilder/Kamera-Uploads","areaId":"mine:7"},"created":true}"""))!!
        assertEquals(DCameraFolder(41, "Bilder/Kamera-Uploads", "mine:7"), f)
        assertNull(DriveJson.cameraFolder(json("""{"ok":true,"folder":null}""")))
        assertNull(DriveJson.cameraFolder(json("""{"ok":true,"folder":{"id":0,"areaId":"mine:7"}}""")))
        assertNull(DriveJson.cameraFolder(json("""{"ok":true,"folder":{"id":4,"areaId":"no area"}}""")))
    }

    // ─── where the photo backup puts a photo ────────────────────────

    private fun dir(id: Long, name: String, parent: Long) = DNode(id, "mine:7", parent, name, true, 0, null, null, 1, null, null, null)

    private fun folder(id: Long, vararg items: DNode) = DResult.Ok(DFolder(dir(id, "x", 0), emptyList(), "manage", items.toList(), items.size, null))

    @Test
    fun `with photosFolder the server's folder, the year and month made below it`() = runTest {
        val api = mockk<DriveApi>()
        val live = mockk<DriveLive>()
        coEvery { live.supports(DMe.FEATURE_PHOTOS_FOLDER) } returns true
        coEvery { api.cameraFolder() } returns DResult.Ok(DCameraFolder(41, "Bilder/Kamera-Uploads", "mine:7"))
        coEvery { api.folder(41, limit = 1000) } returns folder(41, dir(50, "2025", 41))
        coEvery { api.createFolder(41, "2026", false) } returns DResult.Ok(dir(60, "2026", 41))
        coEvery { api.folder(60, limit = 1000) } returns folder(60)
        coEvery { api.createFolder(60, "10", false) } returns DResult.Ok(dir(61, "10", 60))
        val camera = DriveCameraUploads(api, live)
        assertEquals(DResult.Ok(61L), camera.folder(listOf("2026", "10")))
        // known now: no more requests
        assertEquals(DResult.Ok(61L), camera.folder(listOf("2026", "10")))
        coVerify(exactly = 1) { api.cameraFolder() }
        coVerify(exactly = 1) { api.createFolder(60, "10", false) }
        coVerify(exactly = 0) { api.areas() }
    }

    @Test
    fun `without photosFolder Bilder and Kamera-Uploads in my own files`() = runTest {
        val api = mockk<DriveApi>()
        val live = mockk<DriveLive>()
        coEvery { live.supports(DMe.FEATURE_PHOTOS_FOLDER) } returns false
        coEvery { api.me() } returns DResult.Ok(me())
        coEvery { api.areas() } returns DResult.Ok(
            listOf(DArea("family:1", "family", "Familie", "write", "member", 1, false), DArea("mine:7", "mine", "Meine Dateien", "manage", "owner", 2, false)),
        )
        // "bilder" is there (the name without case), Kamera-Uploads not yet
        coEvery { api.folder(2, limit = 1000) } returns folder(2, dir(10, "bilder", 2))
        coEvery { api.folder(10, limit = 1000) } returns folder(10)
        coEvery { api.createFolder(10, "Kamera-Uploads", false) } returns DResult.Ok(dir(11, "Kamera-Uploads", 10))
        coEvery { api.folder(11, limit = 1000) } returns folder(11, dir(12, "2026", 11))
        coEvery { api.folder(12, limit = 1000) } returns folder(12, dir(13, "09", 12))
        val camera = DriveCameraUploads(api, live)
        assertEquals(DResult.Ok(13L), camera.folder(listOf("2026", "09")))
        coVerify(exactly = 0) { api.cameraFolder() }
        coVerify(exactly = 0) { api.createFolder(2, any(), any()) }
    }

    @Test
    fun `a file in the way stops the backup, offline waits`() = runTest {
        val api = mockk<DriveApi>()
        val live = mockk<DriveLive>()
        coEvery { live.supports(DMe.FEATURE_PHOTOS_FOLDER) } returns true
        coEvery { api.cameraFolder() } returns DResult.Failed("exists", 409)
        val camera = DriveCameraUploads(api, live)
        assertEquals("no_folder", (camera.folder(listOf("2026", "10")) as DResult.Failed).code)
        coEvery { api.cameraFolder() } returns DResult.Offline
        assertEquals(DResult.Offline, camera.folder(listOf("2026", "10")))
    }
}
