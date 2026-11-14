package com.gatecontrol.android.notizen

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.AlarmManagerCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gatecontrol.android.R
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.familie.familieLocalized
import com.gatecontrol.android.notify.PushChannels
import com.gatecontrol.android.notify.PushPrefsStore
import com.gatecontrol.android.notify.PushRepository
import com.gatecontrol.android.notify.model.PushMessage
import com.gatecontrol.android.push.PushOpenActivity
import com.gatecontrol.android.support.CrashReports
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone's own alarms for the reminders of tasks (plugin 1.3.0, `reminderActions`): a second line beside the
 * server's push, so a reminder also comes while the server cannot be reached. `GET reminders` is kept on the phone
 * ([NotizenCache]) and read again after the start, after a change (`changed` with `tasks`, an own write), after a boot,
 * an update of the app or a change of the clock, and once a day; [ReminderPlan] says which alarms to set, [ReminderGuard]
 * keeps a reminder from showing twice. With an older plugin (no such word in `features`) nothing is set and nothing kept.
 *
 * Exact alarms only where Android allows them ([exactAllowed]); else a pair of inexact ones — one within
 * [ReminderPlan.WINDOW_MS] while the phone is awake, one that may also ring while it sleeps.
 */
@Singleton
class NotizenReminderCenter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: NotizenRepository,
    private val cache: NotizenCache,
    private val settingsRepository: SettingsRepository,
    private val push: dagger.Lazy<PushRepository>,
    private val pushPrefs: PushPrefsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)
    private val lock = Mutex()
    private val fireLock = Mutex()
    private val wanted = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Replaceable in tests. */
    internal var clock: () -> Long = System::currentTimeMillis

    @Volatile private var started = false

    private val alarms: AlarmManager? get() = context.getSystemService(AlarmManager::class.java)
    private val nm: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    /** Android lets this app ring at the minute ([notizenExactAlarms]). */
    fun exactAllowed(): Boolean = notizenExactAlarms(context)

    /** App start: the kept alarms again, then the server's list; from now on after every change. */
    fun start() {
        if (started) return
        started = true
        scope.launch { wanted.collectLatest { delay(DEBOUNCE_MS); runCatching { sync() } } }
        scope.launch { repo.changed.collect { if (it.isEmpty() || "tasks" in it) wanted.tryEmit(Unit) } }
        // the plugin's abilities became known or changed (the tab opened, a newer plugin)
        scope.launch { repo.me.distinctUntilChangedBy { it?.reminderActions }.collect { if (it != null) wanted.tryEmit(Unit) } }
        scope.launch {
            runCatching { reschedule() }
            if (repo.cachedMe() != null) wanted.tryEmit(Unit)
        }
        NotizenReminderWorker.ensure(context)
    }

    /** Boot, app update, clock or time zone changed, exact alarms allowed or withdrawn: Android dropped or shifted the alarms. */
    suspend fun onSystemChange() {
        runCatching { reschedule() }
        runCatching { sync() }
    }

    /** The daily run: the plugin's abilities again (a newer plugin since the tab was open last), then the list. */
    suspend fun daily(): Boolean {
        if (repo.cachedMe() == null) return true
        repo.refreshMe()
        return sync()
    }

    /** `GET reminders` → the phone's copy and its alarms. False while the server cannot be reached (the kept ones stay). */
    suspend fun sync(): Boolean {
        val me = repo.cachedMe() ?: return true
        if (!me.reminderActions) {
            clear()
            return true
        }
        return when (val r = repo.api.reminders()) {
            is NResult.Ok -> {
                update { it.copy(reminders = if (r.value.enabled) r.value.reminders else emptyList()) }
                reschedule()
                true
            }
            // the plugin is older again, gone, or the login: no alarms of it stay
            is NResult.Failed -> {
                if (r.http == 404 || r.http == 401 || r.http == 403) clear()
                true
            }
            NResult.Offline -> false
            NResult.NotConfigured -> { clear(); true }
        }
    }

    private suspend fun update(f: (ReminderState) -> ReminderState): ReminderState = lock.withLock {
        val next = f(cache.reminders())
        cache.putReminders(next)
        next
    }

    private suspend fun clear() {
        val had = lock.withLock {
            val s = cache.reminders()
            if (s.reminders.isNotEmpty() || s.alarms.isNotEmpty()) cache.putReminders(s.copy(reminders = emptyList(), alarms = emptySet()))
            s.alarms
        }
        had.forEach { cancelAlarm(it) }
    }

    /** The alarms as [ReminderPlan] wants them now. */
    private suspend fun reschedule() {
        val now = clock()
        var plan: ReminderPlan.Plan? = null
        update { s ->
            val p = ReminderPlan.plan(s.reminders, s.alarms, s.seen, now)
            plan = p
            s.copy(alarms = p.set.map { it.key }.toSet(), seen = ReminderGuard.pruned(s.seen, now))
        }
        val p = plan ?: return
        p.cancel.forEach { cancelAlarm(it) }
        val exact = exactAllowed()
        p.set.forEach { setAlarm(it, exact) }
    }

    private fun fireIntent(key: String, slot: Int, tag: String?): Intent =
        Intent(context, NotizenReminderReceiver::class.java)
            .setAction(NotizenReminderReceiver.ACTION_FIRE)
            .setData("gatecontrol-notizen://reminder/$key/$slot".toUri())
            .apply { tag?.let { putExtra(NotizenReminderReceiver.EXTRA_TAG, it) } }

    private fun setAlarm(a: ReminderPlan.Alarm, exact: Boolean) {
        val am = alarms ?: return
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val first = PendingIntent.getBroadcast(context, 0, fireIntent(a.key, 0, a.tag), flags)
        val second = PendingIntent.getBroadcast(context, 0, fireIntent(a.key, 1, a.tag), flags)
        try {
            if (exact) {
                AlarmManagerCompat.setExactAndAllowWhileIdle(am, AlarmManager.RTC_WAKEUP, a.atMs, first)
                am.cancel(second)
            } else {
                // awake: within the window; asleep (Doze): the second one may still ring, later than the time
                am.setWindow(AlarmManager.RTC_WAKEUP, a.atMs, ReminderPlan.WINDOW_MS, first)
                AlarmManagerCompat.setAndAllowWhileIdle(am, AlarmManager.RTC_WAKEUP, a.atMs, second)
            }
        } catch (e: SecurityException) {
            // exact alarms withdrawn between the check and the call
            Timber.w("Notizen: exact alarm refused, set inexact")
            runCatching {
                am.setWindow(AlarmManager.RTC_WAKEUP, a.atMs, ReminderPlan.WINDOW_MS, first)
                AlarmManagerCompat.setAndAllowWhileIdle(am, AlarmManager.RTC_WAKEUP, a.atMs, second)
            }
        } catch (e: Exception) {
            Timber.w("Notizen: alarm not set (%s)", e.javaClass.simpleName)
        }
    }

    private fun cancelAlarm(key: String) {
        val am = alarms ?: return
        for (slot in 0..1) {
            PendingIntent.getBroadcast(context, 0, fireIntent(key, slot, null), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
                runCatching { am.cancel(it) }
                it.cancel()
            }
        }
    }

    // ── an alarm rings ──────────────────────────────────────

    private fun online(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm?.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(false)

    /**
     * The alarm of [tag] rings: shown unless the push showed it or the person decided it, and — with a connection — only
     * while the server still lists it (done or moved on another device meanwhile: dropped). Shown or not, this alarm
     * is over: no second one for the same reminder.
     */
    suspend fun fire(tag: String) = fireLock.withLock {
        val now = clock()
        val state = lock.withLock { cache.reminders() }
        if (!ReminderGuard.alarmShows(state.seen, tag, now)) return@withLock
        val shown = ring(tag, state, now)
        update { it.copy(seen = if (shown) ReminderGuard.shown(it.seen, tag, ReminderGuard.LOCAL, clock()) else ReminderGuard.skipped(it.seen, tag, clock())) }
        Unit
    }

    /** Shows the reminder [tag] of [kept] when nothing speaks against it → whether it is in the shade now. */
    private suspend fun ring(tag: String, kept: ReminderState, now: Long): Boolean {
        // „Mitteilungen“ off on this phone or the topic switched off here: no reminder from the phone either
        val settings = pushPrefs.current()
        if (!settings.enabled || NotizenPush.TOPIC_REMINDERS in settings.mutedTopics) return false
        var state = kept
        if (online()) {
            val fresh = withTimeoutOrNull(CHECK_MS) { runCatching { sync() }.getOrDefault(false) } == true
            if (fresh) state = lock.withLock { cache.reminders() }
        }
        val entry = state.reminders.firstOrNull { it.tag == tag } ?: return false
        // the push came meanwhile
        if (!ReminderGuard.alarmShows(state.seen, tag, now)) return false
        // ticked off, deleted or moved with „Später“ on this phone, still waiting for a connection
        if (ReminderPlan.settledOffline(repo.waiting(), entry.taskId)) return false
        // „1 h stumm“ or the person's quiet hours: shown without sound, as the server would send it
        val hours = runCatching { pushPrefs.quiet.first().hours }.getOrNull()
        val quiet = settings.isLocallyMuted(NotizenPush.TOPIC_REMINDERS, now) ||
            (hours != null && ReminderGuard.inQuietHours(hours.from, hours.to, hours.tz ?: repo.cachedMe()?.timezone, now))
        return show(entry, quiet)
    }

    /** The same notification the push of this reminder uses (its id of the collapse key), on the same channel. */
    internal fun notificationId(key: String): Int = PushChannels.notificationId(0, key)

    /** The channel „Erinnerungen an Aufgaben“ — made as the push would make it while the push stream never said this topic yet. */
    internal fun channel(ctx: Context): String {
        val channel = PushChannels.topicChannelId(NotizenPush.TOPIC_REMINDERS)
        if (nm.getNotificationChannel(channel) == null) {
            nm.createNotificationChannelGroup(NotificationChannelGroup(CHANNEL_GROUP, ctx.getString(R.string.push_channel_group)))
            nm.createNotificationChannel(
                NotificationChannel(channel, ctx.getString(R.string.notizen_reminder_channel), NotificationManager.IMPORTANCE_DEFAULT).apply { group = CHANNEL_GROUP },
            )
        }
        return channel
    }

    /**
     * A reminder the phone makes itself (an alarm, a place): null — „Mitteilungen“ or this topic are off on this phone
     * (nothing is shown); else whether it comes without sound („1 h stumm“, the person's quiet hours).
     */
    internal suspend fun shade(now: Long): Boolean? {
        val settings = pushPrefs.current()
        if (!settings.enabled || NotizenPush.TOPIC_REMINDERS in settings.mutedTopics) return null
        val hours = runCatching { pushPrefs.quiet.first().hours }.getOrNull()
        return settings.isLocallyMuted(NotizenPush.TOPIC_REMINDERS, now) ||
            (hours != null && ReminderGuard.inQuietHours(hours.from, hours.to, hours.tz ?: repo.cachedMe()?.timezone, now))
    }

    private suspend fun show(r: NReminderEntry, quiet: Boolean): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val ctx = familieLocalized(context, settingsRepository)
        val channel = channel(ctx)
        val id = notificationId(r.collapseKey)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val dest = NotizenPush.encode(NotizenPush.Task(r.taskId, r.area))
        val tap = PendingIntent.getActivity(
            context, 0,
            PushOpenActivity.notizenIntent(context, dest, r.tag).setData("gatecontrol-notizen://open/${r.collapseKey}".toUri()),
            flags,
        )
        val builder = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(r.title)
            .setSubText(ctx.getString(R.string.notizen_tab))
            .setWhen(r.atMs)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(quiet)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (r.important) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(tap)
            .setDeleteIntent(PendingIntent.getBroadcast(context, 0, NotizenReminderReceiver.intent(context, NotizenReminderReceiver.ACTION_DISMISSED, r, null, 9), flags))
        if (r.body.isNotBlank()) {
            builder.setContentText(r.body.lineSequence().first())
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(r.body))
        }
        // the server's buttons for this moment, unchanged — here they go straight to the API; „Liste öffnen“ (plugin
        // 1.4.0, a task with a shopping list) opens that list in the app, to tick off
        ReminderPlan.buttons(r).forEachIndexed { i, a ->
            val list = r.attachedList?.takeIf { a.id == NotizenPush.ACTION_OPEN_LIST }
            val intent = if (list != null) {
                PendingIntent.getActivity(
                    context, 0,
                    PushOpenActivity.notizenIntent(context, NotizenPush.encode(NotizenPush.Shopping(list.uid, r.area)), r.tag).setData("gatecontrol-notizen://list/${r.collapseKey}".toUri()),
                    flags,
                )
            } else {
                PendingIntent.getBroadcast(context, 0, NotizenReminderReceiver.intent(context, NotizenReminderReceiver.ACTION_BUTTON, r, a.id, i), flags)
            }
            builder.addAction(0, a.label, intent)
        }
        return try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /** A button of the phone's alarm: the notification goes, the server is told (or the outbox keeps it). */
    suspend fun button(tag: String, taskId: Long, key: String, actionId: String) {
        decided(tag)
        NotificationManagerCompat.from(context).cancel(notificationId(key))
        val until = ReminderGuard.untilOf(actionId)
        val r = when {
            actionId == ACTION_DONE -> repo.completeTask(taskId, true).first.name
            until != null -> repo.snoozeTask(taskId, until, tag).name
            else -> return
        }
        Timber.d("Notizen: reminder button %s → %s", actionId, r)
    }

    /** Decided (a button, swiped away, opened): this reminder is not shown again. */
    suspend fun decided(tag: String) {
        val t = NotizenJson.reminderTag(tag) ?: return
        update { it.copy(seen = ReminderGuard.decided(it.seen, t, clock())) }
    }

    // ── the server's push of the same reminder ──────────────

    /**
     * A push is about to be shown: [ReminderGuard.Push.HIDE] for a reminder decided on this phone already (it is then
     * acknowledged as read, quietly), [ReminderGuard.Push.REPLACE] when the phone's alarm shows it (no second sound).
     */
    suspend fun onPush(message: PushMessage): ReminderGuard.Push {
        // plugin 1.6.0, „wer zuerst“: someone else did it — the push takes the reminder's place (the same collapse key),
        // and the phone's own alarms are asked again, so none rings for a task that is done
        if (NotizenPush.doneElsewhere(message) != null) wanted.tryEmit(Unit)
        val tag = NotizenPush.reminderTag(message) ?: return ReminderGuard.Push.SHOW
        val now = clock()
        var verdict = ReminderGuard.Push.SHOW
        update { s ->
            verdict = ReminderGuard.onPush(s.seen, tag, now)
            if (verdict == ReminderGuard.Push.HIDE) s else s.copy(seen = ReminderGuard.shown(s.seen, tag, ReminderGuard.PUSH, now))
        }
        if (verdict == ReminderGuard.Push.HIDE) {
            scope.launch {
                // after the delivery's own acknowledgement
                delay(ACK_DELAY_MS)
                runCatching { push.get().markRead(listOf(message.id)) }
            }
        }
        return verdict
    }

    /** The push [id] was decided in the shade (a button, „Gesehen“, swiped away, opened): its alarm stays quiet. */
    suspend fun pushDecided(id: Long) {
        val message = runCatching { push.get().inbox.also { it.load() }.get(id) }.getOrNull() ?: return
        NotizenPush.reminderTag(message)?.let { decided(it) }
    }

    companion object {
        const val ACTION_DONE = "done"
        private const val CHANNEL_GROUP = "push"
        private const val DEBOUNCE_MS = 1_500L
        private const val CHECK_MS = 4_000L
        private const val ACK_DELAY_MS = 2_000L
    }
}

/**
 * Android lets this app ring at the minute („Wecker und Erinnerungen“): on by itself up to Android 13, from Android 14
 * on only when the person switched it on in Android's settings.
 */
fun notizenExactAlarms(context: Context): Boolean =
    runCatching { context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true }.getOrDefault(false)

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NotizenReminderEntryPoint {
    fun notizenReminderCenter(): NotizenReminderCenter
}

/**
 * The phone's alarm of a reminder and the buttons of its notification. NOT exported: only the app's own
 * PendingIntents reach it.
 */
@AndroidEntryPoint
class NotizenReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var center: NotizenReminderCenter

    override fun onReceive(context: Context, intent: Intent) {
        val op = intent.action ?: return
        val tag = NotizenJson.reminderTag(intent.getStringExtra(EXTRA_TAG)) ?: return
        val taskId = intent.getLongExtra(EXTRA_TASK, 0L)
        val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        val actionId = intent.getStringExtra(EXTRA_ACTION).orEmpty()
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(TIMEOUT_MS) {
                    when (op) {
                        ACTION_FIRE -> center.fire(tag)
                        ACTION_DISMISSED -> center.decided(tag)
                        ACTION_BUTTON -> if (taskId > 0) center.button(tag, taskId, key, actionId)
                    }
                }
            } catch (e: Exception) {
                Timber.w("Notizen: reminder action failed (%s)", e.javaClass.simpleName)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.gatecontrol.android.notizen.REMINDER_FIRE"
        const val ACTION_BUTTON = "com.gatecontrol.android.notizen.REMINDER_BUTTON"
        const val ACTION_DISMISSED = "com.gatecontrol.android.notizen.REMINDER_DISMISSED"
        const val EXTRA_TAG = "com.gatecontrol.android.notizen.extra.TAG"
        private const val EXTRA_TASK = "com.gatecontrol.android.notizen.extra.TASK"
        private const val EXTRA_KEY = "com.gatecontrol.android.notizen.extra.KEY"
        private const val EXTRA_ACTION = "com.gatecontrol.android.notizen.extra.ACTION"
        /** A broadcast may live ~10 s with goAsync. */
        private const val TIMEOUT_MS = 9_000L

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)

        /** Explicit intent to this non-exported receiver; [slot] keeps the PendingIntents of one notification apart. */
        fun intent(context: Context, op: String, r: NReminderEntry, actionId: String?, slot: Int): Intent =
            Intent(context, NotizenReminderReceiver::class.java)
                .setAction(op)
                .setData("gatecontrol-notizen://reminder-ui/${r.collapseKey}/$slot".toUri())
                .putExtra(EXTRA_TAG, r.tag)
                .putExtra(EXTRA_TASK, r.taskId)
                .putExtra(EXTRA_KEY, r.collapseKey)
                .apply { actionId?.let { putExtra(EXTRA_ACTION, it) } }
    }
}

/**
 * Android dropped or shifted the alarms: after a boot, an update of the app, a change of the clock or the time zone,
 * and when „Wecker und Erinnerungen“ is allowed or withdrawn. Exported for the system's broadcasts; acts on those only.
 */
@AndroidEntryPoint
class NotizenReminderSystemReceiver : BroadcastReceiver() {

    @Inject lateinit var center: NotizenReminderCenter
    @Inject lateinit var places: NotizenGeofences

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val pending = goAsync()
        // Android dropped the geofences as well (boot, an update of the app): the kept places again
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) places.onSystemChange()
        scope.launch {
            try {
                withTimeoutOrNull(TIMEOUT_MS) { center.onSystemChange() }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
        const val TIMEOUT_MS = 9_000L
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)
    }
}

/** Once a day (with a network): `GET reminders` again — the next alarms beyond those set, and what changed unseen. */
class NotizenReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val center = EntryPointAccessors.fromApplication(applicationContext, NotizenReminderEntryPoint::class.java).notizenReminderCenter()
        return if (runCatching { center.daily() }.getOrDefault(false)) Result.success() else Result.retry()
    }

    companion object {
        private const val WORK_NAME = "notizen-reminders-daily"

        fun ensure(context: Context) {
            runCatching {
                val request = PeriodicWorkRequestBuilder<NotizenReminderWorker>(24, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }.onFailure { Timber.w("Notizen: daily reminder job not scheduled (%s)", it.javaClass.simpleName) }
        }
    }
}
