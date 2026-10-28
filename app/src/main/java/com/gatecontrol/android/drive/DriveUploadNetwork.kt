package com.gatecontrol.android.drive

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Hochladen nur im WLAN" (Drive 1.11): whether an upload may go now — any network, or with the setting only an
 * unmetered one (WLAN, Ethernet). [allowed] changes with the network and the setting; the uploads wait while it is false.
 */
@Singleton
class DriveUploadNetwork @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: DriveSettings,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _unmetered = MutableStateFlow(true)
    private val _allowed = MutableStateFlow(true)
    val allowed: StateFlow<Boolean> = _allowed.asStateFlow()

    init {
        runCatching {
            cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.let(::take)
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = take(caps)
            })
        }.onFailure { Timber.w("Drive: network not watched (%s)", it.javaClass.simpleName) }
        update()
    }

    private fun take(caps: NetworkCapabilities) {
        _unmetered.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
        update()
    }

    /** After a change of the setting (the screen calls it) or the network. */
    fun update() {
        // offline the uploads go on as before (they fail and say so); only "metered with WLAN only" makes them wait
        _allowed.value = DriveSettingsLogic.uploadAllowed(settings.current.wifiOnly, connected = true, unmetered = _unmetered.value)
    }

    /** Uploads must wait for WLAN now. */
    fun waitsForWifi(): Boolean = !_allowed.value
}
