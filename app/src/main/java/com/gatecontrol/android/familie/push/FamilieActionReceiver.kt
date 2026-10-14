package com.gatecontrol.android.familie.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieDest
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.locate.AnswerOutcome
import com.gatecontrol.android.familie.locate.LocateAnswerer
import com.gatecontrol.android.familie.places.GeofenceRegistrar
import com.gatecontrol.android.notify.PushRepository
import com.gatecontrol.android.support.CrashReports
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

/**
 * Buttons of Familie notifications that work without opening the app:
 * send / decline the location, task done / in 1 hour, accept / decline a
 * family place reminder, SOS seen. NOT exported: only the app's own
 * PendingIntents reach it. On success the notification goes away and the
 * message counts as read.
 */
@AndroidEntryPoint
class FamilieActionReceiver : BroadcastReceiver() {

    @Inject lateinit var api: FamilieApi
    @Inject lateinit var answerer: LocateAnswerer
    @Inject lateinit var pushRepository: PushRepository
    @Inject lateinit var registrar: GeofenceRegistrar
    @Inject lateinit var notifier: FamilieLocalNotifier

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action?.removePrefix(PREFIX)?.let { a -> FamiliePush.Action.entries.firstOrNull { it.name == a } } ?: return
        val target = intent.getIntExtra(EXTRA_TARGET, -1).takeIf { it > 0 } ?: return
        val messageId = intent.getLongExtra(EXTRA_MESSAGE, -1L)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION, 0)
        val occurrence = intent.getStringExtra(EXTRA_OCCURRENCE)?.takeIf { it.length == 10 }
        val pending = goAsync()
        scope.launch {
            try {
                val ok = withTimeoutOrNull(TIMEOUT_MS) { run(action, target, occurrence) } ?: false
                if (ok) {
                    if (notificationId != 0) NotificationManagerCompat.from(context).cancel(notificationId)
                    if (messageId > 0) runCatching { pushRepository.markRead(listOf(messageId), action.name.lowercase()) }
                } else if (notificationId != 0) {
                    notifier.showActionFailed(notificationId, failureDest(action))
                }
            } catch (e: Exception) {
                Timber.w("Familie: notification action failed (%s)", e.javaClass.simpleName)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun run(action: FamiliePush.Action, target: Int, occurrence: String?): Boolean = when (action) {
        // Only offered when the app may locate in the background (FamiliePermissions.sendPath).
        FamiliePush.Action.SEND_LOCATION -> answerer.answer(target, appVisible = false).let { it is AnswerOutcome.Sent || it is AnswerOutcome.Failed }
        FamiliePush.Action.DECLINE_LOCATION -> api.decline(target) is FamilieResult.Ok
        FamiliePush.Action.TASK_DONE -> api.taskDone(target, occurrence) is FamilieResult.Ok
        FamiliePush.Action.TASK_SNOOZE -> api.taskSnooze(target, SNOOZE_MINUTES, occurrence) is FamilieResult.Ok
        FamiliePush.Action.PLACE_ACCEPT, FamiliePush.Action.PLACE_DECLINE -> {
            val ok = api.placeConsent(target, action == FamiliePush.Action.PLACE_ACCEPT) is FamilieResult.Ok
            if (ok) registrar.syncFromServer()
            ok
        }
        FamiliePush.Action.SOS_SEEN -> api.sosAck(target) is FamilieResult.Ok
    }

    private fun failureDest(action: FamiliePush.Action): FamilieDest = when (action) {
        FamiliePush.Action.SEND_LOCATION, FamiliePush.Action.DECLINE_LOCATION -> FamilieDest.Child
        FamiliePush.Action.TASK_DONE, FamiliePush.Action.TASK_SNOOZE -> FamilieDest.Tasks
        FamiliePush.Action.PLACE_ACCEPT, FamiliePush.Action.PLACE_DECLINE -> FamilieDest.Places
        FamiliePush.Action.SOS_SEEN -> FamilieDest.Home
    }

    companion object {
        private const val PREFIX = "com.gatecontrol.android.familie.action."
        private const val EXTRA_TARGET = "com.gatecontrol.android.familie.extra.TARGET"
        private const val EXTRA_MESSAGE = "com.gatecontrol.android.familie.extra.MESSAGE"
        private const val EXTRA_NOTIFICATION = "com.gatecontrol.android.familie.extra.NOTIFICATION"
        private const val EXTRA_OCCURRENCE = "com.gatecontrol.android.familie.extra.OCCURRENCE"
        /** Locating takes up to 30 s, the answer up to 20 s more. */
        private const val TIMEOUT_MS = 55_000L
        const val SNOOZE_MINUTES = 60

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)

        /** Explicit intent to this non-exported receiver. */
        fun intent(context: Context, action: FamiliePush.Action, target: Int, messageId: Long, notificationId: Int, occurrence: String? = null): Intent =
            Intent(context, FamilieActionReceiver::class.java)
                .setAction(PREFIX + action.name)
                .putExtra(EXTRA_TARGET, target)
                .putExtra(EXTRA_MESSAGE, messageId)
                .putExtra(EXTRA_NOTIFICATION, notificationId)
                .apply { if (occurrence != null) putExtra(EXTRA_OCCURRENCE, occurrence) }
    }
}
