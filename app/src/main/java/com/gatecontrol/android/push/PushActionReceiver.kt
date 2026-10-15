package com.gatecontrol.android.push

import com.gatecontrol.android.support.CrashReports
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gatecontrol.android.notify.PushRepository
import com.gatecontrol.android.notify.model.PushAction
import com.gatecontrol.android.notify.model.PushActionType
import com.gatecontrol.android.quickactions.QuickActionRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

/**
 * Notification buttons that need no screen ("Gesehen", "1 h stumm",
 * "Erledigt", ack, a plugin's quick action) and the swipe-away of a
 * message. NOT exported: only the app's own PendingIntents reach it. Each
 * one acknowledges to the server.
 */
@AndroidEntryPoint
class PushActionReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: PushRepository
    @Inject lateinit var quickActions: QuickActionRepository
    @Inject lateinit var notifier: PushNotifier

    override fun onReceive(context: Context, intent: Intent) {
        val op = intent.action ?: return
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id <= 0) return
        val actionId = intent.getStringExtra(EXTRA_ACTION_ID)
        val actionType = intent.getStringExtra(EXTRA_ACTION_TYPE)
        val plugin = intent.getStringExtra(EXTRA_PLUGIN)
        // Read now: the receiver's context must not be kept for the async part.
        val deviceLocked = context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked ?: true
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(TIMEOUT_MS) {
                    when (op) {
                        ACTION_READ -> repository.markRead(listOf(id))
                        ACTION_DISMISSED -> repository.dismissed(id)
                        ACTION_RUN -> if (actionId != null && actionType in PushActionType.BACKGROUND) {
                            repository.runAction(id, PushAction(actionId, actionId, actionType!!))
                        }
                        ACTION_QUICK -> {
                            // Runs on outside the broadcast's time: a lock may answer late; the
                            // notification shows "läuft" until then, never a wrong "failed".
                            val job = scope.async { runQuickAction(id, plugin, actionId, deviceLocked) }
                            if (withTimeoutOrNull(QUICK_WAIT_MS) { job.await() } == null) {
                                repository.inbox.get(id)?.let { notifier.showQuickActionPending(it) }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.w("Push: notification action failed (%s)", e.javaClass.simpleName)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * A quick_action button (core 1.169+): checked against the message
     * (its own button, not expired, unlocked device for `auth: device` —
     * Android asked for the unlock already), run with `source: notification`,
     * acknowledged with the action id on success; the notification shows the
     * result.
     */
    private suspend fun runQuickAction(id: Long, plugin: String?, actionId: String?, deviceLocked: Boolean) {
        repository.inbox.load()
        val message = repository.inbox.get(id)
        val decision = NotificationQuickActions.decide(message, plugin, actionId, System.currentTimeMillis(), deviceLocked)
        if (message == null) return
        if (decision != NotificationQuickActions.Decision.RUN || plugin == null || actionId == null) {
            Timber.w("Push: quick action refused (%s)", decision)
            notifier.showQuickActionResult(
                message, ok = false, text = notifier.refusalText(decision),
                keepButtons = decision == NotificationQuickActions.Decision.LOCKED,
            )
            return
        }
        val result = quickActions.runFromNotification(plugin, actionId)
        if (result.ok) repository.markRead(listOf(id), actionId)
        notifier.showQuickActionResult(message, result.ok, result.message)
    }

    companion object {
        const val ACTION_READ = "com.gatecontrol.android.push.READ"
        const val ACTION_DISMISSED = "com.gatecontrol.android.push.DISMISSED"
        const val ACTION_RUN = "com.gatecontrol.android.push.RUN"
        const val ACTION_QUICK = "com.gatecontrol.android.push.QUICK"
        private const val EXTRA_ID = "com.gatecontrol.android.push.extra.ID"
        private const val EXTRA_ACTION_ID = "com.gatecontrol.android.push.extra.ACTION_ID"
        private const val EXTRA_ACTION_TYPE = "com.gatecontrol.android.push.extra.ACTION_TYPE"
        private const val EXTRA_PLUGIN = "com.gatecontrol.android.push.extra.PLUGIN"
        /** A quick action may take a while (a door lock); the broadcast may live ~10 s with goAsync. */
        private const val TIMEOUT_MS = 9_000L
        private const val QUICK_WAIT_MS = 8_000L

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)

        /** Explicit intent to this non-exported receiver. */
        fun intent(context: Context, op: String, id: Long, action: PushAction?): Intent =
            Intent(context, PushActionReceiver::class.java)
                .setAction(op)
                .putExtra(EXTRA_ID, id)
                .apply {
                    if (action != null) {
                        putExtra(EXTRA_ACTION_ID, action.id)
                        putExtra(EXTRA_ACTION_TYPE, action.type)
                        action.plugin?.let { putExtra(EXTRA_PLUGIN, it) }
                    }
                }
    }
}
