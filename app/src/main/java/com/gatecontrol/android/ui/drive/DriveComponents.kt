package com.gatecontrol.android.ui.drive

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import com.gatecontrol.android.drive.DriveNodeResolver
import com.gatecontrol.android.drive.DrivePreviews
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import com.gatecontrol.android.ui.components.GcIconButton
import androidx.compose.ui.draw.shadow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.DriveTransfers
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.io.File
import java.time.Instant

/** Icon and colors of a node's kind. */
internal data class KindLook(val icon: ImageVector, val fg: Color, val bg: Color)

@Composable
internal fun kindLook(kind: DriveLogic.Kind): KindLook {
    val x = GateControlTheme.extraColors
    val accent = MaterialTheme.colorScheme.primary
    return when (kind) {
        DriveLogic.Kind.FOLDER -> KindLook(GcIcons.Folder, x.accentText, x.accentBg)
        DriveLogic.Kind.IMAGE -> KindLook(GcIcons.Photo, x.blue, x.blueBg)
        DriveLogic.Kind.VIDEO -> KindLook(GcIcons.Film, x.blue, x.blueBg)
        DriveLogic.Kind.AUDIO -> KindLook(GcIcons.Music, x.pro, x.proBg)
        DriveLogic.Kind.PDF -> KindLook(GcIcons.File, MaterialTheme.colorScheme.error, x.errorBg)
        DriveLogic.Kind.ARCHIVE -> KindLook(GcIcons.Archive, x.warn, x.warnBg)
        DriveLogic.Kind.DOC, DriveLogic.Kind.TEXT -> KindLook(GcIcons.Note, x.blue, x.blueBg)
        DriveLogic.Kind.SHEET -> KindLook(GcIcons.Logs, accent, x.accentBg)
        DriveLogic.Kind.SLIDES -> KindLook(GcIcons.Board, x.warn, x.warnBg)
        DriveLogic.Kind.OTHER -> KindLook(GcIcons.File, x.muted, x.panel2)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DrivePreviewsEntryPoint {
    fun drivePreviews(): DrivePreviews
    fun driveNodeResolver(): DriveNodeResolver
    fun driveVaultSession(): com.gatecontrol.android.drive.vault.DriveVaultSession
}

/** The vault session for names of vault nodes (null in previews). */
@Composable
internal fun rememberVaultSession(): com.gatecontrol.android.drive.vault.DriveVaultSession? {
    if (LocalInspectionMode.current) return null
    val context = LocalContext.current.applicationContext
    return remember(context) {
        runCatching { EntryPointAccessors.fromApplication(context, DrivePreviewsEntryPoint::class.java).driveVaultSession() }.getOrNull()
    }
}

/**
 * The name to show: a vault node's decrypted one (when this device holds the keys), else "Tresor" / "Verschlüsselt" —
 * never its opaque name.
 */
@Composable
internal fun nodeTitle(node: DNode): String {
    if (!node.encrypted) return node.name
    val session = rememberVaultSession()
    return session?.nameOf(node) ?: stringResource(if (node.isDir) R.string.drive_vault else R.string.drive_vault_locked_item)
}

/** Symbol of a document kind the AI recognised (Drive 1.8). */
internal fun docKindIcon(kind: String?): ImageVector = when (kind) {
    "invoice" -> GcIcons.Receipt
    "contract" -> GcIcons.Edit
    "notice" -> GcIcons.Mail
    "medical" -> GcIcons.Medical
    "certificate" -> GcIcons.School
    "statement" -> GcIcons.Wallet
    "insurance" -> GcIcons.Shield
    "warranty" -> GcIcons.ShieldCheck
    else -> GcIcons.File
}

internal fun docKindLabel(kind: String?): Int = when (kind) {
    "invoice" -> R.string.drive_kind_invoice
    "contract" -> R.string.drive_kind_contract
    "notice" -> R.string.drive_kind_notice
    "medical" -> R.string.drive_kind_medical
    "certificate" -> R.string.drive_kind_certificate
    "statement" -> R.string.drive_kind_statement
    "insurance" -> R.string.drive_kind_insurance
    "warranty" -> R.string.drive_kind_warranty
    else -> R.string.drive_kind_other
}

/** The small symbol of the recognised document kind, bottom right on a file's icon. */
@Composable
private fun DocKindBadge(kind: String, size: Int, modifier: Modifier = Modifier) {
    val x = GateControlTheme.extraColors
    Box(
        modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface).padding(1.5.dp).clip(CircleShape).background(x.accentBg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(docKindIcon(kind), contentDescription = stringResource(docKindLabel(kind)), tint = x.accentText, modifier = Modifier.size((size * 0.6f).dp))
    }
}

/**
 * The server's preview picture of [node] ([DrivePreviews]); null for none, while loading and offline. A short entry
 * without its media id (a photo of the timeline, Drive 1.7) is fetched in full first ([DriveNodeResolver]).
 */
@Composable
internal fun rememberDrivePreview(node: DNode, large: Boolean = false): ImageBitmap? {
    if (!node.preview || node.isDir || LocalInspectionMode.current) return null
    val context = LocalContext.current.applicationContext
    val entry = remember(context) {
        runCatching { EntryPointAccessors.fromApplication(context, DrivePreviewsEntryPoint::class.java) }.getOrNull()
    } ?: return null
    val previews = remember(entry) { entry.drivePreviews() }
    val bitmap by produceState(previews.peek(node, large)?.asImageBitmap(), node.id, node.mediaId, large) {
        if (value == null) {
            val full = if (node.mediaId != null) node else entry.driveNodeResolver().resolve(node.id) ?: return@produceState
            value = previews.load(full, large)?.asImageBitmap()
        }
    }
    return bitmap
}

@Composable
internal fun NodeIcon(node: DNode, size: Int = 44) {
    val look = if (node.encrypted) {
        // a vault (Drive 1.9): no preview, the lock instead of the kind
        val x = GateControlTheme.extraColors
        KindLook(if (node.isDir) GcIcons.Vault else GcIcons.Lock, x.warn, x.warnBg)
    } else {
        kindLook(DriveLogic.kind(node))
    }
    val picture = if (node.encrypted) null else rememberDrivePreview(node)
    val kind = DriveLogic.docKind(node)
    Box {
        GcIconSquare(size = size.dp, background = look.bg) {
            if (picture != null) {
                Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size.dp))
                if (DriveLogic.kind(node) == DriveLogic.Kind.VIDEO) VideoMark(size / 3)
            } else {
                Icon(look.icon, contentDescription = null, tint = look.fg, modifier = Modifier.size((size / 2).dp))
            }
        }
        if (kind != null) DocKindBadge(kind, (size * 0.42f).toInt().coerceAtLeast(16), Modifier.align(Alignment.BottomEnd).padding(0.dp))
    }
}

/** A small play mark on the picture of a video. */
@Composable
internal fun VideoMark(size: Int) {
    Box(
        Modifier.size(size.coerceAtLeast(16).dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(GcIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size((size.coerceAtLeast(16) * 0.6f).dp))
    }
}

/** "1,2 MB · vor 3 Std." of a file, "12. Okt." of a folder. */
@Composable
internal fun nodeLine(node: DNode, extra: String? = null): String {
    val time = (node.mtime ?: node.updatedAt)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    val ago = time?.let { DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString() }
    val parts = buildList {
        if (!node.isDir) add(DriveLogic.size(node.size))
        if (ago != null) add(ago)
        if (extra != null) add(extra)
    }
    return parts.joinToString(" · ")
}

/**
 * A row of a folder, the trash or the search. With a selection ([selecting]) a tap ticks it ([selected]: the round
 * tick filled, the row tinted); [onMore] puts ⋯ on the right (the menu of this one item); [fresh] marks "neu hier".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NodeRow(
    node: DNode,
    onClick: (() -> Unit)?,
    description: String? = null,
    progress: Float? = null,
    trailing: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    selecting: Boolean = false,
    selected: Boolean = false,
    onMore: (() -> Unit)? = null,
    fresh: Boolean = false,
    title: String? = null,
) {
    if (!selecting && onMore == null && !fresh) {
        Column {
            GcListRow(
                title = title ?: nodeTitle(node),
                description = description ?: nodeLine(node),
                onClick = onClick,
                leading = { NodeIcon(node) },
                trailing = trailing,
                onLongClick = onLongClick,
                onLongClickLabel = onLongClick?.let { stringResource(R.string.drive_sel_select) },
            )
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(3.dp))
            }
        }
        return
    }
    val x = GateControlTheme.extraColors
    val name = title ?: nodeTitle(node)
    val state = stringResource(if (selected) R.string.drive_sel_selected else R.string.drive_sel_not_selected)
    val clickLabel = if (selecting) stringResource(if (selected) R.string.drive_sel_deselect else R.string.drive_sel_select) else null
    val longLabel = stringResource(R.string.drive_sel_select)
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .then(if (selected) Modifier.background(x.accentBg) else Modifier)
                .then(
                    if (onClick == null) {
                        Modifier
                    } else {
                        Modifier.combinedClickable(
                            role = Role.Button,
                            onClickLabel = clickLabel,
                            onClick = onClick,
                            onLongClick = onLongClick,
                            onLongClickLabel = onLongClick?.let { longLabel },
                        )
                    },
                )
                .semantics { if (selecting) { this.selected = selected; stateDescription = state } }
                .padding(start = 16.dp, end = if (onMore != null && !selecting) 4.dp else 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (selecting) SelectCheck(selected)
            NodeIcon(node)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (fresh) NewHereBadge()
                }
                val d = description ?: nodeLine(node)
                if (d.isNotBlank()) Text(d, style = MaterialTheme.typography.bodySmall, color = x.muted)
            }
            trailing?.invoke()
            if (!selecting && onMore != null) {
                GcIconButton(GcIcons.MoreVertical, contentDescription = stringResource(R.string.drive_sel_more_for, name), onClick = onMore, size = 44.dp, iconSize = 20.dp)
            }
        }
        if (progress != null) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(3.dp))
        }
    }
}

/** A Drive code as a sentence. */
@Composable
internal fun driveMessage(code: String?): String? {
    val context = androidx.compose.ui.platform.LocalContext.current
    return code?.let { driveText(context, it) }
}

/** A Drive code (or `done_*`) as a sentence, outside of composition (toasts). */
internal fun driveText(context: Context, code: String): String = when (code) {
    "done_folder" -> context.getString(R.string.drive_done_folder)
    "done_rename" -> context.getString(R.string.drive_done_rename)
    "done_move" -> context.getString(R.string.drive_done_move)
    "done_trash" -> context.getString(R.string.drive_done_trash)
    "done_local" -> context.getString(R.string.drive_done_local)
    "done_version" -> context.getString(R.string.drive_done_version)
    "done_restore" -> context.getString(R.string.drive_done_restore)
    "done_purge" -> context.getString(R.string.drive_done_purge)
    "offline" -> context.getString(R.string.drive_error_offline)
    "not_licensed" -> context.getString(R.string.drive_not_licensed)
    "no_seat" -> context.getString(R.string.drive_no_seat)
    "exists", "conflict" -> context.getString(R.string.drive_code_exists)
    "forbidden", "login_required" -> context.getString(R.string.drive_code_forbidden)
    "quota" -> context.getString(R.string.drive_code_quota)
    "invalid" -> context.getString(R.string.drive_code_invalid)
    "not_found" -> context.getString(R.string.drive_code_not_found)
    "links_disabled" -> context.getString(R.string.drive_code_links_disabled)
    "no_unlimited" -> context.getString(R.string.drive_code_no_unlimited)
    "no_public_url" -> context.getString(R.string.drive_code_no_public_url)
    "scan_read" -> context.getString(R.string.drive_scan_read_failed)
    "read" -> context.getString(R.string.drive_share_read_failed)
    "device_locked" -> context.getString(R.string.drive_code_device_locked)
    "guard_released" -> context.getString(R.string.drive_guard_released)
    "done_insight" -> context.getString(R.string.drive_done_insight)
    "done_insight_again" -> context.getString(R.string.drive_done_insight_again)
    "done_approved" -> context.getString(R.string.drive_done_approved)
    "done_declined" -> context.getString(R.string.drive_done_declined)
    "done_upload" -> context.getString(R.string.drive_done_upload)
    "no_text" -> context.getString(R.string.drive_insight_no_text)
    "media_disabled" -> context.getString(R.string.drive_insight_media_disabled)
    "encrypted" -> context.getString(R.string.drive_code_encrypted)
    "feature_off" -> context.getString(R.string.drive_code_feature_off)
    "office_unavailable" -> context.getString(R.string.drive_code_office_unavailable)
    "locked" -> context.getString(R.string.drive_code_locked)
    "decrypt" -> context.getString(R.string.drive_code_decrypt)
    "stale_key" -> context.getString(R.string.drive_code_stale_key)
    "recovery_key" -> context.getString(R.string.drive_code_recovery_key)
    "done_rotated" -> context.getString(R.string.drive_done_rotated)
    "old_keys" -> context.getString(R.string.drive_code_old_keys)
    "wifi_wait" -> context.getString(R.string.drive_upload_wifi_wait)
    "auth_required" -> context.getString(R.string.drive_vault_gate_title)
    "no_screen_lock" -> context.getString(R.string.drive_vault_no_lock)
    else -> context.getString(R.string.drive_error_generic, code)
}

/** Full-width message with an optional retry, for a screen that could not load. */
@Composable
internal fun DriveErrorBox(code: String, onRetry: (() -> Unit)?) {
    val x = GateControlTheme.extraColors
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(if (code == "offline") GcIcons.Cloud else GcIcons.Alert, contentDescription = null, tint = x.muted, modifier = Modifier.size(40.dp))
        Text(driveMessage(code).orEmpty(), style = MaterialTheme.typography.bodyLarge, color = x.muted, textAlign = TextAlign.Center)
        if (onRetry != null && code != "no_seat" && code != "not_licensed") {
            GcPrimaryButton(stringResource(R.string.drive_retry), onClick = onRetry, fillWidth = false)
        }
    }
}

@Composable
internal fun DriveLoading() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/**
 * The round button bottom right: a plus (new folder, upload, … – [onAdd]) while nothing uploads; while uploads run,
 * a ring with the progress of all of them (yellow on a taken name, red with failures) that opens [UploadsSheet].
 * Finished uploads are cleared by themselves, then the plus comes back. Nothing shows when the folder is read only
 * and nothing uploads.
 */
@Composable
internal fun DriveFab(
    uploads: List<DriveTransfers.Upload>,
    onAdd: (() -> Unit)?,
    onOpenUploads: () -> Unit,
    onAllDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = uploads.any { it.state == DriveTransfers.State.QUEUED || it.state == DriveTransfers.State.RUNNING || it.state == DriveTransfers.State.CONFLICT }
    val failed = uploads.any { it.state == DriveTransfers.State.FAILED }
    LaunchedEffect(active, failed, uploads.size) {
        if (!active && !failed && uploads.isNotEmpty()) {
            delay(1_200)
            onAllDone()
        }
    }
    val x = GateControlTheme.extraColors
    if (!active && !failed) {
        if (onAdd == null) return
        val label = stringResource(R.string.drive_add)
        Box(
            modifier
                .size(56.dp)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onAdd)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(GcIcons.Plus, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(26.dp))
        }
        return
    }
    val total = uploads.sumOf { it.total.coerceAtLeast(0) }
    val sent = uploads.sumOf { u -> if (u.state == DriveTransfers.State.RUNNING || u.state == DriveTransfers.State.QUEUED) u.sent else u.total }
    val progress = if (total > 0) (sent.toFloat() / total).coerceIn(0f, 1f) else 0f
    val color = when {
        uploads.any { it.state == DriveTransfers.State.CONFLICT || (it.state == DriveTransfers.State.QUEUED && it.code == "device_locked") } -> x.warn
        failed && !active -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val label = stringResource(R.string.drive_uploads_running, uploads.count { it.state.finished() }, uploads.size)
    Box(
        modifier
            .size(56.dp)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onOpenUploads)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (active && total == 0L) {
            CircularProgressIndicator(modifier = Modifier.size(44.dp), color = color, strokeWidth = 4.dp)
        } else {
            CircularProgressIndicator(progress = { if (active) progress else 1f }, modifier = Modifier.size(44.dp), color = color, strokeWidth = 4.dp, trackColor = x.panel2)
        }
        Icon(if (failed && !active) GcIcons.Alert else GcIcons.Upload, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
    }
}

private fun DriveTransfers.State.finished() =
    this == DriveTransfers.State.DONE || this == DriveTransfers.State.SAME || this == DriveTransfers.State.SKIPPED || this == DriveTransfers.State.FAILED

/** The uploads one by one, each with its own bar; a name taken asks what to do. */
@Composable
internal fun UploadsSheet(
    uploads: List<DriveTransfers.Upload>,
    onConflict: (DriveTransfers.Upload) -> Unit,
    onClear: () -> Unit,
) {
    val x = GateControlTheme.extraColors
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.drive_uploads), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (uploads.any { it.state.finished() }) TextButton(onClick = onClear) { Text(stringResource(R.string.drive_clear)) }
        }
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            uploads.takeLast(50).forEach { u ->
                val line = when (u.state) {
                    DriveTransfers.State.QUEUED ->
                        when (u.code) {
                            "device_locked" -> stringResource(R.string.drive_upload_paused_locked)
                            "wifi_wait" -> stringResource(R.string.drive_upload_wifi_wait)
                            else -> stringResource(R.string.drive_upload_waiting)
                        }
                    DriveTransfers.State.RUNNING -> if (u.total > 0) "${DriveLogic.size(u.sent)} / ${DriveLogic.size(u.total)}" else DriveLogic.size(u.sent)
                    DriveTransfers.State.CONFLICT -> stringResource(R.string.drive_upload_conflict)
                    DriveTransfers.State.DONE -> stringResource(R.string.drive_upload_done)
                    DriveTransfers.State.SAME -> stringResource(R.string.drive_upload_same)
                    DriveTransfers.State.SKIPPED -> stringResource(R.string.drive_upload_skipped)
                    DriveTransfers.State.FAILED -> stringResource(R.string.drive_upload_failed, driveMessage(u.code).orEmpty())
                }
                val tint = when {
                    u.state == DriveTransfers.State.FAILED -> MaterialTheme.colorScheme.error
                    u.state == DriveTransfers.State.CONFLICT || u.code == "device_locked" -> x.warn
                    else -> x.muted
                }
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    GcListRow(
                        title = u.name,
                        description = line,
                        onClick = if (u.state == DriveTransfers.State.CONFLICT) ({ onConflict(u) }) else null,
                        titleColor = MaterialTheme.colorScheme.onSurface,
                    )
                    val p = when {
                        u.state.finished() -> 1f
                        u.total > 0 -> (u.sent.toFloat() / u.total).coerceIn(0f, 1f)
                        else -> 0f
                    }
                    LinearProgressIndicator(
                        progress = { p },
                        color = if (u.state == DriveTransfers.State.FAILED || u.state == DriveTransfers.State.CONFLICT) tint else MaterialTheme.colorScheme.primary,
                        trackColor = x.panel2,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
    }
}

// ─── list or tiles ───────────────────────────────────────────────

/** Whether the files show as tiles (else a list); one choice for all Drive screens, kept on the device. */
internal object DriveViewPref {
    private const val PREFS = "drive_ui"
    private const val KEY = "grid"
    private const val KEY_PHOTOS = "photos"

    fun grid(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setGrid(context: Context, grid: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, grid).apply()
    }

    /** The Drive tab shows the photos (Drive 1.7), not the files. */
    fun photos(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PHOTOS, false)

    fun setPhotos(context: Context, photos: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PHOTOS, photos).apply()
    }

    private const val KEY_CAMERA = "photos_camera"

    /** The Photos tab shows only the camera uploads (Drive 1.10, feature `photosFolder`). */
    fun cameraOnly(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CAMERA, false)

    fun setCameraOnly(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CAMERA, on).apply()
    }

    private const val KEY_SORT = "sort"
    private const val KEY_SORT_DESC = "sort_desc"

    /** How folders are sorted (name, date, size, type; up or down) — one choice for all folders. */
    fun sort(context: Context): DriveLogic.FolderSort {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return DriveLogic.sortOf(p.getString(KEY_SORT, null), if (p.contains(KEY_SORT_DESC)) p.getBoolean(KEY_SORT_DESC, false) else null)
    }

    fun setSort(context: Context, sort: DriveLogic.FolderSort) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SORT, sort.key).putBoolean(KEY_SORT_DESC, sort.desc).apply()
    }
}

/** List or tiles, the same for every Drive screen ([DriveViewPref.grid]). */
@Composable
internal fun rememberDriveGrid(): androidx.compose.runtime.MutableState<Boolean> {
    val context = LocalContext.current
    return remember { androidx.compose.runtime.mutableStateOf(DriveViewPref.grid(context)) }
}

/**
 * The files as tiles: the preview (or the kind's symbol), the name below with ⋯ ([onMore]); a long press ticks
 * ([selected], the tick top left, the tile inset).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NodeGrid(
    items: List<DNode>,
    onClick: (DNode) -> Unit,
    onLongClick: (DNode) -> Unit,
    progress: (DNode) -> Float? = { null },
    selected: Set<Long> = emptySet(),
    onMore: ((DNode) -> Unit)? = null,
    fresh: (DNode) -> Boolean = { false },
) {
    val selecting = selected.isNotEmpty()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = (maxWidth / 112.dp).toInt().coerceIn(3, 8)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { n ->
                        NodeTile(
                            n,
                            onClick = { onClick(n) },
                            onLongClick = { onLongClick(n) },
                            progress = progress(n),
                            selecting = selecting,
                            selected = n.id in selected,
                            onMore = onMore?.let { m -> { m(n) } },
                            fresh = fresh(n),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeTile(
    node: DNode,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    progress: Float?,
    selecting: Boolean,
    selected: Boolean,
    onMore: (() -> Unit)?,
    fresh: Boolean,
    modifier: Modifier = Modifier,
) {
    val x = GateControlTheme.extraColors
    val look = if (node.encrypted) {
        KindLook(if (node.isDir) GcIcons.Vault else GcIcons.Lock, x.warn, x.warnBg)
    } else {
        kindLook(DriveLogic.kind(node))
    }
    val picture = if (node.encrypted) null else rememberDrivePreview(node)
    val kind = DriveLogic.docKind(node)
    val haptic = LocalHapticFeedback.current
    val name = nodeTitle(node)
    val state = stringResource(if (selected) R.string.drive_sel_selected else R.string.drive_sel_not_selected)
    val clickLabel = if (selecting) stringResource(if (selected) R.string.drive_sel_deselect else R.string.drive_sel_select) else null
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) x.accentBg else MaterialTheme.colorScheme.surface)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = clickLabel,
                onClick = onClick,
                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() },
                onLongClickLabel = stringResource(R.string.drive_sel_select),
            )
            .semantics { if (selecting) { this.selected = selected; stateDescription = state } },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(if (selected) 6.dp else 0.dp)
                .clip(RoundedCornerShape(if (selected) 10.dp else 0.dp))
                .background(look.bg),
            contentAlignment = Alignment.Center,
        ) {
            if (picture != null) {
                Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                if (DriveLogic.kind(node) == DriveLogic.Kind.VIDEO) VideoMark(28)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(look.icon, contentDescription = null, tint = look.fg, modifier = Modifier.size(26.dp))
                    if (!node.isDir && !node.encrypted) Text(DriveLogic.badge(node, stringResource(R.string.drive_photo)), style = MaterialTheme.typography.labelSmall, color = look.fg)
                }
            }
            if (kind != null) DocKindBadge(kind, 22, Modifier.align(Alignment.TopEnd).padding(6.dp))
            if (selecting) SelectCheck(selected, Modifier.align(Alignment.TopStart).padding(6.dp), onImage = picture != null)
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 8.dp, top = 6.dp, bottom = 6.dp, end = if (onMore != null && !selecting) 0.dp else 8.dp)) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (fresh) Text(stringResource(R.string.drive_new_here), style = MaterialTheme.typography.labelSmall, color = x.accentText)
            }
            if (onMore != null && !selecting) {
                GcIconButton(GcIcons.MoreVertical, contentDescription = stringResource(R.string.drive_sel_more_for, name), onClick = onMore, size = 44.dp, iconSize = 18.dp)
            }
        }
    }
}

// ─── opening and sending files ──────────────────────────────────────

private fun uriOf(context: Context, file: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".provider", file)

private fun mimeOf(node: DNode, file: File): String =
    node.mime?.takeIf { it.contains('/') && it != "application/octet-stream" }
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
        ?: "application/octet-stream"

/** Opens [file] (the downloaded [node]) with an app of the device. */
internal fun openFile(context: Context, node: DNode, file: File) {
    val uri = uriOf(context, file)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(node, file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.drive_open_none, Toast.LENGTH_LONG).show()
    }
}

/** Sends [file] to another app (share sheet). */
internal fun sendFile(context: Context, node: DNode, file: File) {
    val uri = uriOf(context, file)
    val intent = Intent(Intent.ACTION_SEND).setType(mimeOf(node, file)).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, node.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Opens or sends [node] once its file is fetched; a failure becomes a toast. */
internal suspend fun fetchThen(context: Context, node: DNode, fetch: suspend (DNode) -> DResult<File>, then: (File) -> Unit, onError: (String) -> Unit) {
    when (val r = fetch(node)) {
        is DResult.Ok -> then(r.value)
        is DResult.Failed -> onError(r.code)
        DResult.Offline -> onError("offline")
        DResult.NotConfigured -> onError("not_configured")
    }
}

