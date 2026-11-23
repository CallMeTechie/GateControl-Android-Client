package com.gatecontrol.android.tv.ui.drive

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.gatecontrol.android.drive.DriveMediaLogic
import com.gatecontrol.android.drive.DriveVideoPlaces
import com.gatecontrol.android.tv.drive.TvDriveMedia
import com.gatecontrol.android.tv.drive.TvMediaSelection
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The viewer of Drive on the TV, over the whole screen: the pictures of a folder (◀ ▶, ⏯ a slide show), its videos
 * in the player (mockup 4) or a document's preview. The list comes from the Drive page ([TvMediaSelection]); without
 * one (the process was gone) it closes. Pictures and videos stay in memory and the server's media cache.
 */
@AndroidEntryPoint
class TvMediaActivity : ComponentActivity() {

    @Inject lateinit var selection: TvMediaSelection
    @Inject lateinit var media: TvDriveMedia

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val items = selection.items
        if (items.isEmpty()) {
            finish()
            return
        }
        // a video or a slide show must not fall asleep
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val start = selection.at
        val document = selection.document
        setContent {
            GateControlTvTheme {
                var at by rememberSaveable { mutableIntStateOf(start) }
                val node = items[at.coerceIn(0, items.size - 1)]
                when {
                    document -> TvPhotoViewer(listOf(node), 0, media, onAt = {}, onClose = ::finish, document = true)
                    DriveMediaLogic.isVideo(node) -> TvVideoPlayer(
                        items = items,
                        at = at,
                        media = media,
                        places = DriveVideoPlaces.shared,
                        onAt = { at = it },
                        onClose = ::finish,
                    )
                    else -> TvPhotoViewer(items, at, media, onAt = { at = it }, onClose = ::finish)
                }
            }
        }
    }
}
