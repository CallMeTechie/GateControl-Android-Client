package com.gatecontrol.android.ui.drive

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.gatecontrol.android.drive.DArea
import com.gatecontrol.android.drive.DDeadlineCalendars
import com.gatecontrol.android.drive.DDeadlineTarget
import com.gatecontrol.android.drive.DDeadlineTargetRow
import com.gatecontrol.android.drive.DriveDeadlineTargets
import com.gatecontrol.android.drive.DMe
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveCache
import com.gatecontrol.android.drive.DriveLive
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.DriveSettings
import com.gatecontrol.android.drive.DriveSettingsLogic
import com.gatecontrol.android.drive.DriveSettingsValues
import com.gatecontrol.android.drive.DriveUploadNetwork
import com.gatecontrol.android.drive.backup.DriveBackup
import com.gatecontrol.android.drive.backup.DriveBackupConfig
import com.gatecontrol.android.drive.backup.DriveBackupFolder
import com.gatecontrol.android.drive.backup.DriveBackupWork
import com.gatecontrol.android.drive.backup.DriveFolderBackup
import com.gatecontrol.android.drive.backup.DriveFolderBackupWork
import com.gatecontrol.android.drive.vault.DriveVaultSession
import com.gatecontrol.android.ui.components.DeviceAuthResult
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.components.rememberGcDeviceAuth
import com.gatecontrol.android.ui.settings.SettingsGroup
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The Drive settings (Drive 1.11): kept on this phone, except the AI per area (the server's). */
@HiltViewModel
class DriveSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: DriveApi,
    val live: DriveLive,
    private val settings: DriveSettings,
    private val network: DriveUploadNetwork,
    private val cache: DriveCache,
    private val session: DriveVaultSession,
    private val backup: DriveBackup,
    private val folderBackup: DriveFolderBackup,
) : ViewModel() {
    val values: StateFlow<DriveSettingsValues> = settings.values
    val backupConfig: StateFlow<DriveBackupConfig> = backup.config
    val backupStats: StateFlow<DriveBackup.Stats> = backup.stats
    val folders: StateFlow<List<DriveBackupFolder>> = folderBackup.folders

    private val _areas = MutableStateFlow<List<DArea>>(emptyList())
    val areas: StateFlow<List<DArea>> = _areas.asStateFlow()

    /** Drive 1.17 (`deadlineMailCalendar`): my target per area for new deadlines; null while not known (then the switch). */
    private val _targets = MutableStateFlow<List<DDeadlineTargetRow>?>(null)
    val targets: StateFlow<List<DDeadlineTargetRow>?> = _targets.asStateFlow()
    private val _calendars = MutableStateFlow(DDeadlineCalendars.NONE)
    val calendars: StateFlow<DDeadlineCalendars> = _calendars.asStateFlow()

    private val _cacheSize = MutableStateFlow<Long?>(null)
    val cacheSize: StateFlow<Long?> = _cacheSize.asStateFlow()

    /** This device for the vaults: its safety number and the vaults it holds keys for. */
    data class VaultInfo(val safetyNumber: String?, val approved: List<String>)

    private val _vaultInfo = MutableStateFlow(VaultInfo(null, emptyList()))
    val vaultInfo: StateFlow<VaultInfo> = _vaultInfo.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _sort = MutableStateFlow(DriveViewPref.sort(context))
    internal val sort: StateFlow<DriveLogic.FolderSort> = _sort.asStateFlow()
    private val _grid = MutableStateFlow(DriveViewPref.grid(context))
    val grid: StateFlow<Boolean> = _grid.asStateFlow()
    private val _cameraOnly = MutableStateFlow(DriveViewPref.cameraOnly(context))
    val cameraOnly: StateFlow<Boolean> = _cameraOnly.asStateFlow()

    init {
        if (live.me.value == null) viewModelScope.launch { live.current() }
    }

    fun clearMessage() { _message.value = null }

    /** The screen shows (again): what may have changed elsewhere. */
    fun load() {
        viewModelScope.launch { api.areas().valueOrNull()?.let { _areas.value = DriveLogic.sortAreas(it) } }
        viewModelScope.launch { _cacheSize.value = cache.size() }
        viewModelScope.launch {
            val me = live.me.value ?: live.current()
            if (me?.has(DMe.FEATURE_DEADLINE_MAIL_CALENDAR) != true) {
                _targets.value = null
                return@launch
            }
            api.deadlineCalendars().valueOrNull()?.let { _calendars.value = it }
            api.deadlineTargets().valueOrNull()?.let { _targets.value = it }
        }
        viewModelScope.launch {
            val safety = withContext(Dispatchers.IO) { runCatching { session.safetyNumber() }.getOrNull() }
            _vaultInfo.value = VaultInfo(safety, _vaultInfo.value.approved)
            if (live.me.value?.has(DMe.FEATURE_VAULT) != true) return@launch
            val list = api.vaults().valueOrNull() ?: return@launch
            val approved = withContext(Dispatchers.IO) { list.filter { session.approved(it) }.map { session.label(it) } }
            _vaultInfo.update { it.copy(approved = approved.map { n -> n ?: "" }) }
        }
    }

    /** The phone has a screen lock (needed for the vaults). */
    fun deviceSecure(): Boolean = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** "Mit Fingerabdruck öffnen" (after the person confirmed): the device key moves under the matching Keystore key. */
    fun setBiometric(on: Boolean) {
        viewModelScope.launch {
            if (session.protectDeviceKey(on)) {
                settings.update { it.copy(vaultBiometric = on) }
            } else {
                _message.value = "device_key"
            }
        }
    }

    fun setAutoLock(minutes: Int) = settings.update { it.copy(autoLockMinutes = minutes) }

    fun setLockOnLeave(on: Boolean) = settings.update { it.copy(lockOnLeave = on) }

    fun setNotifyDeadlines(on: Boolean) = settings.update { it.copy(notifyDeadlines = on) }

    fun setNotifyVault(on: Boolean) = settings.update { it.copy(notifyVaultRequests = on) }

    /** "Hochladen nur im WLAN": the queue and the backups follow at once. */
    fun setWifiOnly(on: Boolean) {
        settings.update { it.copy(wifiOnly = on) }
        network.update()
        DriveBackupWork.apply(context, backup.config.value)
        DriveFolderBackupWork.apply(context, folderBackup.active(), folderBackup.settings.value)
    }

    fun clearCache() {
        viewModelScope.launch {
            cache.clear()
            _cacheSize.value = cache.size()
            _message.value = "cache_cleared"
        }
    }

    internal fun setSort(value: DriveLogic.FolderSort) {
        _sort.value = value
        DriveViewPref.setSort(context, value)
    }

    fun setGrid(on: Boolean) {
        _grid.value = on
        DriveViewPref.setGrid(context, on)
    }

    fun setCameraOnly(on: Boolean) {
        _cameraOnly.value = on
        DriveViewPref.setCameraOnly(context, on)
    }

    /** "KI für Dokumente" of [area] on or off (the write right); the switch shows the new setting at once. */
    fun setDocAi(area: DArea, on: Boolean) {
        val before = area.docAi
        fun put(value: Boolean?) = _areas.update { list -> list.map { if (it.id == area.id) it.copy(docAi = value) else it } }
        put(on)
        viewModelScope.launch {
            when (val r = api.setAreaDocAi(area.id, on)) {
                is DResult.Ok -> {
                    put(r.value)
                    // the targets follow the areas with AI for documents
                    if (_targets.value != null) api.deadlineTargets().valueOrNull()?.let { _targets.value = it }
                }
                else -> {
                    put(before)
                    _message.value = r.errorCode()
                }
            }
        }
    }

    /** "Fristen aus <Bereich>" (Drive 1.17, `deadlineMailCalendar`): my target of [areaId], shown at once. */
    fun setDeadlineTarget(areaId: String, target: DDeadlineTarget?) {
        val before = _targets.value ?: return
        _targets.value = DriveDeadlineTargets.put(before, areaId, target)
        viewModelScope.launch {
            when (val r = api.setDeadlineTarget(areaId, target)) {
                is DResult.Ok -> _targets.value = r.value.ifEmpty { _targets.value }
                else -> {
                    _targets.value = before
                    _message.value = r.errorCode()
                }
            }
        }
    }

    /** "Fristen aus <Bereich>" as a switch into the family calendar of [area] (Drive 1.14, `deadlineCalendar`, the write right). */
    fun setDeadlinesToCalendar(area: DArea, on: Boolean) {
        val before = area.deadlinesToCalendar
        fun put(value: Boolean?) = _areas.update { list -> list.map { if (it.id == area.id) it.copy(deadlinesToCalendar = value) else it } }
        put(on)
        viewModelScope.launch {
            when (val r = api.setAreaDeadlinesToCalendar(area.id, on)) {
                is DResult.Ok -> put(r.value)
                else -> {
                    put(before)
                    _message.value = r.errorCode()
                }
            }
        }
    }
}

/**
 * "Drive-Einstellungen" (Drive 1.11, the gear of the Drive menu; the app settings' row "Drive" leads here): backup,
 * vaults, AI for documents, the view, the data on this phone, notifications.
 */
@Composable
fun DriveSettingsScreen(
    onBack: () -> Unit,
    onBackup: () -> Unit,
    onFolders: () -> Unit,
    onPlaces: () -> Unit = {},
    viewModel: DriveSettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val x = GateControlTheme.extraColors
    val values by viewModel.values.collectAsStateWithLifecycle()
    val me by viewModel.live.me.collectAsStateWithLifecycle()
    val areas by viewModel.areas.collectAsStateWithLifecycle()
    val cacheSize by viewModel.cacheSize.collectAsStateWithLifecycle()
    val vaultInfo by viewModel.vaultInfo.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val backup by viewModel.backupConfig.collectAsStateWithLifecycle()
    val stats by viewModel.backupStats.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val grid by viewModel.grid.collectAsStateWithLifecycle()
    val cameraOnly by viewModel.cameraOnly.collectAsStateWithLifecycle()
    val targets by viewModel.targets.collectAsStateWithLifecycle()
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()
    var pickTarget by remember { mutableStateOf<String?>(null) }
    val auth = rememberGcDeviceAuth()
    var secure by remember { mutableStateOf(viewModel.deviceSecure()) }
    var askOff by remember { mutableStateOf(false) }
    var pickLock by remember { mutableStateOf(false) }
    var pickSort by remember { mutableStateOf(false) }
    LifecycleResumeEffect(viewModel) {
        secure = viewModel.deviceSecure()
        viewModel.load()
        onPauseOrDispose { }
    }
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, settingsText(context, it), Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }
    val confirmTitle = stringResource(R.string.drive_vault_gate_prompt)
    /** The switch "Mit Fingerabdruck öffnen": either way only after the person confirmed. */
    val switchBiometric: (Boolean) -> Unit = { on ->
        auth.authenticate(confirmTitle, null) { r ->
            when (r) {
                DeviceAuthResult.SUCCESS -> viewModel.setBiometric(on)
                DeviceAuthResult.CANCELLED -> Unit
                DeviceAuthResult.FAILED, DeviceAuthResult.UNAVAILABLE ->
                    Toast.makeText(context, R.string.drive_vault_gate_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        GcSubpageBar(stringResource(R.string.drive_settings_title), onBack = onBack, backDescription = stringResource(R.string.drive_back))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 1. Sicherung
            SettingsGroup(stringResource(R.string.drive_settings_backup)) {
                GcListRow(
                    title = stringResource(R.string.drive_backup_title),
                    description = if (backup.enabled) {
                        pluralStringResource(R.plurals.drive_backup_saved, stats.saved, stats.saved) + " · " +
                            stringResource(if (backup.wifiOnly) R.string.drive_backup_wifi_short else R.string.drive_backup_mobile_short)
                    } else {
                        stringResource(R.string.drive_backup_off_text)
                    },
                    onClick = onBackup,
                    leading = { Icon(GcIcons.Camera, contentDescription = null, tint = x.accentText, modifier = Modifier.size(22.dp)) },
                    trailing = { GcChevron() },
                )
                GcRowDivider()
                GcListRow(
                    title = stringResource(R.string.drive_folders_title),
                    description = if (folders.isEmpty()) {
                        stringResource(R.string.drive_folders_off_text)
                    } else {
                        pluralStringResource(R.plurals.drive_folders_count, folders.size, folders.size)
                    },
                    onClick = onFolders,
                    leading = { Icon(GcIcons.Folder, contentDescription = null, tint = x.accentText, modifier = Modifier.size(22.dp)) },
                    trailing = { GcChevron() },
                )
            }

            // Drive 1.16: places and people of the photos
            if (me?.has(DMe.FEATURE_PHOTO_PLACES) == true || me?.has(DMe.FEATURE_PHOTO_PEOPLE) == true) {
                SettingsGroup(stringResource(R.string.drive_mode_photos)) {
                    GcListRow(
                        title = stringResource(R.string.drive_pp_settings_title),
                        description = stringResource(R.string.drive_pp_settings_row),
                        onClick = onPlaces,
                        leading = { Icon(GcIcons.Pin, contentDescription = null, tint = x.accentText, modifier = Modifier.size(22.dp)) },
                        trailing = { GcChevron() },
                    )
                }
            }

            // 2. Tresore
            if (me?.has(DMe.FEATURE_VAULT) == true) {
                SettingsGroup(stringResource(R.string.drive_vaults)) {
                    if (!secure) {
                        GcBanner(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), tone = GcTone.Warn, icon = GcIcons.Alert) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(stringResource(R.string.drive_vault_no_lock), style = MaterialTheme.typography.bodyMedium)
                                GcOutlineButton(stringResource(R.string.drive_vault_no_lock_settings), onClick = {
                                    runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                }, icon = GcIcons.Settings)
                            }
                        }
                    }
                    GcSwitchRow(
                        label = stringResource(R.string.drive_settings_vault_biometric),
                        description = stringResource(R.string.drive_settings_vault_biometric_desc),
                        checked = values.vaultBiometric,
                        onCheckedChange = { on -> if (on) switchBiometric(true) else askOff = true },
                        icon = GcIcons.Fingerprint,
                        enabled = secure,
                    )
                    if (!values.vaultBiometric) {
                        GcBanner(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), tone = GcTone.Warn, icon = GcIcons.Alert) {
                            Text(stringResource(R.string.drive_settings_vault_biometric_off), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    GcRowDivider()
                    GcListRow(
                        title = stringResource(R.string.drive_settings_vault_auto_lock),
                        description = stringResource(R.string.drive_settings_vault_auto_lock_desc),
                        onClick = { pickLock = true },
                        leading = { Icon(GcIcons.Clock, contentDescription = null, tint = x.accentText, modifier = Modifier.size(22.dp)) },
                        trailing = {
                            Text(
                                pluralStringResource(R.plurals.drive_settings_vault_after, values.autoLockMinutes, values.autoLockMinutes),
                                style = MaterialTheme.typography.labelMedium,
                                color = x.muted,
                            )
                        },
                    )
                    GcRowDivider()
                    GcSwitchRow(
                        label = stringResource(R.string.drive_settings_vault_leave),
                        description = stringResource(R.string.drive_settings_vault_leave_desc),
                        checked = values.lockOnLeave,
                        onCheckedChange = viewModel::setLockOnLeave,
                        icon = GcIcons.Lock,
                    )
                    GcRowDivider()
                    GcListRow(
                        title = stringResource(R.string.drive_settings_vault_safety),
                        description = stringResource(R.string.drive_settings_vault_safety_desc),
                        leading = { Icon(GcIcons.Key, contentDescription = null, tint = x.accentText, modifier = Modifier.size(22.dp)) },
                    )
                    vaultInfo.safetyNumber?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = MonoFontFamily),
                            modifier = Modifier.padding(start = 52.dp, end = 16.dp, bottom = 12.dp),
                        )
                    }
                    GcRowDivider()
                    val approved = vaultInfo.approved
                    val unnamed = stringResource(R.string.drive_vault)
                    GcListRow(
                        title = stringResource(R.string.drive_settings_vault_approved),
                        description = if (approved.isEmpty()) {
                            stringResource(R.string.drive_settings_vault_approved_none)
                        } else {
                            approved.map { it.ifEmpty { unnamed } }.joinToString(", ")
                        },
                        leading = { Icon(GcIcons.Vault, contentDescription = null, tint = x.accentText, modifier = Modifier.size(22.dp)) },
                        trailing = if (approved.isNotEmpty()) ({
                            Text(pluralStringResource(R.plurals.drive_settings_vault_count, approved.size, approved.size), style = MaterialTheme.typography.labelMedium, color = x.muted)
                        }) else null,
                    )
                }
            }

            // 3. KI für Dokumente (per area, the server's; the write right)
            val aiAreas = areas.filter { it.writable && it.docAi != null }
            if (me?.has(DMe.FEATURE_DOC_AI) == true && aiAreas.isNotEmpty()) {
                SettingsGroup(stringResource(R.string.drive_area_doc_ai)) {
                    aiAreas.forEachIndexed { i, a ->
                        if (i > 0) GcRowDivider()
                        GcSwitchRow(
                            label = a.label,
                            description = stringResource(R.string.drive_area_doc_ai_desc, a.label),
                            checked = a.docAi == true,
                            onCheckedChange = { on -> viewModel.setDocAi(a, on) },
                            icon = GcIcons.Receipt,
                        )
                        val row = targets?.firstOrNull { it.areaId == a.id }
                        if (a.docAi == true && row != null) {
                            // Drive 1.17 (`deadlineMailCalendar`): my target of the area, the family calendar or a mail calendar
                            GcRowDivider()
                            GcListRow(
                                title = stringResource(R.string.drive_dl_from, a.label),
                                description = stringResource(R.string.drive_dl_from_desc),
                                onClick = { pickTarget = a.id },
                                leading = { Icon(GcIcons.Calendar, contentDescription = null, tint = x.accentText, modifier = Modifier.size(22.dp)) },
                                trailing = {
                                    Text(
                                        deadlineTargetText(row.target, calendars),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = x.muted,
                                        modifier = Modifier.widthIn(max = 160.dp),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        } else if (me?.has(DMe.FEATURE_DEADLINE_CALENDAR) == true && a.docAi == true && a.deadlinesToCalendar != null) {
                            // Drive 1.14 (`deadlineCalendar`): new deadlines of the area into the family calendar on their own
                            GcRowDivider()
                            GcSwitchRow(
                                label = stringResource(R.string.drive_dl_from, a.label),
                                description = stringResource(R.string.drive_dl_from_switch_desc),
                                checked = a.deadlinesToCalendar == true,
                                onCheckedChange = { on -> viewModel.setDeadlinesToCalendar(a, on) },
                                icon = GcIcons.Calendar,
                            )
                        }
                    }
                    GcRowDivider()
                    Text(
                        stringResource(R.string.drive_settings_ai_vaults),
                        style = MaterialTheme.typography.bodySmall,
                        color = x.muted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

            // 4. Ansicht
            SettingsGroup(stringResource(R.string.drive_settings_view)) {
                GcListRow(
                    title = stringResource(R.string.drive_sort),
                    onClick = { pickSort = true },
                    leading = { Icon(GcIcons.Sliders, contentDescription = null, tint = x.accentText, modifier = Modifier.size(22.dp)) },
                    trailing = { Text(sortLabel(sort), style = MaterialTheme.typography.labelMedium, color = x.muted) },
                )
                GcRowDivider()
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.drive_settings_view_layout), style = MaterialTheme.typography.titleMedium)
                    GcSegmented(
                        options = listOf(false to stringResource(R.string.drive_settings_view_list), true to stringResource(R.string.drive_settings_view_grid)),
                        selected = grid,
                        onSelect = viewModel::setGrid,
                    )
                }
                if (me?.has(DMe.FEATURE_PHOTOS_FOLDER) == true) {
                    GcRowDivider()
                    GcSwitchRow(
                        label = stringResource(R.string.drive_settings_view_camera),
                        description = null,
                        checked = cameraOnly,
                        onCheckedChange = viewModel::setCameraOnly,
                        icon = GcIcons.Camera,
                    )
                }
            }

            // 5. Daten auf diesem Gerät
            SettingsGroup(stringResource(R.string.drive_settings_device)) {
                GcListRow(
                    title = stringResource(R.string.drive_settings_cache),
                    description = stringResource(R.string.drive_settings_cache_desc, cacheSize?.let { DriveLogic.size(it) } ?: "…"),
                    leading = { Icon(GcIcons.Archive, contentDescription = null, tint = x.accentText, modifier = Modifier.size(22.dp)) },
                    trailing = { TextButton(onClick = viewModel::clearCache, enabled = (cacheSize ?: 0L) > 0L) { Text(stringResource(R.string.drive_settings_cache_clear)) } },
                )
                GcRowDivider()
                GcSwitchRow(
                    label = stringResource(R.string.drive_settings_wifi),
                    description = stringResource(R.string.drive_settings_wifi_desc),
                    checked = values.wifiOnly,
                    onCheckedChange = viewModel::setWifiOnly,
                    icon = GcIcons.Wifi,
                )
            }

            // 6. Benachrichtigungen (Drive's pushes, filtered on this phone)
            SettingsGroup(stringResource(R.string.drive_settings_notify)) {
                if (me?.has(DMe.FEATURE_DOC_AI) == true) {
                    GcSwitchRow(
                        label = stringResource(R.string.drive_settings_notify_deadlines),
                        description = null,
                        checked = values.notifyDeadlines,
                        onCheckedChange = viewModel::setNotifyDeadlines,
                        icon = GcIcons.Calendar,
                    )
                    GcRowDivider()
                }
                GcSwitchRow(
                    label = stringResource(R.string.drive_settings_notify_vault),
                    description = stringResource(R.string.drive_settings_notify_vault_desc),
                    checked = values.notifyVaultRequests,
                    onCheckedChange = viewModel::setNotifyVault,
                    icon = GcIcons.Bell,
                )
            }
        }
    }

    if (askOff) {
        AlertDialog(
            onDismissRequest = { askOff = false },
            title = { Text(stringResource(R.string.drive_settings_vault_biometric_off_title)) },
            text = { Text(stringResource(R.string.drive_settings_vault_biometric_off)) },
            confirmButton = { TextButton(onClick = { askOff = false; switchBiometric(false) }) { Text(stringResource(R.string.drive_settings_vault_biometric_off_go)) } },
            dismissButton = { TextButton(onClick = { askOff = false }) { Text(stringResource(R.string.drive_cancel)) } },
        )
    }
    if (pickLock) {
        ChoiceDialog(
            title = stringResource(R.string.drive_settings_vault_auto_lock),
            options = DriveSettingsLogic.AUTO_LOCK_CHOICES.map { it to pluralStringResource(R.plurals.drive_settings_vault_after, it, it) },
            selected = values.autoLockMinutes,
            onSelect = { pickLock = false; viewModel.setAutoLock(it) },
            onDismiss = { pickLock = false },
        )
    }
    pickTarget?.let { id ->
        val row = targets?.firstOrNull { it.areaId == id }
        if (row == null) {
            pickTarget = null
        } else {
            DeadlineTargetDialog(
                title = stringResource(R.string.drive_dl_from, areas.firstOrNull { it.id == id }?.label ?: row.label),
                groups = DriveDeadlineTargets.groups(calendars, mailFeature = me?.has(DMe.FEATURE_DEADLINE_MAIL_CALENDAR) == true),
                selected = row.target,
                onSelect = { t -> pickTarget = null; viewModel.setDeadlineTarget(id, t) },
                onDismiss = { pickTarget = null },
            )
        }
    }
    if (pickSort) {
        val keys = listOf("name", "mtime", "size", "type")
        val options = keys.flatMap { k -> listOf(DriveLogic.FolderSort(k, false), DriveLogic.FolderSort(k, true)) }
        ChoiceDialog(
            title = stringResource(R.string.drive_sort),
            options = options.map { it to sortLabel(it) },
            selected = sort,
            onSelect = { pickSort = false; viewModel.setSort(it) },
            onDismiss = { pickSort = false },
        )
    }
}

/** A message of the settings as a sentence (outside of composition). */
private fun settingsText(context: Context, code: String): String = when (code) {
    "cache_cleared" -> context.getString(R.string.drive_settings_cache_cleared)
    "device_key" -> context.getString(R.string.drive_settings_vault_key_failed)
    else -> driveText(context, code)
}

/** "Name, aufsteigend" and so on. */
@Composable
private fun sortLabel(s: DriveLogic.FolderSort): String {
    val key = stringResource(
        when (s.key) {
            "mtime" -> R.string.drive_sort_date
            "size" -> R.string.drive_sort_size
            "type" -> R.string.drive_sort_type
            else -> R.string.drive_sort_name
        },
    )
    return key + ", " + stringResource(if (s.desc) R.string.drive_sort_desc else R.string.drive_sort_asc)
}

/** One choice of several, as radio rows (≥ 48 dp). */
@Composable
private fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                options.forEach { (value, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = value == selected, role = Role.RadioButton, onClick = { onSelect(value) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.drive_cancel)) } },
    )
}
