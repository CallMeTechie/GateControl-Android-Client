package com.gatecontrol.android.ui.drive

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gatecontrol.android.drive.DriveFrameLogic.Skip
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Drive 1.31: the snackbar after „Für Fotorahmen freigeben“ / „Aus Fotorahmen entfernen“, in German. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "de")
class DriveFrameTextTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun text(s: DrivePhotosViewModel.FrameSnack) = frameSnackText(ctx.resources, s)

    @Test
    fun `shared with the skipped ones and why`() {
        assertEquals(
            "3 Fotos für den Fotorahmen freigegeben.\nÜbersprungen: 2 Videos, 1 im Tresor, 1 vom Virenscan gesperrt, 1 aus einem Kinderordner, 2 nicht gefunden",
            text(DrivePhotosViewModel.FrameSnack(true, 3, linkedMapOf(Skip.VIDEO to 2, Skip.VAULT to 1, Skip.BLOCKED to 1, Skip.CHILD to 1, Skip.NOT_FOUND to 2))),
        )
        assertEquals("1 Foto für den Fotorahmen freigegeben.", text(DrivePhotosViewModel.FrameSnack(true, 1)))
    }

    @Test
    fun `nothing new, only skipped, removed`() {
        assertEquals("Die Fotos sind bereits für den Fotorahmen freigegeben.", text(DrivePhotosViewModel.FrameSnack(true, 0)))
        assertEquals("Übersprungen: 1 Video", text(DrivePhotosViewModel.FrameSnack(true, 0, mapOf(Skip.VIDEO to 1))))
        assertEquals("2 Fotos aus dem Fotorahmen entfernt.", text(DrivePhotosViewModel.FrameSnack(false, 2)))
        assertEquals("Keines der Fotos war im Fotorahmen.", text(DrivePhotosViewModel.FrameSnack(false, 0)))
    }

    @Test
    fun `this account may not share`() {
        assertEquals("Dieses Konto darf keine Fotos für den Fotorahmen freigeben.", text(DrivePhotosViewModel.FrameSnack(true, 0, forbidden = true)))
    }

    @Test
    fun `every reason has its words`() {
        val words = Skip.entries.map { ctx.resources.getQuantityString(frameSkipRes(it), 1, 1) }
        assertEquals(listOf("1 Video", "1 kein Foto", "1 im Tresor", "1 vom Virenscan gesperrt", "1 aus einem Kinderordner", "1 nicht gefunden", "1 aus einem anderen Grund"), words)
    }
}
