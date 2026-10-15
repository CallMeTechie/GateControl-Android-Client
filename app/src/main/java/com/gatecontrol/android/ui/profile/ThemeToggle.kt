package com.gatecontrol.android.ui.profile

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The app theme ("system" | "light" | "dark") for the sun/moon switch. */
@HiltViewModel
class ThemeToggleViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val theme: StateFlow<String> = settingsRepository.getTheme()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "system")

    fun setTheme(theme: String) {
        viewModelScope.launch { settingsRepository.setTheme(theme) }
    }

    companion object {
        /** Whether [theme] shows dark right now ("system" follows the device). */
        fun isDark(theme: String, systemDark: Boolean): Boolean = when (theme) {
            "dark" -> true
            "light" -> false
            else -> systemDark
        }
    }
}

/**
 * Sun/moon switch between light and dark (until 1.35 in the start tab's
 * header, now top right of the profile): shows the sun while dark, a tap
 * switches to the other one. "System" can be picked again in Settings →
 * Darstellung.
 */
@Composable
fun ThemeToggleButton(modifier: Modifier = Modifier, viewModel: ThemeToggleViewModel = hiltViewModel()) {
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val dark = ThemeToggleViewModel.isDark(theme, isSystemInDarkTheme())
    GcIconButton(
        icon = if (dark) GcIcons.Sun else GcIcons.Moon,
        contentDescription = stringResource(if (dark) R.string.vpn_theme_to_light else R.string.vpn_theme_to_dark),
        onClick = { viewModel.setTheme(if (dark) "light" else "dark") },
        modifier = modifier,
    )
}
