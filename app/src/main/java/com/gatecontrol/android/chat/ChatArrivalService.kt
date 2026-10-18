package com.gatecontrol.android.chat

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.gatecontrol.android.R
import com.gatecontrol.android.location.DeviceLocator
import com.gatecontrol.android.push.PushOpenActivity
import com.gatecontrol.android.support.CrashReports
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject

/** One live arrival this device shares. */
data class ChatLiveShare(val conversationId: Long, val messageId: Long, val destLabel: String, val startedAtMs: Long)

/** Pure decisions of the live arrival. Unit-tested. */
object ChatArrivalLogic {
    /** Positions go out at most this often (the server's limit). */
    const val MIN_INTERVAL_MS = 20_000L

    /** The server stops a live arrival after 3 h; the device does too. */
    const val MAX_DURATION_MS = 3 * 3_600_000L

    fun shouldPost(lastPostMs: Long?, nowMs: Long): Boolean = lastPostMs == null || nowMs - lastPostMs >= MIN_INTERVAL_MS

    fun expired(share: ChatLiveShare, nowMs: Long): Boolean = nowMs - share.startedAtMs >= MAX_DURATION_MS

    /** Whether sharing goes on after the server's answer: only while the message is still `live`. */
    fun keepGoing(arrival: ChatArrival?): Boolean = arrival == null || arrival.live

    /** The server will take no more positions: no longer live (`closed`), not the sender, gone. */
    fun gone(r: ChatResult<*>): Boolean = r is ChatResult.Failed && (r.code in setOf("closed", "not_sender", "not_found", "deleted") || r.http == 404)

    fun encode(shares: Collection<ChatLiveShare>): Set<String> =
        shares.map { "${it.conversationId}|${it.messageId}|${it.startedAtMs}|${it.destLabel.replace('|', ' ')}" }.toSet()

    fun decode(raw: Set<String>?): List<ChatLiveShare> = raw.orEmpty().mapNotNull { e ->
        val p = e.split('|', limit = 4)
        if (p.size != 4) return@mapNotNull null
        val conv = p[0].toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        val msg = p[1].toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        val start = p[2].toLongOrNull() ?: return@mapNotNull null
        ChatLiveShare(conv, msg, p[3], start)
    }
}

/**
 * Live arrival (Messenger 1.4, `kind: arrival`): while one of the caller's
 * arrivals is `live`, this foreground service (type location, with an
 * ongoing notification and "Stopp") sends the position at most every 20 s
 * (`POST messages/:id/arrival`). It ends when the server answers that the
 * arrival is no longer live (arrived, stopped), on "Stopp", or after 3 h.
 * Started only from the app's own screen while visible (location "while in
 * use" is enough for a foreground service started then).
 */
@AndroidEntryPoint
class ChatArrivalService : Service() {

    @Inject lateinit var extras: ChatExtrasRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)
    private val mutex = Mutex()
    private var listener: LocationListener? = null
    private val lastPost = HashMap<Long, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                val msg = intent.getLongExtra(EXTRA_MESSAGE, 0L)
                val share = shares(this).firstOrNull { it.messageId == msg }
                remove(this, msg)
                lastPost.remove(msg)
                if (share != null) scope.launch { extras.arrivalStop(share.conversationId, msg) }
            }
            else -> Unit
        }
        val shares = shares(this)
        if (shares.isEmpty() || !DeviceLocator.hasPermission(this)) {
            stopNow()
            return START_NOT_STICKY
        }
        ensureChannel()
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notification(shares), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            // Not allowed from the background (Android 12+ / no permission): the arrival stays without updates.
            Timber.w("Chats: live arrival not started (%s)", e.javaClass.simpleName)
            stopNow()
            return START_NOT_STICKY
        }
        startUpdates()
        return START_STICKY
    }

    @SuppressLint("MissingPermission") // checked in onStartCommand
    private fun startUpdates() {
        if (listener != null) return
        val lm = getSystemService(LocationManager::class.java) ?: return
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { lm.hasProvider(it) && lm.isProviderEnabled(it) }.getOrDefault(false) } ?: return
        val l = LocationListener { loc -> onLocation(loc) }
        listener = l
        runCatching { lm.requestLocationUpdates(provider, UPDATE_MS, 0f, l, Looper.getMainLooper()) }
            .onFailure { Timber.w("Chats: no location updates (%s)", it.javaClass.simpleName) }
    }

    private fun onLocation(loc: Location) {
        scope.launch {
            mutex.withLock {
                val now = System.currentTimeMillis()
                for (share in shares(this@ChatArrivalService)) {
                    if (ChatArrivalLogic.expired(share, now)) {
                        stopShare(share.messageId, tellServer = true)
                        continue
                    }
                    if (!ChatArrivalLogic.shouldPost(lastPost[share.messageId], now)) continue
                    lastPost[share.messageId] = now
                    val body = ChatJson14.positionBody(
                        loc.latitude, loc.longitude,
                        if (loc.hasAccuracy()) loc.accuracy else null,
                        if (loc.hasSpeed()) loc.speed else null,
                    )
                    val r = extras.arrivalPosition(share.conversationId, share.messageId, body)
                    val arrived = r is ChatResult.Ok && r.value
                    if (arrived || ChatArrivalLogic.gone(r) || !ChatArrivalLogic.keepGoing(extras.message(share.messageId)?.arrival)) {
                        stopShare(share.messageId, tellServer = false)
                    }
                }
            }
        }
    }

    private suspend fun stopShare(messageId: Long, tellServer: Boolean) {
        val share = shares(this).firstOrNull { it.messageId == messageId }
        remove(this, messageId)
        lastPost.remove(messageId)
        if (tellServer && share != null) extras.arrivalStop(share.conversationId, messageId)
        val left = shares(this)
        if (left.isEmpty()) {
            stopNow()
        } else {
            ContextCompat.getSystemService(this, NotificationManager::class.java)?.notify(NOTIF_ID, notification(left))
        }
    }

    private fun stopNow() {
        listener?.let { l -> runCatching { getSystemService(LocationManager::class.java)?.removeUpdates(l) } }
        listener = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        listener?.let { l -> runCatching { getSystemService(LocationManager::class.java)?.removeUpdates(l) } }
        listener = null
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.chat_arrival_channel), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            description = getString(R.string.chat_arrival_channel_desc)
        }
        nm.createNotificationChannel(channel)
    }

    private fun notification(shares: List<ChatLiveShare>): android.app.Notification {
        val first = shares.first()
        val open = PendingIntent.getActivity(
            this, 0,
            PushOpenActivity.chatIntent(this, first.conversationId, first.messageId).setData("gatecontrol-chat://arrival/${first.messageId}".toUri()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, ChatArrivalService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_MESSAGE, first.messageId)
                .setData("gatecontrol-chat://arrival-stop/${first.messageId}".toUri()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.chat_arrival_notif_title))
            .setContentText(getString(R.string.chat_arrival_notif_text, first.destLabel))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(open)
            .addAction(0, getString(R.string.chat_arrival_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "chat_arrival"
        private const val NOTIF_ID = 4711
        private const val UPDATE_MS = 10_000L
        private const val ACTION_STOP = "com.gatecontrol.android.chat.ARRIVAL_STOP"
        private const val EXTRA_MESSAGE = "message"
        private const val PREFS = "chat_arrival"
        private const val KEY_SHARES = "shares"

        fun shares(context: Context): List<ChatLiveShare> =
            ChatArrivalLogic.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_SHARES, emptySet()))

        private fun remove(context: Context, messageId: Long) {
            val left = shares(context).filter { it.messageId != messageId }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putStringSet(KEY_SHARES, ChatArrivalLogic.encode(left)) }
        }

        /** Starts sharing for arrival [messageId] (call while the app is visible, with a location permission). */
        fun start(context: Context, conversationId: Long, messageId: Long, destLabel: String) {
            val all = shares(context).filter { it.messageId != messageId } + ChatLiveShare(conversationId, messageId, destLabel, System.currentTimeMillis())
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putStringSet(KEY_SHARES, ChatArrivalLogic.encode(all)) }
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ChatArrivalService::class.java)) }
                .onFailure { Timber.w("Chats: live arrival service not started (%s)", it.javaClass.simpleName) }
        }

        /** "Stopp" from the app (the card's button). */
        fun stop(context: Context, messageId: Long) {
            runCatching {
                context.startService(Intent(context, ChatArrivalService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_MESSAGE, messageId))
            }
        }

        fun isSharing(context: Context, messageId: Long): Boolean = shares(context).any { it.messageId == messageId }
    }
}
