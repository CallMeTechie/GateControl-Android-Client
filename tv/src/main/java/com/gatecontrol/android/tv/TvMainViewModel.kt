package com.gatecontrol.android.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.tunnel.TunnelConfig
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.tv.tunnel.TvTunnel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** VPN and the connection settings of the paired TV (start page status, Einstellungen › Verbindung, Gerät & Konto). */
@HiltViewModel
class TvMainViewModel @Inject constructor(
    private val tunnel: TvTunnel,
    private val settings: TvSettings,
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
    private val frameSource: com.gatecontrol.android.tv.frame.TvFrameSource,
) : ViewModel() {

    val tunnelState: StateFlow<TunnelState> = tunnel.state
    val autostartTunnel: StateFlow<Boolean> = settings.autostartTunnel
    val openOnBoot: StateFlow<Boolean> = settings.openOnBoot

    private val _consentDenied = MutableStateFlow(false)

    /** The VPN consent was refused (or the system has no dialog for it): the VPN switch says so. */
    val consentDenied: StateFlow<Boolean> = _consentDenied.asStateFlow()

    /** `gate.example.com` of the stored server. */
    fun serverHost(): String = runCatching { java.net.URI(setupRepository.getServerUrl()).host }.getOrNull().orEmpty()

    /** The tunnel address of this TV (`10.8.0.14`) from the WireGuard config. */
    fun tunnelAddress(): String =
        runCatching { TunnelConfig.parse(setupRepository.getWireGuardConfig()).address.substringBefore('/') }.getOrNull().orEmpty()

    /** Called with the VPN consent given (or already there). */
    fun connect() {
        _consentDenied.value = false
        tunnel.connectDetached()
    }

    fun onConsent(granted: Boolean) {
        if (granted) connect() else _consentDenied.value = true
    }

    fun disconnect() {
        viewModelScope.launch { tunnel.disconnect() }
    }

    fun setAutostartTunnel(on: Boolean) = settings.setAutostartTunnel(on)
    fun setOpenOnBoot(on: Boolean) = settings.setOpenOnBoot(on)

    /** "Kopplung aufheben": tunnel down, token and config gone (the server keeps the device until it is removed there). */
    fun unpair(done: () -> Unit) {
        viewModelScope.launch {
            tunnel.disconnect()
            setupRepository.clear()
            apiClientProvider.invalidate()
            frameSource.clear()
            done()
        }
    }
}
