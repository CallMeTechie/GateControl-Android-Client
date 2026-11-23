package com.gatecontrol.android.tv.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.gatecontrol.android.door.DoorPush
import com.gatecontrol.android.door.DoorRepository
import com.gatecontrol.android.notify.ChannelImportance
import com.gatecontrol.android.notify.ChannelSpec
import com.gatecontrol.android.notify.PushChannels
import com.gatecontrol.android.notify.PushPresenter
import com.gatecontrol.android.notify.model.PushMessage
import com.gatecontrol.android.notify.model.PushTopic
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.TvActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Messages of the push stream on GateControl TV. Fire OS shows Android notifications only briefly (a short banner,
 * the list in its settings), so a message also goes to [messages]: the app shows its own overlay there (the doorbell
 * with the camera picture). The door messages reach [DoorRepository] like on the phone (an open camera page reloads).
 */
@Singleton
class TvPushPresenter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val door: dagger.Lazy<DoorRepository>,
) : PushPresenter {

    private val _messages = MutableSharedFlow<PushMessage>(extraBufferCapacity = 16)

    /** Every message seen for the first time (never a replay after a reconnect), shown or not. */
    val messages: SharedFlow<PushMessage> = _messages.asSharedFlow()

    private val nm: NotificationManager? get() = context.getSystemService(NotificationManager::class.java)

    fun ensureFixedChannels() {
        val manager = nm ?: return
        PushChannels.fixed().forEach { manager.createNotificationChannel(channel(it)) }
    }

    override suspend fun ensureTopicChannels(topics: List<PushTopic>) {
        val manager = nm ?: return
        PushChannels.forTopics(topics).forEach { manager.createNotificationChannel(channel(it)) }
    }

    override suspend fun show(message: PushMessage, notificationId: Int, channel: ChannelSpec, quiet: Boolean) {
        val manager = nm ?: return
        // A plugin topic's channel appears on the fly (labels from `hello`, else the topic).
        if (manager.getNotificationChannel(channel.id) == null) manager.createNotificationChannel(channel(channel))
        val notification = NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(message.title)
            .setContentText(message.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
            .setAutoCancel(true)
            .setOnlyAlertOnce(quiet)
            .setSilent(quiet)
            .setContentIntent(openApp())
            .build()
        runCatching { manager.notify(notificationId, notification) }
            .onFailure { Timber.w("TV push: not shown (%s)", it.javaClass.simpleName) }
    }

    override fun cancel(notificationId: Int) {
        runCatching { nm?.cancel(notificationId) }
    }

    override suspend fun onNew(message: PushMessage) {
        if (DoorPush.isDoorMessage(message)) door.get().onPush(message)
        _messages.tryEmit(message)
    }

    /** The ongoing card of the push service ("Verbindung", importance min). */
    fun ongoing(text: String) = NotificationCompat.Builder(context, PushChannels.CONNECTION)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(context.getString(R.string.tv_app_name))
        .setContentText(text)
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setContentIntent(openApp())
        .build()

    fun updateOngoing(text: String) {
        runCatching { nm?.notify(ONGOING_ID, ongoing(text)) }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, TvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun channel(spec: ChannelSpec): NotificationChannel {
        val importance = when (spec.importance) {
            ChannelImportance.MIN -> NotificationManager.IMPORTANCE_MIN
            ChannelImportance.LOW -> NotificationManager.IMPORTANCE_LOW
            ChannelImportance.DEFAULT -> NotificationManager.IMPORTANCE_DEFAULT
            ChannelImportance.HIGH -> NotificationManager.IMPORTANCE_HIGH
        }
        return NotificationChannel(spec.id, channelName(spec), importance).apply {
            if (!spec.sound) {
                setSound(null, null)
                enableVibration(false)
            }
        }
    }

    private fun channelName(spec: ChannelSpec): String = when (spec.key) {
        PushChannels.KEY_CRITICAL -> context.getString(R.string.tv_channel_critical)
        PushChannels.KEY_INFO -> context.getString(R.string.tv_channel_info)
        PushChannels.KEY_SILENT -> context.getString(R.string.tv_channel_silent)
        PushChannels.KEY_CONNECTION -> context.getString(R.string.tv_channel_connection)
        PushTopic.SECURITY -> context.getString(R.string.tv_channel_security)
        PushTopic.DEVICES -> context.getString(R.string.tv_channel_devices)
        PushTopic.SERVICES -> context.getString(R.string.tv_channel_services)
        PushTopic.SYSTEM -> context.getString(R.string.tv_channel_system)
        PushTopic.ADMIN_NOTICE -> context.getString(R.string.tv_channel_admin)
        else -> spec.label?.takeIf { it.isNotBlank() }?.take(40) ?: context.getString(R.string.tv_channel_other)
    }

    companion object {
        /** The push service's ongoing card (the phone app uses the same id). */
        const val ONGOING_ID = 1003
    }
}
