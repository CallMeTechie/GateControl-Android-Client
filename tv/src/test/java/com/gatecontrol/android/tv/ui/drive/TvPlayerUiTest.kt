package com.gatecontrol.android.tv.ui.drive

import com.gatecontrol.android.media.player.PlayerTrack
import com.gatecontrol.android.media.player.PlayerTrackMenu
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TvPlayerUiTest {
    @Test
    fun `the format pill`() {
        assertEquals("4K · HEVC", TvPlayerUi.formatLabel(2160, "video/hevc"))
        assertEquals("1080p · H.264", TvPlayerUi.formatLabel(1080, "video/avc"))
        assertEquals("AV1", TvPlayerUi.formatLabel(0, "video/av01"))
        assertNull(TvPlayerUi.formatLabel(0, null))
    }

    @Test
    fun `what the bar says about subtitles`() {
        val de = PlayerTrack(PlayerTrack.Kind.TEXT, 3, 0, "Deutsch (SDH)", "de", 0, selected = true)
        assertEquals("Deutsch (SDH)", TvPlayerUi.textState(PlayerTrackMenu(emptyList(), listOf(de)), "aus"))
        assertEquals("aus", TvPlayerUi.textState(PlayerTrackMenu(emptyList(), listOf(de.copy(selected = false))), "aus"))
    }
}
