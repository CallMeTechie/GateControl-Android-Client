package com.gatecontrol.android.media.player

import androidx.media3.common.C
import com.gatecontrol.android.media.MediaSideSubtitle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Side subtitles: Jellyfin's shown at once, a share's subtitle file beside the video only offered (forced ones as such). */
class MediaPlayersTest {

    @Test
    fun `selection flags of side subtitles`() {
        val jellyfin = MediaSideSubtitle(3, "http://192.168.1.20:8096/s.srt", "application/x-subrip", "ger", "Deutsch")
        assertEquals(C.SELECTION_FLAG_DEFAULT, MediaPlayers.flags(jellyfin))
        val share = MediaSideSubtitle(1_000, "gcshare://share/Film.de.srt", "application/x-subrip", "de", null, default = false)
        assertEquals(0, MediaPlayers.flags(share))
        assertEquals(C.SELECTION_FLAG_FORCED, MediaPlayers.flags(share.copy(forced = true)))
    }
}
