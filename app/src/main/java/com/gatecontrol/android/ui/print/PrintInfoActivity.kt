package com.gatecontrol.android.ui.print

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.print.POverview
import com.gatecontrol.android.print.PResult
import com.gatecontrol.android.print.PrintLogic
import com.gatecontrol.android.print.PrintSender
import com.gatecontrol.android.print.PrintTexts
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * "Drucken über GateControl": the settings of the print service (Settings › Druckdienste › GateControl › ⋮) and
 * the app's Settings › Drucken. Says which printers the person may use — or why there are none (no plugin for the
 * apps, no right, no connection) — and that the print dialog of other apps shows them as "GateControl – …", not
 * by the name the printer has in the Wi-Fi at home.
 */
@AndroidEntryPoint
class PrintInfoActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    private val model: PrintInfoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val theme by settingsRepository.getTheme().collectAsStateWithLifecycle(initialValue = "system")
            val sysLang = rememberSaveable { Locale.getDefault().language }
            val locale by settingsRepository.getLocale().collectAsStateWithLifecycle(initialValue = if (sysLang == "de") "de" else "en")
            LaunchedEffect(locale) {
                val config = Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) }
                @Suppress("DEPRECATION")
                resources.updateConfiguration(config, resources.displayMetrics)
            }
            val dark = when (theme) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            GateControlTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val ui by model.ui.collectAsStateWithLifecycle()
                    PrintInfoScreen(
                        ui,
                        onBack = { finish() },
                        onRetry = model::load,
                        onOpenSettings = { openPrintSettings(this) },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.load()
    }

    companion object {
        fun open(context: Context) {
            runCatching { context.startActivity(Intent(context, PrintInfoActivity::class.java)) }
        }

        /** Android's print services (where "GateControl" is switched on). */
        fun openPrintSettings(context: Context) {
            runCatching { context.startActivity(Intent(Settings.ACTION_PRINT_SETTINGS)) }
                .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
        }
    }
}

@Composable
private fun PrintInfoScreen(ui: PrintInfoViewModel.Ui, onBack: () -> Unit, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    val x = GateControlTheme.extraColors
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        GcSubpageBar(stringResource(R.string.print_info_title), onBack = onBack, backDescription = stringResource(R.string.common_back))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val overview = ui.overview
            when {
                ui.loading && overview == null && ui.error == null -> GcCard {
                    Text(stringResource(R.string.print_info_loading), style = MaterialTheme.typography.bodyMedium, color = x.muted)
                }
                ui.error != null -> {
                    GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(ui.error, style = MaterialTheme.typography.bodyMedium)
                            if (ui.unavailable) Text(stringResource(R.string.print_info_unavailable_detail), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    GcOutlineButton(stringResource(R.string.print_info_retry), onClick = onRetry, loading = ui.loading)
                }
                overview != null -> Printers(overview)
            }

            GcSectionLabel(stringResource(R.string.print_info_how_title), modifier = Modifier.padding(horizontal = 4.dp))
            GcCard {
                Text(stringResource(R.string.print_info_how), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.print_info_enable), style = MaterialTheme.typography.bodySmall, color = x.muted)
                GcOutlineButton(stringResource(R.string.print_info_open_settings), onClick = onOpenSettings, icon = GcIcons.Settings)
                Text(stringResource(R.string.print_info_share), style = MaterialTheme.typography.bodySmall, color = x.muted)
                Text(stringResource(R.string.print_info_wait), style = MaterialTheme.typography.bodySmall, color = x.muted)
            }
        }
    }
}

@Composable
private fun Printers(o: POverview) {
    val x = GateControlTheme.extraColors
    if (!o.rights.print) {
        GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
            Text(stringResource(R.string.print_err_no_right), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    if (o.printers.isEmpty()) {
        GcCard { Text(stringResource(R.string.print_info_no_printer), style = MaterialTheme.typography.bodyMedium) }
        return
    }
    GcSectionLabel(pluralStringResource(R.plurals.print_info_printers, o.printers.size, o.printers.size), modifier = Modifier.padding(horizontal = 4.dp))
    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        o.printers.forEachIndexed { i, p ->
            if (i > 0) GcRowDivider()
            val notes = listOfNotNull(
                p.location,
                if (p.color && !o.rights.bwOnly) null else stringResource(R.string.print_bw_only),
            )
            GcListRow(
                title = stringResource(R.string.print_printer_name, p.name),
                description = notes.joinToString(" · ").ifEmpty { null },
                leading = { Icon(GcIcons.Printer, contentDescription = null, tint = x.accentText, modifier = Modifier.size(22.dp)) },
                trailing = {
                    val ready = PrintLogic.note(p) == null
                    GcChip(
                        text = stringResource(if (ready || PrintLogic.note(p) == PrintLogic.Note.BUSY) R.string.print_info_ready else R.string.print_info_off),
                        tone = if (ready) GcTone.Ok else GcTone.Neutral,
                        small = true,
                    )
                },
            )
        }
    }
    o.pagesLeft?.let {
        Text(pluralStringResource(R.plurals.print_pages_left, it, it), style = MaterialTheme.typography.bodySmall, color = x.muted, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/** What "Drucken über GateControl" shows: the overview, or the reason there is none. */
@HiltViewModel
class PrintInfoViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sender: PrintSender,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    data class Ui(
        val loading: Boolean = true,
        val overview: POverview? = null,
        val error: String? = null,
        /** The plugin is not there for the apps (404). */
        val unavailable: Boolean = false,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    fun load() {
        _ui.value = _ui.value.copy(loading = true)
        viewModelScope.launch {
            val r = sender.overview()
            val t = PrintTexts.localized(context, settingsRepository)
            _ui.value = when (r) {
                is PResult.Ok -> Ui(loading = false, overview = r.value)
                is PResult.Failed -> Ui(loading = false, error = PrintTexts.failure(t, PResult.Failed(r.code, r.status, if (r.status == 404) null else r.text)), unavailable = r.status == 404)
                else -> Ui(loading = false, error = PrintTexts.failure(t, r))
            }
        }
    }
}
