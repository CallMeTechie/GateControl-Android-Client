package com.gatecontrol.android.ui.print

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatShareRules
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.familie.PluginDirectory
import com.gatecontrol.android.print.PJob
import com.gatecontrol.android.print.POverview
import com.gatecontrol.android.print.PPrinter
import com.gatecontrol.android.print.PResult
import com.gatecontrol.android.print.PrintFiles
import com.gatecontrol.android.print.PrintLogic
import com.gatecontrol.android.print.PrintSender
import com.gatecontrol.android.print.PrintTexts
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject

/**
 * Share target "Mit GateControl drucken" (PDF files and pictures, the print plugin's media types): a small sheet
 * over the sharing app with the printer, copies, colour and double-sided, then the job's state. The file goes to
 * the server over the app's normal connection (with or without the VPN) and the gateway at home prints it. On only
 * while the server offers the print plugin to the apps ([setEnabled]).
 */
@AndroidEntryPoint
class PrintShareActivity : ComponentActivity() {

    @Inject lateinit var setupRepository: SetupRepository
    @Inject lateinit var pluginDirectory: PluginDirectory
    @Inject lateinit var settingsRepository: SettingsRepository

    private val share: PrintShareViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!setupRepository.isConfigured() || !pluginDirectory.state.value.print) {
            Toast.makeText(this, R.string.print_err_unavailable, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            finish()
            return
        }
        if (savedInstanceState == null) {
            val uris: List<Uri> = if (action == Intent.ACTION_SEND_MULTIPLE) {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            } else {
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            }
            val items = uris.filter { ChatShareRules.acceptable(it, packageName) }.mapNotNull { uri ->
                val (name, size) = describe(uri)
                val mime = PrintFiles.mimeOf(this, uri, intent.type, name)
                val upload = PrintLogic.upload(mime) ?: return@mapNotNull null
                PrintShareViewModel.Item(uri, name?.let { PrintLogic.jobName(it) } ?: getString(R.string.print_share_file), size, PrintLogic.uploadType(mime), upload)
            }.take(PrintLogic.MAX_SHARED)
            if (items.isEmpty()) {
                Toast.makeText(this, R.string.print_share_nothing, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            share.start(items)
        }
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
                val ui by share.ui.collectAsStateWithLifecycle()
                ModalBottomSheet(onDismissRequest = { finish() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    PrintSheet(
                        ui,
                        onPrinter = share::selectPrinter,
                        onCopies = share::setCopies,
                        onColor = share::setColor,
                        onDuplex = share::setDuplex,
                        onPrint = share::print,
                        onRetry = share::reload,
                        onClose = { finish() },
                    )
                }
            }
        }
    }

    private fun describe(uri: Uri): Pair<String?, Long?> = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null to null
            val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }
            val s = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
            n to s
        } ?: (null to null)
    }.getOrDefault(null to null)

    companion object {
        /** On while the server offers the print plugin to the apps: only then "Mit GateControl drucken" is in the share sheet. */
        fun setEnabled(context: Context, enabled: Boolean) {
            runCatching {
                val component = ComponentName(context, PrintShareActivity::class.java)
                val want = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                if (context.packageManager.getComponentEnabledSetting(component) != want) {
                    context.packageManager.setComponentEnabledSetting(component, want, PackageManager.DONT_KILL_APP)
                }
            }.onFailure { Timber.w("Print: share target not switched (%s)", it.javaClass.simpleName) }
        }
    }
}

@Composable
private fun PrintSheet(
    ui: PrintShareViewModel.Ui,
    onPrinter: (String) -> Unit,
    onCopies: (Int) -> Unit,
    onColor: (Boolean) -> Unit,
    onDuplex: (Boolean) -> Unit,
    onPrint: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val x = GateControlTheme.extraColors
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.print_share_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

        // the files (and after sending: each one's state)
        GcCard(verticalArrangement = Arrangement.spacedBy(0.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
            ui.items.take(SHOWN).forEachIndexed { i, item ->
                if (i > 0) GcRowDivider()
                val sent = ui.sent?.getOrNull(i)
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(if (item.mime.startsWith("image/")) GcIcons.Photo else GcIcons.File, contentDescription = null, tint = x.muted)
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (sent != null) {
                            Text(
                                sent.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = when (sent.tone) {
                                    GcTone.Error -> MaterialTheme.colorScheme.error
                                    GcTone.Ok -> x.accentText
                                    else -> x.muted
                                },
                            )
                        }
                    }
                }
            }
            if (ui.items.size > SHOWN) {
                GcRowDivider()
                Text(
                    pluralStringResource(R.plurals.print_share_more_files, ui.items.size - SHOWN, ui.items.size - SHOWN),
                    style = MaterialTheme.typography.bodySmall,
                    color = x.muted,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
        }

        val o = ui.overview
        val printer = ui.printer
        if (ui.sent == null) {
            when {
                ui.error != null -> {
                    GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) { Text(ui.error, style = MaterialTheme.typography.bodyMedium) }
                    if (ui.retry) GcOutlineButton(stringResource(R.string.print_info_retry), onClick = onRetry, loading = ui.loading)
                }
                ui.loading || o == null -> Text(stringResource(R.string.print_info_loading), style = MaterialTheme.typography.bodyMedium, color = x.muted)
                !o.rights.print -> GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
                    Text(stringResource(R.string.print_err_no_right), style = MaterialTheme.typography.bodyMedium)
                }
                printer == null -> GcBanner(tone = GcTone.Info, icon = GcIcons.Info) {
                    Text(stringResource(R.string.print_info_no_printer), style = MaterialTheme.typography.bodyMedium)
                }
                else -> Options(ui, o, printer, onPrinter, onCopies, onColor, onDuplex)
            }
        }
        ui.sendError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        if (ui.sent == null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GcOutlineButton(stringResource(R.string.print_share_cancel), onClick = onClose, modifier = Modifier.weight(1f))
                GcPrimaryButton(
                    if (ui.sending) stringResource(R.string.print_share_sending) else stringResource(R.string.print_share_go),
                    onClick = onPrint,
                    enabled = printer != null && o?.rights?.print == true && !ui.sending,
                    loading = ui.sending,
                    icon = GcIcons.Printer,
                    modifier = Modifier.weight(1.4f),
                )
            }
            Text(stringResource(R.string.print_share_wait_note), style = MaterialTheme.typography.bodySmall, color = x.muted)
        } else {
            GcPrimaryButton(stringResource(R.string.print_share_close), onClick = onClose)
        }
        Text(stringResource(R.string.print_share_dialog_hint), style = MaterialTheme.typography.bodySmall, color = x.faint)
    }
}

@Composable
private fun Options(
    ui: PrintShareViewModel.Ui,
    o: POverview,
    printer: PPrinter,
    onPrinter: (String) -> Unit,
    onCopies: (Int) -> Unit,
    onColor: (Boolean) -> Unit,
    onDuplex: (Boolean) -> Unit,
) {
    val x = GateControlTheme.extraColors
    var menu by remember { mutableStateOf(false) }
    GcCard(contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        // printer (a menu when there are several)
        Box {
            Row(
                Modifier.fillMaxWidth().clickable(enabled = o.printers.size > 1) { menu = true }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.print_share_printer), color = x.muted, style = MaterialTheme.typography.bodyMedium)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(printer.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
                    val note = when (PrintLogic.note(printer)) {
                        PrintLogic.Note.OFF -> stringResource(R.string.print_printer_off)
                        PrintLogic.Note.BUSY -> stringResource(R.string.print_printer_busy)
                        PrintLogic.Note.STOPPED -> stringResource(R.string.print_printer_stopped)
                        PrintLogic.Note.CERT -> stringResource(R.string.print_printer_cert)
                        null -> printer.location
                    }
                    note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = x.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (o.printers.size > 1) GcChevron()
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                o.printers.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.name + (p.location?.let { " · $it" } ?: "")) },
                        onClick = {
                            menu = false
                            onPrinter(p.id)
                        },
                    )
                }
            }
        }
        GcRowDivider()
        // copies
        val range = PrintLogic.copiesRange(printer)
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.print_share_copies), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            GcIconButton(GcIcons.Minus, stringResource(R.string.print_share_less), onClick = { onCopies(ui.copies - 1) }, enabled = ui.copies > range.first)
            Text("${ui.copies}", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 28.dp))
            GcIconButton(GcIcons.Plus, stringResource(R.string.print_share_more), onClick = { onCopies(ui.copies + 1) }, enabled = ui.copies < range.last)
        }
        GcRowDivider()
        val colorOk = PrintLogic.colorAllowed(printer, o.rights)
        GcSwitchRow(
            label = stringResource(R.string.print_share_color),
            description = when {
                !printer.color -> stringResource(R.string.print_share_bw_printer)
                o.rights.bwOnly -> stringResource(R.string.print_bw_only)
                else -> null
            },
            checked = ui.color && colorOk,
            onCheckedChange = onColor,
            enabled = colorOk,
        )
        if (printer.duplex) {
            GcRowDivider()
            GcSwitchRow(label = stringResource(R.string.print_share_duplex), description = null, checked = ui.duplex, onCheckedChange = onDuplex)
        }
    }
    o.pagesLeft?.let {
        Text(pluralStringResource(R.plurals.print_pages_left, it, it), style = MaterialTheme.typography.bodySmall, color = x.muted, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

private const val SHOWN = 5

/** The state behind "Mit GateControl drucken": files, printer and options, then each job's state. */
@HiltViewModel
class PrintShareViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sender: PrintSender,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    /** A shared file: [mime] the type it goes up as, [upload] whether it is turned into a JPEG first. */
    data class Item(val uri: Uri, val name: String, val size: Long?, val mime: String, val upload: PrintLogic.Upload)

    /** A sent file: its job (null when sending failed) and the state in words. */
    data class Sent(val job: PJob?, val text: String, val tone: GcTone)

    data class Ui(
        val items: List<Item> = emptyList(),
        val loading: Boolean = true,
        val overview: POverview? = null,
        val printerId: String? = null,
        val copies: Int = 1,
        val color: Boolean = false,
        val duplex: Boolean = false,
        val error: String? = null,
        /** The error may go away when asked again (connection). */
        val retry: Boolean = false,
        val sending: Boolean = false,
        val sendError: String? = null,
        /** After sending: one entry per file. */
        val sent: List<Sent>? = null,
    ) {
        val printer: PPrinter? get() = overview?.printers?.firstOrNull { it.id == printerId }
    }

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()
    private val prefs = context.getSharedPreferences("print_ui", Context.MODE_PRIVATE)

    fun start(items: List<Item>) {
        _ui.value = Ui(items = items)
        reload()
    }

    fun reload() {
        _ui.update { it.copy(loading = true) }
        viewModelScope.launch {
            val r = sender.overview()
            val t = PrintTexts.localized(context, settingsRepository)
            when (r) {
                is PResult.Ok -> {
                    val printers = r.value.printers
                    val last = prefs.getString(KEY_PRINTER, null)
                    val pick = printers.firstOrNull { it.id == last } ?: printers.firstOrNull()
                    _ui.update {
                        it.copy(
                            loading = false, overview = r.value, printerId = pick?.id, error = null, retry = false,
                            color = pick != null && prefs.getBoolean(KEY_COLOR, false) && PrintLogic.colorAllowed(pick, r.value.rights),
                            duplex = pick?.duplex == true && prefs.getBoolean(KEY_DUPLEX, false),
                        )
                    }
                }
                else -> _ui.update {
                    it.copy(loading = false, error = PrintTexts.failure(t, r), retry = r is PResult.Offline || (r is PResult.Failed && r.status >= 500))
                }
            }
        }
    }

    fun selectPrinter(id: String) = _ui.update { u ->
        val o = u.overview ?: return@update u
        val p = o.printers.firstOrNull { it.id == id } ?: return@update u
        u.copy(
            printerId = id,
            copies = u.copies.coerceIn(PrintLogic.copiesRange(p)),
            color = u.color && PrintLogic.colorAllowed(p, o.rights),
            duplex = u.duplex && p.duplex,
        )
    }

    fun setCopies(n: Int) = _ui.update { u -> u.printer?.let { u.copy(copies = n.coerceIn(PrintLogic.copiesRange(it))) } ?: u }

    fun setColor(on: Boolean) = _ui.update { it.copy(color = on) }

    fun setDuplex(on: Boolean) = _ui.update { it.copy(duplex = on) }

    /** Sends every file as its own job, then follows the jobs for a while (the server notifies when they are done). */
    fun print() {
        val u = _ui.value
        val p = u.printer ?: return
        val o = u.overview ?: return
        if (u.sending) return
        val color = u.color && PrintLogic.colorAllowed(p, o.rights)
        val duplex = u.duplex && p.duplex
        prefs.edit {
            putString(KEY_PRINTER, p.id)
            putBoolean(KEY_COLOR, u.color)
            putBoolean(KEY_DUPLEX, u.duplex)
        }
        _ui.update { it.copy(sending = true, sendError = null) }
        viewModelScope.launch {
            val t = PrintTexts.localized(context, settingsRepository)
            val sent = mutableListOf<Sent>()
            for (item in u.items) {
                val file = withContext(Dispatchers.IO) { PrintFiles.copy(context, item.uri, item.upload) }
                val r = if (file == null) {
                    PResult.Failed(if (item.size != null && item.size > PrintLogic.MAX_BYTES) "too_large" else "read", 0)
                } else {
                    try {
                        sender.send(file, item.mime, p.id, u.copies, color, duplex, item.name)
                    } finally {
                        file.delete()
                    }
                }
                sent += when (r) {
                    is PResult.Ok -> stateOf(t, r.value.job)
                    else -> Sent(null, PrintTexts.failure(t, r), GcTone.Error)
                }
            }
            if (sent.all { it.job == null } && sent.size == 1) {
                // nothing went out: stay on the options, with the reason
                _ui.update { it.copy(sending = false, sendError = sent.first().text) }
                return@launch
            }
            _ui.update { it.copy(sending = false, sent = sent.toList()) }
            follow(t)
        }
    }

    private suspend fun follow(t: Context) {
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < FOLLOW_MS) {
            val open = _ui.value.sent.orEmpty().filter { s -> s.job != null && !PrintLogic.isFinal(PrintLogic.phase(s.job)) }
            if (open.isEmpty()) return
            delay(FOLLOW_EVERY_MS)
            val jobs = (sender.overview() as? PResult.Ok)?.value?.jobs ?: continue
            _ui.update { u ->
                u.copy(sent = u.sent?.map { s -> s.job?.let { j -> jobs.firstOrNull { it.id == j.id } }?.let { stateOf(t, it) } ?: s })
            }
        }
    }

    private fun stateOf(t: Context, job: PJob): Sent {
        val tone = when (PrintLogic.phase(job)) {
            PrintLogic.Phase.DONE -> GcTone.Ok
            PrintLogic.Phase.FAILED -> GcTone.Error
            PrintLogic.Phase.WAITING, PrintLogic.Phase.STOPPED -> GcTone.Warn
            else -> GcTone.Neutral
        }
        return Sent(job, PrintTexts.state(t, job), tone)
    }

    private companion object {
        const val KEY_PRINTER = "share_printer"
        const val KEY_COLOR = "share_color"
        const val KEY_DUPLEX = "share_duplex"
        const val FOLLOW_MS = 3 * 60_000L
        const val FOLLOW_EVERY_MS = 4_000L
    }
}
