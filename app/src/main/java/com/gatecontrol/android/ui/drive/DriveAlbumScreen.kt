package com.gatecontrol.android.ui.drive

import android.widget.Toast
import androidx.activity.compose.BackHandler
import com.gatecontrol.android.drive.DriveBulk
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DMe
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * One album (Drive 1.7): its photos full screen by a tap; with the right to change it ([com.gatecontrol.android.drive.DAlbum.canEdit])
 * also add photos, take ticked ones out, a cover, rename, delete, and the photo frame (feature `frame`).
 */
@Composable
fun DriveAlbumScreen(onBack: () -> Unit, viewModel: DriveAlbumViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val album by viewModel.album.collectAsStateWithLifecycle()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val picker by viewModel.picker.collectAsStateWithLifecycle()
    val picked by viewModel.picked.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val gone by viewModel.gone.collectAsStateWithLifecycle()
    val me by viewModel.live.me.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, photosText(context, it), Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(gone) { if (gone) onBack() }

    val a = (album as? DriveLoad.Ready)?.value
    val canEdit = a?.canEdit == true
    val selecting = selected.isNotEmpty()
    // back ends the selection first
    BackHandler(enabled = selecting) { viewModel.clearSelection() }
    Column(Modifier.fillMaxSize()) {
        // the title row only, as high either way: the photos do not move when ticking starts
        SelectionSwap(
            selecting,
            bar = { AlbumBar(a?.name ?: stringResource(R.string.drive_photos_albums), canEdit, onBack, onAdd = viewModel::openPicker, onRename = { renaming = true }, onDelete = { deleting = true }) },
            selection = { SelectionHead(count = selected.size, line = null, allSelected = false, onClose = viewModel::clearSelection, onAll = null) },
        )
        when (val s = album) {
            DriveLoad.Loading -> if (!photos.loaded) DriveLoading()
            is DriveLoad.Error -> DriveErrorBox(s.code, onRetry = viewModel::refresh)
            // the photo frame only with its own right (`canFrame`), not with `canEdit` alone
            is DriveLoad.Ready -> if (s.value.canFrame && me?.has(DMe.FEATURE_FRAME) == true) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { FrameCard(s.value, onFrame = viewModel::setFrame) }
            }
        }
        if (album !is DriveLoad.Error) {
            Box(Modifier.weight(1f)) {
                PhotoGrid(
                    pages = photos,
                    selected = selected,
                    onOpen = { viewing = it },
                    onToggle = { if (canEdit) viewModel.toggle(it) },
                    onMore = viewModel::more,
                    onRetry = viewModel::refresh,
                    empty = stringResource(R.string.drive_album_empty),
                )
            }
            if (selecting) {
                SelectionActionBar(
                    listOf(
                        BarAction(
                            GcIcons.Photo,
                            stringResource(R.string.drive_album_cover),
                            if (selected.size == 1) DriveBulk.Can.YES else DriveBulk.Can.no(DriveBulk.Why.ONLY_ONE),
                        ) { viewModel.coverSelected() },
                        BarAction(GcIcons.Minus, stringResource(R.string.drive_album_remove), DriveBulk.Can.YES, danger = true) { viewModel.removeSelected() },
                    ),
                )
            }
        }
    }

    viewing?.let { start ->
        PhotoViewer(items = photos.items, start = start, fetch = viewModel::fetch, onMore = viewModel::more, onClose = { viewing = null })
    }
    picked?.let { p ->
        PhotoPickerDialog(picker, p, onPick = viewModel::pick, onMore = viewModel::pickerMore, onRetry = viewModel::pickerRetry, onAdd = viewModel::addPicked, onDismiss = viewModel::closePicker)
    }
    if (renaming && a != null) {
        AlbumNameDialog(
            title = stringResource(R.string.drive_album_rename),
            initial = a.name,
            confirm = stringResource(R.string.drive_save),
            areas = emptyList(),
            onConfirm = { name, _ -> renaming = false; if (name != a.name) viewModel.rename(name) },
            onDismiss = { renaming = false },
        )
    }
    if (deleting && a != null) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            text = { Text(stringResource(R.string.drive_album_delete_confirm, a.name)) },
            confirmButton = { TextButton(onClick = { deleting = false; viewModel.delete() }) { Text(stringResource(R.string.drive_album_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.drive_cancel), color = GateControlTheme.extraColors.muted) } },
        )
    }
}

/** The album's bar: back, its name, and with the right to change it "Fotos hinzufügen" and the menu (rename, delete). */
@Composable
private fun AlbumBar(title: String, canEdit: Boolean, onBack: () -> Unit, onAdd: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    GcSubpageBar(title, onBack = onBack, backDescription = stringResource(R.string.drive_back)) {
        if (canEdit) {
            GcIconButton(GcIcons.Plus, contentDescription = stringResource(R.string.drive_album_add), onClick = onAdd, tint = MaterialTheme.colorScheme.onBackground)
            Box {
                GcIconButton(GcIcons.MoreVertical, contentDescription = stringResource(R.string.drive_more), onClick = { menu = true }, tint = MaterialTheme.colorScheme.onBackground)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.drive_album_rename)) },
                        leadingIcon = { Icon(GcIcons.Edit, contentDescription = null) },
                        onClick = { menu = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.drive_album_delete)) },
                        leadingIcon = { Icon(GcIcons.Trash, contentDescription = null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}
