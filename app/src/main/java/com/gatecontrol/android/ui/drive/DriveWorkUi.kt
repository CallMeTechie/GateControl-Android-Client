package com.gatecontrol.android.ui.drive

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DriveLive
import com.gatecontrol.android.drive.work.DriveWorkLogic
import com.gatecontrol.android.drive.work.DriveWorkPhotos
import com.gatecontrol.android.drive.work.DriveWorkStore
import com.gatecontrol.android.drive.work.DriveWorkWifi
import com.gatecontrol.android.drive.work.WifiZone
import com.gatecontrol.android.drive.work.WorkPlace
import com.gatecontrol.android.drive.work.WorkSettings
import com.gatecontrol.android.trustednet.TrustedNetSnapshot
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject

/** The settings of the work photos (Foto-Sicherung) and the question about a new WLAN. */
@HiltViewModel
class DriveWorkViewModel @Inject constructor(
    val work: DriveWorkPhotos,
    val wifi: DriveWorkWifi,
    private val store: DriveWorkStore,
    val live: DriveLive,
) : ViewModel() {
    val settings: StateFlow<WorkSettings> = work.settings
    val learned: StateFlow<DriveWorkLogic.Learned> = work.learned
    val places: StateFlow<List<WorkPlace>> = work.places
    val network: StateFlow<TrustedNetSnapshot> = wifi.network
    val ask: StateFlow<String?> = store.ask
    val removals: StateFlow<List<Long>> = store.removals

    private val _message = MutableStateFlow<String?>(null)
    /** A code after a change that did not take ([workText]); the screen shows and clears it. */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var started = false

    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            live.current()
            work.refreshSettings()
        }
        viewModelScope.launch { work.learnedNow(); work.placesNow() }
    }

    fun clearMessage() { _message.value = null }

    private fun run(block: suspend () -> String?) {
        viewModelScope.launch {
            _busy.value = true
            try {
                _message.value = block()
            } finally {
                _busy.value = false
            }
        }
    }

    fun chooseFolder(id: Long, label: String) = run { work.chooseWorkFolder(id, label) }
    fun clearFolder() = run { work.clearWorkFolder() }
    fun setSubfolders(on: Boolean) = run { work.setSubfolders(on) }
    fun setNeverInMemories(never: Boolean) = run { work.setInMemories(!never) }
    fun setAutoSort(on: Boolean) = work.setAutoSort(on)
    fun setAskNewWifi(on: Boolean) = work.setAskNewWifi(on)
    fun setRemoveAfter(on: Boolean) = work.setRemoveAfter(on)
    fun resetLearning() = work.resetLearning()

    /** The answer of "Wo bist du gerade?" (null: "Später" — asked again another time). */
    fun answer(ssid: String, zone: WifiZone?, stopAsking: Boolean) = wifi.answer(ssid, zone, stopAsking)

    fun forget(ssid: String) = work.setZone(ssid, null)

    /** After a permission: the WLAN callback anew, then a look. */
    fun refreshWifi() {
        wifi.refresh()
        viewModelScope.launch { wifi.sample(wait = true) }
    }

    /** The phone's work photos waiting to go (their media store URIs). */
    fun removalUris(): List<Uri> = removals.value.map { id ->
        if (id < 0) ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, -id)
        else ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
    }

    fun removalsDone(ids: List<Long>) = store.clearRemovals(ids)
}

/** A work-photo code as a sentence (outside of composition). */
internal fun workText(context: Context, code: String): String = when {
    code == "no_work_folder" -> context.getString(R.string.drive_work_error_no_folder)
    code == "move_failed" -> context.getString(R.string.drive_work_error_move)
    code == "review_failed" -> context.getString(R.string.drive_work_error_review)
    code == "work_folder:camera" -> context.getString(R.string.drive_work_error_camera)
    code == "work_folder:encrypted" -> context.getString(R.string.drive_work_error_encrypted)
    code == "work_folder:forbidden" -> context.getString(R.string.drive_work_error_forbidden)
    code == "work_folder:archived" -> context.getString(R.string.drive_work_error_archived)
    code.startsWith("work_folder:") -> context.getString(R.string.drive_work_error_folder)
    else -> driveText(context, code)
}

/** What Android lets the app know of WLAN names. */
private data class WifiAccess(val fine: Boolean, val background: Boolean, val locationOn: Boolean)

private fun wifiAccess(context: Context): WifiAccess {
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    return WifiAccess(
        fine = granted(Manifest.permission.ACCESS_FINE_LOCATION),
        background = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        locationOn = context.getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled != false,
    )
}

private fun openDetails(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Foto-Sicherung › the work photos: "Ziele" (private, "Arbeitsfotos nach", subfolders), "WLANs" (Firma / Zuhause, the
 * current one, asking about new ones), "KI für Arbeitsfotos" (what it learned, sorting alone, Aufräumen, Neu lernen) and
 * "Auf dem Handy".
 */
@Composable
internal fun WorkPhotoSections(onCleanup: () -> Unit, viewModel: DriveWorkViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val x = GateControlTheme.extraColors
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val learned by viewModel.learned.collectAsStateWithLifecycle()
    val places by viewModel.places.collectAsStateWithLifecycle()
    val network by viewModel.network.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val removals by viewModel.removals.collectAsStateWithLifecycle()
    val me by viewModel.live.me.collectAsStateWithLifecycle()
    val classes = viewModel.work.serverClasses(me)
    var access by remember { mutableStateOf(wifiAccess(context)) }
    var choosing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var resetAsk by remember { mutableStateOf(false) }
    var rationale by rememberSaveable { mutableStateOf(false) }
    var bgRationale by rememberSaveable { mutableStateOf(false) }
    val workLabel = s.folderLabel ?: stringResource(R.string.drive_work_work_default)

    LaunchedEffect(Unit) { viewModel.start() }
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, workText(context, it), Toast.LENGTH_LONG).show()
            viewModel.clearMessage()
        }
    }
    LifecycleResumeEffect(Unit) {
        access = wifiAccess(context)
        onPauseOrDispose { }
    }
    val fineLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        access = wifiAccess(context)
        if (access.fine) {
            viewModel.refreshWifi()
        } else {
            val activity = context as? Activity
            if (result.isNotEmpty() && activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)) openDetails(context)
        }
    }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        access = wifiAccess(context)
        val activity = context as? Activity
        if (!granted && activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) openDetails(context)
        viewModel.refreshWifi()
    }
    val trash = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) viewModel.removalsDone(removals)
    }

    if (removals.isNotEmpty()) {
        GcBanner(tone = GcTone.Info, icon = GcIcons.Phone) {
            Text(pluralStringResource(R.plurals.drive_work_remove_pending, removals.size, removals.size), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = {
                val uris = viewModel.removalUris()
                val sender = runCatching { MediaStore.createTrashRequest(context.contentResolver, uris, true).intentSender }.getOrNull()
                if (sender != null) trash.launch(IntentSenderRequest.Builder(sender).build())
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.drive_work_remove_now)) }
        }
    }

    // ─── Ziele ───────────────────────────────────────────────────────
    GcSectionLabel(stringResource(R.string.drive_work_section_targets), Modifier.padding(horizontal = 4.dp).semantics { heading() })
    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        GcListRow(
            title = stringResource(R.string.drive_work_private_photos),
            description = stringResource(R.string.drive_work_private_path),
            leading = { ZoneIcon(GcIcons.House, x.accentText, x.accentBg) },
        )
        GcRowDivider()
        GcListRow(
            title = stringResource(R.string.drive_work_target),
            description = s.folderLabel ?: stringResource(R.string.drive_work_target_none),
            onClick = { if (!busy) choosing = true },
            leading = { ZoneIcon(GcIcons.Briefcase, x.blue, x.blueBg) },
            trailing = { SmallButton(stringResource(if (s.active) R.string.drive_work_change else R.string.drive_work_choose)) { if (!busy) choosing = true } },
        )
        if (s.active) {
            GcRowDivider()
            val now = LocalDate.now()
            GcSwitchRow(
                label = stringResource(R.string.drive_work_subfolders),
                description = stringResource(
                    R.string.drive_work_subfolders_example,
                    workLabel.substringAfterLast(" › "),
                    "%04d".format(Locale.ROOT, now.year),
                    "%02d".format(Locale.ROOT, now.monthValue),
                ),
                checked = s.subfolders,
                enabled = !busy,
                onCheckedChange = viewModel::setSubfolders,
            )
        }
        GcRowDivider()
        Text(
            stringResource(R.string.drive_work_team_note),
            style = MaterialTheme.typography.bodySmall,
            color = x.muted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }

    // ─── WLANs ───────────────────────────────────────────────────────
    GcSectionLabel(stringResource(R.string.drive_work_section_wifi), Modifier.padding(horizontal = 4.dp).semantics { heading() })
    when {
        !access.fine -> WifiWarning(stringResource(R.string.drive_work_wifi_perm), stringResource(R.string.drive_work_wifi_perm_grant)) { rationale = true }
        !access.locationOn -> WifiWarning(stringResource(R.string.drive_work_wifi_perm_off), stringResource(R.string.drive_work_wifi_perm_settings)) {
            runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        !access.background -> WifiWarning(stringResource(R.string.drive_work_wifi_perm_bg), stringResource(R.string.drive_work_wifi_perm_settings)) { bgRationale = true }
    }
    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        val rows = s.zones.work.sorted().map { it to WifiZone.WORK } + s.zones.home.sorted().map { it to WifiZone.HOME } + s.zones.neither.sorted().map { it to WifiZone.NEITHER }
        if (rows.isEmpty()) {
            Text(stringResource(R.string.drive_work_wifi_none_yet), style = MaterialTheme.typography.bodySmall, color = x.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }
        rows.forEach { (ssid, zone) ->
            val edit = stringResource(R.string.drive_work_wifi_edit, ssid)
            GcListRow(
                title = ssid,
                description = stringResource(
                    when (zone) {
                        WifiZone.WORK -> R.string.drive_work_wifi_to_work
                        WifiZone.HOME -> R.string.drive_work_wifi_to_private
                        WifiZone.NEITHER -> R.string.drive_work_wifi_to_review
                    },
                ),
                onClick = { editing = ssid },
                modifier = Modifier.semantics { contentDescription = edit },
                leading = { ZoneIcon(GcIcons.Wifi, zoneColor(zone), zoneBg(zone)) },
                trailing = { ZoneTag(zone) },
            )
            GcRowDivider()
        }
        val current = if (network.kind == TrustedNetSnapshot.Kind.WIFI) network.ssid else null
        val currentZone = s.zones.zoneOf(current)
        GcListRow(
            title = when {
                current != null -> stringResource(R.string.drive_work_wifi_current, current)
                network.kind == TrustedNetSnapshot.Kind.WIFI -> stringResource(R.string.drive_work_wifi_current_hidden)
                else -> stringResource(R.string.drive_work_wifi_current_none)
            },
            description = if (current != null && currentZone == null) stringResource(R.string.drive_work_wifi_current_unknown) else null,
            trailing = if (current != null) ({
                if (currentZone == null) SmallButton(stringResource(R.string.drive_work_wifi_assign)) { editing = current } else ZoneTag(currentZone)
            }) else null,
            onClick = if (current != null) ({ editing = current }) else null,
        )
        GcRowDivider()
        GcSwitchRow(
            label = stringResource(R.string.drive_work_wifi_ask),
            description = null,
            checked = s.askNewWifi,
            onCheckedChange = viewModel::setAskNewWifi,
        )
        GcRowDivider()
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(GcIcons.Info, contentDescription = null, tint = x.muted, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.drive_work_wifi_note), style = MaterialTheme.typography.bodySmall, color = x.muted)
        }
    }

    // ─── KI für Arbeitsfotos ─────────────────────────────────────────
    GcSectionLabel(stringResource(R.string.drive_work_section_ai), Modifier.padding(horizontal = 4.dp).semantics { heading() })
    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(if (learned.ready) R.string.drive_work_ai_ready else R.string.drive_work_ai_learning), style = MaterialTheme.typography.bodyLarge)
            Text(
                if (learned.ready) stringResource(R.string.drive_work_ai_progress_ready, learned.total)
                else stringResource(R.string.drive_work_ai_progress, learned.progress, DriveWorkLogic.MIN_EXAMPLES),
                style = MaterialTheme.typography.bodySmall,
                color = x.muted,
            )
            val progressLabel = stringResource(R.string.drive_work_ai_progress_label)
            LinearProgressIndicator(
                progress = { learned.progress.toFloat() / DriveWorkLogic.MIN_EXAMPLES },
                color = x.blue,
                trackColor = x.panel2,
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).semantics {
                    contentDescription = progressLabel
                    progressBarRangeInfo = ProgressBarRangeInfo(learned.progress.toFloat(), 0f..DriveWorkLogic.MIN_EXAMPLES.toFloat())
                },
            )
            Text(
                stringResource(R.string.drive_work_ai_learned, learned.work, learned.workFromWifi, learned.private),
                style = MaterialTheme.typography.bodySmall,
                color = x.muted,
            )
        }
        GcRowDivider()
        val workPlaces = places.count { it.work }
        val homePlaces = places.size - workPlaces
        GcListRow(
            title = stringResource(R.string.drive_work_places),
            description = if (places.isEmpty()) stringResource(R.string.drive_work_places_none)
            else stringResource(R.string.drive_work_places_learned, workPlaces, homePlaces),
        )
        GcRowDivider()
        GcSwitchRow(
            label = stringResource(R.string.drive_work_ai_auto),
            description = stringResource(R.string.drive_work_ai_auto_desc),
            checked = s.autoSort,
            onCheckedChange = viewModel::setAutoSort,
        )
        GcRowDivider()
        GcListRow(title = stringResource(R.string.drive_work_ai_local), description = stringResource(R.string.drive_work_ai_local_desc))
        GcRowDivider()
        GcListRow(
            title = stringResource(R.string.drive_work_ai_cleanup),
            description = stringResource(R.string.drive_work_ai_cleanup_desc),
            onClick = onCleanup,
            trailing = { SmallButton(stringResource(R.string.drive_work_ai_open), onClick = onCleanup) },
        )
        GcRowDivider()
        GcListRow(
            title = stringResource(R.string.drive_work_ai_reset),
            description = stringResource(R.string.drive_work_ai_reset_desc),
            trailing = { SmallButton(stringResource(R.string.drive_work_ai_reset_button), danger = true) { resetAsk = true } },
        )
    }

    // ─── Auf dem Handy ───────────────────────────────────────────────
    GcSectionLabel(stringResource(R.string.drive_work_section_phone), Modifier.padding(horizontal = 4.dp).semantics { heading() })
    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        GcSwitchRow(
            label = stringResource(R.string.drive_work_remove),
            description = stringResource(R.string.drive_work_remove_desc),
            checked = s.removeAfter,
            onCheckedChange = viewModel::setRemoveAfter,
        )
        GcRowDivider()
        GcSwitchRow(
            label = stringResource(R.string.drive_work_memories),
            description = if (classes) null else stringResource(R.string.drive_work_needs_1130),
            checked = !s.inMemories,
            enabled = classes && !busy,
            onCheckedChange = viewModel::setNeverInMemories,
        )
    }

    if (choosing) {
        WorkFolderChooser(
            onPicked = { id, label -> choosing = false; viewModel.chooseFolder(id, label) },
            onNone = if (s.active) ({ choosing = false; viewModel.clearFolder() }) else null,
            onDismiss = { choosing = false },
        )
    }
    editing?.let { ssid ->
        WifiZoneDialog(
            ssid = ssid,
            workLabel = workLabel,
            initial = s.zones.zoneOf(ssid),
            asking = false,
            onSave = { zone, _ -> editing = null; viewModel.work.setZone(ssid, zone) },
            onLater = { editing = null },
            onForget = if (s.zones.zoneOf(ssid) != null) ({ editing = null; viewModel.forget(ssid) }) else null,
        )
    }
    if (resetAsk) {
        AlertDialog(
            onDismissRequest = { resetAsk = false },
            title = { Text(stringResource(R.string.drive_work_ai_reset_title)) },
            text = { Text(stringResource(R.string.drive_work_ai_reset_text)) },
            confirmButton = {
                TextButton(onClick = { resetAsk = false; viewModel.resetLearning() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.drive_work_ai_reset_button), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { resetAsk = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.drive_cancel)) } },
        )
    }
    if (rationale) {
        AlertDialog(
            onDismissRequest = { rationale = false },
            title = { Text(stringResource(R.string.drive_work_perm_title)) },
            text = { Text(stringResource(R.string.drive_work_perm_explain)) },
            confirmButton = {
                TextButton(onClick = {
                    rationale = false
                    fineLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }) { Text(stringResource(R.string.drive_work_perm_continue)) }
            },
            dismissButton = { TextButton(onClick = { rationale = false }) { Text(stringResource(R.string.drive_cancel)) } },
        )
    }
    if (bgRationale) {
        AlertDialog(
            onDismissRequest = { bgRationale = false },
            title = { Text(stringResource(R.string.drive_work_bg_title)) },
            text = { Text(stringResource(R.string.drive_work_bg_explain)) },
            confirmButton = {
                TextButton(onClick = {
                    bgRationale = false
                    bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }) { Text(stringResource(R.string.drive_work_perm_continue)) }
            },
            dismissButton = { TextButton(onClick = { bgRationale = false }) { Text(stringResource(R.string.drive_cancel)) } },
        )
    }
}

@Composable
private fun WifiWarning(text: String, button: String, onClick: () -> Unit) {
    GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            GcOutlineButton(text = button, onClick = onClick, minHeight = 44.dp)
        }
    }
}

/** A small outlined button at the end of a row (≥ 44 dp high). */
@Composable
private fun SmallButton(text: String, danger: Boolean = false, onClick: () -> Unit) {
    val x = GateControlTheme.extraColors
    val color = if (danger) MaterialTheme.colorScheme.error else x.accentText
    TextButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 44.dp).border(1.dp, if (danger) color.copy(alpha = 0.4f) else x.border2, RoundedCornerShape(10.dp)),
        shape = RoundedCornerShape(10.dp),
    ) { Text(text, color = color, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun ZoneIcon(icon: ImageVector, tint: Color, bg: Color) {
    GcIconSquare(size = 36.dp, background = bg) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp)) }
}

@Composable
private fun zoneColor(zone: WifiZone): Color {
    val x = GateControlTheme.extraColors
    return when (zone) {
        WifiZone.WORK -> x.blue
        WifiZone.HOME -> x.accentText
        WifiZone.NEITHER -> x.warn
    }
}

@Composable
private fun zoneBg(zone: WifiZone): Color {
    val x = GateControlTheme.extraColors
    return when (zone) {
        WifiZone.WORK -> x.blueBg
        WifiZone.HOME -> x.accentBg
        WifiZone.NEITHER -> x.warnBg
    }
}

@Composable
private fun zoneLabel(zone: WifiZone): String = stringResource(
    when (zone) {
        WifiZone.WORK -> R.string.drive_work_zone_work
        WifiZone.HOME -> R.string.drive_work_zone_home
        WifiZone.NEITHER -> R.string.drive_work_zone_neither
    },
)

@Composable
private fun ZoneTag(zone: WifiZone) {
    Text(
        zoneLabel(zone),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = zoneColor(zone),
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(zoneBg(zone)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/**
 * "Wo bist du gerade?": the WLAN [ssid] is Firma, Zuhause or Weder noch. [asking]: the question about a new WLAN (with
 * "Bei neuen WLANs nicht mehr fragen"); else changing one of the list ([onForget] takes it off).
 */
@Composable
internal fun WifiZoneDialog(
    ssid: String,
    workLabel: String,
    initial: WifiZone?,
    asking: Boolean,
    onSave: (WifiZone, Boolean) -> Unit,
    onLater: () -> Unit,
    onForget: (() -> Unit)? = null,
) {
    var zone by rememberSaveable(ssid) { mutableStateOf(initial ?: WifiZone.NEITHER) }
    var stop by rememberSaveable(ssid) { mutableStateOf(false) }
    val group = stringResource(R.string.drive_work_ask_group)
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.drive_work_ask_title), modifier = Modifier.semantics { heading() }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.drive_work_ask_text, ssid), style = MaterialTheme.typography.bodyMedium, color = GateControlTheme.extraColors.muted)
                Column(Modifier.selectableGroup().semantics { contentDescription = group }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZoneChoice(WifiZone.WORK, stringResource(R.string.drive_work_ask_work_desc, workLabel), GcIcons.Briefcase, zone == WifiZone.WORK) { zone = WifiZone.WORK }
                    ZoneChoice(WifiZone.HOME, stringResource(R.string.drive_work_ask_home_desc), GcIcons.House, zone == WifiZone.HOME) { zone = WifiZone.HOME }
                    ZoneChoice(WifiZone.NEITHER, stringResource(R.string.drive_work_ask_neither_desc), GcIcons.Alert, zone == WifiZone.NEITHER) { zone = WifiZone.NEITHER }
                }
                if (asking) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp).toggleable(value = stop, role = Role.Checkbox, onValueChange = { stop = it }),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(checked = stop, onCheckedChange = null)
                        Text(stringResource(R.string.drive_work_ask_stop), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(zone, stop) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.drive_work_ask_save), fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            Row {
                if (onForget != null) {
                    TextButton(onClick = onForget, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.drive_work_ask_forget), color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onLater, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.drive_work_ask_later)) }
            }
        },
    )
}

@Composable
private fun ZoneChoice(zone: WifiZone, description: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val x = GateControlTheme.extraColors
    val color = zoneColor(zone)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) color.copy(alpha = 0.10f) else Color.Transparent)
            .border(1.dp, if (selected) color else x.border2, RoundedCornerShape(14.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        ZoneIcon(icon, color, zoneBg(zone))
        Column(Modifier.weight(1f)) {
            Text(zoneLabel(zone), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = x.muted)
        }
    }
}

/** Over every screen: the question about a new WLAN, while one waits and work photos are set up. */
@Composable
fun DriveWorkAskHost(viewModel: DriveWorkViewModel = hiltViewModel()) {
    val ask by viewModel.ask.collectAsStateWithLifecycle()
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val ssid = ask ?: return
    if (!s.active) return
    WifiZoneDialog(
        ssid = ssid,
        workLabel = s.folderLabel ?: stringResource(R.string.drive_work_work_default),
        initial = null,
        asking = true,
        onSave = { zone, stop -> viewModel.answer(ssid, zone, stop) },
        onLater = { viewModel.answer(ssid, null, false) },
    )
}

/** "Arbeitsfotos nach": a folder the person may write to (no vault); its path for the settings. */
@Composable
private fun WorkFolderChooser(onPicked: (Long, String) -> Unit, onNone: (() -> Unit)?, onDismiss: () -> Unit, viewModel: DriveFolderChooserViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.start() }
    Box {
        FolderPickerDialog(
            title = stringResource(R.string.drive_work_choose_title),
            areas = state.areas,
            folder = state.folder,
            confirm = stringResource(R.string.drive_work_choose_here),
            canPick = { it.writable && !it.node.encrypted },
            onBrowse = viewModel::browse,
            onPick = {
                val label = viewModel.pickedLabel() ?: return@FolderPickerDialog
                val id = viewModel.picked() ?: return@FolderPickerDialog
                onPicked(id, label)
            },
            onDismiss = onDismiss,
            onNewFolder = viewModel::createFolderIn,
            extra = onNone?.let { none -> { TextButton(onClick = none, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.drive_work_choose_none), color = MaterialTheme.colorScheme.error) } } },
        )
    }
}

// ─── on the photos ──────────────────────────────────────────────────

/** The yellow question mark of a photo to be reviewed (top right of its tile). */
@Composable
internal fun ReviewMark(modifier: Modifier = Modifier, size: Int = 24, description: String? = null) {
    val x = GateControlTheme.extraColors
    Box(
        modifier
            .then(if (description != null) Modifier.clearAndSetSemantics { contentDescription = description } else Modifier.clearAndSetSemantics { })
            .size(size.dp)
            .clip(CircleShape)
            .background(x.warn)
            .border(2.dp, Color.Black.copy(alpha = 0.45f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("?", color = Color(0xFF0D1015), fontWeight = FontWeight.Bold, fontSize = (size * 0.6f).sp)
    }
}

/** The small blue bag of a work photo (bottom left of its tile, in "Alle"). */
@Composable
internal fun WorkBag(modifier: Modifier = Modifier) {
    val x = GateControlTheme.extraColors
    Box(modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
        Icon(GcIcons.Briefcase, contentDescription = null, tint = x.blue, modifier = Modifier.size(13.dp))
    }
}

/**
 * The bar of a selection whose photos are all to be reviewed: the AI's hint, the big "Arbeit" (blue) and "Privat"
 * (green) in front, small Senden, Löschen, Mehr.
 */
@Composable
internal fun ReviewAssignBar(hint: DriveWorkLogic.Hint?, onWork: () -> Unit, onPrivate: () -> Unit, small: List<BarAction>) {
    val x = GateControlTheme.extraColors
    val context = LocalContext.current
    val barLabel = stringResource(R.string.drive_work_assign_bar)
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        GcRowDivider()
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hint != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                    Icon(GcIcons.Zap, contentDescription = null, tint = x.blue, modifier = Modifier.size(14.dp))
                    Text(
                        if (hint.work) stringResource(R.string.drive_work_hint_work, hint.sure) else stringResource(R.string.drive_work_hint_private, hint.sure),
                        style = MaterialTheme.typography.bodySmall,
                        color = x.muted,
                    )
                }
            }
            Row(Modifier.fillMaxWidth().semantics { contentDescription = barLabel }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BigAssign(stringResource(R.string.drive_work_to_work), GcIcons.Briefcase, x.blue, Color(0xFF0D1015), onWork, Modifier.weight(1.6f))
                BigAssign(stringResource(R.string.drive_work_to_private), GcIcons.House, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, onPrivate, Modifier.weight(1.6f))
                small.forEach { a ->
                    val why = whyText(a.can.why)
                    com.gatecontrol.android.ui.components.GcIconButton(
                        icon = a.icon,
                        contentDescription = a.label,
                        onClick = { if (a.can.ok) a.onClick() else if (why != null) Toast.makeText(context, why, Toast.LENGTH_SHORT).show() },
                        tint = if (!a.can.ok) x.faint else if (a.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun BigAssign(text: String, icon: ImageVector, bg: Color, fg: Color, onClick: () -> Unit, modifier: Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp), maxLines = 1)
    }
}

/** A hint line shown as a band (Prüfen, Aufräumen) with a button at the end. */
@Composable
internal fun WorkBand(text: String, button: String?, warn: Boolean, onClick: (() -> Unit)?) {
    val x = GateControlTheme.extraColors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (warn) x.warnBg else x.blueBg)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (warn) ReviewMark(size = 24) else Icon(GcIcons.Briefcase, contentDescription = null, tint = x.blue, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
        if (button != null && onClick != null) {
            TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 44.dp)) { Text(button, color = x.accentText, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/**
 * A chip of "Alle | Privat | Arbeit | Prüfen (N)": as the other filter chips, with the yellow count of [count];
 * [why] greys it out (a tap says why). The touch area is at least 48 dp.
 */
@Composable
internal fun WorkChip(text: String, selected: Boolean, onClick: () -> Unit, count: Int? = null, why: String? = null, description: String? = null) {
    val x = GateControlTheme.extraColors
    val context = LocalContext.current
    val enabled = why == null
    Row(
        Modifier
            .minimumInteractiveComponentSize()
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) x.accentBg else Color.Transparent)
            .border(1.dp, if (selected) Color.Transparent else x.border2, RoundedCornerShape(10.dp))
            .selectable(selected = selected, role = Role.Tab) { if (enabled) onClick() else Toast.makeText(context, why, Toast.LENGTH_SHORT).show() }
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = if (selected) x.accentText else if (enabled) x.muted else x.faint)
        if (count != null && count > 0) {
            Text(
                if (count > 999) "999+" else count.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0D1015),
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(x.warn).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}
