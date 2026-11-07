package com.gatecontrol.android.drive

import com.gatecontrol.android.drive.DriveMediaAccess.Access
import com.gatecontrol.android.drive.DriveMediaAccess.FillLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DriveMediaAccessTest {

    private fun of(sdk: Int, images: Boolean = false, video: Boolean = false, selected: Boolean = false, legacy: Boolean = false, videos: Boolean = false) =
        DriveMediaAccess.of(sdk, images, video, selected, legacy, videos)

    @Test
    fun `Android 14+ - picked photos only, or the photos without the videos, is partial`() {
        // the user's case: "Alle zulassen" for the photos grants USER_SELECTED too — never enough for videos
        assertEquals(Access.PARTIAL, of(34, images = true, selected = true, videos = true))
        assertEquals(Access.PARTIAL, of(37, images = true, selected = true, videos = true))
        assertEquals(Access.FULL, of(34, images = true, selected = true, videos = false))
        assertEquals(Access.FULL, of(34, images = true, video = true, selected = true, videos = true))
        // "Ausgewählte Fotos"
        assertEquals(Access.PARTIAL, of(34, selected = true))
        assertEquals(Access.PARTIAL, of(34, selected = true, videos = true))
        assertEquals(Access.NONE, of(34))
    }

    @Test
    fun `Android 13 - images and videos apart, no picking`() {
        assertEquals(Access.FULL, of(33, images = true))
        assertEquals(Access.PARTIAL, of(33, images = true, videos = true))
        assertEquals(Access.FULL, of(33, images = true, video = true, videos = true))
        assertEquals(Access.NONE, of(33, selected = true))
        assertEquals(Access.NONE, of(33, video = true))
    }

    @Test
    fun `Android 12 and older - the storage permission`() {
        assertEquals(Access.FULL, of(31, legacy = true, videos = true))
        assertEquals(Access.NONE, of(32))
    }

    @Test
    fun `the line of Orte nachtragen`() {
        assertEquals(FillLine.PERMISSION, DriveMediaAccess.fillLine(canLook = false, partial = false, running = false, lastRunAt = 0, looked = 0))
        assertEquals(FillLine.PARTIAL, DriveMediaAccess.fillLine(canLook = true, partial = true, running = false, lastRunAt = 5, looked = 0))
        assertEquals(FillLine.RUNNING, DriveMediaAccess.fillLine(canLook = true, partial = true, running = true, lastRunAt = 5, looked = 0))
        // a pass that looked at nothing with full access: "Nichts nachzutragen …", not "0 · 0"
        assertEquals(FillLine.NOTHING, DriveMediaAccess.fillLine(canLook = true, partial = false, running = false, lastRunAt = 5, looked = 0))
        assertEquals(FillLine.LAST, DriveMediaAccess.fillLine(canLook = true, partial = false, running = false, lastRunAt = 5, looked = 3))
        assertEquals(FillLine.INTRO, DriveMediaAccess.fillLine(canLook = true, partial = false, running = false, lastRunAt = 0, looked = 0))
    }
}
