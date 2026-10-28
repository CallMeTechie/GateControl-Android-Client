package com.gatecontrol.android.ui.drive

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DArea
import com.gatecontrol.android.drive.DFolder
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveLogic
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "In Drive speichern" from other parts of the app (a chat attachment, a school letter, GateDrop): the person picks a
 * Drive folder they may write to — the areas first, then the folders below, starting in the folder used last — and
 * the caller saves there (its plugin hands the file to Drive on the server, nothing goes through the phone). A new
 * folder can be made on the way ("Neuer Ordner"); it is then the one shown and picked.
 */
@Composable
internal fun DriveFolderChooser(
    onPicked: (folderId: Long) -> Unit,
    onDismiss: () -> Unit,
    viewModel: DriveFolderChooserViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.start() }
    FolderPickerDialog(
        title = stringResource(R.string.drive_scan_folder_title),
        areas = state.areas,
        folder = state.folder,
        confirm = stringResource(R.string.drive_scan_folder_here),
        canPick = { it.writable },
        onBrowse = viewModel::browse,
        onPick = {
            val id = viewModel.picked() ?: return@FolderPickerDialog
            onPicked(id)
        },
        onDismiss = onDismiss,
        onNewFolder = viewModel::createFolderIn,
    )
}

@HiltViewModel
class DriveFolderChooserViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: DriveApi,
) : ViewModel() {

    data class State(val areas: List<DArea> = emptyList(), val folder: DriveLoad<DFolder>? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val prefs = context.getSharedPreferences("drive_ui", Context.MODE_PRIVATE)
    private var started = false

    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            val areas = DriveLogic.sortAreas(api.areas().valueOrNull().orEmpty().filter { it.writable })
            _state.value = State(areas = areas)
            prefs.getLong(KEY_LAST, 0L).takeIf { it > 0 }?.let { browse(it, initial = true) }
        }
    }

    fun browse(folderId: Long?) = browse(folderId, initial = false)

    private fun browse(folderId: Long?, initial: Boolean) {
        if (folderId == null) {
            _state.value = _state.value.copy(folder = null)
            return
        }
        _state.value = _state.value.copy(folder = DriveLoad.Loading)
        viewModelScope.launch {
            val load = api.folder(folderId, limit = 500).toLoad { it }
            // the folder used last is gone or read-only now: start at the areas
            val gone = initial && (load is DriveLoad.Error || (load as? DriveLoad.Ready)?.value?.writable == false)
            _state.value = _state.value.copy(folder = if (gone) null else load)
        }
    }

    /** "Neuer Ordner" in the folder shown. */
    suspend fun createFolderIn(parentId: Long, name: String): DResult<DNode> = api.createFolder(parentId, name.trim())

    /** The folder shown, remembered for the next time; null when none is (the areas are shown). */
    fun picked(): Long? {
        val f = (_state.value.folder as? DriveLoad.Ready)?.value?.takeIf { it.writable } ?: return null
        prefs.edit().putLong(KEY_LAST, f.node.id).apply()
        return f.node.id
    }

    private companion object {
        const val KEY_LAST = "save_folder"
    }
}
