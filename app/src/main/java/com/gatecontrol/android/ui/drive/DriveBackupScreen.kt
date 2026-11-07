package com.gatecontrol.android.ui.drive

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DriveMediaAccess
import com.gatecontrol.android.drive.backup.DriveBackup
import com.gatecontrol.android.drive.backup.DriveBackupConfig
import com.gatecontrol.android.drive.backup.DriveBackupLogic
import com.gatecontrol.android.drive.backup.DriveBackupWork
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Settings and state of the photo backup. */
@HiltViewModel
class DriveBackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val backup: DriveBackup,
) : ViewModel() {

    private val _pending = MutableStateFlow<Int?>(null)
    val pending: StateFlow<Int?> = _pending.asStateFlow()
    private val _pendingVideos = MutableStateFlow<Int?>(null)
    /** Older videos still to catch up ("Alle Videos"); null: none. */
    val pendingVideos: StateFlow<Int?> = _pendingVideos.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * "Neue Fotos werden jetzt unter Bilder/Kamera-Uploads gesichert …": once for a login that had chosen a folder of
     * its own before (shown while these settings are open, then never again).
     */
    val targetNotice: Boolean = backup.takeTargetNotice()

    fun refresh() {
        viewModelScope.launch {
            _pending.value = backup.pending()
            _pendingVideos.value = backup.pendingVideos()
        }
    }

    /** What the app may see of the gallery (photos, and videos with "Auch Videos"). */
    fun access(videos: Boolean): DriveMediaAccess.Access = DriveMediaAccess.of(context, videos)

    /** "Auch Videos" on: ask which videos when the backup has a mark (else the first switch-on asks for both). */
    fun videosNeedChoice(): Boolean = backup.videosNeedChoice()

    fun videosOn(all: Boolean) {
        backup.videosOn(all)
        refresh()
    }

    fun videosOff() {
        backup.videosOff()
        refresh()
    }

    /** "Erneut versuchen": the skipped ones go into the next pass. */
    fun retrySkipped() = backup.retrySkipped()

    /** The backup never ran for this login: ask whether the photos there are already go up too. */
    fun firstTime(): Boolean = backup.mark() == null

    /** Switches it on (into Kamera-Uploads; the folders are made by the first run). */
    fun enable(all: Boolean) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                backup.enable(DriveBackupLogic.startMark(backup.mark(), all, backup.markNow()))
                _pending.value = backup.pending()
            } finally {
                _busy.value = false
            }
        }
    }

    fun disable() = backup.update { it.copy(enabled = false) }

    fun set(f: (DriveBackupConfig) -> DriveBackupConfig) {
        backup.update(f)
        refresh()
    }

    fun runNow() = DriveBackupWork.runNow(context)
}

/** What the photo backup needs: the photos (and videos), their original location data, the progress notification. */
private fun mediaPermissions(videos: Boolean): Array<String> = DriveMediaAccess.permissions(videos)

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** A backup code as a sentence. */
internal fun backupText(context: Context, code: String): String = when (code) {
    "no_permission" -> context.getString(R.string.drive_backup_error_permission)
    "no_folder", "not_found" -> context.getString(R.string.drive_backup_error_camera)
    else -> driveText(context, code)
}

/** Why the backup left a file out ("Übersprungen"), in plain words. */
internal fun skipText(context: Context, code: String): String = when (code) {
    "read" -> context.getString(R.string.drive_backup_skip_read)
    "empty" -> context.getString(R.string.drive_backup_skip_empty)
    "too_large", "http_413" -> context.getString(R.string.drive_backup_skip_too_large)
    "unsupported_type", "http_415" -> context.getString(R.string.drive_backup_skip_type)
    com.gatecontrol.android.scan.MediaScanJson.INFECTED -> context.getString(R.string.drive_backup_skip_infected)
    "invalid", "invalid_answer", "upload_invalid" -> context.getString(R.string.drive_backup_skip_invalid)
    else -> context.getString(R.string.drive_backup_skip_other, code)
}

/** Drive tab › Foto-Sicherung. */
@Composable
fun DriveBackupScreen(onBack: () -> Unit, onCleanup: () -> Unit = {}, viewModel: DriveBackupViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val config by viewModel.backup.config.collectAsStateWithLifecycle()
    val stats by viewModel.backup.stats.collectAsStateWithLifecycle()
    val status by viewModel.backup.status.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val pendingVideos by viewModel.pendingVideos.collectAsStateWithLifecycle()
    val skipped by viewModel.backup.skipped.collectAsStateWithLifecycle()
    val sent by viewModel.backup.sent.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var askVideos by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf(viewModel.targetNotice) }
    var askWhich by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    var canRead by remember { mutableStateOf(viewModel.backup.canRead()) }
    // what the app sees: everything, only picked photos, the photos but not the videos (Android 13+)
    var photoAccess by remember { mutableStateOf(viewModel.access(false)) }
    var videoAccess by remember { mutableStateOf(viewModel.access(true)) }
    val recheck = {
        canRead = viewModel.backup.canRead()
        photoAccess = viewModel.access(false)
        videoAccess = viewModel.access(true)
    }

    LifecycleResumeEffect(Unit) {
        recheck()
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val start = { if (viewModel.firstTime()) askWhich = true else viewModel.enable(all = true) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        recheck()
        denied = !canRead
        if (canRead && !config.enabled) start()
        viewModel.refresh()
    }
    val turnOn = {
        // full access first (with "Auch Videos" the videos too): "Ausgewählte Fotos" alone never shows new ones
        if (viewModel.access(config.videos) == DriveMediaAccess.Access.FULL) start() else permissions.launch(mediaPermissions(config.videos))
    }
    // "Auch Videos" on: the videos must be readable (READ_MEDIA_VIDEO) — asked for now, never assumed
    val videosOn = { all: Boolean ->
        viewModel.videosOn(all)
        if (viewModel.access(true) != DriveMediaAccess.Access.FULL) permissions.launch(mediaPermissions(true))
    }

    Column(Modifier.fillMaxSize()) {
        GcSubpageBar(stringResource(R.string.drive_backup_title), onBack = onBack, backDescription = stringResource(R.string.drive_back))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (notice) {
                GcBanner(tone = GcTone.Info, icon = GcIcons.Photo) {
                    Text(stringResource(R.string.drive_backup_moved), modifier = Modifier.weight(1f))
                    TextButton(onClick = { notice = false }) { Text(stringResource(R.string.drive_backup_dismiss)) }
                }
            }
            GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                GcSwitchRow(
                    label = stringResource(R.string.drive_backup_switch),
                    description = stringResource(R.string.drive_backup_switch_text),
                    checked = config.enabled,
                    enabled = !busy,
                    onCheckedChange = { on -> if (on) turnOn() else viewModel.disable() },
                )
            }

            if (busy) DriveLoading()
            if (denied || (config.enabled && !canRead)) {
                GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
                    Text(stringResource(R.string.drive_backup_error_permission), modifier = Modifier.weight(1f))
                    TextButton(onClick = { openAppSettings(context) }) { Text(stringResource(R.string.drive_backup_settings)) }
                }
            } else if (photoAccess == DriveMediaAccess.Access.PARTIAL || (config.videos && videoAccess != DriveMediaAccess.Access.FULL)) {
                // only picked photos (Android 14+), or the photos but not the videos: new ones stay invisible to the backup
                val videosOnly = photoAccess == DriveMediaAccess.Access.FULL
                GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
                    Text(stringResource(if (videosOnly) R.string.drive_media_videos_missing else R.string.drive_media_partial), modifier = Modifier.weight(1f))
                    TextButton(onClick = { permissions.launch(mediaPermissions(config.videos)) }) { Text(stringResource(R.string.drive_media_allow)) }
                }
            }

            if (config.enabled) {
                BackupStatusCard(config, stats, status, pending, pendingVideos, sent, onRun = viewModel::runNow)
            }
            if (skipped.isNotEmpty()) SkippedCard(skipped, onRetry = viewModel::retrySkipped)

            // Drive 1.13: work photos apart (targets, WLANs, the AI, on the phone)
            WorkPhotoSections(onCleanup = onCleanup)

            GcSectionLabel(stringResource(R.string.drive_work_section_when), Modifier.padding(horizontal = 4.dp))
            GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                GcSwitchRow(
                    label = stringResource(R.string.drive_backup_wifi),
                    description = stringResource(R.string.drive_backup_wifi_text),
                    checked = config.wifiOnly,
                    onCheckedChange = { v -> viewModel.set { it.copy(wifiOnly = v) } },
                )
                GcRowDivider()
                GcSwitchRow(
                    label = stringResource(R.string.drive_backup_charging),
                    description = null,
                    checked = config.chargingOnly,
                    onCheckedChange = { v -> viewModel.set { it.copy(chargingOnly = v) } },
                )
                GcRowDivider()
                GcSwitchRow(
                    label = stringResource(R.string.drive_backup_videos),
                    description = stringResource(R.string.drive_backup_videos_text),
                    checked = config.videos,
                    onCheckedChange = { v ->
                        when {
                            !v -> viewModel.videosOff()
                            // the backup ran before: which videos — the ones already on the phone too, or only new ones
                            viewModel.videosNeedChoice() -> askVideos = true
                            else -> videosOn(true)
                        }
                    },
                )
                GcRowDivider()
                GcSwitchRow(
                    label = stringResource(R.string.drive_backup_camera),
                    description = stringResource(R.string.drive_backup_camera_text),
                    checked = config.cameraOnly,
                    onCheckedChange = { v -> viewModel.set { it.copy(cameraOnly = v) } },
                )
            }
            Text(
                stringResource(R.string.drive_backup_note),
                style = MaterialTheme.typography.bodySmall,
                color = GateControlTheme.extraColors.muted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    if (askVideos) {
        AlertDialog(
            onDismissRequest = { askVideos = false },
            title = { Text(stringResource(R.string.drive_backup_videos_which_title)) },
            text = { Text(stringResource(R.string.drive_backup_videos_which_text)) },
            confirmButton = { TextButton(onClick = { askVideos = false; videosOn(true) }) { Text(stringResource(R.string.drive_backup_videos_which_all)) } },
            dismissButton = { TextButton(onClick = { askVideos = false; videosOn(false) }) { Text(stringResource(R.string.drive_backup_videos_which_new)) } },
        )
    }

    if (askWhich) {
        AlertDialog(
            onDismissRequest = { askWhich = false },
            title = { Text(stringResource(R.string.drive_backup_which_title)) },
            text = { Text(stringResource(R.string.drive_backup_which_text)) },
            confirmButton = { TextButton(onClick = { askWhich = false; viewModel.enable(all = true) }) { Text(stringResource(R.string.drive_backup_which_all)) } },
            dismissButton = { TextButton(onClick = { askWhich = false; viewModel.enable(all = false) }) { Text(stringResource(R.string.drive_backup_which_new)) } },
        )
    }
}

@Composable
private fun BackupStatusCard(
    config: DriveBackupConfig,
    stats: DriveBackup.Stats,
    status: DriveBackup.Status,
    pending: Int?,
    pendingVideos: Int?,
    sent: List<DriveBackup.Sent>,
    onRun: () -> Unit,
) {
    val context = LocalContext.current
    val x = GateControlTheme.extraColors
    GcCard {
        Text(pluralStringResource(R.plurals.drive_backup_saved, stats.saved, stats.saved), style = MaterialTheme.typography.titleMedium)
        when {
            status.running -> {
                LinearProgressIndicator(
                    progress = { if (status.total > 0) status.done.toFloat() / status.total else 0f },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                )
                Text(
                    stringResource(if (status.videos) R.string.drive_backup_videos_running else R.string.drive_backup_running, status.done + 1, status.total),
                    color = x.muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            stats.error != null -> Text(backupText(context, stats.error), color = x.warn, style = MaterialTheme.typography.bodyMedium)
            pending != null && pending > 0 -> Text(
                pluralStringResource(if (config.wifiOnly) R.plurals.drive_backup_waiting_wifi else R.plurals.drive_backup_waiting, pending, pending),
                color = x.muted,
                style = MaterialTheme.typography.bodyMedium,
            )
            pendingVideos != null && pendingVideos > 0 -> Unit
            else -> Text(stringResource(R.string.drive_backup_uptodate), color = x.muted, style = MaterialTheme.typography.bodyMedium)
        }
        // the older videos ("Alle Videos"), after the new photos
        if (!status.running && stats.error == null && pendingVideos != null && pendingVideos > 0) {
            Text(pluralStringResource(R.plurals.drive_backup_videos_waiting, pendingVideos, pendingVideos), color = x.muted, style = MaterialTheme.typography.bodyMedium)
        }
        // where the last ones went (Kamera-Uploads, or the work folder)
        if (sent.isNotEmpty()) {
            Text(stringResource(R.string.drive_backup_sent_title), style = MaterialTheme.typography.labelMedium, color = x.muted)
            val workDefault = stringResource(R.string.drive_work_work_default)
            sent.forEach { s ->
                val path = s.path.replace("/", " › ")
                val where = if (s.work) stringResource(R.string.drive_backup_sent_work, s.label ?: workDefault, path) else stringResource(R.string.drive_backup_sent_private, path)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if (s.video) GcIcons.Film else GcIcons.Photo, contentDescription = null, tint = x.muted, modifier = Modifier.size(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(where, style = MaterialTheme.typography.bodySmall, color = x.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (stats.lastRunAt > 0) {
            Text(
                stringResource(R.string.drive_backup_last, DateUtils.getRelativeTimeSpanString(stats.lastRunAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)),
                color = x.muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!status.running) GcOutlineButton(stringResource(R.string.drive_backup_now), onClick = onRun, icon = GcIcons.Refresh)
    }
}

/** "Übersprungen (N)": what the backup left out, each with its reason, and "Erneut versuchen". */
@Composable
private fun SkippedCard(list: List<com.gatecontrol.android.drive.backup.DriveBackupLogic.Skipped>, onRetry: () -> Unit) {
    val context = LocalContext.current
    val x = GateControlTheme.extraColors
    GcCard {
        Text(stringResource(R.string.drive_backup_skipped_title, list.size), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.drive_backup_skipped_text), color = x.muted, style = MaterialTheme.typography.bodySmall)
        list.take(SKIPPED_SHOWN).forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (s.video) GcIcons.Film else GcIcons.Photo, contentDescription = null, tint = x.warn, modifier = Modifier.size(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(skipText(context, s.code), style = MaterialTheme.typography.bodySmall, color = x.muted)
                }
            }
        }
        if (list.size > SKIPPED_SHOWN) Text(pluralStringResource(R.plurals.drive_backup_skipped_more, list.size - SKIPPED_SHOWN, list.size - SKIPPED_SHOWN), color = x.muted, style = MaterialTheme.typography.bodySmall)
        GcOutlineButton(stringResource(R.string.drive_backup_skipped_retry), onClick = onRetry, icon = GcIcons.Refresh)
    }
}

private const val SKIPPED_SHOWN = 20

/** The photo backup's state for its row in the settings. */
@HiltViewModel
class DriveBackupSettingsViewModel @Inject constructor(backup: DriveBackup, folderBackup: com.gatecontrol.android.drive.backup.DriveFolderBackup) : ViewModel() {
    val config: StateFlow<DriveBackupConfig> = backup.config
    val stats: StateFlow<DriveBackup.Stats> = backup.stats
    /** The phone folders backed up ("Ordner sichern"), for their row. */
    val folders: StateFlow<List<com.gatecontrol.android.drive.backup.DriveBackupFolder>> = folderBackup.folders
}
