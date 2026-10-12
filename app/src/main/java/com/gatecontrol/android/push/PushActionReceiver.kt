package com.gatecontrol.android.push

import com.gatecontrol.android.support.CrashReports
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gatecontrol.android.notify.PushRepository
import com.gatecontrol.android.notify.model.PushAction
import com.gatecontrol.android.notify.model.PushActionType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

/**
 * Notification buttons that need no screen ("Gesehen", "1 h stumm",
 * "Erledigt", ack) and the swipe-away of a message. NOT exported: only the
 * app's own PendingIntents reach it. Each one acknowledges to the server.
 */
@AndroidEntryPoint
class PushActionReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: PushRepository

    override fun onReceive(context: Context, intent: Intent) {
        val op = intent.action ?: return
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id <= 0) return
        val actionId = intent.getStringExtra(EXTRA_ACTION_ID)
        val actionType = intent.getStringExtra(EXTRA_ACTION_TYPE)
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
                    }
                }
            } catch (e: Exception) {
                Timber.w("Push: notification action failed (%s)", e.javaClass.simpleName)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_READ = "com.gatecontrol.android.push.READ"
        const val ACTION_DISMISSED = "com.gatecontrol.android.push.DISMISSED"
        const val ACTION_RUN = "com.gatecontrol.android.push.RUN"
        private const val EXTRA_ID = "com.gatecontrol.android.push.extra.ID"
        private const val EXTRA_ACTION_ID = "com.gatecontrol.android.push.extra.ACTION_ID"
        private const val EXTRA_ACTION_TYPE = "com.gatecontrol.android.push.extra.ACTION_TYPE"
        private const val TIMEOUT_MS = 8_000L

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
                    }
                }
    }
}
