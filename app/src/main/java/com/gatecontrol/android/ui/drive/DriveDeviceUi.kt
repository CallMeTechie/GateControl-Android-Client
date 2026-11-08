package com.gatecontrol.android.ui.drive

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.net.Uri
import android.provider.Settings
import android.text.format.DateUtils
import android.util.LruCache
import android.util.Size
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.device.DeviceEntry
import com.gatecontrol.android.drive.device.DeviceFileProvider
import com.gatecontrol.android.drive.device.DeviceFilesLogic
import com.gatecontrol.android.drive.device.DeviceFilesLogic.Category
import com.gatecontrol.android.drive.device.DeviceFilesLogic.InDrive
import com.gatecontrol.android.drive.device.DeviceFilesLogic.Type
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSecondaryButton
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// ─── the entry: the hard disk beside "Übersicht · Dateien · Fotos" ───

/**
 * The switch of the Drive tab with "Auf dem Gerät" on its right: a button of its own (48 dp, as high as the switch),
 * not a tab — it opens a page of its own.
 */
@Composable
internal fun DriveModeRow(tabs: List<DriveLogic.Tab>, tab: DriveLogic.Tab, onTab: (DriveLogic.Tab) -> Unit, onDevice: () -> Unit) {
    val x = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DriveModeSwitch(tabs = tabs, tab = tab, onTab = onTab, modifier = Modifier.weight(1f))
        val label = stringResource(R.string.drive_dev_open)
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(x.panel2)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onDevice),
            contentAlignment = Alignment.Center,
        ) {
            Icon(GcIcons.HardDrive, contentDescription = label, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(22.dp))
        }
    }
}

// ─── first visit: "Zugriff auf alle Dateien" ─────────────────────────

/**
 * Why the page needs Android's "Zugriff auf alle Dateien", with "Zugriff erlauben" and "Nicht jetzt". [play]: the
 * build cannot ask for it (Google Play) — it says so and only goes back.
 */
@Composable
internal fun DeviceAccessPage(play: Boolean, onAllow: () -> Unit, onLater: () -> Unit, modifier: Modifier = Modifier) {
    val x = GateControlTheme.extraColors
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GcCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                GcIconSquare(size = 56.dp, background = x.panel2) {
                    Icon(GcIcons.HardDrive, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(28.dp))
                }
                Text(
                    stringResource(R.string.drive_dev_access_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.drive_dev_access_text), style = MaterialTheme.typography.bodyLarge, color = x.muted)
                AccessPoint(GcIcons.Smartphone, R.string.drive_dev_access_stays_title, R.string.drive_dev_access_stays)
                AccessPoint(GcIcons.Shield, R.string.drive_dev_access_revoke_title, R.string.drive_dev_access_revoke)
                AccessPoint(GcIcons.Info, R.string.drive_dev_access_without_title, R.string.drive_dev_access_without)
            }
            if (play) {
                GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.drive_dev_access_play_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.drive_dev_access_play), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (play) {
                GcOutlineButton(stringResource(R.string.drive_dev_access_back), onClick = onLater)
            } else {
                GcPrimaryButton(stringResource(R.string.drive_dev_access_allow), onClick = onAllow)
                GcSecondaryButton(stringResource(R.string.drive_dev_access_later), onClick = onLater)
            }
        }
    }
}

@Composable
private fun AccessPoint(icon: androidx.compose.ui.graphics.vector.ImageVector, title: Int, text: Int) {
    val x = GateControlTheme.extraColors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GcIconSquare(size = 36.dp, background = x.panel2) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = x.muted)
        }
    }
}

/** Opens Android's page "Zugriff auf alle Dateien" for this app (or the list of all apps); false when there is none. */
internal fun openAllFilesAccess(context: Context, launch: (Intent) -> Unit): Boolean {
    val own = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", context.packageName, null))
    val all = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    for (i in listOf(own, all)) {
        try {
            launch(i)
            return true
        } catch (e: ActivityNotFoundException) {
            continue
        }
    }
    Toast.makeText(context, R.string.drive_dev_access_none, Toast.LENGTH_LONG).show()
    return false
}

// ─── looks ──────────────────────────────────────────────────────────

@Composable
internal fun categoryLook(c: Category): KindLook {
    val x = GateControlTheme.extraColors
    return when (c) {
        Category.DOWNLOAD -> KindLook(GcIcons.Download, x.blue, x.blueBg)
        Category.IMAGES -> KindLook(GcIcons.Photo, x.accentText, x.accentBg)
        Category.VIDEOS -> KindLook(GcIcons.Film, x.pro, x.proBg)
        Category.DOCUMENTS -> KindLook(GcIcons.Note, x.warn, x.warnBg)
        Category.AUDIO -> KindLook(GcIcons.Music, MaterialTheme.colorScheme.error, x.errorBg)
        Category.ARCHIVES -> KindLook(GcIcons.ViewGrid, x.muted, x.panel2)
    }
}

internal fun categoryLabel(c: Category): Int = when (c) {
    Category.DOWNLOAD -> R.string.drive_dev_cat_download
    Category.IMAGES -> R.string.drive_dev_cat_images
    Category.VIDEOS -> R.string.drive_dev_cat_videos
    Category.DOCUMENTS -> R.string.drive_dev_cat_documents
    Category.AUDIO -> R.string.drive_dev_cat_audio
    Category.ARCHIVES -> R.string.drive_dev_cat_archives
}

/** The same symbols and colours as Drive's own files. */
@Composable
internal fun typeLook(t: Type): KindLook = when (t) {
    Type.FOLDER -> kindLook(DriveLogic.Kind.FOLDER)
    Type.PDF -> kindLook(DriveLogic.Kind.PDF)
    Type.IMAGE -> kindLook(DriveLogic.Kind.IMAGE)
    Type.VIDEO -> kindLook(DriveLogic.Kind.VIDEO)
    Type.AUDIO -> kindLook(DriveLogic.Kind.AUDIO)
    Type.DOCUMENT -> kindLook(DriveLogic.Kind.DOC)
    Type.ARCHIVE -> kindLook(DriveLogic.Kind.ARCHIVE)
    Type.APK -> categoryLook(Category.ARCHIVES)
    Type.OTHER -> kindLook(DriveLogic.Kind.OTHER)
}

/** The small picture of a photo or video of the phone, else the kind's symbol. */
@Composable
internal fun DeviceFileIcon(e: DeviceEntry, size: Int = 44) {
    val type = DeviceFilesLogic.typeOf(e)
    val look = typeLook(type)
    val thumb = if (type == Type.IMAGE || type == Type.VIDEO) rememberDeviceThumb(e, size) else null
    GcIconSquare(size = size.dp, background = look.bg) {
        if (thumb != null) {
            Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size.dp))
            if (type == Type.VIDEO) VideoMark(size / 3)
        } else {
            Icon(look.icon, contentDescription = null, tint = look.fg, modifier = Modifier.size((size / 2).dp))
        }
    }
}

/** Small pictures of the phone's photos and videos, kept while the app runs (about 16 MB). */
private object DeviceThumbs {
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun key(e: DeviceEntry, px: Int) = "${e.path}|${e.modified}|$px"

    fun peek(key: String): Bitmap? = cache.get(key)

    suspend fun load(e: DeviceEntry, px: Int): Bitmap? = withContext(Dispatchers.IO) {
        val f = File(e.path)
        val b = runCatching {
            if (DeviceFilesLogic.typeOf(e) == Type.VIDEO) ThumbnailUtils.createVideoThumbnail(f, Size(px, px), null)
            else ThumbnailUtils.createImageThumbnail(f, Size(px, px), null)
        }.getOrNull()
        b?.also { cache.put(key(e, px), it) }
    }
}

@Composable
private fun rememberDeviceThumb(e: DeviceEntry, sizeDp: Int): ImageBitmap? {
    if (LocalInspectionMode.current) return null
    val px = with(LocalDensity.current) { sizeDp.dp.roundToPx() }.coerceIn(48, 512)
    val key = DeviceThumbs.key(e, px)
    val bitmap by produceState(DeviceThumbs.peek(key)?.asImageBitmap(), key) {
        if (value == null) value = DeviceThumbs.load(e, px)?.asImageBitmap()
    }
    return bitmap
}

/** "240 KB · vor 12 Min." of a file, "12 Elemente · 3. Okt." of a folder; [extra] at the end. */
@Composable
internal fun deviceLine(e: DeviceEntry, extra: String? = null): String {
    val ago = e.modified.takeIf { it > 0 }?.let {
        DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    }
    val items = e.children?.let { pluralStringResource(R.plurals.drive_dev_items, it, it) }
    return listOfNotNull(if (e.isDir) items else DriveLogic.size(e.size), ago, extra).joinToString(" · ")
}

/** "In Drive" / "Nur hier" of [e] once Drive answered; null meanwhile and when it cannot be said. */
@Composable
internal fun rememberInDrive(e: DeviceEntry, ask: suspend (DeviceEntry) -> InDrive): InDrive {
    if (LocalInspectionMode.current || e.isDir) return InDrive.UNKNOWN
    val state by produceState(InDrive.UNKNOWN, e.path, e.size, e.modified) { value = ask(e) }
    return state
}

@Composable
internal fun inDriveText(s: InDrive): String? = when (s) {
    InDrive.IN_DRIVE -> stringResource(R.string.drive_dev_in_drive)
    InDrive.ONLY_HERE -> stringResource(R.string.drive_dev_only_here)
    InDrive.UNKNOWN -> null
}

/** The label at the end of a row: ✓ "In Drive" in the accent colour, "Nur hier" plain, nothing when unknown. */
@Composable
internal fun InDriveLabel(s: InDrive) {
    val x = GateControlTheme.extraColors
    when (s) {
        InDrive.IN_DRIVE -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(GcIcons.Check, contentDescription = null, tint = x.accentText, modifier = Modifier.size(14.dp))
            Text(stringResource(R.string.drive_dev_in_drive), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = x.accentText)
        }
        InDrive.ONLY_HERE -> Text(stringResource(R.string.drive_dev_only_here), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = x.muted)
        InDrive.UNKNOWN -> Unit
    }
}

// ─── handing files to other apps ─────────────────────────────────────

internal fun deviceMime(e: DeviceEntry): String {
    e.mime?.takeIf { it.contains('/') && it != "application/octet-stream" }?.let { return it }
    val ext = DeviceFilesLogic.ext(e.name)
    if (DeviceFilesLogic.typeOf(e) == Type.APK && ext == "apk") return "application/vnd.android.package-archive"
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
}

/** Opens [e] with an app of the device (read only, for this one intent). */
internal fun openDeviceFile(context: Context, e: DeviceEntry) {
    val uri = DeviceFileProvider.uriOf(context, File(e.path)) ?: return Toast.makeText(context, R.string.drive_open_none, Toast.LENGTH_LONG).show()
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, deviceMime(e))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (ex: ActivityNotFoundException) {
        Toast.makeText(context, R.string.drive_open_none, Toast.LENGTH_LONG).show()
    }
}

/** The share sheet for the files [entries] (folders are left out). */
internal fun shareDeviceFiles(context: Context, entries: List<DeviceEntry>) {
    val files = entries.filter { !it.isDir }
    val uris = files.mapNotNull { DeviceFileProvider.uriOf(context, File(it.path)) }
    if (uris.isEmpty()) return
    val mimes = files.map(::deviceMime).distinct()
    val type = mimes.singleOrNull() ?: mimes.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).setType(type).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    intent.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, files.singleOrNull()?.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
