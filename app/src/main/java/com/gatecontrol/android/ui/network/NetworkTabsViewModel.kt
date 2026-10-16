package com.gatecontrol.android.ui.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The segment the Netzwerk tab opens with: not read yet, or the stored choice (null = none). */
sealed interface NetworkDefault {
    data object Loading : NetworkDefault
    data class Known(val tab: String?) : NetworkDefault
}

/** Default segment of the Netzwerk tab, chosen by a long press on a segment. */
@HiltViewModel
class NetworkTabsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val default: StateFlow<NetworkDefault> = settingsRepository.getNetworkDefaultTab()
        .map<String?, NetworkDefault> { NetworkDefault.Known(NetworkTabs.normalize(it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetworkDefault.Loading)

    fun setDefault(tab: String) {
        viewModelScope.launch { settingsRepository.setNetworkDefaultTab(tab) }
    }
}
