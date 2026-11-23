package com.gatecontrol.android.tv.doorbell

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.door.DoorPushInfo
import com.gatecontrol.android.notify.model.PushMessage
import com.gatecontrol.android.tv.ui.doorbell.TvDoorbellOverlay
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The doorbell's overlay: a translucent activity with the window at the bottom right (mockup "3 · Kamera-Einblendung
 * beim Klingeln"), or the picture over the whole screen ([EXTRA_FULLSCREEN], "Groß ansehen"). Goes by itself after
 * 30 s unless the remote touched it; Zurück closes it.
 */
@AndroidEntryPoint
class TvDoorbellActivity : ComponentActivity() {

    private val viewModel: TvDoorbellViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startFull = intent.getBooleanExtra(EXTRA_FULLSCREEN, false)
        setContent {
            GateControlTvTheme {
                var full by rememberSaveable { mutableStateOf(startFull) }
                val ui by viewModel.ui.collectAsStateWithLifecycle()
                val live by viewModel.liveUi.collectAsStateWithLifecycle()
                TvDoorbellOverlay(
                    ui = ui,
                    live = live,
                    sourceFactory = viewModel.live::sourceFactory,
                    fullscreen = full,
                    onToggleSize = { full = !full },
                    onOpen = viewModel::open,
                    onConfirm = viewModel::confirmed,
                    onPinPassed = viewModel::pinPassed,
                    checkPin = viewModel::checkPin,
                    onCancelStep = viewModel::cancelStep,
                    onClose = ::finish,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        TvForeground.onStart()
    }

    override fun onStop() {
        TvForeground.onStop()
        super.onStop()
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        const val EXTRA_TYPE = "type"
        const val EXTRA_EVENT = "event"
        const val EXTRA_CAMERA = "camera"
        const val EXTRA_FULLSCREEN = "fullscreen"

        fun intent(context: Context, message: PushMessage, info: DoorPushInfo, fullscreen: Boolean): Intent =
            Intent(context, TvDoorbellActivity::class.java)
                .putExtra(EXTRA_TITLE, message.title.take(120))
                .putExtra(EXTRA_BODY, message.body.take(240))
                .putExtra(EXTRA_TYPE, info.type)
                .putExtra(EXTRA_EVENT, info.eventId ?: 0L)
                .putExtra(EXTRA_CAMERA, info.cameraId ?: 0L)
                .putExtra(EXTRA_FULLSCREEN, fullscreen)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
