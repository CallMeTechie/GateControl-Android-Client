package com.gatecontrol.android.ui.drive

import android.content.pm.ActivityInfo
import androidx.media3.common.VideoSize
import com.gatecontrol.android.drive.vault.DriveVaultHeld
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The player of a vault's video: kept over a new activity (fold, unfold, turn), released with the viewer. */
class DriveVaultVideoTest {

    private class Fake(val name: String) { var released = 0 }

    private fun kept() = VaultKeptPlayer<Fake> { it.released++ }

    @Test
    fun `the same video gets the same player - a fold or a turn does not start it anew`() {
        val kept = kept()
        val held = DriveVaultHeld()
        val video = Any()
        var made = 0
        val first = kept.get(video, held) { made++; Fake("a") }
        val again = kept.get(video, held) { made++; Fake("b") }
        assertSame(first, again)
        assertEquals(1, made)
        assertEquals(0, first!!.released)
    }

    @Test
    fun `closing the viewer or the lock releases the player, once`() {
        val kept = kept()
        val held = DriveVaultHeld()
        val p = kept.get(Any(), held) { Fake("a") }!!
        held.close()
        assertEquals(1, p.released)
        held.close()
        assertEquals(1, p.released)
    }

    @Test
    fun `after the viewer closed no player is made`() {
        val kept = kept()
        val held = DriveVaultHeld()
        held.close()
        var made = false
        assertNull(kept.get(Any(), held) { made = true; Fake("a") })
        assertFalse(made)
    }

    @Test
    fun `another video - the last player goes, the new one is not released with the old viewer`() {
        val kept = kept()
        val old = DriveVaultHeld()
        val a = kept.get(Any(), old) { Fake("a") }!!
        val now = DriveVaultHeld()
        val b = kept.get(Any(), now) { Fake("b") }!!
        assertEquals(1, a.released)
        old.close()
        assertEquals(1, a.released, "released once")
        assertEquals(0, b.released, "the old viewer's close leaves the new player")
        now.close()
        assertEquals(1, b.released)
    }

    @Test
    fun `wide or high - from the size of the picture and its pixels`() {
        assertEquals(true, vaultVideoWide(VideoSize(1920, 1080)))
        assertEquals(false, vaultVideoWide(VideoSize(1080, 1920)))
        // anamorphic: 1440 × 1080 stored, shown 4:3 → 16:9 wide
        assertEquals(true, vaultVideoWide(VideoSize(1080, 1080, 1.5f)))
        assertNull(vaultVideoWide(VideoSize.UNKNOWN))
    }

    @Test
    fun `full screen - landscape kept while the activity is made anew under the vault viewer`() {
        assertFalse(ViewerOrientation.restore(forced = true, recreating = true, survivesRecreation = true))
        assertTrue(ViewerOrientation.restore(forced = true, recreating = false, survivesRecreation = true))
        // the viewers that do not come back with the activity give the orientation back as before
        assertTrue(ViewerOrientation.restore(forced = true, recreating = true, survivesRecreation = false))
        assertFalse(ViewerOrientation.restore(forced = false, recreating = false, survivesRecreation = false))
    }

    @Test
    fun `the orientation to go back to - a landscape the viewer forced itself is none`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, ViewerOrientation.before(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ViewerOrientation.before(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, ViewerOrientation.before(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED))
    }
}
