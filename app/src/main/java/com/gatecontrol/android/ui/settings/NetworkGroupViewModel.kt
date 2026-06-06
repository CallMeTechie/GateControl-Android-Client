// FILE: app/src/main/java/com/gatecontrol/android/ui/settings/NetworkGroupViewModel.kt
//
// ViewModel 驱动两个屏幕：
//   • NetworkGroupListScreen  — 分组列表（替代旧的 NetworkPresetsSection inline 展开）
//   • NetworkGroupEditScreen  — 单个分组内部的 CIDR 管理

package com.gatecontrol.android.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.data.NetworkGroupRepository
import com.gatecontrol.android.data.db.NetworkCidrEntity
import com.gatecontrol.android.data.db.NetworkGroupEntity
import com.gatecontrol.android.data.db.NetworkGroupWithCidrs
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

// ── List screen state ──────────────────────────────────────────────────────

data class NetworkGroupListUiState(
    val groups: List<NetworkGroupWithCidrs> = emptyList(),
    val isLoading: Boolean = false,
    val snackbar: String? = null,
)

// ── Edit screen state ──────────────────────────────────────────────────────

data class NetworkGroupEditUiState(
    val groupId: Long = -1L,
    val groupName: String = "",
    val allCidrs: List<NetworkCidrEntity> = emptyList(),
    val filteredCidrs: List<NetworkCidrEntity> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val snackbar: String? = null,
    /** Set to a File when export is ready for the caller to share. */
    val exportFile: File? = null,
)

// ══════════════════════════════════════════════════════════════════════════════
// NetworkGroupListViewModel
// ══════════════════════════════════════════════════════════════════════════════

@HiltViewModel
class NetworkGroupListViewModel @Inject constructor(
    private val repo: NetworkGroupRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(NetworkGroupListUiState())
    val state: StateFlow<NetworkGroupListUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repo.migrateFromDataStoreIfNeeded()
        }
        viewModelScope.launch {
            repo.observeAllGroupsWithCidrs().collect { groups ->
                _state.update { it.copy(groups = groups) }
            }
        }
    }

    fun createGroup(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repo.createGroup(name.trim())
        }
    }

    fun setGroupEnabled(groupId: Long, enabled: Boolean) {
        viewModelScope.launch {
            repo.setGroupEnabled(groupId, enabled)
        }
    }

    fun deleteGroup(groupId: Long) {
        viewModelScope.launch {
            repo.deleteGroup(groupId)
            _state.update { it.copy(snackbar = "Group deleted") }
        }
    }

    /** Import a group from a user-picked SQLite file URI. */
    fun importGroup(context: Context, uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                // Copy to cache first (Room / SQLite can't open content:// URIs directly)
                val tmp = File(context.cacheDir, "import_${System.currentTimeMillis()}.sqlite3")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                val newId = repo.importGroup(tmp)
                tmp.delete()
                if (newId >= 0) {
                    _state.update { it.copy(isLoading = false, snackbar = "Group imported successfully") }
                } else {
                    _state.update { it.copy(isLoading = false, snackbar = "Import failed — invalid file") }
                }
            } catch (e: Exception) {
                Timber.e(e, "importGroup failed")
                _state.update { it.copy(isLoading = false, snackbar = "Import error: ${e.localizedMessage}") }
            }
        }
    }

    fun clearSnackbar() = _state.update { it.copy(snackbar = null) }
}

// ══════════════════════════════════════════════════════════════════════════════
// NetworkGroupEditViewModel
// ══════════════════════════════════════════════════════════════════════════════

@HiltViewModel
class NetworkGroupEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: NetworkGroupRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    // Route argument: "networkGroupEdit/{groupId}"
    private val groupId: Long = savedStateHandle.get<Long>("groupId") ?: -1L

    private val _state = MutableStateFlow(NetworkGroupEditUiState(groupId = groupId))
    val state: StateFlow<NetworkGroupEditUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repo.observeCidrsForGroup(groupId).collect { cidrs ->
                _state.update { s ->
                    val filtered = applyFilter(cidrs, s.searchQuery)
                    s.copy(allCidrs = cidrs, filteredCidrs = filtered)
                }
            }
        }
    }

    /** Call after navigation to load the group name. */
    fun loadGroupName(name: String) {
        _state.update { it.copy(groupName = name) }
    }

    // ── Search ────────────────────────────────────────────────────────────

    fun onSearchQueryChanged(query: String) {
        _state.update { s ->
            s.copy(
                searchQuery = query,
                filteredCidrs = applyFilter(s.allCidrs, query),
            )
        }
    }

    private fun applyFilter(cidrs: List<NetworkCidrEntity>, query: String): List<NetworkCidrEntity> {
        if (query.isBlank()) return cidrs
        val q = query.trim().lowercase()
        return cidrs.filter { it.cidr.lowercase().contains(q) || it.label.lowercase().contains(q) }
    }

    // ── Add ───────────────────────────────────────────────────────────────

    /** Single CIDR add. Returns error message or null on success. */
    fun addCidr(cidr: String, label: String = ""): String? {
        if (cidr.isBlank()) return "CIDR cannot be empty"
        var result: String? = null
        viewModelScope.launch {
            val ok = repo.addCidr(groupId, cidr.trim(), label.trim())
            if (!ok) {
                _state.update { it.copy(snackbar = "Invalid or duplicate CIDR: $cidr") }
                result = "Invalid or duplicate"
            }
        }
        return result
    }

    /** Bulk add — newline-separated CIDRs. */
    fun addCidrsBulk(rawText: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val (added, skipped) = repo.addCidrsBulk(groupId, rawText)
            _state.update {
                it.copy(
                    isLoading = false,
                    snackbar = "Added $added, skipped $skipped (invalid or duplicate)",
                )
            }
        }
    }

    // ── Delete ────────────────────────────────────────────────────────────

    fun deleteCidr(cidr: NetworkCidrEntity) {
        viewModelScope.launch {
            repo.deleteCidr(cidr)
        }
    }

    // ── Export ────────────────────────────────────────────────────────────

    fun exportGroup() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val file = repo.exportGroup(groupId)
            if (file != null) {
                _state.update { it.copy(isLoading = false, exportFile = file) }
            } else {
                _state.update { it.copy(isLoading = false, snackbar = "Export failed") }
            }
        }
    }

    fun clearExportFile() = _state.update { it.copy(exportFile = null) }

    fun clearSnackbar() = _state.update { it.copy(snackbar = null) }
}
