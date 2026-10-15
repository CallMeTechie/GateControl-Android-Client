package com.gatecontrol.android.familie.presence

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.HomeNetworkStatus
import com.gatecontrol.android.network.probeHomeNetwork
import com.gatecontrol.android.support.CrashReports
import com.gatecontrol.android.trustednet.TrustedNetworkMonitor
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** What the phone senses, and since when (epoch ms). */
private data class Sensed(val sense: PresenceSense, val sinceMs: Long)

/**
 * Familie 1.2 presence (contract C): my consent ("Anwesenheit teilen") and
 * the home / away report. While I share (consent + module on) the phone
 * asks the server itself whether it is home (`GET home-network` with its
 * private LAN addresses as `local`, [PresenceLogic.interpret]) — no GPS, no
 * extra permission, independent of "Vertrauenswürdige WLANs" and of the
 * VPN (through the tunnel when it is up, directly otherwise; core 1.167+
 * also recognises the LAN behind a gateway). It asks when the network or
 * the tunnel changes (the network callback of [TrustedNetworkMonitor],
 * debounced) and from a periodic WorkManager job ([PresenceCheckWorker])
 * as the fallback while the process is gone. A clear answer replaces the
 * state, "can't tell" keeps the last one. A state is reported after it
 * held for two minutes, and only when it differs from the last report;
 * the POST itself is a WorkManager job that waits for a network. Right
 * after sharing goes on (nothing reported yet) the phone checks at once and
 * reports the first clear answer without the two minutes
 * ([PresenceLogic.reportFirstNow]).
 */
@Singleton
class PresenceReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val monitor: TrustedNetworkMonitor,
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
    private val tunnelManager: TunnelManager,
    private val repository: FamilieRepository,
    private val api: FamilieApi,
    private val store: PresenceStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CrashReports.coroutineHandler)
    private val checkLock = Mutex()
    private var started = false

    /** Sharing just went on and nothing was reported since: the first clear answer goes out at once. */
    private val firstPending = AtomicBoolean(false)

    private val sensed = MutableStateFlow(Sensed(PresenceSense.UNKNOWN, System.currentTimeMillis()))

    private val _sense = MutableStateFlow(PresenceSense.UNKNOWN)
    /** What the phone senses right now (last clear answer; not debounced). */
    val sense: StateFlow<PresenceSense> = _sense.asStateFlow()

    private val _check = MutableStateFlow(PresenceCheck.NOT_YET)
    /** How the last home check went (older server, failed, …), for the explanation. */
    val check: StateFlow<PresenceCheck> = _check.asStateFlow()

    /** My consent as last confirmed by the server. */
    val share: StateFlow<Boolean> = store.share

    private val moduleOn: Flow<Boolean> = repository.profile.map { it?.modules?.presence == true }.distinctUntilChanged()

    /** Consent given and the module on: only then is anything checked or sent. */
    private val active: Flow<Boolean> = combine(store.share, moduleOn) { share, on -> share && on }.distinctUntilChanged()

    @OptIn(FlowPreview::class)
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            repository.cachedProfile()
            launch {
                active.collect { on ->
                    if (on) {
                        schedulePeriodic()
                        // Nothing reported for this consent yet: ask now, not after the settle delay.
                        if (store.lastReported() == null) {
                            firstPending.set(true)
                            launch { checkNow() }
                        }
                    } else {
                        firstPending.set(false)
                        cancelPeriodic()
                        cancelReport()
                        // A later consent starts from what the phone senses then, not from an old state.
                        reset()
                    }
                }
            }
            launch {
                // Ask on every network or tunnel change while active; a newer change cancels a running check.
                combine(
                    active,
                    monitor.network.map { it.key to it.kind }.distinctUntilChanged(),
                    tunnelManager.state.map { it is TunnelState.Connected }.distinctUntilChanged(),
                ) { on, network, _ -> if (on) network else null }
                    .debounce(SETTLE_MS)
                    .collectLatest { network -> if (network != null) checkWithRetries() }
            }
            launch {
                combine(sensed.debounce(PresenceLogic.DEBOUNCE_MS), store.share, moduleOn) { stable, share, on ->
                    Triple(stable, share, on)
                }.collect { (stable, share, on) ->
                    if (share && on && PresenceLogic.shouldReport(stable.sense, share, on, store.lastReported())) {
                        enqueue(stable.sense == PresenceSense.HOME, stable.sinceMs)
                    }
                }
            }
        }
    }

    /**
     * One check now: no question on mobile data or without a network,
     * otherwise `GET home-network?local=…`. Returns what it saw, or null
     * when nothing could be asked (no server).
     */
    suspend fun checkNow(): PresenceObservation? = checkLock.withLock {
        val network = monitor.network.value
        val local = monitor.localAddresses()
        PresenceLogic.senseWithoutAsking(network.kind, local.isNotEmpty())?.let { sense ->
            val observation = PresenceObservation(sense, if (sense == PresenceSense.UNKNOWN) _check.value else PresenceCheck.OK)
            record(observation)
            return@withLock observation
        }
        val serverUrl = setupRepository.getServerUrl().takeIf { it.isNotBlank() } ?: return@withLock null
        val status = try {
            apiClientProvider.getClient(serverUrl).probeHomeNetwork(local)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            HomeNetworkStatus.Unknown
        }
        val observation = PresenceLogic.interpret(status)
        // No addresses in the log (privacy).
        Timber.d("Familie: presence check → %s (%s, %d local)", observation.sense, observation.check, local.size)
        record(observation)
        observation
    }

    /**
     * After a change: check, retry a failed check a few times, and check a
     * new state once more before it can be reported (a server may still see
     * the previous endpoint right after a network change).
     */
    private suspend fun checkWithRetries() {
        var failures = 0
        var confirmed = false
        while (true) {
            val before = _sense.value
            val observation = checkNow() ?: return
            when {
                observation.check == PresenceCheck.FAILED && failures < RETRY_MS.size -> delay(RETRY_MS[failures++])
                observation.sense != PresenceSense.UNKNOWN && observation.sense != before && !confirmed -> {
                    confirmed = true
                    delay(CONFIRM_MS)
                }
                else -> return
            }
        }
    }

    /**
     * The periodic job: check, and when that brings a state that was not
     * reported yet, hold on for the two minutes (the process may only live
     * for this job), check again and queue the report if it held.
     */
    suspend fun periodicCheck() {
        if (!active.first()) return
        repository.cachedProfile()
        checkNow() ?: return
        var stable = sensed.value
        if (!due(stable.sense)) return
        val waited = System.currentTimeMillis() - stable.sinceMs
        if (waited < PresenceLogic.DEBOUNCE_MS) {
            delay(PresenceLogic.DEBOUNCE_MS - waited + 1_000L)
            checkNow() ?: return
            stable = sensed.value
            if (System.currentTimeMillis() - stable.sinceMs < PresenceLogic.DEBOUNCE_MS) return
        }
        if (due(stable.sense)) enqueue(stable.sense == PresenceSense.HOME, stable.sinceMs)
    }

    private suspend fun due(sense: PresenceSense): Boolean =
        PresenceLogic.shouldReport(sense, store.share.value, moduleOn.first(), store.lastReported())

    private fun record(observation: PresenceObservation) {
        _check.value = observation.check
        val next = PresenceLogic.next(_sense.value, observation.sense)
        if (next != sensed.value.sense) sensed.value = Sensed(next, System.currentTimeMillis())
        _sense.value = next
        val moduleOn = repository.profile.value?.modules?.presence == true
        if (PresenceLogic.reportFirstNow(next, firstPending.get(), store.share.value, moduleOn, store.lastReported()) &&
            firstPending.compareAndSet(true, false)
        ) {
            enqueue(next == PresenceSense.HOME, sensed.value.sinceMs)
        }
    }

    private fun reset() {
        _sense.value = PresenceSense.UNKNOWN
        _check.value = PresenceCheck.NOT_YET
        if (sensed.value.sense != PresenceSense.UNKNOWN) sensed.value = Sensed(PresenceSense.UNKNOWN, System.currentTimeMillis())
    }

    /**
     * "Anwesenheit teilen" on / off (`PUT presence/consent`, only for
     * oneself). Stored locally only when the server took it.
     */
    suspend fun setShare(share: Boolean): FamilieResult<Unit> {
        val r = api.presenceConsent(share)
        if (r is FamilieResult.Ok) {
            store.setShare(share)
            if (!share) cancelReport()
        }
        return r
    }

    /** `GET presence`; my consent from the answer replaces the stored one. */
    suspend fun fetch(): FamilieResult<FamilieApi.PresenceFetch> {
        val me = (repository.profile.value ?: repository.cachedProfile())?.me?.userId
            ?: return FamilieResult.Failed("not_member", 403)
        val r = api.presence(me)
        if (r is FamilieResult.Ok) r.value.myConsent?.let { if (it != store.share.value) store.setShare(it) }
        return r
    }

    private fun enqueue(home: Boolean, sinceMs: Long) {
        val at = Instant.ofEpochMilli(sinceMs).truncatedTo(ChronoUnit.SECONDS).toString()
        runCatching {
            val request = OneTimeWorkRequestBuilder<PresenceReportWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(PresenceReportWorker.KEY_HOME to home, PresenceReportWorker.KEY_AT to at))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                .build()
            // The newest state wins; an older one still waiting is dropped.
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request)
            Timber.i("Familie: presence %s queued", if (home) "home" else "away")
        }.onFailure { Timber.w("Familie: presence report not queued (%s)", it.javaClass.simpleName) }
    }

    private fun cancelReport() {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK) }
    }

    private fun schedulePeriodic() {
        runCatching {
            val request = PeriodicWorkRequestBuilder<PresenceCheckWorker>(PERIODIC_MINUTES, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }.onFailure { Timber.w("Familie: presence check not scheduled (%s)", it.javaClass.simpleName) }
    }

    private fun cancelPeriodic() {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK) }
    }

    companion object {
        private const val WORK = "familie_presence_report"
        private const val PERIODIC_WORK = "familie_presence_check"
        /** Fallback check while the process is gone (WorkManager's minimum is 15). */
        private const val PERIODIC_MINUTES = 15L
        /** Network or tunnel quiet this long before asking (WireGuard roams, DHCP settles). */
        private const val SETTLE_MS = 5_000L
        /** Second look at a new state before it can be reported. */
        private const val CONFIRM_MS = 45_000L
        /** Retries of a failed check after a change. */
        private val RETRY_MS = longArrayOf(20_000L, 60_000L)
    }
}

/** Periodic fallback: one presence check (and its report) while the app's process may be gone. */
class PresenceCheckWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun presenceReporter(): PresenceReporter
    }

    override suspend fun doWork(): Result {
        try {
            EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).presenceReporter().periodicCheck()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Familie: periodic presence check failed (%s)", e.javaClass.simpleName)
        }
        return Result.success()
    }
}

/** Sends one home / away report (`POST presence`), retried while offline or too soon. */
class PresenceReportWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun familieApi(): FamilieApi
        fun familieRepository(): FamilieRepository
        fun presenceStore(): PresenceStore
    }

    override suspend fun doWork(): Result {
        if (!inputData.keyValueMap.containsKey(KEY_HOME)) return Result.failure()
        val home = inputData.getBoolean(KEY_HOME, false)
        val at = inputData.getString(KEY_AT) ?: return Result.failure()
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val store = deps.presenceStore()
        val profile = deps.familieRepository().cachedProfile()
        // Consent withdrawn or module off meanwhile: nothing leaves the phone.
        if (!store.share.value || profile?.modules?.presence != true) return Result.success()
        // Already sent (the in-process debounce and the periodic job can both queue it).
        if (store.lastReported() == home) return Result.success()
        return when (val r = deps.familieApi().reportPresence(home, at)) {
            is FamilieResult.Ok -> {
                store.reported(home, System.currentTimeMillis())
                Result.success()
            }
            is FamilieResult.Failed -> when {
                // At most one report per minute.
                r.code == "too_soon" || r.code == "rate_limited" || r.http == 429 ->
                    if (runAttemptCount < 8) Result.retry() else Result.success()
                // No consent on the server any more: the switch goes off here too.
                r.code == "no_consent" || r.code == "consent_required" -> {
                    store.setShare(false)
                    Result.success()
                }
                r.http >= 500 -> if (runAttemptCount < 6) Result.retry() else Result.success()
                else -> Result.success()
            }
            else -> if (runAttemptCount < 10) Result.retry() else Result.success()
        }
    }

    companion object {
        const val KEY_HOME = "home"
        const val KEY_AT = "at"
    }
}
