package com.gatecontrol.android.familie.presence

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.service.ClientPolicyManager
import com.gatecontrol.android.support.CrashReports
import com.gatecontrol.android.trustednet.TrustedNetworkMonitor
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** What the phone senses, and since when (epoch ms). */
private data class Sensed(val sense: PresenceSense, val sinceMs: Long)

/**
 * Familie 1.2 presence (contract C): my consent ("Anwesenheit teilen") and
 * the home / away report. The state comes from the home-network check the
 * app already runs for trusted WLANs ([TrustedNetworkMonitor] → its
 * [com.gatecontrol.android.trustednet.HomeCheck]) — no GPS, no extra
 * permission. A state is reported only with consent and the module on,
 * after it held for two minutes, and only when it differs from the last
 * report. It runs wherever that monitor runs (app process, also in the
 * background while the VPN service keeps the process alive); the POST
 * itself is a WorkManager job that waits for a network.
 */
@Singleton
class PresenceReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val monitor: TrustedNetworkMonitor,
    private val settingsRepository: SettingsRepository,
    private val clientPolicyManager: ClientPolicyManager,
    private val repository: FamilieRepository,
    private val api: FamilieApi,
    private val store: PresenceStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CrashReports.coroutineHandler)
    private var started = false

    private val _available = MutableStateFlow(false)
    /** The home-network check is on (trusted WLANs with "Heimnetz des Servers"). */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private val _sense = MutableStateFlow(PresenceSense.UNKNOWN)
    /** What the phone senses right now (not debounced). */
    val sense: StateFlow<PresenceSense> = _sense.asStateFlow()

    /** My consent as last confirmed by the server. */
    val share: StateFlow<Boolean> = store.share

    @OptIn(FlowPreview::class)
    @Synchronized
    fun start() {
        if (started) return
        started = true
        val sensed = MutableStateFlow(Sensed(PresenceSense.UNKNOWN, System.currentTimeMillis()))
        scope.launch {
            repository.cachedProfile()
            combine(
                monitor.network.map { it.kind }.distinctUntilChanged(),
                monitor.status.map { it.home }.distinctUntilChanged(),
                settingsRepository.getTrustedNetworkSettings().map { it.enabled to it.homeEnabled }.distinctUntilChanged(),
                clientPolicyManager.policy.map { it.canDisconnect }.distinctUntilChanged(),
            ) { kind, home, trusted, canDisconnect ->
                val available = PresenceLogic.detectionAvailable(trusted.first, trusted.second, canDisconnect)
                _available.value = available
                PresenceLogic.sense(available, kind, home)
            }
                .distinctUntilChanged()
                .collect { s ->
                    _sense.value = s
                    sensed.value = Sensed(s, System.currentTimeMillis())
                }
        }
        scope.launch {
            combine(
                sensed.debounce(PresenceLogic.DEBOUNCE_MS),
                store.share,
                repository.profile.map { it?.modules?.presence == true }.distinctUntilChanged(),
            ) { stable, share, moduleOn -> Triple(stable, share, moduleOn) }
                .collect { (stable, share, moduleOn) ->
                    if (!share || !moduleOn) {
                        cancelReport()
                    } else if (PresenceLogic.shouldReport(stable.sense, share, moduleOn, store.lastReported())) {
                        enqueue(stable.sense == PresenceSense.HOME, stable.sinceMs)
                    }
                }
        }
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

    companion object {
        private const val WORK = "familie_presence_report"
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
