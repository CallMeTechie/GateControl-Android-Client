package com.gatecontrol.android.location

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.data.SetupRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Satellite button on the start screen and the "Standortfreigabe" settings page. */
@HiltViewModel
class LocationShareViewModel @Inject constructor(
    private val repository: LocationShareRepository,
    private val setupRepository: SetupRepository,
) : ViewModel() {

    val enabled: StateFlow<Boolean> = repository.enabled
    val server: StateFlow<LocationServerStatus> = repository.server
    val state: StateFlow<LocationShareState> = repository.state
    val lastSent: StateFlow<SentLocation?> = repository.lastSent

    /** null = idle, true/false = result of the last delete. */
    private val _deleteResult = MutableStateFlow<Boolean?>(null)
    val deleteResult: StateFlow<Boolean?> = _deleteResult.asStateFlow()

    private val _deleting = MutableStateFlow(false)
    val deleting: StateFlow<Boolean> = _deleting.asStateFlow()

    private val _serverHost = MutableStateFlow("")
    val serverHost: StateFlow<String> = _serverHost.asStateFlow()

    init {
        viewModelScope.launch {
            _serverHost.value = setupRepository.getServerUrl()
                .removePrefix("https://").removePrefix("http://").trimEnd('/')
        }
    }

    fun refresh() = repository.refreshServerAsync()

    fun shareNow() = repository.shareNow()

    fun dismiss() = repository.dismiss()

    fun setEnabled(value: Boolean) {
        viewModelScope.launch {
            repository.setEnabled(value)
            if (value) repository.refreshServer()
        }
    }

    fun deleteOnServer() {
        if (_deleting.value) return
        _deleting.value = true
        viewModelScope.launch {
            _deleteResult.value = repository.deleteOnServer()
            _deleting.value = false
        }
    }

    fun clearDeleteResult() {
        _deleteResult.value = null
    }
}
