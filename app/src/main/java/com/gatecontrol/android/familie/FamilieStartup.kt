package com.gatecontrol.android.familie

import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.familie.emergency.EmergencyRepository
import com.gatecontrol.android.familie.places.GeofenceRegistrar
import com.gatecontrol.android.familie.presence.PresenceReporter
import com.gatecontrol.android.familie.shopping.ShoppingRepository
import com.gatecontrol.android.support.CrashReports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Background duties of the Familie feature when the app process starts and
 * after boot: geofences back in place (Android drops them on reboot), the
 * plugin list and membership refreshed, waiting shopping changes sent.
 */
@Singleton
class FamilieStartup @Inject constructor(
    private val setupRepository: SetupRepository,
    private val plugins: PluginDirectory,
    private val repository: FamilieRepository,
    private val registrar: GeofenceRegistrar,
    private val shopping: ShoppingRepository,
    private val emergency: EmergencyRepository,
    private val presence: PresenceReporter,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)

    fun onAppStart() {
        // Presence (1.2) follows the home-network check for as long as the process lives.
        runCatching { presence.start() }.onFailure { Timber.w("Familie: presence not started (%s)", it.javaClass.simpleName) }
        if (!setupRepository.isConfigured()) return
        scope.launch {
            runCatching { registrar.reRegisterFromCache() }
            val listed = plugins.refresh()
            val familie = plugins.state.value.familieApi
            when {
                familie -> {
                    val profile = repository.refreshProfile().valueOrNull()
                    registrar.syncFromServer()
                    shopping.syncIfPending()
                    // Keep the offline copy of the emergency card fresh for when it is needed.
                    if (profile?.modules?.emergency == true) emergency.refresh()
                    // My consent as the server has it (set in the portal or on another phone).
                    if (profile?.modules?.presence == true) presence.fetch()
                }
                // The server answered and the plugin is gone: no reminders, no emergency card any more.
                listed -> {
                    registrar.clear()
                    repository.forgetEmergency()
                }
            }
        }.invokeOnCompletion { e -> if (e != null) Timber.w("Familie: start-up work failed (%s)", e.javaClass.simpleName) }
    }

    /**
     * BOOT_COMPLETED: the last known fences again (the process start ran
     * [onAppStart] already; this also covers a boot before the app was
     * configured or while the server is unreachable).
     */
    fun onBoot() {
        scope.launch { runCatching { registrar.reRegisterFromCache() } }
    }
}
