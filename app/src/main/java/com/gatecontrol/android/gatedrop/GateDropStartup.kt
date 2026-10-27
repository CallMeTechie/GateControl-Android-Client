package com.gatecontrol.android.gatedrop

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.gatecontrol.android.chat.ChatShareActivity
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.familie.PluginDirectory
import com.gatecontrol.android.support.CrashReports
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GateDrop's background duties, started with the app:
 * * the share targets: "Teilen → GateControl" is on while the server offers
 *   the chats or GateDrop, "In Drive speichern" while it offers Drive;
 * * presence: this device's LAN addresses go to the plugin on every
 *   network change and every ~5 minutes while the app is in the
 *   foreground (`listening` false: Android only listens while a transfer
 *   needs it);
 * * what was sent to this device while it was offline (`GET pending`):
 *   fetched at start and in the foreground, on network changes, with the
 *   presence tick and every 15 minutes in the background
 *   ([GateDropPendingWorker]); the push stream's (re)connect asks too
 *   ([AppPluginEventSink]);
 * * left-over send copies are pruned.
 */
@Singleton
class GateDropStartup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pluginDirectory: PluginDirectory,
    private val repository: GateDropRepository,
    private val setupRepository: SetupRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)
    private var started = false
    private var ticker: Job? = null
    private var debounce: Job? = null

    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch {
            pluginDirectory.state.map { it.messengerTab || it.gatedrop }.distinctUntilChanged().collect { on ->
                ChatShareActivity.setEnabled(context, on)
            }
        }
        // "In Drive speichern" in the share sheet: on while the server offers Drive.
        scope.launch {
            pluginDirectory.state.map { it.drive }.distinctUntilChanged().collect { on ->
                com.gatecontrol.android.ui.drive.DriveShareActivity.setEnabled(context, on)
            }
        }
        scope.launch {
            setupRepository.revision.drop(1).collect { repository.store.reload() }
        }
        scope.launch {
            repository.available.collect { on ->
                if (on) {
                    presenceSoon(force = true)
                    repository.pollPendingSoon()
                    GateDropPendingWorker.schedule(context)
                }
            }
        }
        scope.launch { runCatching { repository.store.prune() } }
        watchNetworks()
        runCatching {
            scope.launch(Dispatchers.Main) {
                ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) = startTicker()
                    override fun onStop(owner: LifecycleOwner) {
                        ticker?.cancel()
                        ticker = null
                    }
                })
            }
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                runCatching { repository.reportPresence() }
                runCatching { repository.pollPending() }
                delay(PRESENCE_INTERVAL_MS)
            }
        }
    }

    /** Network changes come in bursts: report once things settled. */
    private fun presenceSoon(force: Boolean = false) {
        debounce?.cancel()
        debounce = scope.launch {
            delay(3_000)
            runCatching { repository.reportPresence(force = force) }
        }
    }

    private fun watchNetworks() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching {
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = changed()
                override fun onLost(network: Network) = changed()
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = changed()
            })
        }.onFailure { Timber.w("GateDrop: network watch not registered (%s)", it.javaClass.simpleName) }
    }

    private fun changed() {
        if (!repository.available.value) return
        presenceSoon()
        repository.pollPendingSoon(NETWORK_SETTLE_MS)
    }

    companion object {
        private const val PRESENCE_INTERVAL_MS = 5 * 60_000L
        /** A new network (and the tunnel over it) needs a moment before the server is reachable. */
        private const val NETWORK_SETTLE_MS = 5_000L
    }
}
