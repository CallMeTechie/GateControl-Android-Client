package com.gatecontrol.android.familie

import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.familie.places.GeofenceRegistrar
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)

    fun onAppStart() {
        if (!setupRepository.isConfigured()) return
        scope.launch {
            runCatching { registrar.reRegisterFromCache() }
            val listed = plugins.refresh()
            val familie = plugins.state.value.familieApi
            when {
                familie -> {
                    repository.refreshProfile()
                    registrar.syncFromServer()
                    shopping.syncIfPending()
                }
                // The server answered and the plugin is gone: no reminders any more.
                listed -> registrar.clear()
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
