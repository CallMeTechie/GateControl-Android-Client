package com.gatecontrol.android.tv.frame

import android.graphics.Bitmap
import android.os.Bundle
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.TvSettings
import com.gatecontrol.android.tv.drive.TvDriveMedia
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import java.util.Date
import javax.inject.Inject

/**
 * The photo frame over the whole screen: the pictures of the chosen Drive folder, one every [TvFrameSettings.seconds],
 * fading into each other, with the time and date. ◀ ▶ step, any other key closes (as a screen saver). The folder is
 * read again every half hour. Pictures only in memory and the shared preview cache.
 */
@AndroidEntryPoint
class TvFrameActivity : ComponentActivity() {

    @Inject lateinit var settings: TvSettings
    @Inject lateinit var api: DriveApi
    @Inject lateinit var media: TvDriveMedia

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val frame = settings.frame.value
        val folder = frame.folderId
        if (folder == null) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { GateControlTvTheme { Frame(folder, frame) } }
    }

    /** All pictures of the folder (pages of 500, at most [TvFrameSchedule.MAX_ITEMS]); null when it cannot be read. */
    private suspend fun pictures(folder: Long): List<DNode>? {
        val out = ArrayList<DNode>()
        var offset: Int? = 0
        while (offset != null && out.size < TvFrameSchedule.MAX_ITEMS) {
            val r = api.folder(folder, offset = offset, limit = 500)
            if (r !is DResult.Ok) return if (out.isEmpty()) null else out
            out += TvFrameSchedule.pictures(r.value.items)
            offset = r.value.next
        }
        return out.take(TvFrameSchedule.MAX_ITEMS)
    }

    @Composable
    private fun Frame(folder: Long, frame: TvFrameSettings) {
        val playlist = remember { TvFramePlaylist(emptyList(), frame.shuffle) }
        var picture by remember { mutableStateOf<Bitmap?>(null) }
        var state by remember { mutableStateOf(FrameState.LOADING) }
        var step by remember { mutableLongStateOf(0L) }
        var direction by remember { mutableIntStateOf(1) }
        val focus = remember { FocusRequester() }

        LaunchedEffect(Unit) {
            var loadedAt = 0L
            while (true) {
                val now = System.currentTimeMillis()
                if (loadedAt == 0L || TvFrameSchedule.refreshDue(loadedAt, now)) {
                    val fresh = pictures(folder)
                    if (fresh != null) {
                        playlist.update(fresh)
                        loadedAt = now
                    }
                    if (playlist.size == 0) {
                        state = if (fresh == null) FrameState.FAILED else FrameState.EMPTY
                        delay(60_000)
                        continue
                    }
                }
                // a picture that cannot be loaded is skipped (a few tries per step)
                var shown: Bitmap? = null
                repeat(3) {
                    if (shown != null) return@repeat
                    val node = (if (direction < 0) playlist.previous() else playlist.next()) ?: return@repeat
                    shown = media.picture(node)
                }
                direction = 1
                if (shown != null) {
                    picture = shown
                    state = FrameState.SHOWING
                }
                val waitUntil = step
                // the time per picture, or until ◀ ▶ asks for another one
                var waited = 0L
                while (waited < frame.seconds * 1_000L && step == waitUntil) {
                    delay(250)
                    waited += 250
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(focus)
                .focusable()
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                            direction = 1
                            step++
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                            direction = -1
                            step++
                        }
                        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> return@onKeyEvent false
                        else -> finish()
                    }
                    true
                },
        ) {
            Crossfade(targetState = picture, animationSpec = tween(1_200), label = "frame") { bmp ->
                if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            if (state != FrameState.SHOWING) {
                Text(
                    stringResource(
                        when (state) {
                            FrameState.EMPTY -> R.string.tv_frame_empty
                            FrameState.FAILED -> R.string.tv_frame_failed
                            else -> R.string.tv_drive_loading
                        },
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (frame.clock) FrameClock(Modifier.align(Alignment.BottomEnd).padding(36.dp))
        }
    }

    private enum class FrameState { LOADING, SHOWING, EMPTY, FAILED }
}

/** Time and date in the corner, updated by the minute. */
@Composable
private fun FrameClock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000 - now % 60_000)
        }
    }
    val date = Date(now)
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(DateFormat.getTimeFormat(context).format(date), style = MaterialTheme.typography.displaySmall, color = Color.White)
        Text(DateFormat.getLongDateFormat(context).format(date), style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.85f))
    }
}
