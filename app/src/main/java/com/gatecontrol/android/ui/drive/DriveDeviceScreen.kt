package com.gatecontrol.android.ui.drive

import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.device.DeviceEntry
import com.gatecontrol.android.drive.device.DeviceFiles
import com.gatecontrol.android.drive.device.DeviceFilesLogic
import com.gatecontrol.android.drive.device.DeviceFilesLogic.Category
import com.gatecontrol.android.drive.device.DeviceFilesLogic.InDrive
import com.gatecontrol.android.drive.device.DeviceFilesLogic.Part
import com.gatecontrol.android.drive.device.DeviceFilesLogic.VolumeKind
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import javax.inject.Inject

@HiltViewModel
class DriveDeviceViewModel @Inject constructor(val files: DeviceFiles) : ViewModel() {
    val summary = files.summary
    private val _volumes = MutableStateFlow<List<DeviceFiles.Volume>>(emptyList())
    val volumes: StateFlow<List<DeviceFiles.Volume>> = _volumes.asStateFlow()
    private val _access = MutableStateFlow(files.hasAccess())
    /** "Zugriff auf alle Dateien" is given (asked again each time the page comes back). */
    val access: StateFlow<Boolean> = _access.asStateFlow()

    init {
        // a USB stick or an SD card attached or removed: the places again
        viewModelScope.launch { files.volumeChanges.collect { loadVolumes() } }
    }

    fun onResume() {
        _access.value = files.hasAccess()
        if (!_access.value) return
        loadVolumes()
        viewModelScope.launch { files.refresh() }
    }

    private fun loadVolumes() {
        viewModelScope.launch { _volumes.value = withContext(Dispatchers.IO) { files.volumes() } }
    }

    suspend fun inDrive(e: DeviceEntry): InDrive = files.inDrive(e)
}

/**
 * Drive › "Auf dem Gerät" (the hard disk beside "Übersicht · Dateien · Fotos"): the internal storage with its bar, the
 * categories, the places (internal, SD card, USB stick when attached) and "Zuletzt hinzugefügt" with "In Drive" /
 * "Nur hier". Without "Zugriff auf alle Dateien" it explains why it is needed (and asks), "Nicht jetzt" goes back.
 */
@Composable
fun DriveDeviceScreen(
    onBack: () -> Unit,
    onFolder: (String) -> Unit,
    onCategory: (String) -> Unit,
    onRecent: () -> Unit,
    viewModel: DriveDeviceViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val access by viewModel.access.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val volumes by viewModel.volumes.collectAsStateWithLifecycle()
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.onResume() }
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    var viewing by remember { mutableStateOf<DeviceEntry?>(null) }
    Column(Modifier.fillMaxSize()) {
        GcSubpageBar(stringResource(R.string.drive_dev_title), onBack = onBack, backDescription = stringResource(R.string.drive_back))
        if (!access) {
            DeviceAccessPage(
                play = !viewModel.files.available,
                onAllow = { openAllFilesAccess(context) { settings.launch(it) } },
                onLater = onBack,
                modifier = Modifier.weight(1f),
            )
            return@Column
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "usage") { UsageCard(summary) }
            item(key = "categories") {
                DeviceHead(stringResource(R.string.drive_dev_categories))
                Categories(summary) { c ->
                    if (c == Category.DOWNLOAD) {
                        onFolder(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path)
                    } else {
                        onCategory(c.key)
                    }
                }
            }
            item(key = "places") {
                DeviceHead(stringResource(R.string.drive_dev_places))
                Places(volumes, onFolder)
            }
            item(key = "recent") {
                DeviceHead(stringResource(R.string.drive_dev_recent), link = stringResource(R.string.drive_pp_all), onLink = onRecent)
                Recent(summary?.recent, ask = viewModel::inDrive, onOpen = { viewing = it })
                Text(
                    stringResource(R.string.drive_dev_in_drive_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = GateControlTheme.extraColors.muted,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
            }
        }
    }
    viewing?.let { e -> DeviceViewer(e, onClose = { viewing = null }) }
}

/** The head of a section, "Alle" ([link]) on the right. */
@Composable
internal fun DeviceHead(title: String, link: String? = null, onLink: (() -> Unit)? = null) {
    val x = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 16.dp, bottom = 4.dp).heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).semantics { heading() })
        if (link != null && onLink != null) {
            TextButton(onClick = onLink, modifier = Modifier.heightIn(min = 44.dp)) {
                Text(link, color = x.accentText, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun partColor(p: Part): Color {
    val x = GateControlTheme.extraColors
    return when (p) {
        Part.MEDIA -> x.blue
        Part.DOCUMENTS -> x.warn
        Part.AUDIO -> x.pro
        Part.OTHER -> x.muted
    }
}

private fun partLabel(p: Part): Int = when (p) {
    Part.MEDIA -> R.string.drive_dev_bar_media
    Part.DOCUMENTS -> R.string.drive_dev_bar_docs
    Part.AUDIO -> R.string.drive_dev_bar_audio
    Part.OTHER -> R.string.drive_dev_bar_other
}

/** "Interner Speicher · 187 GB von 256 GB belegt" with the bar of what takes the room. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsageCard(s: DeviceFiles.Summary?) {
    val x = GateControlTheme.extraColors
    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface).border(1.dp, x.border, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.drive_dev_internal), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(
                s?.let { stringResource(R.string.drive_dev_used, DriveLogic.size(it.used), DriveLogic.size(it.total)) } ?: stringResource(R.string.drive_dev_counting),
                style = MaterialTheme.typography.bodySmall,
                color = x.muted,
            )
        }
        Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(x.panel2)) {
            val parts = s?.bar.orEmpty().filter { it.second > 0.001f }
            parts.forEach { (p, f) -> Box(Modifier.fillMaxHeight().weight(f).background(partColor(p))) }
            val rest = 1f - parts.sumOf { it.second.toDouble() }.toFloat()
            if (rest > 0.001f) Spacer(Modifier.weight(rest))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Part.entries.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(partColor(p)))
                    Text(stringResource(partLabel(p)), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** The six tiles, three in a row: symbol, name, "146 · 3,1 GB". */
@Composable
private fun Categories(s: DeviceFiles.Summary?, onOpen: (Category) -> Unit) {
    val x = GateControlTheme.extraColors
    val numbers = remember { NumberFormat.getIntegerInstance() }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Category.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { c ->
                    val look = categoryLook(c)
                    val count = s?.categories?.get(c)
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, x.border, RoundedCornerShape(16.dp))
                            .clickable(role = Role.Button) { onOpen(c) }.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GcIconSquare(size = 40.dp, background = look.bg) {
                            Icon(look.icon, contentDescription = null, tint = look.fg, modifier = Modifier.size(20.dp))
                        }
                        Text(stringResource(categoryLabel(c)), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            count?.let { stringResource(R.string.drive_dev_count, numbers.format(it.files), DriveLogic.size(it.bytes)) } ?: stringResource(R.string.drive_dev_counting),
                            style = MaterialTheme.typography.bodySmall,
                            color = x.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DeviceCard(content: @Composable () -> Unit) {
    val x = GateControlTheme.extraColors
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, x.border, RoundedCornerShape(16.dp)),
    ) { content() }
}

internal fun volumeIcon(kind: VolumeKind) = when (kind) {
    VolumeKind.INTERNAL -> GcIcons.Smartphone
    VolumeKind.SD -> GcIcons.SdCard
    VolumeKind.USB -> GcIcons.Usb
}

/** The name of a place: "Interner Speicher", else Android's own name of the card or stick. */
@Composable
internal fun volumeName(v: DeviceFiles.Volume): String = when {
    v.kind == VolumeKind.INTERNAL -> stringResource(R.string.drive_dev_internal)
    v.label.isNotBlank() -> v.label
    v.kind == VolumeKind.USB -> stringResource(R.string.drive_dev_usb)
    else -> stringResource(R.string.drive_dev_sd)
}

/** Internal storage, SD cards, USB sticks; "USB-Stick · Erscheint, wenn einer angeschlossen ist" while there is none. */
@Composable
private fun Places(volumes: List<DeviceFiles.Volume>, onFolder: (String) -> Unit) {
    DeviceCard {
        val rows = volumes.size + if (volumes.none { it.kind == VolumeKind.USB }) 1 else 0
        var i = 0
        volumes.forEach { v ->
            PlaceRow(
                icon = volumeIcon(v.kind),
                title = volumeName(v),
                line = stringResource(if (v.readOnly) R.string.drive_dev_free_ro else R.string.drive_dev_free, DriveLogic.size(v.free)),
                onClick = { onFolder(v.path) },
                last = ++i == rows,
            )
        }
        if (volumes.none { it.kind == VolumeKind.USB }) {
            PlaceRow(GcIcons.Usb, stringResource(R.string.drive_dev_usb), stringResource(R.string.drive_dev_usb_hint), onClick = null, last = true)
        }
    }
}

@Composable
private fun PlaceRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, line: String, onClick: (() -> Unit)?, last: Boolean) {
    val x = GateControlTheme.extraColors
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GcIconSquare(size = 40.dp, background = x.panel2) {
                Icon(icon, contentDescription = null, tint = if (onClick != null) MaterialTheme.colorScheme.onSurface else x.faint, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = if (onClick != null) MaterialTheme.colorScheme.onSurface else x.muted)
                Text(line, style = MaterialTheme.typography.bodySmall, color = x.muted)
            }
            if (onClick != null) GcChevron()
        }
        if (!last) GcRowDivider()
    }
}

/** Where a file is, for "Zuletzt hinzugefügt": "Download", else its category ("Bilder"), else nothing. */
@Composable
private fun placeOf(e: DeviceEntry): String? {
    if (e.path.contains("/Download/")) return stringResource(R.string.drive_dev_cat_download)
    return DeviceFilesLogic.categoryOf(DeviceFilesLogic.typeOf(e))?.let { stringResource(categoryLabel(it)) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Recent(recent: List<DeviceEntry>?, ask: suspend (DeviceEntry) -> InDrive, onOpen: (DeviceEntry) -> Unit) {
    val x = GateControlTheme.extraColors
    DeviceCard {
        when {
            recent == null -> Text(stringResource(R.string.drive_dev_counting), color = x.muted, modifier = Modifier.padding(16.dp))
            recent.isEmpty() -> Text(stringResource(R.string.drive_dev_recent_empty), color = x.muted, modifier = Modifier.padding(16.dp))
            else -> recent.forEachIndexed { i, e ->
                val state = rememberInDrive(e, ask)
                Column {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { onOpen(e) }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        DeviceFileIcon(e, 40)
                        Column(Modifier.weight(1f)) {
                            Text(e.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(placeOf(e), deviceLine(e)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = x.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        InDriveLabel(state)
                    }
                    if (i < recent.size - 1) GcRowDivider()
                }
            }
        }
    }
}
