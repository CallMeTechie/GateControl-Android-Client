package com.gatecontrol.android.ui.rdp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.data.LicenseRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.RdpRoute
import com.gatecontrol.android.rdp.RdpManager
import com.gatecontrol.android.rdp.RdpProgress
import com.gatecontrol.android.rdp.RdpSession
import com.gatecontrol.android.rdp.WolClient
import com.gatecontrol.android.tunnel.TunnelManager
import com.gatecontrol.android.tunnel.TunnelState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject

// ---------------------------------------------------------------------------
// Domain types owned by the ViewModel layer
// ---------------------------------------------------------------------------

enum class StatusFilter { ALL, ONLINE, OFFLINE }

enum class ErrorType { Forbidden, Network, ServerError }

sealed class ConnectState {
    object Idle : ConnectState()
    data class Connecting(val progress: RdpProgress) : ConnectState()
    data class Connected(val session: RdpSession, val passwordCopied: Boolean = false) : ConnectState()
    data class Error(val message: String) : ConnectState()
    data class NeedsPassword(val username: String, val domain: String?) : ConnectState()
    object MaintenanceWarning : ConnectState()
}

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

@HiltViewModel
class RdpViewModel @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
    private val licenseRepository: LicenseRepository,
    private val rdpManager: RdpManager,
    private val wolClient: WolClient,
    private val tunnelManager: TunnelManager
) : ViewModel() {

    // --- State ---

    private val _routes = MutableStateFlow<List<RdpRoute>>(emptyList())
    val routes: StateFlow<List<RdpRoute>> = _routes.asStateFlow()

    private val _filteredRoutes = MutableStateFlow<List<RdpRoute>>(emptyList())
    val filteredRoutes: StateFlow<List<RdpRoute>> = _filteredRoutes.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _statusFilter = MutableStateFlow(StatusFilter.ALL)
    val statusFilter: StateFlow<StatusFilter> = _statusFilter.asStateFlow()

    private val _selectedRoute = MutableStateFlow<RdpRoute?>(null)
    val selectedRoute: StateFlow<RdpRoute?> = _selectedRoute.asStateFlow()

    private val _connectState = MutableStateFlow<ConnectState>(ConnectState.Idle)
    val connectState: StateFlow<ConnectState> = _connectState.asStateFlow()

    private val _activeSessions = MutableStateFlow<List<RdpSession>>(emptyList())
    val activeSessions: StateFlow<List<RdpSession>> = _activeSessions.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<ErrorType?>(null)
    val error: StateFlow<ErrorType?> = _error.asStateFlow()

    // --- Actions ---

    fun loadRoutes() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val serverUrl = setupRepository.getServerUrl()
                if (serverUrl.isEmpty()) return@launch
                val client = apiClientProvider.getClient(serverUrl)
                val response = client.getRdpRoutes()
                if (response.ok) {
                    _routes.value = response.routes
                    applyFilters()
                } else {
                    _error.value = ErrorType.ServerError
                }
            } catch (e: HttpException) {
                Timber.w(e, "RdpViewModel: HTTP %d loading routes", e.code())
                _error.value = when (e.code()) {
                    403 -> ErrorType.Forbidden
                    else -> ErrorType.ServerError
                }
            } catch (e: IOException) {
                Timber.w(e, "RdpViewModel: network error loading routes")
                _error.value = ErrorType.Network
            } catch (e: Exception) {
                Timber.w(e, "RdpViewModel: failed to load routes")
                _error.value = ErrorType.ServerError
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        applyFilters()
    }

    fun setStatusFilter(filter: StatusFilter) {
        _statusFilter.value = filter
        applyFilters()
    }

    fun selectRoute(route: RdpRoute) {
        _selectedRoute.value = route
        _connectState.value = ConnectState.Idle
    }

    fun connect(routeId: Int, password: String? = null, forceBypass: Boolean = false) {
        val route = _routes.value.firstOrNull { it.id == routeId } ?: return
        viewModelScope.launch {
            try {
                val serverUrl = setupRepository.getServerUrl()
                if (serverUrl.isEmpty()) {
                    _connectState.value = ConnectState.Error("Server not configured")
                    return@launch
                }
                val client = apiClientProvider.getClient(serverUrl)

                val result = rdpManager.connect(
                    route = route,
                    apiClient = client,
                    isVpnConnected = tunnelManager.state.value is TunnelState.Connected,
                    userPassword = password,
                    forceMaintenanceBypass = forceBypass,
                    onProgress = { step ->
                        _connectState.value = ConnectState.Connecting(step)
                    }
                )

                when (result) {
                    is RdpManager.ConnectResult.Success -> {
                        _connectState.value = ConnectState.Connected(result.session, result.passwordCopied)
                        _activeSessions.value = _activeSessions.value + result.session
                    }
                    is RdpManager.ConnectResult.NeedsPassword -> {
                        _connectState.value = ConnectState.NeedsPassword(
                            username = result.username,
                            domain = result.domain
                        )
                    }
                    is RdpManager.ConnectResult.MaintenanceWarning -> {
                        _connectState.value = ConnectState.MaintenanceWarning
                    }
                    is RdpManager.ConnectResult.Error -> {
                        _connectState.value = ConnectState.Error(result.message)
                    }
                    is RdpManager.ConnectResult.VpnRequired -> {
                        _connectState.value = ConnectState.Error(result.message)
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "RdpViewModel: connect failed for route $routeId")
                _connectState.value = ConnectState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun disconnect(routeId: Int) {
        val serverUrl = setupRepository.getServerUrl()
        if (serverUrl.isEmpty()) return
        val client = apiClientProvider.getClient(serverUrl)
        rdpManager.disconnect(routeId, client, viewModelScope)
        _activeSessions.value = _activeSessions.value.filter { it.routeId != routeId }
        _connectState.value = ConnectState.Idle
    }

    fun sendWol(routeId: Int) {
        viewModelScope.launch {
            try {
                val serverUrl = setupRepository.getServerUrl()
                if (serverUrl.isEmpty()) return@launch
                val client = apiClientProvider.getClient(serverUrl)
                wolClient.sendWol(client, routeId)
            } catch (e: Exception) {
                Timber.w(e, "RdpViewModel: WoL failed for route $routeId")
            }
        }
    }

    fun dismissSheet() {
        _selectedRoute.value = null
        _connectState.value = ConnectState.Idle
    }

    // --- Helpers ---

    private fun applyFilters() {
        val query = _searchQuery.value.trim().lowercase()
        val filter = _statusFilter.value
        _filteredRoutes.value = _routes.value.filter { route ->
            val nameMatch = query.isEmpty() || route.name.lowercase().contains(query)
            val statusMatch = when (filter) {
                StatusFilter.ALL -> true
                StatusFilter.ONLINE -> route.status?.online == true
                StatusFilter.OFFLINE -> route.status?.online != true
            }
            nameMatch && statusMatch
        }
    }
}
