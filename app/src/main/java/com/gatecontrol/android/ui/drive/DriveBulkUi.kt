package com.gatecontrol.android.ui.drive

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DFolder
import com.gatecontrol.android.drive.DOpFile
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveBulk
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.DriveOps
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// ─── words ──────────────────────────────────────────────────────────

/** Why an action is greyed out, in words. */
@Composable
internal fun whyText(why: DriveBulk.Why?): String? = why?.let { stringResource(whyRes(it)) }

internal fun whyRes(why: DriveBulk.Why): Int = when (why) {
    DriveBulk.Why.READ_ONLY -> R.string.drive_why_read_only
    DriveBulk.Why.CHILD -> R.string.drive_why_child
    DriveBulk.Why.NEEDS_BULK -> R.string.drive_why_bulk
    DriveBulk.Why.NEEDS_COPY_SERVER -> R.string.drive_why_copy_server
    DriveBulk.Why.LINKS_OFF -> R.string.drive_why_links_off
    DriveBulk.Why.LINK_FILES_ONLY -> R.string.drive_why_link_files
    DriveBulk.Why.TOO_MANY -> R.string.drive_why_too_many
    DriveBulk.Why.ZIP_SHAPE -> R.string.drive_why_zip_shape
    DriveBulk.Why.FOLDERS_NEED_ZIP -> R.string.drive_why_folders_zip
    DriveBulk.Why.ONLY_ONE -> R.string.drive_why_only_one
    DriveBulk.Why.ONLY_FILES -> R.string.drive_why_only_files
    DriveBulk.Why.NOT_MEDIA -> R.string.drive_why_not_media
    DriveBulk.Why.VAULT -> R.string.drive_why_vault
    DriveBulk.Why.NO_OFFICE -> R.string.drive_why_office
    DriveBulk.Why.NO_ALBUMS -> R.string.drive_why_albums
}

/** "2 Dateien, 1 Ordner · 1,6 MB + Ordnerinhalt". */
@Composable
internal fun summaryText(s: DriveBulk.Summary): String {
    val parts = buildList {
        if (s.files > 0) add(pluralStringResource(R.plurals.drive_sel_files, s.files, s.files))
        if (s.folders > 0) add(pluralStringResource(R.plurals.drive_sel_folders, s.folders, s.folders))
    }.joinToString(", ")
    if (s.files == 0) return parts
    val size = DriveLogic.size(s.bytes) + if (s.folders > 0) " " + stringResource(R.string.drive_sel_folder_content) else ""
    return "$parts · $size"
}

/** A message of the bulk actions as a sentence (outside of composition). */
internal fun bulkText(context: Context, code: String): String {
    val n = code.substringAfter(':', "").toIntOrNull() ?: 0
    return when {
        code.startsWith("bulk_saved:") -> context.resources.getQuantityString(R.plurals.drive_bulk_saved, n, n)
        code == "bulk_save_failed" -> context.getString(R.string.drive_bulk_save_failed)
        code == "bulk_failed" -> context.getString(R.string.drive_bulk_failed)
        code.startsWith("bulk_some_blocked:") -> context.resources.getQuantityString(R.plurals.drive_bulk_some_blocked, n, n)
        code.startsWith("bulk_blocked:") -> context.getString(R.string.drive_bulk_blocked, reasonText(context, code.substringAfter(':')))
        code.startsWith("bulk_error:") -> reasonText(context, code.substringAfter(':'))
        code == "link_mixed" -> context.getString(R.string.drive_why_link_files)
        code == "zip_mixed" -> context.getString(R.string.drive_why_zip_shape)
        code == "too_many" -> context.getString(R.string.drive_why_too_many)
        code in setOf("into_itself", "root", "same_folder", "abandoned", "job_limit", "too_large") -> reasonText(context, code)
        else -> photosText(context, code)
    }
}

private fun reasonText(context: Context, reason: String): String = when (reason) {
    "into_itself" -> context.getString(R.string.drive_code_into_itself)
    "root" -> context.getString(R.string.drive_code_root)
    "same_folder" -> context.getString(R.string.drive_code_same_folder)
    "abandoned" -> context.getString(R.string.drive_code_abandoned)
    "job_limit" -> context.getString(R.string.drive_code_job_limit)
    "too_large" -> context.getString(R.string.drive_code_too_large)
    else -> driveText(context, reason)
}

private fun bulkDay(iso: String?): String? = iso?.let {
    runCatching { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(Instant.parse(it)) }.getOrNull()
}

// ─── the head and the bar of a selection ────────────────────────────

/**
 * The bar on top while items are ticked: ✕ (ends the selection), "N ausgewählt" with [line] below (the summary, or a
 * hint), "Alle" / "Keine" on the right ([onAll], none without it).
 */
@Composable
internal fun SelectionHead(count: Int, line: String?, allSelected: Boolean, onClose: () -> Unit, onAll: (() -> Unit)?) {
    val x = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GcIconButton(GcIcons.Close, contentDescription = stringResource(R.string.drive_sel_end), onClick = onClose, tint = MaterialTheme.colorScheme.onSurface)
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                pluralStringResource(R.plurals.drive_sel_count, count, count),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                // TalkBack says the new count when it changes
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite; heading() },
            )
            // one line: the head is as high whatever it says (SelectionSwap keeps the page below in place)
            if (!line.isNullOrBlank()) Text(line, style = MaterialTheme.typography.bodySmall, color = x.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onAll != null) {
            val desc = stringResource(if (allSelected) R.string.drive_sel_none_desc else R.string.drive_sel_all_desc)
            TextButton(onClick = onAll, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = desc }) {
                Text(stringResource(if (allSelected) R.string.drive_sel_none else R.string.drive_sel_all), color = x.accentText, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** An action of the bar below a selection; [can] greys it out (a tap then says why). */
internal data class BarAction(val icon: ImageVector, val label: String, val can: DriveBulk.Can, val danger: Boolean = false, val onClick: () -> Unit)

/** The bar below a selection (in place of the round plus): icon over a short label, each ≥ 56 dp high. */
@Composable
internal fun SelectionActionBar(actions: List<BarAction>, modifier: Modifier = Modifier) {
    val x = GateControlTheme.extraColors
    val context = LocalContext.current
    val barLabel = stringResource(R.string.drive_sel_actions)
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).semantics { contentDescription = barLabel }) {
        GcRowDivider()
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            actions.forEach { a ->
                val why = whyText(a.can.why)
                val tint = when {
                    !a.can.ok -> x.faint
                    a.danger -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }
                val unavailable = why?.let { stringResource(R.string.drive_sel_unavailable, a.label, it) }
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClickLabel = a.label) {
                            if (a.can.ok) a.onClick() else if (why != null) Toast.makeText(context, why, Toast.LENGTH_SHORT).show()
                        }
                        .semantics { if (unavailable != null) stateDescription = unavailable }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(a.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                    Text(a.label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

/** The round tick of a row or tile while selecting: empty, or filled with a check. */
@Composable
internal fun SelectCheck(selected: Boolean, modifier: Modifier = Modifier, onImage: Boolean = false) {
    val x = GateControlTheme.extraColors
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (selected) accent else if (onImage) Color.Black.copy(alpha = 0.25f) else Color.Transparent)
            .border(2.dp, if (selected) accent else if (onImage) Color.White else x.muted, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(GcIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
    }
}

/** "neu hier" next to an item that just arrived. */
@Composable
internal fun NewHereBadge() {
    val x = GateControlTheme.extraColors
    Text(
        stringResource(R.string.drive_new_here),
        style = MaterialTheme.typography.labelSmall,
        color = x.accentText,
        modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(6.dp)).background(x.accentBg).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ─── "Mehr" ─────────────────────────────────────────────────────────

/** An entry of the "Mehr" sheet; [can] greys it out with its reason below the title. */
internal data class MoreItem(val icon: ImageVector, val title: String, val description: String? = null, val can: DriveBulk.Can = DriveBulk.Can.YES, val onClick: () -> Unit)

/**
 * "Mehr" for a selection (and the menu of one row, ⋯): the head ([title] or "N ausgewählt", the summary), [quick] as a
 * row of icons (the row's menu only), then [multi], the section "Nur bei einem Element" ([single], greyed out with
 * more than one) and [last].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BulkMoreSheet(
    count: Int,
    title: String?,
    summary: String,
    quick: List<BarAction>,
    multi: List<MoreItem>,
    single: List<MoreItem>,
    last: List<MoreItem>,
    onDismiss: () -> Unit,
    /** Further sections at the end, each with its label (null: none). */
    sections: List<Pair<String?, List<MoreItem>>> = emptyList(),
) {
    val x = GateControlTheme.extraColors
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp).padding(bottom = 24.dp)) {
            Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                Text(
                    title ?: pluralStringResource(R.plurals.drive_sel_count, count, count),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    modifier = Modifier.semantics { heading() },
                )
                if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodySmall, color = x.muted)
            }
            if (quick.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                    quick.forEach { a ->
                        val why = whyText(a.can.why)
                        val tint = if (!a.can.ok) x.faint else if (a.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        val unavailable = why?.let { stringResource(R.string.drive_sel_unavailable, a.label, it) }
                        Column(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 56.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(role = Role.Button, onClickLabel = a.label) {
                                    if (a.can.ok) { onDismiss(); a.onClick() } else if (why != null) Toast.makeText(context, why, Toast.LENGTH_SHORT).show()
                                }
                                .semantics { if (unavailable != null) stateDescription = unavailable }
                                .padding(horizontal = 4.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(a.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                            Text(a.label, style = MaterialTheme.typography.labelMedium, color = tint, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
                GcRowDivider(Modifier.padding(vertical = 4.dp))
            }
            multi.forEach { MoreRow(it, onDismiss) }
            if (single.isNotEmpty()) {
                if (multi.isNotEmpty()) GcRowDivider(Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                if (count > 1) GcSectionLabel(stringResource(R.string.drive_more_only_one), modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp))
                single.forEach { MoreRow(it, onDismiss) }
            }
            if (last.isNotEmpty()) {
                GcRowDivider(Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                last.forEach { MoreRow(it, onDismiss) }
            }
            sections.forEach { (label, items) ->
                if (items.isEmpty()) return@forEach
                GcRowDivider(Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                if (label != null) GcSectionLabel(label, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp).semantics { heading() })
                items.forEach { MoreRow(it, onDismiss) }
            }
        }
    }
}

@Composable
private fun MoreRow(item: MoreItem, onDismiss: () -> Unit) {
    val x = GateControlTheme.extraColors
    val enabled = item.can.ok
    // "Nur bei einem Element" says it already as the section's label
    val why = item.can.why?.takeIf { it != DriveBulk.Why.ONLY_ONE }?.let { whyText(it) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button) { onDismiss(); item.onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GcIconSquare(size = 40.dp, background = x.panel2) {
            Icon(item.icon, contentDescription = null, tint = if (enabled) MaterialTheme.colorScheme.onSurface else x.faint, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else x.faint)
            val d = if (enabled) item.description else why ?: item.description
            if (!d.isNullOrBlank()) Text(d, style = MaterialTheme.typography.bodySmall, color = if (enabled) x.muted else x.faint)
        }
    }
}

// ─── choosing the target ────────────────────────────────────────────

/**
 * "Ziel wählen" (full screen): Verschieben | Kopieren, the areas (read-only ones greyed out "(nur ansehen)"), the path,
 * the folders (a ticked folder greyed out: not into itself), "Neuer Ordner hier", the folders used last, and below the
 * target with the big button.
 */
@Composable
internal fun TargetPickerDialog(
    t: DriveBulkActions.Target,
    pathOf: (DFolder) -> String,
    titleOf: @Composable (DNode) -> String,
    onMode: (DriveBulk.Mode) -> Unit,
    onBrowse: (Long) -> Unit,
    onNewFolder: suspend (String) -> DResult<DNode>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val x = GateControlTheme.extraColors
    val scope = rememberCoroutineScope()
    var naming by remember { mutableStateOf(false) }
    var newError by remember { mutableStateOf<String?>(null) }
    val ready = t.ready
    val n = t.nodes.size
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                GcIconButton(GcIcons.Close, contentDescription = stringResource(R.string.drive_cancel), onClick = onDismiss, tint = MaterialTheme.colorScheme.onBackground)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.drive_target_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    Text(
                        if (t.from != null) pluralStringResource(R.plurals.drive_target_from, n, n, t.from) else pluralStringResource(R.plurals.drive_items, n, n),
                        style = MaterialTheme.typography.bodySmall,
                        color = x.muted,
                    )
                }
            }
            ModeSwitch(t.mode, t.copyOk, onMode)
            if (!t.copyOk) {
                Text(
                    stringResource(R.string.drive_target_copy) + ": " + (whyText(t.copyWhy) ?: stringResource(R.string.drive_why_bulk)),
                    style = MaterialTheme.typography.bodySmall,
                    color = x.faint,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            if (t.areas.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    t.areas.forEach { a ->
                        val ok = DriveBulk.areaEnabled(a)
                        GcFilterChip(
                            if (ok) a.label else stringResource(R.string.drive_target_view_only, a.label),
                            selected = ready?.node?.areaId == a.id,
                            onClick = { onBrowse(a.rootId) },
                            enabled = ok,
                        )
                    }
                }
            }
            if (ready != null) {
                if (t.vaultRoot != null) {
                    Text(
                        titleOf(ready.node),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                } else {
                    TargetPath(ready, t, onBrowse)
                }
            }
            GcRowDivider()
            Box(Modifier.weight(1f)) {
                when (val f = t.folder) {
                    null -> Unit
                    DriveLoad.Loading -> DriveLoading()
                    is DriveLoad.Error -> DriveErrorBox(f.code, onRetry = ready?.let { r -> { onBrowse(r.node.id) } })
                    is DriveLoad.Ready -> {
                        val rows = DriveBulk.targetRows(f.value.items, t.nodes.firstNotNullOfOrNull { it.vaultId })
                        val itself = stringResource(if (t.mode == DriveBulk.Mode.MOVE) R.string.drive_target_itself_move else R.string.drive_target_itself_copy)
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                            // within a vault: up to its own folder, never above
                            val up = f.value.crumbs.lastOrNull()?.id
                            if (t.vaultRoot != null && f.value.node.id != t.vaultRoot && up != null) {
                                item(key = "up") {
                                    Row(
                                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { onBrowse(up) }.padding(horizontal = 20.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("‹ " + stringResource(R.string.drive_back), style = MaterialTheme.typography.bodyLarge, color = x.accentText)
                                    }
                                }
                            }
                            items(rows, key = { it.id }) { d ->
                                val block = DriveBulk.rowBlock(d, t.selectedIds)
                                val name = titleOf(d)
                                val open = stringResource(R.string.drive_target_open, name)
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 56.dp)
                                        .clickable(enabled = block == null, role = Role.Button, onClickLabel = open) { onBrowse(d.id) }
                                        .padding(horizontal = 20.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    Icon(GcIcons.Folder, contentDescription = null, tint = if (block == null) x.muted else x.faint, modifier = Modifier.size(22.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(name, style = MaterialTheme.typography.bodyLarge, color = if (block == null) MaterialTheme.colorScheme.onBackground else x.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (block != null) Text(itself, style = MaterialTheme.typography.bodySmall, color = x.faint)
                                    }
                                    if (block == null) GcChevron()
                                }
                            }
                            if (rows.isEmpty()) {
                                item(key = "empty") { Text(stringResource(R.string.drive_target_empty), color = x.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) }
                            }
                            if (f.value.writable && t.block != DriveBulk.Block.INTO_ITSELF) {
                                item(key = "new") {
                                    Row(
                                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { newError = null; naming = true }.padding(horizontal = 20.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    ) {
                                        Icon(GcIcons.FolderPlus, contentDescription = null, tint = x.accentText, modifier = Modifier.size(22.dp))
                                        Text(stringResource(R.string.drive_target_new_folder), style = MaterialTheme.typography.bodyLarge, color = x.accentText)
                                    }
                                }
                            }
                            newError?.let { e -> item(key = "err") { Text(driveMessage(e).orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp)) } }
                            if (t.recent.isNotEmpty()) {
                                item(key = "recent") {
                                    Column(Modifier.padding(top = 12.dp)) {
                                        GcSectionLabel(stringResource(R.string.drive_target_recent), modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            t.recent.forEach { r -> GcFilterChip(r.label, selected = ready?.node?.id == r.id, onClick = { onBrowse(r.id) }) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val block = t.block
                if (ready != null) {
                    Text(
                        if (block == null) stringResource(R.string.drive_target_to, pathOf(ready)) else blockText(block),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (block == null) x.muted else x.warn,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                GcPrimaryButton(
                    stringResource(if (t.mode == DriveBulk.Mode.MOVE) R.string.drive_target_move_here else R.string.drive_target_copy_here, n),
                    onClick = onConfirm,
                    enabled = ready != null && block == null && !t.busy,
                    loading = t.busy,
                    minHeight = 52.dp,
                )
            }
        }
    }
    if (naming) {
        NameDialog(
            title = stringResource(R.string.drive_new_folder),
            initial = "",
            confirm = stringResource(R.string.drive_create),
            onConfirm = { name ->
                naming = false
                scope.launch { onNewFolder(name).errorCode()?.let { newError = it } }
            },
            onDismiss = { naming = false },
        )
    }
}

@Composable
private fun blockText(b: DriveBulk.Block): String = stringResource(
    when (b) {
        DriveBulk.Block.INTO_ITSELF -> R.string.drive_target_block_itself
        DriveBulk.Block.SAME_FOLDER -> R.string.drive_target_block_same
        DriveBulk.Block.READ_ONLY -> R.string.drive_target_block_read
        DriveBulk.Block.VAULT -> R.string.drive_target_block_vault
    },
)

/** Verschieben | Kopieren: two choices of one group; copy greyed out without Drive 1.12. */
@Composable
private fun ModeSwitch(mode: DriveBulk.Mode, copyOk: Boolean, onMode: (DriveBulk.Mode) -> Unit) {
    val x = GateControlTheme.extraColors
    val label = stringResource(R.string.drive_target_what)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(4.dp)
            .selectableGroup()
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(DriveBulk.Mode.MOVE to R.string.drive_target_move, DriveBulk.Mode.COPY to R.string.drive_target_copy).forEach { (m, res) ->
            val on = mode == m
            val enabled = m == DriveBulk.Mode.MOVE || copyOk
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) x.accentBg else Color.Transparent)
                    .selectable(selected = on, enabled = enabled, role = Role.RadioButton) { onMode(m) },
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(res), style = MaterialTheme.typography.titleSmall, color = if (on) x.accentText else if (enabled) x.muted else x.faint)
            }
        }
    }
}

/** Area › Folder › Folder of the target; a tap goes there. */
@Composable
private fun TargetPath(f: DFolder, t: DriveBulkActions.Target, onBrowse: (Long) -> Unit) {
    val x = GateControlTheme.extraColors
    val label = stringResource(R.string.drive_target_path)
    val area = t.areas.firstOrNull { it.id == f.node.areaId }?.label
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 2.dp).semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        f.crumbs.forEachIndexed { i, c ->
            Text(
                if (i == 0) area ?: c.name.ifEmpty { "…" } else c.name,
                style = MaterialTheme.typography.bodySmall,
                color = x.accentText,
                modifier = Modifier.heightIn(min = 44.dp).clickable(role = Role.Button) { onBrowse(c.id) }.padding(vertical = 12.dp),
            )
            Text("  ›  ", style = MaterialTheme.typography.bodySmall, color = x.faint)
        }
        Text(
            if (f.node.parentId == null) area ?: f.node.name else nodeTitle(f.node),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ─── the question about names taken ─────────────────────────────────

/** "N Namen gibt es dort schon": what is there, what comes, and one choice for all of them. */
@Composable
internal fun BulkAskDialog(ask: DriveBulkActions.Ask, onAnswer: (String?) -> Unit) {
    val x = GateControlTheme.extraColors
    var choice by remember(ask) { mutableStateOf(if (ask.versionOk) DriveBulk.ON_VERSION else DriveBulk.ON_RENAME) }
    val n = ask.asking
    val example = ask.rows.firstOrNull { !it.same }?.name?.let { name ->
        val ext = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 8 }
        "… (2)" + (ext?.let { ".$it" } ?: "")
    } ?: "… (2)"
    AlertDialog(
        onDismissRequest = { onAnswer(null) },
        title = { Text(pluralStringResource(R.plurals.drive_ask_title, n, n)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.drive_ask_in, ask.targetName), style = MaterialTheme.typography.bodySmall, color = x.muted)
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.background).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ask.rows.take(6).forEach { r ->
                        Column {
                            Text(r.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val there = listOfNotNull(r.thereSize?.let { DriveLogic.size(it) }, bulkDay(r.thereTime)).joinToString(", ")
                            val new = when {
                                r.same -> stringResource(R.string.drive_ask_same)
                                r.newSize == null && r.thereSize == null -> stringResource(R.string.drive_ask_folder)
                                else -> listOfNotNull(r.newSize?.let { DriveLogic.size(it) }, bulkDay(r.newTime)).joinToString(", ")
                            }
                            Text(stringResource(R.string.drive_ask_line, there.ifEmpty { "…" }, new.ifEmpty { "…" }), style = MaterialTheme.typography.bodySmall, color = x.muted)
                        }
                    }
                    if (ask.rows.size > 6) Text("…", color = x.muted)
                }
                Text(stringResource(R.string.drive_ask_what), style = MaterialTheme.typography.bodySmall, color = x.muted, fontWeight = FontWeight.Medium)
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AskChoice(stringResource(R.string.drive_ask_version), if (ask.versionOk) stringResource(R.string.drive_ask_version_desc) else stringResource(R.string.drive_why_bulk), choice == DriveBulk.ON_VERSION, ask.versionOk) { choice = DriveBulk.ON_VERSION }
                    AskChoice(stringResource(R.string.drive_ask_rename), stringResource(R.string.drive_ask_rename_desc, example), choice == DriveBulk.ON_RENAME, true) { choice = DriveBulk.ON_RENAME }
                    AskChoice(stringResource(R.string.drive_ask_skip), pluralStringResource(R.plurals.drive_ask_skip_desc, n, n), choice == DriveBulk.ON_SKIP, true) { choice = DriveBulk.ON_SKIP }
                }
                if (ask.same.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(GcIcons.Info, contentDescription = null, tint = x.muted, modifier = Modifier.size(18.dp))
                        Text(
                            if (ask.same.size == 1) stringResource(R.string.drive_ask_same_one, ask.same.first()) else pluralStringResource(R.plurals.drive_ask_same_many, ask.same.size, ask.same.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = x.muted,
                        )
                    }
                }
                if (ask.versionOk && ask.rows.any { it.newSize == null && it.thereSize == null && !it.same }) {
                    Text(stringResource(R.string.drive_ask_folders_note), style = MaterialTheme.typography.bodySmall, color = x.muted)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(choice) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (ask.mode == DriveBulk.Mode.MOVE) R.string.drive_target_move else R.string.drive_target_copy))
            }
        },
        dismissButton = { TextButton(onClick = { onAnswer(null) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.drive_cancel)) } },
    )
}

@Composable
private fun AskChoice(title: String, description: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val x = GateControlTheme.extraColors
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else x.border2, shape)
            .background(if (selected) x.accentBg else Color.Transparent)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = if (enabled) MaterialTheme.colorScheme.onSurface else x.faint)
            Text(description, style = MaterialTheme.typography.bodySmall, color = if (enabled) x.muted else x.faint)
        }
    }
}

// ─── jobs: progress, snackbar ───────────────────────────────────────

/**
 * The round button of a page without uploads (the photos, the search, a vault): a plus ([onAdd], if any) or, while
 * jobs run, their ring; a tap on it opens the sheet with the jobs and their items.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JobsFab(jobs: List<DriveOps.Job>, onCancel: (String) -> Unit, modifier: Modifier = Modifier, onAdd: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    DriveFab(uploads = emptyList(), onAdd = onAdd, onOpenUploads = { open = true }, onAllDone = {}, modifier = modifier, jobs = jobs)
    if (open && jobs.isNotEmpty()) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            UploadsSheet(emptyList(), onConflict = {}, onClear = {}, jobs = jobs, onCancelJob = onCancel)
        }
    }
}

/** What the app does with the selection itself ("Senden", a ZIP): a card on top while it runs, with "Abbrechen". */
@Composable
internal fun BulkBusyCard(busy: DriveBulkActions.Busy?, onCancel: () -> Unit) {
    busy?.let { b -> BusyCard(b, onCancel) }
}

/**
 * A running job in the uploads sheet: "Verschiebe „X“ nach Y", how far, "Abbrechen"; below, its items one by one with
 * their bars like the uploads (Drive 1.12.1) — without them (an older server, a job of this app) one bar for the job.
 */
@Composable
internal fun JobRows(job: DriveOps.Job, onCancel: () -> Unit) {
    val x = GateControlTheme.extraColors
    val op = job.op
    val what = if (job.count == 1 && job.title != null) stringResource(R.string.drive_quoted, job.title) else pluralStringResource(R.plurals.drive_items, job.count, job.count)
    val target = job.target ?: stringResource(R.string.drive_op_target_unknown)
    val title = when (op.kind) {
        "undo" -> stringResource(R.string.drive_snack_undo)
        "copy" -> stringResource(R.string.drive_op_copy, what, target)
        "move" -> stringResource(R.string.drive_op_move, what, target)
        "trash" -> stringResource(R.string.drive_op_trash, what)
        "restore" -> stringResource(R.string.drive_op_restore, what)
        else -> stringResource(R.string.drive_op_purge, what)
    }
    val counts = buildList {
        val total = op.total.coerceAtLeast(op.done)
        if (total > 0) add(stringResource(if (job.local) R.string.drive_op_items else R.string.drive_op_files, op.done, total))
        if (op.bytesTotal > 0) add(stringResource(R.string.drive_op_bytes, DriveLogic.size(op.bytesDone), DriveLogic.size(op.bytesTotal)))
    }.joinToString(" · ")
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(jobIcon(op.kind), contentDescription = null, tint = x.blue, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        // asked for, the server has not answered yet (Drive 1.12.2 answers at once, an older one after a while)
                        job.pending -> stringResource(R.string.drive_op_preparing)
                        counts.isNotEmpty() -> counts
                        else -> stringResource(R.string.drive_op_running)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = x.muted,
                )
            }
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.drive_cancel), color = x.accentText, fontWeight = FontWeight.SemiBold) }
        }
        Text(
            stringResource(if (job.local) R.string.drive_op_local else R.string.drive_op_server),
            style = MaterialTheme.typography.bodySmall,
            color = x.muted,
            modifier = Modifier.padding(start = 52.dp, end = 20.dp, bottom = 4.dp),
        )
        val files = op.files
        if (files.isNullOrEmpty()) {
            val label = stringResource(R.string.drive_op_progress)
            LinearProgressIndicator(
                progress = { DriveBulk.progress(op) },
                color = MaterialTheme.colorScheme.primary,
                trackColor = x.panel2,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).semantics { contentDescription = label },
            )
        } else {
            val shown = DriveBulk.visibleFiles(files)
            shown.forEach { f -> JobFileRow(f) }
            val more = op.filesMore + files.size - shown.size
            if (more > 0) {
                Text(
                    pluralStringResource(R.plurals.drive_op_files_more, more, more),
                    style = MaterialTheme.typography.bodySmall,
                    color = x.muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
        }
        GcRowDivider(Modifier.padding(top = 6.dp))
    }
}

/** One item of a job: its name, its state, its bar (without a measure while it runs on the server). */
@Composable
private fun JobFileRow(f: DOpFile) {
    val x = GateControlTheme.extraColors
    val line = when (f.state) {
        "waiting" -> stringResource(R.string.drive_upload_waiting)
        "running" -> f.bytesDone?.takeIf { f.size > 0 }?.let { "${DriveLogic.size(it)} / ${DriveLogic.size(f.size)}" } ?: stringResource(R.string.drive_op_file_running)
        "done" -> stringResource(R.string.drive_upload_done)
        "skipped" -> if (f.reason == "cancelled") stringResource(R.string.drive_op_file_cancelled) else stringResource(R.string.drive_upload_skipped)
        else -> stringResource(R.string.drive_upload_failed, bulkReason(f.reason ?: "error"))
    }
    val failed = f.state == "failed"
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        GcListRow(
            title = f.name ?: stringResource(if (f.isDir) R.string.drive_op_folder_encrypted else R.string.drive_op_file_encrypted),
            description = line,
            titleColor = MaterialTheme.colorScheme.onSurface,
        )
        val p = DriveBulk.fileProgress(f)
        val color = when {
            failed -> MaterialTheme.colorScheme.error
            f.state == "skipped" -> x.muted
            else -> MaterialTheme.colorScheme.primary
        }
        val bar = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(4.dp).clip(RoundedCornerShape(2.dp))
        if (p == null) {
            LinearProgressIndicator(modifier = bar, color = color, trackColor = x.panel2)
        } else {
            LinearProgressIndicator(progress = { p }, modifier = bar, color = color, trackColor = x.panel2)
        }
    }
}

/** A job's reason (`into_itself`, `locked`, a Drive code …) as words. */
@Composable
private fun bulkReason(reason: String): String {
    val context = LocalContext.current
    return reasonText(context, reason)
}

@Composable
private fun BusyCard(b: DriveBulkActions.Busy, onCancel: () -> Unit) {
    val x = GateControlTheme.extraColors
    GcCard(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (b.kind == "zip") stringResource(R.string.drive_busy_zip) else stringResource(R.string.drive_busy_send, b.done + 1, b.total),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.drive_cancel), color = x.accentText) }
        }
        if (b.kind == "zip" && b.done == 0) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = x.blue, trackColor = x.panel2)
        } else {
            LinearProgressIndicator(progress = { if (b.total > 0) b.done.toFloat() / b.total else 0f }, modifier = Modifier.fillMaxWidth(), color = x.blue, trackColor = x.panel2)
        }
    }
}

/** The snackbar's sentence after a job. */
internal fun snackText(context: Context, s: DriveBulkActions.Snack): String {
    val r = context.resources
    val base = when {
        s.cancelled -> context.getString(R.string.drive_snack_cancelled)
        s.kind == "undo" -> context.getString(R.string.drive_snack_undone)
        s.kind == "move" -> r.getQuantityString(R.plurals.drive_snack_move, s.count, s.count, s.target ?: "…")
        s.kind == "copy" -> r.getQuantityString(R.plurals.drive_snack_copy, s.count, s.count, s.target ?: "…")
        else -> r.getQuantityString(R.plurals.drive_snack_trash, s.count, s.count)
    }
    val skipped = if (s.skipped > 0) r.getQuantityString(R.plurals.drive_snack_skipped, s.skipped, s.skipped) else ""
    val failed = if (s.failed > 0) r.getQuantityString(R.plurals.drive_snack_failed, s.failed, s.failed) else ""
    return base + skipped + failed
}

// ─── everything a screen with a selection shows on top ──────────────

/**
 * The dialogs and sheets of [bulk]: the target, the question about taken names, the question before a delete without
 * Drive 1.12, one link for all, the album; its messages as toasts and its snackbar (with "Rückgängig") in [snackbar].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DriveBulkHost(bulk: DriveBulkActions, snackbar: SnackbarHostState, titleOf: @Composable (DNode) -> String = { nodeTitle(it) }) {
    val context = LocalContext.current
    val target by bulk.target.collectAsStateWithLifecycle()
    val ask by bulk.ask.collectAsStateWithLifecycle()
    val confirm by bulk.confirmDelete.collectAsStateWithLifecycle()
    val link by bulk.link.collectAsStateWithLifecycle()
    val albums by bulk.albums.collectAsStateWithLifecycle()
    val message by bulk.message.collectAsStateWithLifecycle()
    val snack by bulk.snack.collectAsStateWithLifecycle()
    var newAlbum by remember { mutableStateOf(false) }
    val undoLabel = stringResource(R.string.drive_snack_undo)

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, bulkText(context, it), Toast.LENGTH_LONG).show()
            bulk.clearMessage()
        }
    }
    LaunchedEffect(snack) {
        val s = snack ?: return@LaunchedEffect
        bulk.snackShown()
        val undo = s.undoId
        val result = snackbar.showSnackbar(
            snackText(context, s),
            actionLabel = if (undo != null) undoLabel else null,
            withDismissAction = undo == null,
            duration = if (undo != null) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed && undo != null) bulk.undo(undo, s.count)
    }

    target?.let { t ->
        TargetPickerDialog(
            t = t,
            pathOf = { f -> bulk.pathOf(f, t.areas) },
            titleOf = titleOf,
            onMode = bulk::setMode,
            onBrowse = bulk::browse,
            onNewFolder = bulk::newFolderHere,
            onConfirm = bulk::confirmTarget,
            onDismiss = bulk::closeTarget,
        )
    }
    ask?.let { a -> BulkAskDialog(a, onAnswer = bulk::answer) }
    confirm?.let { nodes ->
        AlertDialog(
            onDismissRequest = bulk::cancelDelete,
            title = { Text(pluralStringResource(R.plurals.drive_bulk_delete_title, nodes.size, nodes.size)) },
            text = { Text(stringResource(R.string.drive_bulk_delete_text)) },
            confirmButton = {
                TextButton(onClick = bulk::deleteConfirmed, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.drive_bulk_delete_go), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = bulk::cancelDelete, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.drive_cancel)) } },
        )
    }
    link?.let { l ->
        ModalBottomSheet(onDismissRequest = bulk::closeLink) {
            MultiLinkSheet(l, onCreate = bulk::createLink)
        }
    }
    albums?.let { a ->
        ModalBottomSheet(onDismissRequest = bulk::closeAlbums) {
            AlbumChooser(
                albums = (a as? DriveLoad.Ready)?.value ?: if (a is DriveLoad.Error) emptyList() else null,
                onAlbum = bulk::addToAlbum,
                onNew = { bulk.closeAlbums(); newAlbum = true },
            )
        }
    }
    if (newAlbum) {
        AlbumNameDialog(
            title = stringResource(R.string.drive_album_new),
            initial = "",
            confirm = stringResource(R.string.drive_create),
            areas = bulk.albumAreas(),
            onConfirm = { name, area -> newAlbum = false; bulk.newAlbum(name, area) },
            onDismiss = { newAlbum = false },
        )
    }
}

/** "Link teilen" for the selection: one link with who may do what, how long, and a PIN. */
@Composable
private fun MultiLinkSheet(l: DriveBulkActions.MultiLink, onCreate: (String, String, String?) -> Unit) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf("download") }
    var expires by remember { mutableStateOf("7d") }
    var pin by remember { mutableStateOf("") }
    val pinOk = pin.isEmpty() || Regex("^[0-9]{4,12}$").matches(pin)
    val n = l.nodes.size
    val what = if (n == 1) nodeTitle(l.nodes.first()) else pluralStringResource(R.plurals.drive_sel_files, n, n)
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.drive_link_files_title, what), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 4.dp))
        l.created?.url?.let { url ->
            GcCard {
                Text(url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GcOutlineButton(stringResource(R.string.drive_link_copy), onClick = { copyLink(context, url) }, icon = GcIcons.Copy, modifier = Modifier.weight(1f))
                    GcPrimaryButton(stringResource(R.string.drive_link_share), onClick = { shareLink(context, url) }, icon = GcIcons.Share, modifier = Modifier.weight(1f))
                }
            }
        }
        l.error?.let { GcBanner(tone = GcTone.Error, icon = GcIcons.Alert) { Text(bulkText(context, it), style = MaterialTheme.typography.bodyMedium) } }
        if (l.created == null) {
            GcSectionLabel(stringResource(R.string.drive_link_who))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GcFilterChip(stringResource(R.string.drive_link_view), selected = mode == "view", onClick = { mode = "view" })
                GcFilterChip(stringResource(R.string.drive_link_download), selected = mode == "download", onClick = { mode = "download" })
            }
            GcSectionLabel(stringResource(R.string.drive_link_valid))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("1d" to R.string.drive_link_1d, "7d" to R.string.drive_link_7d, "30d" to R.string.drive_link_30d, "never" to R.string.drive_link_never).forEach { (k, label) ->
                    GcFilterChip(stringResource(label), selected = expires == k, onClick = { expires = k })
                }
            }
            GcTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(12) },
                label = stringResource(R.string.drive_link_pin),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                error = if (!pinOk) stringResource(R.string.drive_link_pin) else null,
            )
            GcPrimaryButton(stringResource(R.string.drive_link_create), onClick = { onCreate(mode, expires, pin.ifBlank { null }) }, enabled = pinOk && !l.busy, loading = l.busy, icon = GcIcons.Link)
        }
        Spacer(Modifier.width(1.dp))
    }
}

private fun copyLink(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
    cm.setPrimaryClip(android.content.ClipData.newPlainText("Drive", text))
    Toast.makeText(context, R.string.drive_link_copied, Toast.LENGTH_SHORT).show()
}

private fun shareLink(context: Context, text: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(android.content.Intent.createChooser(intent, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
