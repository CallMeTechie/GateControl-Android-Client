package com.gatecontrol.android.tv.frame

import android.graphics.Bitmap
import android.os.Bundle
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.WindowManager
import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.TvSettings
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import java.util.Date
import javax.inject.Inject

/**
 * The photo frame over the whole screen: the photos shared for the photo frame ([TvFrameSource], `GET frame`), one every
 * [TvFrameSchedule.effectiveSeconds], fading into each other, with the time and date. ◀ ▶ step, any other key closes
 * (as a screen saver). Asked again every half hour (the URLs hold 2 h). Pictures only in memory.
 */
@AndroidEntryPoint
class TvFrameActivity : ComponentActivity() {

    @Inject lateinit var settings: TvSettings
    @Inject lateinit var source: TvFrameSource

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val frame = settings.frame.value
        setContent { GateControlTvTheme { Frame(frame) } }
    }

    @Composable
    private fun Frame(frame: TvFrameSettings) {
        var playlist by remember { mutableStateOf<TvFramePlaylist?>(null) }
        var picture by remember { mutableStateOf<Bitmap?>(null) }
        val status by source.status.collectAsStateWithLifecycle()
        var step by remember { mutableLongStateOf(0L) }
        var direction by remember { mutableIntStateOf(1) }
        val focus = remember { FocusRequester() }

        LaunchedEffect(Unit) {
            var misses = 0
            var applied: TvFrameFeed? = null
            while (true) {
                // asked again when due (half an hour; a minute after a failure); the last answer otherwise
                source.load(force = misses >= MISSES_BEFORE_RELOAD)
                if (misses >= MISSES_BEFORE_RELOAD) misses = 0
                val feed = source.last
                if (feed != null && source.status.value is TvFrameStatus.Photos) {
                    val list = playlist ?: TvFramePlaylist(emptyList(), TvFrameSchedule.effectiveOrder(frame, feed) == TvFrameOrder.RANDOM).also { playlist = it }
                    if (feed !== applied) {
                        list.update(feed.items)
                        applied = feed
                    }
                } else {
                    applied = null
                    playlist = null
                    picture = null
                    delay(TvFrameSchedule.RETRY_MS)
                    continue
                }
                val list = playlist ?: continue
                // a picture that cannot be loaded is skipped (a few tries per step); several in a row: asked again
                var shown: Bitmap? = null
                repeat(3) {
                    if (shown != null) return@repeat
                    val photo = (if (direction < 0) list.previous() else list.next()) ?: return@repeat
                    shown = source.picture(photo)
                }
                direction = 1
                if (shown != null) {
                    picture = shown
                    misses = 0
                } else {
                    misses++
                }
                val waitUntil = step
                // the time per picture, or until ◀ ▶ asks for another one
                val ms = TvFrameSchedule.effectiveSeconds(frame, source.last) * 1_000L
                var waited = 0L
                while (waited < ms && step == waitUntil) {
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
            if (picture == null) {
                Text(
                    stringResource(TvFrameTexts.message(status)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).widthIn(max = 760.dp).padding(24.dp),
                )
            }
            if (frame.clock) FrameClock(Modifier.align(Alignment.BottomEnd).padding(36.dp))
        }
    }

    private companion object {
        /** This many steps without a picture in a row: the URLs may have run out, `GET frame` is asked again. */
        const val MISSES_BEFORE_RELOAD = 3
    }
}

/** The texts of the frame's states (the frame and Einstellungen › Fotorahmen). */
object TvFrameTexts {
    @StringRes
    fun message(status: TvFrameStatus): Int = when (status) {
        TvFrameStatus.Unknown, is TvFrameStatus.Photos -> R.string.tv_drive_loading
        TvFrameStatus.NoPhotos -> R.string.tv_frame_none_shared
        TvFrameStatus.NotReady -> R.string.tv_frame_not_ready
        TvFrameStatus.Unavailable -> R.string.tv_frame_unavailable
        TvFrameStatus.Failed -> R.string.tv_frame_failed
    }
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
