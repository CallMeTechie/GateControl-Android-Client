package com.gatecontrol.android.drive.backup

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DUploadCheck
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveLive
import com.gatecontrol.android.drive.DriveMediaAccess
import com.gatecontrol.android.drive.DrivePlacesPrefs
import com.gatecontrol.android.drive.DrivePreviews
import com.gatecontrol.android.drive.DriveTakenAt
import com.gatecontrol.android.drive.PlacesSettings
import com.gatecontrol.android.drive.work.DriveWorkPhotos
import com.gatecontrol.android.drive.work.DriveWorkWifi
import com.gatecontrol.android.drive.work.WorkSettings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * The photo backup against a media store that behaves like Android's (a fake provider): videos only with
 * READ_MEDIA_VIDEO, pending entries hidden, files that are still being written. It proves why the user's new video never
 * went up — and that it does now:
 * 1. Android 14+: READ_MEDIA_VISUAL_USER_SELECTED is granted with full photo access too, so "Auch Videos" counted it as
 *    enough and never asked for READ_MEDIA_VIDEO — the media store then returns no video at all, the pass says "Alles
 *    gesichert" and its mark goes past the video. Now that is partial access (asked for), and the videos the mark went
 *    past are caught up once the app may see them.
 * 2. A video still recording (`IS_PENDING`) has the date it was started: a photo after it moves the mark past it.
 * 3. Nothing is skipped silently: the reason is kept ("Übersprungen"), "Erneut versuchen" sends it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DriveBackupVideosTest {

    private lateinit var context: Context
    private lateinit var dir: File
    private val committed = mutableListOf<String>()
    private val api = mockk<DriveApi>(relaxed = true)
    private val setup = mockk<SetupRepository>(relaxed = true)
    private val live = mockk<DriveLive>(relaxed = true)
    private val dates = mockk<DriveTakenAt>(relaxed = true)
    private val camera = mockk<DriveCameraUploads>(relaxed = true)
    private val work = mockk<DriveWorkPhotos>(relaxed = true)
    private val places = mockk<DrivePlacesPrefs>(relaxed = true)
    private val nowS = System.currentTimeMillis() / 1000

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dir = File(context.cacheDir, "media").apply { mkdirs() }
        FakeMedia.reset(context)
        Robolectric.setupContentProvider(FakeMedia::class.java, "media")
        every { setup.getServerUrl() } returns "https://gc.example"
        every { setup.getApiToken() } returns "token"
        every { live.locked } returns MutableStateFlow(false)
        coEvery { live.supports(any()) } returns false
        every { work.settings } returns MutableStateFlow(WorkSettings())
        every { places.settings } returns MutableStateFlow(PlacesSettings())
        every { places.pending() } returns emptyList()
        every { dates.shot(any(), any(), any()) } returns DriveTakenAt.Shot(null, null)
        coEvery { work.placeNew(any(), any(), any(), any()) } returns null
        coEvery { camera.folder(any()) } returns DResult.Ok(5L)
        coEvery { api.uploadCheck(any(), any(), any(), any()) } answers {
            // already in its folder: the same name and content ("Ein Foto, das schon im Ordner liegt …")
            DResult.Ok(DUploadCheck(exists = false, same = secondArg<String>() in committed, node = null))
        }
        coEvery { api.upload(any<com.gatecontrol.android.drive.DriveSource>(), any(), any()) } returns DResult.Ok("m1")
        coEvery { api.commit(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            val name = thirdArg<String>()
            committed += name
            DResult.Ok(DNode(1, "mine:2", 5, name, false, 1, null, "m1", 1, null, null, null) to true)
        }
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("drive_backup", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun backup() = DriveBackup(
        context, api, setup, mockk<DrivePreviews>(relaxed = true), live, dates, camera, work, mockk<DriveWorkWifi>(relaxed = true),
        mockk<DrivePhotoLabels>(relaxed = true), places,
    )

    private fun grant(vararg p: String) = shadowOf(context as Application).grantPermissions(*p)
    private fun deny(vararg p: String) = shadowOf(context as Application).denyPermissions(*p)

    private fun file(name: String, bytes: Int = 64) = File(dir, name).apply { writeBytes(ByteArray(bytes) { (it + name.length).toByte() }) }

    /** The backup on, from [mark], with "Auch Videos" [videos] (asked: as the app now stores it). */
    private fun on(mark: DriveBackupMark, videos: Boolean, asked: Boolean = true) {
        val prefs = context.getSharedPreferences("drive_backup", Context.MODE_PRIVATE)
        val b = backup()
        b.update { it.copy(enabled = true, videos = videos) }
        prefs.edit().putLong("mark_added", mark.dateAdded).putLong("mark_id", mark.id).apply {
            if (asked) putBoolean("videos_asked", true) else remove("videos_asked")
        }.commit()
    }

    @Test
    fun `root cause - Android 14 partial grant hid every video, the mark went past them, now they are caught up`() = runBlocking {
        // "Fotos sichern" first (videos off): READ_MEDIA_IMAGES with "Alle zulassen" — Android grants USER_SELECTED too
        grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        deny(Manifest.permission.READ_MEDIA_VIDEO)
        FakeMedia.add(1, image = true, added = nowS - 300, file = file("IMG_1.jpg"))
        FakeMedia.add(2, image = false, added = nowS - 200, file = file("PXL_test.mp4"))
        FakeMedia.add(3, image = true, added = nowS - 100, file = file("IMG_3.jpg"))
        on(DriveBackupMark.ALL, videos = true)
        val b = backup()

        // what the app did before: "Ausgewählte Fotos" counted as allowed for videos — so it never asked for them
        assertEquals(DriveMediaAccess.Access.PARTIAL, DriveMediaAccess.of(context, videos = true))
        assertTrue(b.canRead(videos = true))
        assertTrue("now the screen says so and asks", b.partialAccess(videos = true))

        assertEquals(DriveBackup.Outcome.DONE, b.run())
        assertEquals("the media store returned no video: only the photos went up", listOf("IMG_1.jpg", "IMG_3.jpg"), committed)
        assertEquals("the mark is past the video", DriveBackupMark(nowS - 100, 3), b.mark())
        // the old query never lists it again
        assertTrue(FakeMedia.ids(DriveBackupLogic.query(b.config.value, b.mark()!!)).isEmpty())

        // the person allows the videos ("Zugriff erlauben"): the next pass catches the video up — no photo again
        grant(Manifest.permission.READ_MEDIA_VIDEO)
        assertEquals(DriveBackup.Outcome.DONE, b.run())
        assertEquals(listOf("IMG_1.jpg", "IMG_3.jpg", "PXL_test.mp4"), committed)
        assertEquals(1, FakeMedia.uploadsOf("PXL_test.mp4", committed))
        assertEquals("the caught-up video counts", 3, b.stats.value.saved)
        assertNull("caught up", b.pendingVideos())
    }

    @Test
    fun `a video still recording (pending) when a later photo moved the mark goes up once it is finished`() = runBlocking {
        grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        // recording started at -120 s (DATE_ADDED stays the start), a photo at -60 s while it records
        FakeMedia.add(10, image = false, added = nowS - 120, file = file("PXL_rec.mp4", 4096), pending = true)
        FakeMedia.add(11, image = true, added = nowS - 60, file = file("PXL_photo.jpg"))
        on(DriveBackupMark.ALL, videos = true)
        val b = backup()
        b.run()
        assertEquals(listOf("PXL_photo.jpg"), committed)
        assertEquals(DriveBackupMark(nowS - 60, 11), b.mark())
        // the recording ends: published, written after it was added
        FakeMedia.publish(10, modified = nowS)
        assertTrue("the plain query does not see it any more", FakeMedia.ids(DriveBackupLogic.query(b.config.value, b.mark()!!)).isEmpty())
        b.run()
        assertEquals(listOf("PXL_photo.jpg", "PXL_rec.mp4"), committed)
        // and only once
        b.run()
        assertEquals(1, FakeMedia.uploadsOf("PXL_rec.mp4", committed))
        assertTrue(b.skipped.value.isEmpty())
    }

    @Test
    fun `a file still being written is looked at again, never skipped as empty`() = runBlocking {
        grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        // visible already, the media store has not seen its size yet
        FakeMedia.add(20, image = false, added = nowS - 30, file = file("VID_live.mp4", 2048), size = 0)
        on(DriveBackupMark.ALL, videos = true)
        val b = backup()
        b.run()
        assertTrue(committed.isEmpty())
        assertTrue("not skipped", b.skipped.value.isEmpty())
        FakeMedia.setSize(20, 2048)
        b.run()
        assertEquals(listOf("VID_live.mp4"), committed)
    }

    @Test
    fun `a skipped file is listed with its reason, Erneut versuchen sends it`() = runBlocking {
        grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        FakeMedia.add(30, image = false, added = nowS - 30, file = File(dir, "missing.mp4"), size = 100)
        FakeMedia.add(31, image = true, added = nowS - 20, file = file("IMG_ok.jpg"))
        on(DriveBackupMark.ALL, videos = true)
        val b = backup()
        b.run()
        assertEquals(listOf("IMG_ok.jpg"), committed)
        val skipped = b.skipped.value.single()
        assertEquals(30L, skipped.id)
        assertEquals("read", skipped.code)
        assertTrue(skipped.video)
        // kept across a restart of the app
        assertEquals(listOf(30L), backup().skipped.value.map { it.id })
        // readable now: "Erneut versuchen"
        File(dir, "missing.mp4").writeBytes(ByteArray(100) { 7 })
        b.retrySkipped()
        assertTrue(b.skipped.value.isEmpty())
        b.run()
        assertEquals(listOf("IMG_ok.jpg", "missing.mp4"), committed)
        assertTrue(b.skipped.value.isEmpty())
    }

    @Test
    fun `Auch Videos was on before the update - the older videos are caught up once without asking`() = runBlocking {
        grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        FakeMedia.add(40, image = false, added = nowS - 3 * 86400, file = file("VID_old.mp4"))
        FakeMedia.add(41, image = true, added = nowS - 2 * 86400, file = file("IMG_old.jpg"))
        // the photo went up long ago, the mark is past both; never asked which videos (up to 1.92)
        committed += "IMG_old.jpg"
        on(DriveBackupMark(nowS - 2 * 86400, 41), videos = true, asked = false)
        val b = backup()
        assertEquals(1, b.pendingVideos())
        b.run()
        assertEquals(listOf("IMG_old.jpg", "VID_old.mp4"), committed)
        assertNull(b.pendingVideos())
    }

    @Test
    fun `Auch Videos switched on later - Alle Videos takes the older ones, Nur neue does not, no photo twice`() = runBlocking {
        grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        FakeMedia.add(50, image = true, added = nowS - 500, file = file("IMG_a.jpg"))
        FakeMedia.add(51, image = false, added = nowS - 400, file = file("VID_a.mp4"))
        on(DriveBackupMark.ALL, videos = false)
        val b = backup()
        b.run()
        assertEquals(listOf("IMG_a.jpg"), committed)
        // "Nur neue": the older video stays
        b.videosOn(all = false)
        b.run()
        assertEquals(listOf("IMG_a.jpg"), committed)
        // off and on with "Alle Videos": it goes up, the photo not again
        b.videosOff()
        b.videosOn(all = true)
        b.run()
        assertEquals(listOf("IMG_a.jpg", "VID_a.mp4"), committed)
        assertTrue("with a mark the switch asks which videos", b.videosNeedChoice())
    }

    /** A media store like Android's: the Files collection and the Images / Video items, by permission and IS_PENDING. */
    class FakeMedia : ContentProvider() {
        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val ctx = context!!
            fun granted(p: String) = ctx.checkCallingOrSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
            // no picked items in this store: "Ausgewählte Fotos" alone shows nothing
            val visible = buildList {
                if (granted(Manifest.permission.READ_MEDIA_IMAGES)) add("media_type = 1")
                if (granted(Manifest.permission.READ_MEDIA_VIDEO)) add("media_type = 3")
            }.ifEmpty { listOf("0") }.joinToString(" OR ")
            val where = "is_pending = 0 AND ($visible)" + (selection?.let { " AND ($it)" } ?: "")
            return db!!.query("files", projection, where, selectionArgs, null, null, sortOrder)
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val id = uri.lastPathSegment!!.toLong()
            val path = db!!.rawQuery("SELECT path FROM files WHERE _id = ?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?: throw java.io.FileNotFoundException(uri.toString())
            return ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

        companion object {
            private var db: SQLiteDatabase? = null

            fun reset(context: Context) {
                db?.close()
                db = SQLiteDatabase.create(null).apply {
                    execSQL(
                        "CREATE TABLE files (_id INTEGER PRIMARY KEY, media_type INTEGER, relative_path TEXT, date_added INTEGER, date_modified INTEGER, " +
                            "datetaken INTEGER, _display_name TEXT, mime_type TEXT, _size INTEGER, duration INTEGER, is_pending INTEGER, path TEXT)",
                    )
                }
            }

            fun add(id: Long, image: Boolean, added: Long, file: File, pending: Boolean = false, size: Long? = null) {
                db!!.insert("files", null, ContentValues().apply {
                    put("_id", id)
                    put("media_type", if (image) 1 else 3)
                    put("relative_path", "DCIM/Camera/")
                    put("date_added", added)
                    put("date_modified", added)
                    put("_display_name", file.name)
                    put("mime_type", if (image) "image/jpeg" else "video/mp4")
                    put("_size", size ?: if (file.exists()) file.length() else 0L)
                    put("is_pending", if (pending) 1 else 0)
                    put("path", file.path)
                })
            }

            fun publish(id: Long, modified: Long) {
                db!!.execSQL("UPDATE files SET is_pending = 0, date_modified = ?, _size = ? WHERE _id = ?", arrayOf<Any>(modified, sizeOf(id), id))
            }

            fun setSize(id: Long, size: Long) = db!!.execSQL("UPDATE files SET _size = ? WHERE _id = ?", arrayOf<Any>(size, id))

            private fun sizeOf(id: Long): Long =
                db!!.rawQuery("SELECT path FROM files WHERE _id = ?", arrayOf(id.toString())).use { c -> c.moveToFirst(); File(c.getString(0)).length() }

            /** The ids a query of the backup returns (as the app's permissions allow). */
            internal fun ids(q: DriveBackupLogic.Query): List<Long> {
                val ctx = ApplicationProvider.getApplicationContext<Context>()
                return ctx.contentResolver.query(android.provider.MediaStore.Files.getContentUri("external"), arrayOf("_id"), q.selection, q.args, q.sort)!!
                    .use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
            }

            fun uploadsOf(name: String, committed: List<String>) = committed.count { it == name }
        }
    }
}
