package com.gatecontrol.android.tv.player

import android.view.KeyEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TvPlayerKeysTest {

    @Test
    fun `the keys of the Fire TV remote`() {
        // OK and ⏯
        assertEquals(TvPlayerAction.TOGGLE, TvPlayerKeys.action(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(TvPlayerAction.TOGGLE, TvPlayerKeys.action(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        // ◀ ▶ and ⏪ ⏩
        assertEquals(TvPlayerAction.SEEK_BACK, TvPlayerKeys.action(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(TvPlayerAction.SEEK_BACK, TvPlayerKeys.action(KeyEvent.KEYCODE_MEDIA_REWIND))
        assertEquals(TvPlayerAction.SEEK_FORWARD, TvPlayerKeys.action(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(TvPlayerAction.SEEK_FORWARD, TvPlayerKeys.action(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        // ▲ and ☰: sound and subtitles; ▼ the bar; Zurück
        assertEquals(TvPlayerAction.TRACKS, TvPlayerKeys.action(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(TvPlayerAction.TRACKS, TvPlayerKeys.action(KeyEvent.KEYCODE_MENU))
        assertEquals(TvPlayerAction.SHOW_CONTROLS, TvPlayerKeys.action(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(TvPlayerAction.BACK, TvPlayerKeys.action(KeyEvent.KEYCODE_BACK))
    }

    @Test
    fun `other remotes and keyboards`() {
        assertEquals(TvPlayerAction.PLAY, TvPlayerKeys.action(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(TvPlayerAction.PAUSE, TvPlayerKeys.action(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertEquals(TvPlayerAction.NEXT, TvPlayerKeys.action(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(TvPlayerAction.PREVIOUS, TvPlayerKeys.action(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertEquals(TvPlayerAction.STOP, TvPlayerKeys.action(KeyEvent.KEYCODE_MEDIA_STOP))
        assertEquals(TvPlayerAction.TOGGLE, TvPlayerKeys.action(KeyEvent.KEYCODE_SPACE))
        assertEquals(TvPlayerAction.TRACKS, TvPlayerKeys.action(KeyEvent.KEYCODE_CAPTIONS))
        // Home and the volume stay with the system
        assertNull(TvPlayerKeys.action(KeyEvent.KEYCODE_HOME))
        assertNull(TvPlayerKeys.action(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun `seeking 10 s, faster when held, never past the ends`() {
        assertEquals(10_000L, TvPlayerKeys.seekStep(0))
        assertEquals(10_000L, TvPlayerKeys.seekStep(4))
        assertEquals(30_000L, TvPlayerKeys.seekStep(5))
        assertEquals(15_000L, TvPlayerKeys.seekTarget(5_000, 10_000, 60_000))
        assertEquals(0L, TvPlayerKeys.seekTarget(5_000, -10_000, 60_000))
        // the very end would end the video: a second before it
        assertEquals(59_000L, TvPlayerKeys.seekTarget(55_000, 10_000, 60_000))
        // unknown length (a stream still opening): only the start holds
        assertEquals(80_000L, TvPlayerKeys.seekTarget(70_000, 10_000, 0))
    }

    @Test
    fun `the bar's times`() {
        assertEquals("00:00", TvPlayerKeys.time(-5))
        assertEquals("04:27", TvPlayerKeys.time(267_000))
        assertEquals("12:04", TvPlayerKeys.time(724_900))
        assertEquals("1:12:30", TvPlayerKeys.time(4_350_000))
    }

    @Test
    fun `the photo viewer`() {
        assertEquals(TvPhotoAction.PREVIOUS, TvPhotoKeys.action(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(TvPhotoAction.NEXT, TvPhotoKeys.action(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(TvPhotoAction.NEXT, TvPhotoKeys.action(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        assertEquals(TvPhotoAction.SLIDESHOW, TvPhotoKeys.action(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(TvPhotoAction.SLIDESHOW, TvPhotoKeys.action(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(TvPhotoAction.BACK, TvPhotoKeys.action(KeyEvent.KEYCODE_BACK))
        assertEquals(TvPhotoAction.SEND, TvPhotoKeys.action(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(TvPhotoAction.SEND, TvPhotoKeys.action(KeyEvent.KEYCODE_MENU))
        assertNull(TvPhotoKeys.action(KeyEvent.KEYCODE_DPAD_DOWN))
    }
}
