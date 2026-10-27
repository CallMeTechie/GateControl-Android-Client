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
}

/** The server's preview picture of [node] ([DrivePreviews]); null for none, while loading and offline. */
@Composable
internal fun rememberDrivePreview(node: DNode, large: Boolean = false): ImageBitmap? {
    if (!node.preview || node.isDir || node.mediaId == null || LocalInspectionMode.current) return null
    val context = LocalContext.current.applicationContext
    val previews = remember(context) {
        runCatching { EntryPointAccessors.fromApplication(context, DrivePreviewsEntryPoint::class.java).drivePreviews() }.getOrNull()
    } ?: return null
    val bitmap by produceState(previews.peek(node, large)?.asImageBitmap(), node.mediaId, large) {
        if (value == null) value = previews.load(node, large)?.asImageBitmap()
    }
    return bitmap
}

@Composable
internal fun NodeIcon(node: DNode, size: Int = 44) {
    val look = kindLook(DriveLogic.kind(node))
    val picture = rememberDrivePreview(node)
    GcIconSquare(size = size.dp, background = look.bg) {
        if (picture != null) {
            Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size.dp))
            if (DriveLogic.kind(node) == DriveLogic.Kind.VIDEO) VideoMark(size / 3)
        } else {
            Icon(look.icon, contentDescription = null, tint = look.fg, modifier = Modifier.size((size / 2).dp))
        }
    }
}

/** A small play mark on the picture of a video. */
@Composable
private fun VideoMark(size: Int) {
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

/** A row of a folder, the trash or the search. */
@Composable
internal fun NodeRow(
    node: DNode,
    onClick: (() -> Unit)?,
    description: String? = null,
    progress: Float? = null,
    trailing: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    Column {
        GcListRow(
            title = node.name,
            description = description ?: nodeLine(node),
            onClick = onClick,
            leading = { NodeIcon(node) },
            trailing = trailing,
            onLongClick = onLongClick,
            onLongClickLabel = onLongClick?.let { stringResource(R.string.drive_more) },
        )
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
 * Uploads in progress: a ring bottom right with the progress of all of them (red with failures). A tap opens
 * [UploadsSheet] with each file. Finished uploads are cleared by themselves; nothing shows when none run.
 */
@Composable
internal fun UploadsFab(uploads: List<DriveTransfers.Upload>, onOpen: () -> Unit, onAllDone: () -> Unit, modifier: Modifier = Modifier) {
    val active = uploads.any { it.state == DriveTransfers.State.QUEUED || it.state == DriveTransfers.State.RUNNING || it.state == DriveTransfers.State.CONFLICT }
    val failed = uploads.any { it.state == DriveTransfers.State.FAILED }
    LaunchedEffect(active, failed, uploads.size) {
        if (!active && !failed && uploads.isNotEmpty()) {
            delay(1_200)
            onAllDone()
        }
    }
    if (!active && !failed) return
    val total = uploads.sumOf { it.total.coerceAtLeast(0) }
    val sent = uploads.sumOf { u -> if (u.state == DriveTransfers.State.RUNNING || u.state == DriveTransfers.State.QUEUED) u.sent else u.total }
    val progress = if (total > 0) (sent.toFloat() / total).coerceIn(0f, 1f) else 0f
    val x = GateControlTheme.extraColors
    val color = when {
        uploads.any { it.state == DriveTransfers.State.CONFLICT } -> x.warn
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
            .clickable(role = Role.Button, onClickLabel = label, onClick = onOpen)
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
                    DriveTransfers.State.QUEUED -> stringResource(R.string.drive_upload_waiting)
                    DriveTransfers.State.RUNNING -> if (u.total > 0) "${DriveLogic.size(u.sent)} / ${DriveLogic.size(u.total)}" else DriveLogic.size(u.sent)
                    DriveTransfers.State.CONFLICT -> stringResource(R.string.drive_upload_conflict)
                    DriveTransfers.State.DONE -> stringResource(R.string.drive_upload_done)
                    DriveTransfers.State.SAME -> stringResource(R.string.drive_upload_same)
                    DriveTransfers.State.SKIPPED -> stringResource(R.string.drive_upload_skipped)
                    DriveTransfers.State.FAILED -> stringResource(R.string.drive_upload_failed, driveMessage(u.code).orEmpty())
                }
                val tint = when (u.state) {
                    DriveTransfers.State.FAILED -> MaterialTheme.colorScheme.error
                    DriveTransfers.State.CONFLICT -> x.warn
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

    fun grid(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setGrid(context: Context, grid: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, grid).apply()
    }
}

/** The files as tiles: the preview (or the kind's symbol), the name below; long press for the menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NodeGrid(
    items: List<DNode>,
    onClick: (DNode) -> Unit,
    onLongClick: (DNode) -> Unit,
    progress: (DNode) -> Float? = { null },
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = (maxWidth / 112.dp).toInt().coerceIn(3, 8)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { n -> NodeTile(n, onClick = { onClick(n) }, onLongClick = { onLongClick(n) }, progress = progress(n), modifier = Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeTile(node: DNode, onClick: () -> Unit, onLongClick: () -> Unit, progress: Float?, modifier: Modifier = Modifier) {
    val look = kindLook(DriveLogic.kind(node))
    val picture = rememberDrivePreview(node)
    val haptic = LocalHapticFeedback.current
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() },
                onLongClickLabel = stringResource(R.string.drive_more),
            ),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(look.bg), contentAlignment = Alignment.Center) {
            if (picture != null) {
                Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                if (DriveLogic.kind(node) == DriveLogic.Kind.VIDEO) VideoMark(28)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(look.icon, contentDescription = null, tint = look.fg, modifier = Modifier.size(26.dp))
                    if (!node.isDir) Text(DriveLogic.badge(node, stringResource(R.string.drive_photo)), style = MaterialTheme.typography.labelSmall, color = look.fg)
                }
            }
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp))
            }
        }
        Text(
            node.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
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

