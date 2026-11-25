package com.gatecontrol.android.ui.media

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.media.MediaLogic
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.share.ShareFolders
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily

/**
 * "Ordner" of the Medien tab (mockup 5): the SMB and WebDAV shares as folders and files, also what is not in
 * Jellyfin. The path bar with the way ("SMB · Zuhause", "WebDAV · unterwegs"), the sub-folders, the videos with
 * their picture, size, length, "noch 19 Min." and bar, a mark on what the phone cannot play ("Ton DTS: am Handy kein
 * Ton", played anyway). A tap plays the folder from there, a long press offers "Von vorn" and "Gesehen".
 */
@Composable
fun MediaFolderPage(viewModel: MediaFolderViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    BackHandler(enabled = searchOpen || ui.canGoUp) {
        if (searchOpen) {
            searchOpen = false
            viewModel.setQuery("")
        } else {
            viewModel.up()
        }
    }
    CompositionLocalProvider(LocalMediaPictures provides viewModel.pictures) {
        val share = ui.share
        if (share == null) {
            ShareList(ui.shares, onOpen = viewModel::openShare)
            return@CompositionLocalProvider
        }
        LazyColumn(
            Modifier.fillMaxSize().testTag(FOLDERS_TAG),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "path") {
                PathBar(
                    ui = ui,
                    share = share,
                    onRoot = if (ui.shares.size > 1) viewModel::allShares else null,
                    onFolder = viewModel::openFolder,
                    onSearch = {
                        searchOpen = !searchOpen
                        if (!searchOpen) viewModel.setQuery("")
                    },
                )
            }
            if (searchOpen) {
                item(key = "search") {
                    GcTextField(
                        value = ui.query,
                        onValueChange = viewModel::setQuery,
                        label = stringResource(R.string.media_folder_search),
                        placeholder = stringResource(R.string.media_folder_search_hint),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.testTag(FOLDER_SEARCH_TAG),
                    )
                }
            }
            ui.problem?.let { p -> item(key = "problem") { MediaProblemCard(p, onRetry = viewModel::retry) } }
            folderList(ui, viewModel)
            if (ui.loaded && ui.problem == null) item(key = "note") { WayNote(ui) }
        }
    }
}

/** More than one share: which to open (each with its way at home and away). */
@Composable
private fun ShareList(shares: List<MediaSource>, onOpen: (MediaSource) -> Unit) {
    val x = GateControlTheme.extraColors
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "shares") {
            GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                shares.forEach { s ->
                    GcListRow(
                        title = MediaLogic.shareAddress(s),
                        description = stringResource(if (s.pairWith != null) R.string.media_pair_note else if (s.kind == MediaSourceKind.SMB) R.string.media_type_smb else R.string.media_type_webdav),
                        leading = { Icon(GcIcons.Folder, contentDescription = null, tint = x.blue, modifier = Modifier.size(22.dp)) },
                        trailing = { Icon(GcIcons.ChevronRight, contentDescription = null, tint = x.faint, modifier = Modifier.size(18.dp)) },
                        onClick = { onOpen(s) },
                    )
                }
            }
        }
    }
}

/** "\\nas\filme › Familie" (each part tappable) and the way chip, the magnifier on the right. */
@Composable
private fun PathBar(ui: MediaFolderUi, share: MediaSource, onRoot: (() -> Unit)?, onFolder: (String) -> Unit, onSearch: () -> Unit) {
    val x = GateControlTheme.extraColors
    val mono = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onRoot != null) {
                Icon(GcIcons.ChevronLeft, contentDescription = stringResource(R.string.media_folder_all_shares), tint = x.muted, modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onRoot).padding(4.dp))
            }
            val crumbs = ShareFolders.crumbs(ui.path)
            Crumb(MediaLogic.shareAddress(share), mono, current = crumbs.isEmpty()) { onFolder("") }
            crumbs.forEachIndexed { i, (name, path) ->
                Text(" › ", style = mono, color = x.faint)
                Crumb(name, mono, current = i == crumbs.lastIndex) { onFolder(path) }
            }
        }
        WayChip(ui)
        GcIconButton(GcIcons.Search, contentDescription = stringResource(R.string.media_folder_search), onClick = onSearch, size = 36.dp, iconSize = 20.dp)
    }
}

@Composable
private fun Crumb(text: String, style: androidx.compose.ui.text.TextStyle, current: Boolean, onClick: () -> Unit) {
    val x = GateControlTheme.extraColors
    Text(
        text,
        style = style,
        color = if (current) MaterialTheme.colorScheme.onBackground else x.muted,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).then(if (current) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick)).padding(horizontal = 2.dp, vertical = 4.dp),
    )
}

/** "SMB · Zuhause" (Wi-Fi) or "WebDAV · unterwegs" (the tunnel): how the share is read just now. */
@Composable
private fun WayChip(ui: MediaFolderUi) {
    val via = ui.via ?: return
    val x = GateControlTheme.extraColors
    val away = ui.atHome == false
    val text = stringResource(
        when {
            via == MediaSourceKind.WEBDAV && away -> R.string.media_way_webdav_away
            via == MediaSourceKind.WEBDAV -> R.string.media_way_webdav_home
            away -> R.string.media_way_smb_away
            else -> R.string.media_way_smb_home
        },
    )
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(x.accentBg).padding(horizontal = 10.dp, vertical = 5.dp).testTag(WAY_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(if (away) GcIcons.Globe else GcIcons.Wifi, contentDescription = null, tint = x.accentText, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = x.accentText, maxLines = 1)
    }
}

/** The folder's rows in a card (mockup 5), or what the search found below it. */
private fun LazyListScope.folderList(ui: MediaFolderUi, viewModel: MediaFolderViewModel) {
    val searching = ui.query.isNotBlank()
    val folders = ui.shownFolders
    val files = if (searching) ui.found.orEmpty() else ui.files
    if (!ui.loaded && ui.loading) {
        item(key = "loading") { Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) } }
        return
    }
    if (folders.isEmpty() && files.isEmpty()) {
        if (ui.loaded && ui.problem == null && !ui.searching) {
            item(key = "empty") {
                Text(
                    stringResource(if (searching) R.string.media_search_none else R.string.media_folder_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = GateControlTheme.extraColors.muted,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    } else {
        item(key = "list") {
            GcCard(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp), verticalArrangement = Arrangement.Top) {
                folders.forEach { f -> FolderRow(f, onClick = { viewModel.openFolder(f.entry.path) }) }
                files.forEach { f ->
                    FileRow(
                        f,
                        path = if (searching) f.entry.path.substringBeforeLast('/', "").removePrefix(ui.path).trimStart('/').ifEmpty { null } else null,
                        onPlay = { viewModel.play(f) },
                        onFromStart = { viewModel.play(f, fromStart = true) },
                        onPlayed = { viewModel.setPlayed(f, !f.title.played) },
                    )
                }
            }
        }
    }
    if (ui.searching) {
        item(key = "searching") { Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) } }
    }
}

@Composable
private fun FolderRow(f: MediaFolderRow, onClick: () -> Unit) {
    val x = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(GcIcons.Folder, contentDescription = null, tint = x.blue, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(f.entry.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            f.count?.let { c ->
                val text = when {
                    c.videos > 0 && c.folders > 0 -> pluralStringResource(R.plurals.media_folder_folders, c.folders, c.folders) + " · " + pluralStringResource(R.plurals.media_folder_videos, c.videos, c.videos)
                    c.folders > 0 -> pluralStringResource(R.plurals.media_folder_folders, c.folders, c.folders)
                    else -> pluralStringResource(R.plurals.media_folder_videos, c.videos, c.videos)
                }
                Text(text, style = MaterialTheme.typography.bodySmall, color = x.muted, maxLines = 1)
            }
        }
        Icon(GcIcons.ChevronRight, contentDescription = null, tint = x.faint, modifier = Modifier.size(18.dp))
    }
}

/** A video: picture (or a tile) with the bar, the file name, "1,8 GB · 42 Min. · noch 19 Min." and the mark. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(f: MediaFileRow, path: String?, onPlay: () -> Unit, onFromStart: () -> Unit, onPlayed: () -> Unit) {
    val x = GateControlTheme.extraColors
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val picture = rememberMediaPicture(f.title)
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .combinedClickable(role = Role.Button, onClick = onPlay, onLongClick = { menu = true }, onLongClickLabel = stringResource(R.string.media_folder_more))
                .padding(vertical = 8.dp)
                .testTag(FILE_TAG),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.width(68.dp).height(38.dp).clip(RoundedCornerShape(8.dp)).background(x.panel2)) {
                if (picture != null) {
                    Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(GcIcons.Film, contentDescription = null, tint = x.muted, modifier = Modifier.align(Alignment.Center).size(18.dp))
                }
                if (f.title.played) {
                    Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(14.dp).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                        Icon(GcIcons.Check, contentDescription = stringResource(R.string.media_played), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(10.dp))
                    }
                }
                if (f.progress > 0f) {
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.35f))) {
                        Box(Modifier.fillMaxWidth(f.progress).height(3.dp).background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(f.entry.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val parts = listOfNotNull(
                    f.entry.size.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) },
                    f.durationMs?.let { runtimeText(it) },
                    f.leftMs?.let { leftText(it) },
                )
                val problem = f.problem?.let { p ->
                    listOfNotNull(
                        p.audio?.let { stringResource(R.string.media_audio_unplayable, it) },
                        p.video?.let { stringResource(R.string.media_video_unplayable, it) },
                    ).joinToString(" · ")
                }
                if (parts.isNotEmpty() || problem != null) {
                    Text(
                        buildAnnotatedString {
                            append(parts.joinToString(" · "))
                            if (problem != null) {
                                if (parts.isNotEmpty()) append(" · ")
                                withStyle(SpanStyle(color = x.warn)) { append(problem) }
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = x.muted,
                        modifier = Modifier.testTag(FILE_LINE_TAG),
                    )
                }
                val extras = listOfNotNull(
                    path,
                    f.video.subtitles.takeIf { it.isNotEmpty() }?.let { subs ->
                        stringResource(R.string.media_folder_subtitles, subs.joinToString(", ") { it.language?.uppercase() ?: it.path.substringAfterLast('.').uppercase() })
                    },
                )
                if (extras.isNotEmpty()) Text(extras.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = x.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.media_play)) }, onClick = { menu = false; onPlay() })
            if (f.title.resumable) DropdownMenuItem(text = { Text(stringResource(R.string.media_from_start)) }, onClick = { menu = false; onFromStart() })
            DropdownMenuItem(
                text = { Text(stringResource(if (f.title.played) R.string.media_mark_unplayed else R.string.media_mark_played)) },
                onClick = { menu = false; onPlayed() },
            )
        }
    }
}

/** Below the list (mockup 5): how the share is read at home and away, and that a share always plays the original. */
@Composable
private fun WayNote(ui: MediaFolderUi) {
    val x = GateControlTheme.extraColors
    val way = when {
        ui.paired && ui.via == MediaSourceKind.WEBDAV -> stringResource(R.string.media_folder_note_pair_away)
        ui.paired -> stringResource(R.string.media_folder_note_pair_home)
        ui.via == MediaSourceKind.SMB -> stringResource(R.string.media_folder_note_smb_only)
        else -> null
    }
    GcCard(contentPadding = PaddingValues(12.dp)) {
        Text(
            listOfNotNull(way, stringResource(R.string.media_folder_note_original)).joinToString(" "),
            style = MaterialTheme.typography.bodySmall,
            color = x.muted,
        )
    }
}

internal const val FOLDERS_TAG = "media-folders"
internal const val FOLDER_SEARCH_TAG = "media-folder-search"
internal const val FILE_TAG = "media-folder-file"
internal const val FILE_LINE_TAG = "media-folder-file-line"
internal const val WAY_TAG = "media-folder-way"
