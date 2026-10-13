package com.gatecontrol.android.ui.services

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.VpnService
import com.gatecontrol.android.network.getServiceLink
import kotlinx.coroutines.CancellationException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

sealed class DnsTestResult {
    data object Idle : DnsTestResult()
    data object Testing : DnsTestResult()
    data class Pass(val servers: String) : DnsTestResult()
    data class Fail(val servers: String) : DnsTestResult()
}

data class ServicesUiState(
    val services: List<VpnService> = emptyList(),
    /** Domains marked as favourite (stored on the device). */
    val favorites: Set<String> = emptySet(),
    /** Service whose address is being fetched right now. */
    val openingId: Int? = null,
    val isLoading: Boolean = false,
    val dnsTestResult: DnsTestResult = DnsTestResult.Idle,
    val error: String? = null
) {
    val sections: ServiceSections get() = sectionServices(services, favorites)
}

/** Favourites on top, then the rest; both by name. */
data class ServiceSections(val favorites: List<VpnService>, val others: List<VpnService>)

fun sectionServices(services: List<VpnService>, favorites: Set<String>): ServiceSections {
    val sorted = services.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.trim() })
    val (fav, rest) = sorted.partition { it.domain in favorites }
    return ServiceSections(fav, rest)
}

@HiltViewModel
class ServicesViewModel @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
    private val licenseRepository: LicenseRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServicesUiState())
    val uiState: StateFlow<ServicesUiState> = _uiState.asStateFlow()

    private val _navigationEvent = MutableSharedFlow<String>()
    val navigationEvent: SharedFlow<String> = _navigationEvent.asSharedFlow()

    init {
        loadServices()
        viewModelScope.launch {
            settingsRepository.getServiceFavorites().collect { favs -> _uiState.update { it.copy(favorites = favs) } }
        }
    }

    fun toggleFavorite(service: VpnService) {
        viewModelScope.launch { settingsRepository.toggleServiceFavorite(service.domain) }
    }

    fun loadServices() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val serverUrl = setupRepository.getServerUrl()
                if (serverUrl.isBlank()) {
                    _uiState.update { it.copy(isLoading = false, services = emptyList()) }
                    return@launch
                }
                val client = apiClientProvider.getClient(serverUrl)
                val response = client.getServices()
                if (response.ok) {
                    _uiState.update { it.copy(isLoading = false, services = response.services) }
                } else {
                    _uiState.update { it.copy(isLoading = false, services = emptyList()) }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load services")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        services = emptyList(),
                        error = e.message
                    )
                }
            }
        }
    }

    fun runDnsLeakTest() {
        viewModelScope.launch {
            _uiState.update { it.copy(dnsTestResult = DnsTestResult.Testing) }
            try {
                val serverUrl = setupRepository.getServerUrl()
                if (serverUrl.isBlank()) {
                    _uiState.update { it.copy(dnsTestResult = DnsTestResult.Fail("No server configured")) }
                    return@launch
                }
                val client = apiClientProvider.getClient(serverUrl)
                val response = client.dnsCheck()
                if (response.ok) {
                    val servers = response.vpnDns
                    _uiState.update { it.copy(dnsTestResult = DnsTestResult.Pass(servers)) }
                } else {
                    _uiState.update { it.copy(dnsTestResult = DnsTestResult.Fail("")) }
                }
            } catch (e: Exception) {
                Timber.e(e, "DNS leak test failed")
                _uiState.update {
                    it.copy(dnsTestResult = DnsTestResult.Fail(e.message ?: "Unknown error"))
                }
            }
        }
    }

    /**
     * Opens [service]: asks the server for the address, a one-time login
     * link when the route allows the automatic login; falls back to the
     * route's own address (older server, offline, route without it).
     */
    fun openService(service: VpnService) {
        if (_uiState.value.openingId != null) return
        _uiState.update { it.copy(openingId = service.id) }
        viewModelScope.launch {
            val link = try {
                val serverUrl = setupRepository.getServerUrl()
                if (serverUrl.isBlank()) null else apiClientProvider.getClient(serverUrl).getServiceLink(service)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d("Service link failed (%s)", e.javaClass.simpleName)
                null
            }
            _uiState.update { it.copy(openingId = null) }
            _navigationEvent.emit(link ?: service.url)
        }
    }
}
