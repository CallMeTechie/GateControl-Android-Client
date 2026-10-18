package com.gatecontrol.android.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.gatecontrol.android.BuildConfig
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** The physical network the device uses (the VPN itself is not counted). */
data class UpdateNetwork(val online: Boolean = true, val metered: Boolean = false, val wifi: Boolean = false, val cellular: Boolean = false)

/** Which update sheet is open: the offer, or download/restart (picked from the state). */
enum class UpdateSheetPage { Offer, Status }

/** Message after an installed update. */
enum class UpdateToast { Installed, InstalledVpnBack }

/**
 * Glue around [PlayUpdater] for the update screens: the open sheet,
 * "Nur über WLAN laden" with the wait for an unmetered network, the
 * measured remaining time, the install when the app goes to the
 * background, and the marker that tells the next start what was updated.
 * Started from the Application (Play build only).
 */
@Singleton
class UpdateCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    val updater: PlayUpdater,
    val prefs: UpdatePrefs,
    val notes: ReleaseNotesRepository,
    private val tunnelManager: TunnelManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    val state: StateFlow<PlayUpdateState> get() = updater.state
    val target: StateFlow<PlayUpdateTarget?> get() = updater.target

    private val _sheet = MutableStateFlow<UpdateSheetPage?>(null)
    val sheet: StateFlow<UpdateSheetPage?> = _sheet.asStateFlow()

    private val _network = MutableStateFlow(UpdateNetwork())
    val network: StateFlow<UpdateNetwork> = _network.asStateFlow()

    private val estimator = EtaEstimator()
    private val _eta = MutableStateFlow<Int?>(null)
    /** Seconds left of the running download, null while not known. */
    val eta: StateFlow<Int?> = _eta.asStateFlow()

    val vpnConnected: StateFlow<Boolean> = tunnelManager.state.map { it is TunnelState.Connected }
        .stateIn(scope, SharingStarted.Eagerly, tunnelManager.state.value is TunnelState.Connected)

    /** Waiting for WLAN and an unmetered network is there: the update may start now. */
    val autoStart: StateFlow<Boolean> = combine(prefs.waitingCode, _network) { waiting, net ->
        waiting != 0 && net.online && !net.metered
    }.stateIn(scope, SharingStarted.Eagerly, false)

    private val _postUpdate = MutableStateFlow<PostUpdateMarker?>(null)
    /** "Aktualisiert auf …" card of this start (shown once). */
    val postUpdate: StateFlow<PostUpdateMarker?> = _postUpdate.asStateFlow()

    private val _toast = MutableStateFlow<UpdateToast?>(null)
    val toast: StateFlow<UpdateToast?> = _toast.asStateFlow()

    private val _errorDismissed = MutableStateFlow(false)
    /** "Später" on the stopped-download card (this run). */
    val errorDismissed: StateFlow<Boolean> = _errorDismissed.asStateFlow()

    private var postUpdateChecked = false

    fun start() {
        if (started) return
        started = true
        watchNetwork()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = installIfChosen()
        })
        scope.launch {
            var previous: PlayUpdateState = updater.state.value
            updater.state.collect { s ->
                when (s) {
                    is PlayUpdateState.Downloading -> {
                        if (s.bytesDownloaded > 0) estimator.add(SystemClock.elapsedRealtime(), s.bytesDownloaded)
                        _eta.value = estimator.remainingSeconds(s.totalBytes)
                        _errorDismissed.value = false
                    }
                    else -> {
                        estimator.reset()
                        _eta.value = null
                    }
                }
                if (s is PlayUpdateState.UpToDate || s is PlayUpdateState.Available) prefs.setLastChecked(System.currentTimeMillis())
                // Finished while the app is in front: the restart sheet opens by itself.
                if (s is PlayUpdateState.Downloaded && previous is PlayUpdateState.Downloading && isForeground()) {
                    _sheet.value = UpdateSheetPage.Status
                }
                previous = s
            }
        }
    }

    private fun isForeground() =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    // --- Sheet -------------------------------------------------------------

    fun openSheet(page: UpdateSheetPage) {
        _sheet.value = page
    }

    fun closeSheet() {
        _sheet.value = null
    }

    suspend fun check() = updater.check()

    /** Loads the notes of the update on offer (installed → target). */
    suspend fun loadNotes() {
        val code = target.value?.versionCode ?: (state.value as? PlayUpdateState.Available)?.versionCode ?: return
        if (code > BuildConfig.VERSION_CODE) notes.load(BuildConfig.VERSION_CODE, code)
    }

    // --- Download ------------------------------------------------------------

    /**
     * "Jetzt aktualisieren": with "Nur über WLAN laden" on a metered
     * network the update waits for WLAN; otherwise Play's flow starts.
     * Returns false when it waits.
     */
    fun requestUpdate(launcher: ActivityResultLauncher<IntentSenderRequest>): Boolean {
        val code = targetCode() ?: return false
        val net = _network.value
        if (prefs.wifiOnly.value && (net.metered || !net.online)) {
            prefs.setWaiting(code)
            _sheet.value = null
            return false
        }
        startNow(launcher)
        return true
    }

    /** "Trotzdem jetzt laden", or the unmetered network arrived. */
    fun startNow(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        prefs.setWaiting(0)
        prefs.installInBackground = false
        if (updater.start(launcher)) _sheet.value = UpdateSheetPage.Status
    }

    /** Play's dialog was closed without updating. */
    fun declined() {
        updater.declined()
        if (updater.state.value !is PlayUpdateState.Downloading) _sheet.value = null
    }

    /** "Später erinnern": the card stays away for a day. */
    fun remindLater() {
        targetCode()?.let { prefs.snooze(it, System.currentTimeMillis() + UpdateFormat.SNOOZE_MS) }
        _sheet.value = null
    }

    fun dismissError() {
        _errorDismissed.value = true
    }

    private fun targetCode(): Int? =
        (state.value as? PlayUpdateState.Available)?.versionCode ?: target.value?.versionCode

    // --- Install ---------------------------------------------------------------

    /** "Jetzt neu starten". */
    fun restartNow() {
        writeMarker()
        updater.complete()
    }

    /** "Später": installs when the app goes to the background. */
    fun restartLater() {
        prefs.installInBackground = true
        _sheet.value = null
    }

    private fun installIfChosen() {
        if (updater.state.value == PlayUpdateState.Downloaded && prefs.installInBackground) {
            Timber.i("Installing the downloaded update in the background")
            writeMarker()
            // In the background Play installs without covering the screen.
            updater.complete()
        }
    }

    private fun writeMarker() {
        val to = target.value?.versionCode ?: return
        if (to <= BuildConfig.VERSION_CODE) return
        prefs.marker = PostUpdateMarker(BuildConfig.VERSION_CODE, to, tunnelManager.state.value is TunnelState.Connected)
    }

    // --- After the update ------------------------------------------------------

    /**
     * Start tab shown: after an update to this version, the "Das ist neu"
     * card and, when the VPN was up before, [reconnect] (returns false
     * when it can't connect without asking, e.g. VPN consent missing).
     */
    fun onStartShown(reconnect: () -> Boolean) {
        if (postUpdateChecked) return
        postUpdateChecked = true
        val marker = prefs.marker
        when (PostUpdate.decide(marker, BuildConfig.VERSION_CODE)) {
            PostUpdate.Decision.Show -> {
                marker ?: return
                prefs.marker = null
                prefs.installInBackground = false
                prefs.setWaiting(0)
                _postUpdate.value = marker
                scope.launch { notes.load(marker.fromCode, marker.toCode) }
                scope.launch {
                    val back = marker.vpnWasConnected && reconnectVpn(reconnect)
                    _toast.value = if (back) UpdateToast.InstalledVpnBack else UpdateToast.Installed
                }
            }
            PostUpdate.Decision.Discard -> prefs.marker = null
            PostUpdate.Decision.Keep, null -> Unit
        }
    }

    private suspend fun reconnectVpn(reconnect: () -> Boolean): Boolean {
        when (tunnelManager.state.value) {
            is TunnelState.Connected -> return true
            is TunnelState.Connecting, is TunnelState.Reconnecting -> Unit
            else -> if (!reconnect()) return false
        }
        val result = withTimeoutOrNull(RECONNECT_TIMEOUT_MS) {
            tunnelManager.state
                .dropWhile { it is TunnelState.Disconnected || it is TunnelState.Error }
                .first { it is TunnelState.Connected || it is TunnelState.Error || it is TunnelState.Disconnected }
        }
        return result is TunnelState.Connected
    }

    fun dismissPostUpdate() {
        _postUpdate.value = null
    }

    fun toastShown() {
        _toast.value = null
    }

    // --- Network -------------------------------------------------------------

    private fun watchNetwork() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val networks = ConcurrentHashMap<Network, NetworkCapabilities>()
        fun publish() {
            val all = networks.values
            // Android prefers an unmetered network (WLAN) when there is one.
            val current = all.firstOrNull { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } ?: all.firstOrNull()
            _network.value = if (current == null) {
                UpdateNetwork(online = false, metered = true)
            } else {
                UpdateNetwork(
                    online = true,
                    metered = !current.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                    wifi = current.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                    cellular = current.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                )
            }
        }
        // Until the callbacks report: the active network (with the VPN up, its metered flag follows the network below).
        runCatching {
            val caps = cm.activeNetwork?.let(cm::getNetworkCapabilities)
            _network.value = UpdateNetwork(
                online = caps != null,
                metered = cm.isActiveNetworkMetered,
                wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
                cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
            )
        }
        // Default request: not the VPN, not restricted.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    networks[network] = caps
                    publish()
                }

                override fun onLost(network: Network) {
                    networks.remove(network)
                    publish()
                }
            })
        } catch (e: RuntimeException) {
            Timber.w("Update network watch unavailable (%s)", e.javaClass.simpleName)
        }
    }

    private companion object {
        const val RECONNECT_TIMEOUT_MS = 25_000L
    }
}
