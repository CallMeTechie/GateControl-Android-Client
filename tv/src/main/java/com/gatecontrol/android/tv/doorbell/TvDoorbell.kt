package com.gatecontrol.android.tv.doorbell

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.gatecontrol.android.door.DoorPush
import com.gatecontrol.android.notify.model.PushMessage
import com.gatecontrol.android.tv.TvSettings
import com.gatecontrol.android.tv.push.TvPushPresenter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Calendar
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Whether an activity of GateControl TV is in front (started, not stopped). */
object TvForeground {
    private val started = AtomicInteger()

    val inFront: Boolean get() = started.get() > 0

    fun onStart() {
        started.incrementAndGet()
    }

    fun onStop() {
        started.updateAndGet { (it - 1).coerceAtLeast(0) }
    }
}

/**
 * A ring at the door: the TV's own overlay ([TvDoorbellActivity]) by [DoorbellRules] — the window at the bottom right
 * or over everything — besides the system notification the push presenter posts anyway.
 */
@Singleton
class TvDoorbell @Inject constructor(
    @ApplicationContext private val context: Context,
    private val presenter: TvPushPresenter,
    private val settings: TvSettings,
) {
    fun start(scope: CoroutineScope) {
        scope.launch { presenter.messages.collect { onMessage(it) } }
    }

    private fun onMessage(message: PushMessage) {
        val info = DoorPush.parse(message) ?: return
        val now = Calendar.getInstance()
        val show = DoorbellRules.decide(
            info = info,
            settings = settings.doorbell.value,
            minuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE),
            appInFront = TvForeground.inFront,
            canDrawOverlays = canDrawOverlays(),
        )
        Timber.i("TV doorbell: %s (%s)", show, info.type)
        if (show != DoorbellShow.WINDOW && show != DoorbellShow.FULLSCREEN) return
        val intent = TvDoorbellActivity.intent(context, message, info, fullscreen = show == DoorbellShow.FULLSCREEN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Android 9 lets the app start it from the background; from Android 10 only with "Über anderen Apps einblenden".
        runCatching { context.startActivity(intent) }
            .onFailure { Timber.w("TV doorbell: overlay refused (%s)", it.javaClass.simpleName) }
    }

    /** "Über anderen Apps einblenden" (on Fire OS only by ADB: `appops set … SYSTEM_ALERT_WINDOW allow`). */
    fun canDrawOverlays(): Boolean = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
}
