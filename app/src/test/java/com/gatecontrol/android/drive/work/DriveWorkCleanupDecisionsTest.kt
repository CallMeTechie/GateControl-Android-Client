package com.gatecontrol.android.drive.work

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gatecontrol.android.drive.DMe
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DPhotos
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveLive
import com.gatecontrol.android.drive.DriveNodeResolver
import com.gatecontrol.android.drive.DriveOps
import com.gatecontrol.android.drive.backup.DriveCameraUploads
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * "Aufräumen" showed the same photos again and again although the person had sorted them long ago: "Nichts
 * verschieben" / unticked photos stayed in Kamera-Uploads and were only an example for the AI — [DriveWorkPhotos.suggest]
 * never asked what the person had decided; a photo moved to work and uploaded again by the backup (a new node, the
 * same content) came back too. Now a decision is kept apart, by node id and content, and the suggestions leave it out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DriveWorkCleanupDecisionsTest {

    private lateinit var context: Context
    private lateinit var store: DriveWorkStore
    private val api = mockk<DriveApi>(relaxed = true)
    private val live = mockk<DriveLive>(relaxed = true)
    private val labeler = mockk<DriveWorkLabeler>(relaxed = true)
    private val camera = mockk<DriveCameraUploads>(relaxed = true)
    private val ops = mockk<DriveOps>(relaxed = true)
    private val geo = mockk<DriveWorkGeo>(relaxed = true)
    private val resolver = mockk<DriveNodeResolver>(relaxed = true)

    // what Kamera-Uploads holds at the moment
    private var cameraUploads: List<DNode> = emptyList()

    private val shaBaustelle = "1".repeat(64)
    private val shaWhiteboard = "2".repeat(64)
    private val shaKatze = "3".repeat(64)
    private val shaNeu = "4".repeat(64)

    private fun photo(id: Long, sha: String?, size: Long, taken: String = "2026-11-03T09:12:00.000Z") =
        DNode(id, "mine:7", CAMERA, "IMG_$id.jpg", false, size, "image/jpeg", null, 1, null, null, null, sha256 = sha, takenAt = taken)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.noBackupFilesDir.listFiles()?.forEach(File::delete)
        context.getSharedPreferences("drive_work", Context.MODE_PRIVATE).edit().clear().commit()
        coEvery { live.supports(any()) } answers { firstArg<String>() in setOf(DMe.FEATURE_PHOTO_CLASSES, DMe.FEATURE_PHOTOS_FOLDER) }
        coEvery { api.photos(any(), any(), any(), any(), any(), any(), any(), any()) } answers { DResult.Ok(DPhotos(cameraUploads, null)) }
        coEvery { api.node(any()) } returns DResult.Failed("not_found", 404)
        coEvery { camera.under(any(), any()) } returns DResult.Ok(WORK)
        coEvery { api.move(any(), any()) } answers { DResult.Ok(photo(firstArg(), null, 1)) }
        // every photo looks like work to the AI (a label it learned from work photos), each with something of its own
        coEvery { labeler.ofNode(any()) } answers { mapOf(WORK_LABEL to 0.9f, (10 + firstArg<DNode>().id.toInt()) to 0.9f) }
    }

    private fun newStore() = DriveWorkStore(context).also { s ->
        s.update { it.copy(workFolder = WORK, folderLabel = "Meine Dateien › Arbeit") }
        if (s.examples().isEmpty()) {
            for (i in 1..4) s.learn(WorkExample("n:${900 + i}", true, WorkExample.Source.ASSIGN, mapOf(WORK_LABEL to 1f), i.toLong()))
            for (i in 1..4) s.learn(WorkExample("n:${910 + i}", false, WorkExample.Source.ASSIGN, mapOf(HOME_LABEL to 1f), i.toLong()))
        }
    }

    private fun photos(s: DriveWorkStore = store) = DriveWorkPhotos(api, live, s, labeler, camera, ops, geo, resolver)

    @Test
    fun `sorted once, a photo is never suggested again, also not after a new upload of the same file`() = runBlocking {
        store = newStore()
        val work = photos()
        val baustelle = photo(1, shaBaustelle, 2_000_001)
        val whiteboard = photo(2, shaWhiteboard, 2_000_002)
        val katze = photo(3, shaKatze, 2_000_003)
        cameraUploads = listOf(baustelle, whiteboard, katze)
        val first = work.suggest()
        assertEquals(listOf(1L, 2L, 3L), first.map { it.node.id }.sorted())
        // the head and the hint in the Photos tab count what the page shows
        assertEquals(3, work.suggested.value)

        // "1 Foto nach Arbeit verschieben": Baustelle is work, Whiteboard and Katze left unticked = private
        val r = work.cleanup(chosen = listOf(baustelle), left = listOf(whiteboard, katze))
        assertNull(r.error)
        coVerify { api.move(1L, WORK) }

        // the next look: Whiteboard and Katze still in Kamera-Uploads, Baustelle uploaded once more (new node, same
        // content — a reinstalled app's backup), Katze renamed, and one new photo
        cameraUploads = listOf(
            whiteboard,
            katze.copy(name = "Katze.jpg"),
            photo(41, shaBaustelle, 2_000_001, taken = "2026-11-03T09:12:00.000Z"),
            photo(42, shaNeu, 2_000_042, taken = "2026-11-07T15:00:00.000Z"),
        )
        assertEquals(listOf(42L), work.suggest().map { it.node.id })
        assertEquals(1, work.suggested.value)

        // kept on the phone: a new start of the app knows them too
        cameraUploads = cameraUploads.filter { it.id != 42L }
        assertEquals(emptyList<Long>(), photos(DriveWorkStore(context)).suggest().map { it.node.id })
    }

    @Test
    fun `nothing moved still decides, Nichts verschieben leaves every photo private for good`() = runBlocking {
        store = newStore()
        val work = photos()
        cameraUploads = listOf(photo(5, shaKatze, 2_000_005), photo(6, null, 2_000_006))
        assertEquals(2, work.suggest().size)
        assertNull(work.cleanup(chosen = emptyList(), left = cameraUploads).error)
        assertEquals(emptyList<Long>(), work.suggest().map { it.node.id })
    }

    @Test
    fun `decisions of the app up to 1_129 (examples only) still count`() = runBlocking {
        // the old app kept "Aufräumen" only as examples of the AI, by node id
        store = newStore()
        store.learn(WorkExample("n:7", false, WorkExample.Source.CLEANUP, mapOf(HOME_LABEL to 1f), 50))
        store.learn(WorkExample("n:8", true, WorkExample.Source.CLEANUP, mapOf(WORK_LABEL to 1f), 51))
        // read anew, as after the update
        val updated = DriveWorkStore(context)
        cameraUploads = listOf(photo(7, shaWhiteboard, 2_000_007), photo(9, shaNeu, 2_000_009))
        assertEquals(listOf(9L), photos(updated).suggest().map { it.node.id })
        // the decision of photo 7 learned its content: a copy of it later is known too
        cameraUploads = listOf(photo(70, shaWhiteboard, 2_000_007, taken = "2026-11-04T08:00:00.000Z"))
        assertEquals(emptyList<Long>(), photos(DriveWorkStore(context)).suggest().map { it.node.id })
    }

    private companion object {
        const val CAMERA = 30L
        const val WORK = 50L
        const val WORK_LABEL = 1
        const val HOME_LABEL = 2
    }
}
