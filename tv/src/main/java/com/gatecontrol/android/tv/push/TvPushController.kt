package com.gatecontrol.android.tv.push

import android.content.Context
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.notify.PushClient
import com.gatecontrol.android.notify.PushMode
import com.gatecontrol.android.notify.PushPrefsStore
import com.gatecontrol.android.notify.PushRepository
import com.gatecontrol.android.notify.PushSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** When the TV runs the push stream. Pure, unit-tested. */
object TvPushPlan {
    /** The service runs while the TV is paired and "Mitteilungen" is on. */
    fun run(configured: Boolean, settings: PushSettings): Boolean = configured && settings.enabled

    /**
     * A paired TV gets its messages without a trip to the settings: push is switched on once (mode "always"), the
     * first time the TV is paired. Switched off later in the settings, it stays off.
     */
    fun autoEnable(configured: Boolean, settings: PushSettings): Boolean = configured && !settings.everEnabled
}

/**
 * Starts and stops [TvPushService] by [TvPushPlan] and tells the server the device preferences when they change —
 * the TV's part of the phone app's `PushController` (mode "always" only: the TV has no "only with VPN").
 */
@Singleton
class TvPushController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: PushPrefsStore,
    private val client: PushClient,
    private val repository: PushRepository,
    private val setupRepository: SetupRepository,
    private val presenter: TvPushPresenter,
) {
    private var started = false
    private var lastRevision = -1L

    fun start(scope: CoroutineScope) {
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch { runCatching { presenter.ensureFixedChannels() } }
        scope.launch {
            combine(store.settings, setupRepository.revision) { s, rev -> Triple(setupRepository.isConfigured(), s, rev) }
                .distinctUntilChanged()
                .collect { (configured, settings, revision) -> apply(configured, settings, revision) }
        }
        scope.launch {
            store.settings.map { Triple(it.enabled, it.mode, it.mutedTopics) }
                .distinctUntilChanged()
                .drop(1)
                .collect { repository.syncPrefs() }
        }
    }

    private suspend fun apply(configured: Boolean, settings: PushSettings, revision: Long) {
        if (TvPushPlan.autoEnable(configured, settings)) {
            store.setMode(PushMode.ALWAYS)
            store.setEnabled(true)
            return // the store's new settings come back through the flow
        }
        val setupChanged = lastRevision >= 0 && revision != lastRevision
        lastRevision = revision
        if (TvPushPlan.run(configured, settings)) {
            if (!TvPushService.running) TvPushService.start(context)
            if (setupChanged && client.isRunning) client.kick("setup", force = true)
        } else {
            TvPushService.stop(context)
            client.stop()
        }
        Timber.d("TV push: configured=%s enabled=%s running=%s", configured, settings.enabled, client.currentOwner())
    }

    /** "Mitteilungen" in the TV settings. */
    suspend fun setEnabled(on: Boolean) = store.setEnabled(on)

    /** The app came to the foreground: start the service if Android refused it before, else retry a wait early. */
    suspend fun onForeground() {
        if (!started) return
        if (!TvPushPlan.run(setupRepository.isConfigured(), store.current())) return
        if (!TvPushService.running) TvPushService.start(context) else client.kick("foreground")
    }
}
