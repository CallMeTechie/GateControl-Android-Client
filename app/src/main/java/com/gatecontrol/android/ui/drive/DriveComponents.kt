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
) {
    Column {
        GcListRow(
            title = node.name,
            description = description ?: nodeLine(node),
            onClick = onClick,
            leading = { NodeIcon(node) },
            trailing = trailing,
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

/** The uploads of this session: name, progress or outcome; a conflict asks on tap. */
@Composable
internal fun UploadsCard(
    uploads: List<DriveTransfers.Upload>,
    onConflict: (DriveTransfers.Upload) -> Unit,
    onClear: () -> Unit,
) {
    if (uploads.isEmpty()) return
    val x = GateControlTheme.extraColors
    GcCard(contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.drive_uploads), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (uploads.none { it.state == DriveTransfers.State.RUNNING || it.state == DriveTransfers.State.QUEUED || it.state == DriveTransfers.State.CONFLICT }) {
                TextButton(onClick = onClear) { Text(stringResource(R.string.drive_clear)) }
            }
        }
        uploads.takeLast(20).forEachIndexed { i, u ->
            if (i > 0) GcRowDivider()
            val line = when (u.state) {
                DriveTransfers.State.QUEUED -> stringResource(R.string.drive_upload_waiting)
                DriveTransfers.State.RUNNING -> if (u.total > 0) "${DriveLogic.size(u.sent)} / ${DriveLogic.size(u.total)}" else DriveLogic.size(u.sent)
                DriveTransfers.State.CONFLICT -> stringResource(R.string.drive_upload_conflict)
                DriveTransfers.State.DONE -> stringResource(R.string.drive_upload_done)
                DriveTransfers.State.SAME -> stringResource(R.string.drive_upload_same)
                DriveTransfers.State.SKIPPED -> stringResource(R.string.drive_upload_skipped)
                DriveTransfers.State.FAILED -> stringResource(R.string.drive_upload_failed, driveMessage(u.code).orEmpty())
            }
            Column(Modifier.fillMaxWidth()) {
                GcListRow(
                    title = u.name,
                    description = line,
                    onClick = if (u.state == DriveTransfers.State.CONFLICT) ({ onConflict(u) }) else null,
                    titleColor = MaterialTheme.colorScheme.onSurface,
                    leading = {
                        val tint = when (u.state) {
                            DriveTransfers.State.FAILED -> MaterialTheme.colorScheme.error
                            DriveTransfers.State.CONFLICT -> x.warn
                            DriveTransfers.State.DONE, DriveTransfers.State.SAME -> MaterialTheme.colorScheme.primary
                            else -> x.muted
                        }
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(x.panel2), contentAlignment = Alignment.Center) {
                            Icon(GcIcons.Upload, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                        }
                    },
                )
                if (u.state == DriveTransfers.State.RUNNING && u.total > 0) {
                    LinearProgressIndicator(
                        progress = { (u.sent.toFloat() / u.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(3.dp),
                    )
                }
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

/** One recent file as a tile (mockup "Zuletzt"). */
@Composable
internal fun RecentTile(node: DNode, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val look = kindLook(DriveLogic.kind(node))
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        val picture = rememberDrivePreview(node)
        Box(Modifier.fillMaxWidth().height(76.dp).background(look.bg), contentAlignment = Alignment.Center) {
            if (picture != null) {
                Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                if (DriveLogic.kind(node) == DriveLogic.Kind.VIDEO) VideoMark(28)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(look.icon, contentDescription = null, tint = look.fg, modifier = Modifier.size(22.dp))
                    Text(DriveLogic.badge(node, stringResource(R.string.drive_photo)), style = MaterialTheme.typography.labelSmall, color = look.fg)
                }
            }
        }
        Text(
            node.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(8.dp),
        )
    }
}
