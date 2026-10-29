package com.gatecontrol.android.drive.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.gatecontrol.android.R
import com.gatecontrol.android.support.CrashReports
import com.gatecontrol.android.trustednet.TrustedNetSnapshot
import com.gatecontrol.android.trustednet.TrustedNetworkMonitor
import com.gatecontrol.android.trustednet.TrustedNetworkRules
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DriveWorkEntryPoint {
    fun driveWorkWifi(): DriveWorkWifi
    fun driveWorkStore(): DriveWorkStore
}

/**
 * The WLAN timeline of the work photos: which WLAN the phone is in, and since when. While the app runs it follows the
 * network callback of [TrustedNetworkMonitor] ("Vertrauenswürdige WLANs", with its location permission and SSID);
 * when it does not, Android wakes it for every WLAN it joins (a network request with a PendingIntent, kept by the
 * system across the app's death and Doze), and the photo backup looks again when new photos come and when it runs.
 * Nothing of it leaves the phone. A WLAN not assigned yet is asked about ("Wo bist du gerade?") with a notification.
 */
@Singleton
class DriveWorkWifi @Inject constructor(
    @ApplicationContext private val context: Context,
    private val monitor: TrustedNetworkMonitor,
    private val store: DriveWorkStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CrashReports.coroutineHandler)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var started = false

    /** The network now (live, from the trusted-network monitor). */
    val network: kotlinx.coroutines.flow.StateFlow<TrustedNetSnapshot> get() = monitor.network

    /** Android tells WLAN names only with the precise location (and the location switched on). */
    fun canSeeNames(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            context.getSystemService(LocationManager::class.java)?.isLocationEnabled != false

    /** Also while the app is in the background ("Immer zulassen"). */
    fun canSeeInBackground(): Boolean = canSeeNames() &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** On app start (once): the wake for new WLANs, then every change while the app runs. */
    @OptIn(FlowPreview::class)
    @Synchronized
    fun start() {
        if (started) return
        started = true
        registerWake()
        scope.launch {
            monitor.network
                .map { ssidOf(it) }
                .distinctUntilChanged()
                // the callback reports a WLAN in steps (connected, then validated, then its name)
                .debounce(SETTLE_MS)
                .collect { onSeen(it) }
        }
    }

    /** The WLAN now, as far as Android tells it; recorded. [wait]: give the callback a moment (just woken up). */
    suspend fun sample(wait: Boolean = false): String? {
        var ssid = ssidOf(monitor.network.value)
        if (ssid == null && wait) {
            ssid = withTimeoutOrNull(WAKE_WAIT_MS) { monitor.network.map { ssidOf(it) }.first { it != null } }
        }
        if (ssid == null && monitor.network.value.kind == TrustedNetSnapshot.Kind.WIFI) ssid = fromWifiManager()
        onSeen(ssid)
        return ssid
    }

    /** After the location permission was granted: the callback anew (it decides then whether it may tell names). */
    fun refresh() {
        monitor.refresh()
        registerWake()
    }

    private fun ssidOf(s: TrustedNetSnapshot): String? = if (s.kind == TrustedNetSnapshot.Kind.WIFI) s.ssid else null

    @Suppress("DEPRECATION")
    private fun fromWifiManager(): String? = if (!canSeeNames()) null else runCatching {
        TrustedNetworkRules.normalizeSsid(context.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid)
    }.getOrNull()

    private fun onSeen(ssid: String?) {
        store.seen(ssid)
        val s = store.settings.value
        if (ssid == null || !s.active || !s.askNewWifi || !s.zones.unknown(ssid)) return
        if (store.ask.value == ssid) return
        val last = prefs.getString(KEY_NOTIFIED, null)
        val lastAt = prefs.getLong(KEY_NOTIFIED_AT, 0L)
        val now = System.currentTimeMillis()
        if (last == ssid && now - lastAt < RENOTIFY_MS) return
        prefs.edit { putString(KEY_NOTIFIED, ssid); putLong(KEY_NOTIFIED_AT, now) }
        store.setAsk(ssid)
        notifyNew(ssid)
    }

    /** The answer of the dialog or the notification's buttons; the notification goes. */
    fun answer(ssid: String, zone: WifiZone?, stopAsking: Boolean = false) {
        if (zone != null) store.setZone(ssid, zone) else if (store.ask.value == ssid) store.setAsk(null)
        if (stopAsking) store.update { it.copy(askNewWifi = false) }
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    // ─── the wake for new WLANs ─────────────────────────────────────

    private fun wakeIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_WAKE,
        Intent(context, DriveWorkWifiReceiver::class.java).setAction(ACTION_WAKE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /**
     * Android sends [ACTION_WAKE] each time a WLAN comes up — the app's process started for it if needed. Kept by the
     * system until the app is removed; registered again on every start (after a reboot it is gone).
     */
    private fun registerWake() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val pi = wakeIntent()
        runCatching { cm.unregisterNetworkCallback(pi) }
        try {
            cm.registerNetworkCallback(request, pi)
        } catch (e: Exception) {
            Timber.w("Drive work: WLAN wake not registered (%s)", e.javaClass.simpleName)
        }
    }

    // ─── the notification ───────────────────────────────────────────

    private fun notifyNew(ssid: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.drive_work_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, NOTIFICATION_ID, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        fun action(zone: WifiZone, label: Int, request: Int): NotificationCompat.Action {
            val intent = Intent(context, DriveWorkWifiReceiver::class.java)
                .setAction(ACTION_ANSWER)
                .putExtra(EXTRA_SSID, ssid)
                .putExtra(EXTRA_ZONE, zone.name)
            val pi = PendingIntent.getBroadcast(context, request, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return NotificationCompat.Action(0, context.getString(label), pi)
        }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.drive_work_notify_title))
            .setContentText(context.getString(R.string.drive_work_notify_text, ssid))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.drive_work_notify_text, ssid)))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .addAction(action(WifiZone.WORK, R.string.drive_work_zone_work, REQUEST_WORK))
            .addAction(action(WifiZone.HOME, R.string.drive_work_zone_home, REQUEST_HOME))
            .addAction(action(WifiZone.NEITHER, R.string.drive_work_zone_neither, REQUEST_NEITHER))
            .apply { open?.let { setContentIntent(it) } }
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n) }
    }

    internal companion object {
        const val ACTION_WAKE = "com.gatecontrol.android.drive.work.WIFI_WAKE"
        const val ACTION_ANSWER = "com.gatecontrol.android.drive.work.WIFI_ANSWER"
        const val EXTRA_SSID = "ssid"
        const val EXTRA_ZONE = "zone"
        private const val PREFS = "drive_work_wifi"
        private const val KEY_NOTIFIED = "notified"
        private const val KEY_NOTIFIED_AT = "notified_at"
        private const val CHANNEL = "drive_work"
        private const val NOTIFICATION_ID = 0x6E000010
        private const val REQUEST_WAKE = 0x6E10
        private const val REQUEST_WORK = 0x6E11
        private const val REQUEST_HOME = 0x6E12
        private const val REQUEST_NEITHER = 0x6E13
        private const val SETTLE_MS = 2_000L
        private const val WAKE_WAIT_MS = 4_000L
        /** The same new WLAN is asked about again after this long (the answer "Später"). */
        private const val RENOTIFY_MS = 12L * 60 * 60 * 1000
    }
}

/**
 * Android's wake for a new WLAN ([DriveWorkWifi.ACTION_WAKE]) and the buttons of the "Neues WLAN" notification. NOT
 * exported: only the system's network request and the app's own notification reach it (their PendingIntents).
 */
class DriveWorkWifiReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val entry = runCatching { EntryPointAccessors.fromApplication(context.applicationContext, DriveWorkEntryPoint::class.java) }.getOrNull() ?: return
        when (intent.action) {
            DriveWorkWifi.ACTION_WAKE -> {
                val pending = goAsync()
                val wifi = entry.driveWorkWifi()
                wifi.start()
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        wifi.sample(wait = true)
                    } finally {
                        pending.finish()
                    }
                }
            }
            DriveWorkWifi.ACTION_ANSWER -> {
                val ssid = intent.getStringExtra(DriveWorkWifi.EXTRA_SSID) ?: return
                val zone = runCatching { WifiZone.valueOf(intent.getStringExtra(DriveWorkWifi.EXTRA_ZONE).orEmpty()) }.getOrNull() ?: return
                entry.driveWorkWifi().answer(ssid, zone)
            }
        }
    }
}
