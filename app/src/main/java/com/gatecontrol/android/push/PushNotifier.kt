package com.gatecontrol.android.push

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gatecontrol.android.MainActivity
import com.gatecontrol.android.R
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.door.DoorPush
import com.gatecontrol.android.door.DoorRepository
import com.gatecontrol.android.familie.push.FamiliePushHandler
import com.gatecontrol.android.support.CrashReports
import com.gatecontrol.android.notify.ChannelImportance
import com.gatecontrol.android.notify.ChannelSpec
import com.gatecontrol.android.notify.PushChannels
import com.gatecontrol.android.notify.PushPresenter
import com.gatecontrol.android.notify.PushStatus
import com.gatecontrol.android.notify.model.PushAction
import com.gatecontrol.android.notify.model.PushActionType
import com.gatecontrol.android.notify.model.PushMessage
import com.gatecontrol.android.notify.model.PushPriority
import com.gatecontrol.android.notify.model.PushTopic
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notifications of the notification center: channels, one notification
 * per message (or per collapse key) with its actions, and the single
 * ongoing notification of [PushService]. Texts follow the language chosen
 * in the app, not only the system language.
 */
@Singleton
class PushNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val familie: dagger.Lazy<FamiliePushHandler>,
    private val media: NotifyMediaLoader,
    private val door: dagger.Lazy<DoorRepository>,
) : PushPresenter {

    companion object {
        const val ONGOING_ID = 1003
        private const val GROUP_ID = "push"
        private const val ACTION_SEEN = "seen"
        /** How long a message waits for its picture before it is posted without (then updated). */
        private const val PICTURE_WAIT_MS = 2_500L
        /** A successful notification quick action's result stays this long. */
        private const val RESULT_TIMEOUT_MS = 10 * 60_000L
        private const val EXTRA_MESSAGE_ID = "com.gatecontrol.android.push.extra.MESSAGE_ID"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)

    private val nm: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    /** Context in the app language (Settings → Darstellung → Sprache). */
    private suspend fun localized(): Context {
        val lang = runCatching { settingsRepository.getLocale().first() }.getOrNull() ?: return context
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang)) }
        return context.createConfigurationContext(config)
    }

    private fun importance(i: ChannelImportance): Int = when (i) {
        ChannelImportance.MIN -> NotificationManager.IMPORTANCE_MIN
        ChannelImportance.LOW -> NotificationManager.IMPORTANCE_LOW
        ChannelImportance.DEFAULT -> NotificationManager.IMPORTANCE_DEFAULT
        ChannelImportance.HIGH -> NotificationManager.IMPORTANCE_HIGH
    }

    private fun channelName(ctx: Context, spec: ChannelSpec): String =
        spec.key?.let { PushText.channelName(it) }?.let { ctx.getString(it) }
            ?: spec.label
            ?: PushText.fallbackLabel(spec.id.removePrefix(PushChannels.topicChannelId("")))

    /** Creates (or renames) the channels. Importance of an existing channel is the user's. */
    private fun createChannels(ctx: Context, specs: List<ChannelSpec>) {
        nm.createNotificationChannelGroup(NotificationChannelGroup(GROUP_ID, ctx.getString(R.string.push_channel_group)))
        // The old low info channel kept info messages off the lock screen; its successor replaces it.
        if (nm.getNotificationChannel(PushChannels.LEGACY_INFO) != null) nm.deleteNotificationChannel(PushChannels.LEGACY_INFO)
        for (spec in specs) {
            val channel = NotificationChannel(spec.id, channelName(ctx, spec), importance(spec.importance)).apply {
                group = GROUP_ID
                if (!spec.sound) {
                    setSound(null, null)
                    enableVibration(false)
                }
                if (spec.id == PushChannels.CONNECTION || spec.id == PushChannels.SILENT) setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    suspend fun ensureFixedChannels() {
        createChannels(localized(), PushChannels.fixed())
    }

    override suspend fun ensureTopicChannels(topics: List<PushTopic>) {
        val ctx = localized()
        createChannels(ctx, PushChannels.fixed() + PushChannels.forTopics(topics.filter { it.isPlugin }))
    }

    override suspend fun show(message: PushMessage, notificationId: Int, channel: ChannelSpec, quiet: Boolean) {
        val ctx = localized()
        if (nm.getNotificationChannel(channel.id) == null) createChannels(ctx, listOf(channel))

        val builder = baseBuilder(ctx, message, channel.id)
            .setOnlyAlertOnce(message.priorityLevel != PushPriority.CRITICAL)
            .setSilent(quiet || message.silent)
            .setDeleteIntent(receiverIntent(PushActionReceiver.ACTION_DISMISSED, message, null, slot = 1))
        addButtons(ctx, builder, message, notificationId)

        // Picture (core 1.169+, e.g. the doorbell snapshot): a short wait, else
        // posted without and updated quietly once it is there.
        val url = message.imageUrl
        val picture = url?.let { media.peek(it, NotifyMediaLoader.NOTIFICATION_SIDE_PX) ?: withTimeoutOrNull(PICTURE_WAIT_MS) { media.load(it) } }
        if (picture != null) applyPicture(builder, picture, message)
        post(notificationId, builder.build())

        if (url != null && picture == null) {
            scope.launch {
                val late = media.load(url) ?: return@launch
                if (!isShowing(notificationId, message.id)) return@launch
                applyPicture(builder.setOnlyAlertOnce(true).setSilent(true), late, message)
                post(notificationId, builder.build())
            }
        }
    }

    /** Title, topic, time, text and the tap of a message notification. */
    private fun baseBuilder(ctx: Context, message: PushMessage, channelId: String): NotificationCompat.Builder {
        val topicLabel = PushText.topicLabel(message.topic)?.let { ctx.getString(it) } ?: PushText.fallbackLabel(message.topic)
        val priority = message.priorityLevel
        val sub = if (priority == PushPriority.CRITICAL) ctx.getString(R.string.push_priority_critical) + " · " + topicLabel else topicLabel
        val whenMs = runCatching { Instant.parse(message.createdAt).toEpochMilli() }.getOrNull() ?: System.currentTimeMillis()

        val builder = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(message.title)
            .setSubText(sub)
            .setWhen(whenMs)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(
                when {
                    message.silent -> NotificationCompat.PRIORITY_LOW
                    priority == PushPriority.CRITICAL -> NotificationCompat.PRIORITY_HIGH
                    else -> NotificationCompat.PRIORITY_DEFAULT
                },
            )
            .setContentIntent(openIntent(message, null, slot = 0, familieDest = familie.get().destination(message)))
            .addExtras(Bundle().apply { putLong(EXTRA_MESSAGE_ID, message.id) })
        if (priority == PushPriority.CRITICAL) builder.setColor(0xFFFF8080.toInt())
        if (message.body.isNotBlank()) {
            builder.setContentText(message.body.lineSequence().first())
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
        }
        return builder
    }

    /** The message's buttons: Familie's own, else the server's with "Gesehen" (quick actions included). */
    private fun addButtons(ctx: Context, builder: NotificationCompat.Builder, message: PushMessage, notificationId: Int) {
        // Familie messages bring their own buttons (send location, done, accept, …).
        val familieButtons = familie.get().buttons(message, notificationId) { slot -> uri(message, slot) }
        if (familieButtons.isNotEmpty()) {
            familieButtons.forEach { builder.addAction(0, ctx.getString(it.label), it.intent) }
            return
        }
        val seen = PushAction(ACTION_SEEN, ctx.getString(R.string.push_action_seen), PushActionType.ACK)
        NotificationQuickActions.plan(message.actions, seen).forEachIndexed { i, button ->
            val action = button.action
            val pi = when (button.kind) {
                ButtonKind.SEEN -> receiverIntent(PushActionReceiver.ACTION_READ, message, null, slot = 2 + i)
                ButtonKind.BACKGROUND -> receiverIntent(PushActionReceiver.ACTION_RUN, message, action, slot = 2 + i)
                ButtonKind.QUICK_ACTION -> receiverIntent(PushActionReceiver.ACTION_QUICK, message, action, slot = 2 + i)
                ButtonKind.OPEN -> openIntent(message, action, slot = 2 + i)
            }
            builder.addAction(
                NotificationCompat.Action.Builder(0, action.label, pi)
                    // auth:'device' — Android asks for the device unlock (biometrics / PIN) before it sends the action.
                    .setAuthenticationRequired(button.authRequired)
                    .build(),
            )
        }
    }

    private fun applyPicture(builder: NotificationCompat.Builder, picture: Bitmap, message: PushMessage) {
        builder.setLargeIcon(picture)
        builder.setStyle(
            NotificationCompat.BigPictureStyle()
                .bigPicture(picture)
                .bigLargeIcon(null as Bitmap?)
                .also { style -> message.body.takeIf { it.isNotBlank() }?.let { style.setSummaryText(it.lineSequence().first()) } },
        )
    }

    /** The notification [notificationId] is still in the shade and still shows message [messageId]. */
    private fun isShowing(notificationId: Int, messageId: Long): Boolean = runCatching {
        nm.activeNotifications.any { it.id == notificationId && it.notification.extras.getLong(EXTRA_MESSAGE_ID, -1L) == messageId }
    }.getOrDefault(false)

    /** The quick action is still running (slow lock): the notification says so, without buttons. */
    suspend fun showQuickActionPending(message: PushMessage) {
        val ctx = localized()
        val channel = PushChannels.channelFor(message.copy(priority = "info", silent = false))
        if (nm.getNotificationChannel(channel.id) == null) createChannels(ctx, listOf(channel))
        val line = ctx.getString(R.string.push_quick_action_running)
        val builder = baseBuilder(ctx, message, channel.id)
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setTimeoutAfter(RESULT_TIMEOUT_MS)
        post(PushChannels.notificationId(message), builder.build())
    }

    /** Text of a refused notification quick action. */
    suspend fun refusalText(decision: NotificationQuickActions.Decision): String = localized().getString(
        when (decision) {
            NotificationQuickActions.Decision.EXPIRED -> R.string.push_quick_action_expired
            NotificationQuickActions.Decision.LOCKED -> R.string.push_quick_action_locked
            else -> R.string.push_quick_action_unavailable
        },
    )

    /**
     * Result of a quick action run from the notification: the message's
     * notification shows it quietly. Success: no buttons, gone after a few
     * minutes; failure: the buttons stay, so it can be tried again.
     */
    suspend fun showQuickActionResult(message: PushMessage, ok: Boolean, text: String?, keepButtons: Boolean = !ok) {
        val ctx = localized()
        val channel = PushChannels.channelFor(message.copy(priority = "info", silent = false))
        if (nm.getNotificationChannel(channel.id) == null) createChannels(ctx, listOf(channel))
        val notificationId = PushChannels.notificationId(message)
        val line = text ?: ctx.getString(if (ok) R.string.push_quick_action_done else R.string.push_quick_action_failed)
        val builder = baseBuilder(ctx, message, channel.id)
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (keepButtons) {
            builder.setDeleteIntent(receiverIntent(PushActionReceiver.ACTION_DISMISSED, message, null, slot = 1))
            addButtons(ctx, builder, message, notificationId)
        } else {
            builder.setTimeoutAfter(RESULT_TIMEOUT_MS)
        }
        post(notificationId, builder.build())
    }

    override fun cancel(notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    /** Familie messages: locate answers, geofence sync, … (see FamiliePushHandler); door: the tab reloads. */
    override suspend fun onNew(message: PushMessage) {
        familie.get().onNew(message)
        if (DoorPush.isDoorMessage(message)) door.get().onPush(message)
    }

    /** Distinct data URI per message and slot: PendingIntents must not overwrite each other. */
    private fun uri(message: PushMessage, slot: Int): Uri =
        Uri.parse("gatecontrol-push://message/${message.id}/${message.seq}/$slot")

    private fun openIntent(message: PushMessage, action: PushAction?, slot: Int, familieDest: String? = null): PendingIntent {
        // Door messages: the ring's detail (or the door tab) for the tap and for "Ansehen".
        val doorDest = DoorPush.destination(message)?.key
        val intent = PushOpenActivity.intent(context, message.id, action, familieDest, doorDest).setData(uri(message, slot))
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun receiverIntent(op: String, message: PushMessage, action: PushAction?, slot: Int): PendingIntent {
        val intent = PushActionReceiver.intent(context, op, message.id, action).setData(uri(message, slot))
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    // ─── Ongoing notification of the push service ───────────────────────

    /** Text of the ongoing notification: VPN state + push state ("VPN verbunden · Mitteilungen empfangsbereit"). */
    suspend fun ongoingText(vpnConnected: Boolean, status: PushStatus): String {
        val ctx = localized()
        val vpn = ctx.getString(if (vpnConnected) R.string.push_ongoing_vpn_on else R.string.push_ongoing_vpn_off)
        val push = ctx.getString(
            when (status) {
                is PushStatus.Connected -> R.string.push_ongoing_ready
                is PushStatus.Blocked -> R.string.push_ongoing_blocked
                is PushStatus.Waiting -> R.string.push_ongoing_waiting
                else -> R.string.push_ongoing_connecting
            },
        )
        return "$vpn · $push"
    }

    suspend fun ongoing(text: String): Notification {
        val ctx = localized()
        createChannels(ctx, PushChannels.fixed())
        val tap = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(ctx, PushChannels.CONNECTION)
            .setSmallIcon(R.drawable.ic_vpn_tile)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .setContentIntent(tap)
            .build()
    }

    /** Last ongoing notification that could not be posted (no permission yet); see [repostOngoing]. */
    @Volatile
    private var pendingOngoing: Notification? = null

    fun updateOngoing(notification: Notification) {
        pendingOngoing = if (post(ONGOING_ID, notification)) null else notification
    }

    /**
     * Posts the latest ongoing notification again if it could not be posted
     * before. On first setup the push service starts before Android allows
     * notifications: the switch to "empfangsbereit" was dropped then, and the
     * card kept saying "verbinden…" until the text changed again (VPN).
     */
    fun repostOngoing() {
        pendingOngoing?.let { if (post(ONGOING_ID, it)) pendingOngoing = null }
    }

    /** True when the notification was handed to Android. */
    private fun post(id: Int, notification: Notification): Boolean {
        if (!canPost()) return false
        return try {
            NotificationManagerCompat.from(context).notify(id, notification)
            true
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call: the inbox still has the message.
            false
        }
    }

    /** POST_NOTIFICATIONS exists from Android 13; before, posting needs no runtime permission. */
    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
