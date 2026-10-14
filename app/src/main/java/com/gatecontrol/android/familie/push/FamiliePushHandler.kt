package com.gatecontrol.android.familie.push

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.LocateMode
import com.gatecontrol.android.familie.locate.FamiliePermissions
import com.gatecontrol.android.familie.locate.LocateAnswerer
import com.gatecontrol.android.familie.places.GeofenceRegistrar
import com.gatecontrol.android.familie.shopping.ShoppingRepository
import com.gatecontrol.android.location.DeviceLocator
import androidx.core.app.NotificationManagerCompat
import com.gatecontrol.android.notify.PushChannels
import com.gatecontrol.android.notify.model.PushMessage
import com.gatecontrol.android.support.CrashReports
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the app does with Familie push messages besides showing them
 * (contract C3): answers "automatisch" locate requests in the background,
 * re-syncs geofences after place messages, refreshes the shopping lists
 * when someone goes shopping, remembers the parent's SOS acknowledgement,
 * and builds the notification buttons.
 */
@Singleton
class FamiliePushHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: FamilieRepository,
    private val answerer: Lazy<LocateAnswerer>,
    private val registrar: Lazy<GeofenceRegistrar>,
    private val shopping: Lazy<ShoppingRepository>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)

    /** A Familie message arrived for the first time on this device. */
    fun onNew(message: PushMessage) {
        val event = FamiliePush.parse(message) ?: return
        val topicKey = FamiliePush.topicKey(message.topic)
        repository.emit(event)
        when {
            event is FamiliePushEvent.LocateRequest && event.mode == LocateMode.AUTO -> {
                if (isFresh(message, System.currentTimeMillis())) {
                    scope.launch {
                        val outcome = answerer.get().answer(event.requestId)
                        Timber.i("Familie: automatic locate answer → %s", outcome.javaClass.simpleName)
                    }
                }
            }
            event is FamiliePushEvent.ShoppingGoing -> shopping.get().syncAsync()
            event is FamiliePushEvent.TaskDone -> {
                // Done by someone else (mode "first"): my reminder of that occurrence goes.
                val key = FamiliePush.reminderCollapseKey(event.taskId, event.occurrence)
                if (key != null && key != message.collapseKey) {
                    NotificationManagerCompat.from(context).cancel(PushChannels.notificationId(0L, key))
                }
            }
            FamiliePush.isSosAck(event, topicKey) -> repository.sosAcknowledged(message.title)
        }
        if (FamiliePush.touchesPlaces(event, topicKey)) registrar.get().syncAsync()
    }

    /** One notification button: label and what it triggers. */
    data class Button(@StringRes val label: Int, val intent: PendingIntent)

    /** Buttons that replace the generic ones; empty: keep the generic buttons. */
    fun buttons(message: PushMessage, notificationId: Int, slot: (Int) -> Uri): List<Button> {
        val event = FamiliePush.parse(message) ?: return emptyList()
        val occurrence = (event as? FamiliePushEvent.TaskDue)?.occurrence
        val rideId = (event as? FamiliePushEvent.Ride)?.rideId
        val target = when (event) {
            is FamiliePushEvent.LocateRequest -> event.requestId
            is FamiliePushEvent.TaskDue -> event.taskId
            is FamiliePushEvent.PlaceConsent -> event.placeId
            is FamiliePushEvent.Sos -> event.sosId
            is FamiliePushEvent.Ride -> if (rideId != null) 0 else return emptyList()
            is FamiliePushEvent.Allowance -> event.requestId ?: return emptyList()
            else -> return emptyList()
        }
        return FamiliePush.actions(event).mapIndexed { i, action ->
            val uri = slot(10 + i)
            val intent = if (action == FamiliePush.Action.SEND_LOCATION &&
                FamiliePermissions.sendPath(DeviceLocator.hasPermission(context), DeviceLocator.hasBackgroundPermission(context)) !=
                FamiliePermissions.SendPath.BACKGROUND
            ) {
                PendingIntent.getActivity(
                    context, 0,
                    FamilieLocateActivity.intent(context, target, message.id, notificationId).setData(uri),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            } else {
                PendingIntent.getBroadcast(
                    context, 0,
                    FamilieActionReceiver.intent(context, action, target, message.id, notificationId, occurrence, rideId).setData(uri),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
            Button(label(action), intent)
        }
    }

    /** Where a tap on the message leads (FamilieDest key), null for other senders. */
    fun destination(message: PushMessage): String? = FamiliePush.parse(message)?.let { FamiliePush.destination(it).key }

    companion object {
        /** Requests expire after 10 minutes; a replay of an older one is not answered. */
        private const val FRESH_MS = 10 * 60_000L

        fun isFresh(message: PushMessage, nowMs: Long): Boolean {
            val created = message.createdAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return true
            return nowMs - created < FRESH_MS
        }

        @StringRes
        fun label(action: FamiliePush.Action): Int = when (action) {
            FamiliePush.Action.SEND_LOCATION -> R.string.familie_action_send_location
            FamiliePush.Action.DECLINE_LOCATION -> R.string.familie_action_decline
            FamiliePush.Action.TASK_DONE -> R.string.familie_action_done
            FamiliePush.Action.TASK_SNOOZE -> R.string.familie_action_snooze
            FamiliePush.Action.PLACE_ACCEPT -> R.string.familie_action_accept
            FamiliePush.Action.PLACE_DECLINE -> R.string.familie_action_decline
            FamiliePush.Action.SOS_SEEN -> R.string.familie_action_seen
            FamiliePush.Action.RIDE_TAKE -> R.string.familie_ride_take
            FamiliePush.Action.RIDE_DECLINE -> R.string.familie_ride_decline
            FamiliePush.Action.ALLOWANCE_APPROVE -> R.string.familie_action_accept
            FamiliePush.Action.ALLOWANCE_DECLINE -> R.string.familie_action_decline
        }
    }
}
