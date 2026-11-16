package com.gatecontrol.android.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.PluginDirectory
import com.gatecontrol.android.terminal.TermPrefs
import com.gatecontrol.android.ui.components.GcSwitchRow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class TerminalSettingsViewModel @Inject constructor(private val prefs: TermPrefs, plugins: PluginDirectory) : ViewModel() {
    val settings = prefs.state
    val available = plugins.state
    fun setBlockScreenshots(on: Boolean) = prefs.setBlockScreenshots(on)
    fun setAskPaste(on: Boolean) = prefs.setAskPaste(on)
}

/**
 * Settings › „SSH-Terminal“ (only with the plugin „Fernzugriff“): „Bildschirmfotos im Terminal verbieten“ (off by
 * default — with it on the terminal sets FLAG_SECURE) and the question before pasting several lines.
 */
@Composable
fun TerminalSettingsGroup(group: @Composable (String, @Composable () -> Unit) -> Unit, viewModel: TerminalSettingsViewModel = hiltViewModel()) {
    val plugins by viewModel.available.collectAsStateWithLifecycle()
    if (!plugins.remote) return
    val s by viewModel.settings.collectAsStateWithLifecycle()
    group(stringResource(R.string.term_settings_group)) {
        GcSwitchRow(
            label = stringResource(R.string.term_settings_secure),
            description = stringResource(R.string.term_settings_secure_desc),
            checked = s.blockScreenshots,
            onCheckedChange = viewModel::setBlockScreenshots,
        )
        GcSwitchRow(
            label = stringResource(R.string.term_settings_paste),
            description = null,
            checked = s.askPaste,
            onCheckedChange = viewModel::setAskPaste,
        )
    }
}
