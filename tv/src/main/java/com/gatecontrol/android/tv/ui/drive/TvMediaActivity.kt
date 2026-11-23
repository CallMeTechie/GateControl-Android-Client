package com.gatecontrol.android.tv.ui.drive

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.gatecontrol.android.drive.DriveMediaLogic
import com.gatecontrol.android.drive.DriveVideoPlaces
import com.gatecontrol.android.tv.drive.TvDriveMedia
import com.gatecontrol.android.tv.drive.TvMediaSelection
import com.gatecontrol.android.tv.gatedrop.TvDropLogic
import com.gatecontrol.android.tv.gatedrop.TvGateDrop
import com.gatecontrol.android.tv.ui.gatedrop.TvSendDialog
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The viewer of the TV over the whole screen: the pictures of a Drive folder (◀ ▶, ⏯ a slide show), its videos in the
 * player (mockup 4), a document's preview — or the pictures and videos of a GateDrop send kept on the TV. ☰ / ▲ sends
 * the one shown to another device ("Senden an …", GateDrop). Or the films and episodes of a media source (Medien). The list comes from the page ([TvMediaSelection]);
 * without one (the process was gone) it closes.
 */
@AndroidEntryPoint
class TvMediaActivity : ComponentActivity() {

    @Inject lateinit var selection: TvMediaSelection
    @Inject lateinit var media: TvDriveMedia
    @Inject lateinit var gatedrop: TvGateDrop
    @Inject lateinit var mediaLibrary: com.gatecontrol.android.tv.media.TvMedia

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val items = selection.items
        val local = selection.local
        val sendId = selection.localSend
        val queue = selection.media
        if (items.isEmpty() && local.isEmpty() && queue == null) {
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
                var sending by remember { mutableStateOf<Int?>(null) }
                if (queue != null) {
                    // Medien: films and episodes of the NAS (no "Senden an …")
                    val videos = remember { com.gatecontrol.android.tv.media.TvMediaVideos(queue, mediaLibrary, resources) }
                    TvVideoPlayer(videos, at.coerceIn(0, videos.count - 1), onAt = { at = it }, onClose = ::finish)
                } else if (local.isNotEmpty() && sendId != null) {
                    val i = at.coerceIn(0, local.size - 1)
                    val files = remember { local.map { it.second } }
                    if (TvDropLogic.isVideo(files[i].mime)) {
                        TvVideoPlayer(remember { TvLocalVideos(files) }, i, onAt = { at = it }, onClose = ::finish, onSend = { sending = i })
                    } else {
                        TvPhotoViewer(remember { TvLocalPhotos(files) }, i, onAt = { at = it }, onClose = ::finish, onSend = { sending = i })
                    }
                    sending?.let { s ->
                        TvSendDialog(
                            name = files[s].name,
                            targets = gatedrop::targets,
                            send = { to -> gatedrop.sendReceived(sendId, local[s].first, to) },
                            onDismiss = { sending = null },
                        )
                    }
                } else {
                    val i = at.coerceIn(0, items.size - 1)
                    val node = items[i]
                    when {
                        document -> TvPhotoViewer(remember { TvDrivePhotos(listOf(node), media) }, 0, onAt = {}, onClose = ::finish, document = true, onSend = { sending = i })
                        DriveMediaLogic.isVideo(node) -> TvVideoPlayer(
                            remember { TvDriveVideos(items, media, DriveVideoPlaces.shared) },
                            i,
                            onAt = { at = it },
                            onClose = ::finish,
                            onSend = { sending = i },
                        )
                        else -> TvPhotoViewer(remember { TvDrivePhotos(items, media) }, i, onAt = { at = it }, onClose = ::finish, onSend = { sending = i })
                    }
                    sending?.let { s ->
                        val n = items[s]
                        TvSendDialog(name = n.name, targets = gatedrop::targets, send = { to -> gatedrop.sendDrive(n, to) }, onDismiss = { sending = null })
                    }
                }
            }
        }
    }
}
