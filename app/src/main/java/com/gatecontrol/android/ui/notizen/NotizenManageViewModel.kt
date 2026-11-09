package com.gatecontrol.android.ui.notizen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.notizen.NDeleted
import com.gatecontrol.android.notizen.NFeature
import com.gatecontrol.android.notizen.NGroup
import com.gatecontrol.android.notizen.NList
import com.gatecontrol.android.notizen.NListUsage
import com.gatecontrol.android.notizen.NMe
import com.gatecontrol.android.notizen.NNotebook
import com.gatecontrol.android.notizen.NNotebookUsage
import com.gatecontrol.android.notizen.NPersonPick
import com.gatecontrol.android.notizen.NResult
import com.gatecontrol.android.notizen.NotizenJson
import com.gatecontrol.android.notizen.NotizenLists
import com.gatecontrol.android.notizen.NotizenRepository
import com.google.gson.JsonObject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Making, changing, sharing and deleting lists, groups and notebooks (the side menu, a list's page, a notebook's
 * notes). Each change goes to the server at once; the screens load again through [NotizenRepository.changed].
 */
@HiltViewModel
class NotizenManageViewModel @Inject constructor(private val repo: NotizenRepository) : ViewModel() {

    /** „„Einkaufsliste“ gelöscht – Rückgängig“: what can be brought back. */
    data class Undo(val kind: String, val id: Long, val name: String)

    data class Ui(
        val me: NMe? = null,
        /** The people one may share with or assign to (`GET people`), loaded when needed. */
        val people: List<NPersonPick>? = null,
        val listUsage: Pair<Long, NListUsage>? = null,
        val notebookUsage: Pair<Long, NNotebookUsage>? = null,
        /** A list or notebook as the server has it after a change (the share dialog shows it). */
        val list: NList? = null,
        val notebook: NNotebook? = null,
        val busy: Boolean = false,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()
    private val _message = MutableStateFlow<NMessage?>(null)
    val message: StateFlow<NMessage?> = _message.asStateFlow()
    /** Shared by the screens: a list deleted on its own page offers „Rückgängig“ on the tab it goes back to. */
    private val _undo get() = sharedUndo
    val undo: StateFlow<Undo?> = sharedUndo.asStateFlow()

    init {
        viewModelScope.launch { _ui.update { it.copy(me = repo.meOrCached()) } }
    }

    fun consumeMessage() { _message.value = null }
    fun consumeUndo() { _undo.value = null }

    private fun has(f: String) = _ui.value.me?.has(f) == true

    private fun done(r: NResult<*>, what: String, ok: NMessage? = null) {
        _message.value = r.code() ?: ok
        repo.touched(what)
    }

    fun loadPeople() {
        if (_ui.value.people != null) return
        viewModelScope.launch {
            when (val r = repo.api.people()) {
                is NResult.Ok -> _ui.update { it.copy(people = r.value) }
                else -> _message.value = r.code()
            }
        }
    }

    // ── lists ───────────────────────────────────────────────

    fun createList(name: String, area: String, groupId: Long?) {
        viewModelScope.launch {
            val r = repo.createList(name, area, groupId)
            _message.value = r.code() ?: if (r.valueOrNull()?.offline == true) "queued" else "list_created"
        }
    }

    /**
     * A new group with [lists] in it: in one step with plugin 1.2.0 (`groupEdit`), else the group first and then
     * each list into it.
     */
    fun createGroup(name: String, area: String, lists: List<Long>) {
        viewModelScope.launch {
            if (has(NFeature.GROUP_EDIT) || lists.isEmpty()) {
                val r = repo.api.createGroup(name, area, lists)
                done(r, "lists", "group_created")
                return@launch
            }
            val r = repo.api.createGroup(name, area, emptyList())
            val gid = r.valueOrNull()?.id
            if (gid == null) {
                done(r, "lists")
                return@launch
            }
            var failed: NResult<*>? = null
            lists.forEach { id -> repo.api.patchList(id, NotizenJson.listPatch(groupId = gid)).takeIf { it !is NResult.Ok }?.let { failed = it } }
            done(failed ?: r, "lists", "group_created")
        }
    }

    fun renameGroup(g: NGroup, name: String) {
        viewModelScope.launch { done(repo.api.renameGroup(g.id, name), "lists") }
    }

    fun dissolveGroup(g: NGroup) {
        viewModelScope.launch { done(repo.api.deleteGroup(g.id), "lists", "group_dissolved") }
    }

    /** „Liste bearbeiten“, „In Gruppe verschieben“, „In ‚Mein Tag‘ zeigen“: only the fields that changed. */
    fun patchList(l: NList, body: JsonObject, ok: NMessage? = null) {
        if (body.size() == 0) return
        viewModelScope.launch {
            val r = repo.api.patchList(l.id, body)
            r.valueOrNull()?.let { nl -> _ui.update { it.copy(list = nl) } }
            done(r, "lists", ok)
        }
    }

    fun loadListUsage(l: NList) {
        _ui.update { it.copy(listUsage = null) }
        if (!has(NFeature.DELETE_KEEP)) return
        viewModelScope.launch {
            repo.api.listUsage(l.id).valueOrNull()?.let { u -> _ui.update { it.copy(listUsage = l.id to u) } }
        }
    }

    /** Deletes [l] (owner): [tasks] `move` or `delete` (plugin 1.2.0) — then „Rückgängig“ while the server keeps it. */
    fun deleteList(l: NList, tasks: String?, then: () -> Unit = {}) {
        viewModelScope.launch {
            val keep = has(NFeature.DELETE_KEEP)
            val r = repo.api.deleteList(l.id, if (keep) tasks else null)
            if (r is NResult.Ok) {
                then()
                if (keep && r.value.restoreUntil != null) _undo.value = Undo("list", l.id, l.name) else _message.value = "list_deleted"
            } else {
                _message.value = r.code()
            }
            repo.touched("lists", "tasks")
        }
    }

    fun leaveList(l: NList, then: () -> Unit = {}) {
        viewModelScope.launch {
            val r = repo.api.deleteList(l.id, null)
            if (r is NResult.Ok) then()
            done(r, "lists", "list_left")
        }
    }

    /** The members of [l] after a change (owner): with roles where the server knows them (1.2.0, `shareRoles`). */
    fun setListMembers(l: NList, members: List<Pair<Long, String>>) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val r = repo.api.listMembers(l.id, members, has(NFeature.SHARE_ROLES))
            _ui.update { it.copy(busy = false, list = r.valueOrNull() ?: it.list) }
            done(r, "lists")
        }
    }

    fun shareList(l: NList) {
        _ui.update { it.copy(list = l) }
        loadPeople()
    }

    // ── notebooks ───────────────────────────────────────────

    fun patchNotebook(nb: NNotebook, body: JsonObject) {
        if (body.size() == 0) return
        viewModelScope.launch {
            val r = repo.api.patchNotebook(nb.id, body)
            r.valueOrNull()?.let { n -> _ui.update { it.copy(notebook = n) } }
            done(r, "notebooks")
        }
    }

    fun shareNotebook(nb: NNotebook) {
        _ui.update { it.copy(notebook = nb) }
        loadPeople()
    }

    fun setNotebookMembers(nb: NNotebook, members: List<Pair<Long, String>>) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val r = repo.api.notebookMembers(nb.id, members, has(NFeature.SHARE_ROLES))
            _ui.update { it.copy(busy = false, notebook = r.valueOrNull() ?: it.notebook) }
            done(r, "notebooks")
        }
    }

    fun loadNotebookUsage(nb: NNotebook) {
        _ui.update { it.copy(notebookUsage = null) }
        if (!has(NFeature.DELETE_KEEP)) return
        viewModelScope.launch {
            repo.api.notebookUsage(nb.id).valueOrNull()?.let { u -> _ui.update { it.copy(notebookUsage = nb.id to u) } }
        }
    }

    fun deleteNotebook(nb: NNotebook, name: String, notes: String?, then: () -> Unit = {}) {
        viewModelScope.launch {
            val keep = has(NFeature.DELETE_KEEP)
            val r = repo.api.deleteNotebook(nb.id, if (keep) notes else null)
            if (r is NResult.Ok) {
                then()
                if (keep && r.value.restoreUntil != null) _undo.value = Undo("notebook", nb.id, name) else _message.value = "notebook_deleted"
            } else {
                _message.value = r.code()
            }
            repo.touched("notebooks", "notes")
        }
    }

    fun leaveNotebook(nb: NNotebook, then: () -> Unit = {}) {
        viewModelScope.launch {
            val r: NResult<NDeleted> = repo.api.deleteNotebook(nb.id, null)
            if (r is NResult.Ok) then()
            done(r, "notebooks", "notebook_left")
        }
    }

    // ── undo ────────────────────────────────────────────────

    fun restore(u: Undo) {
        _undo.value = null
        viewModelScope.launch {
            val r = if (u.kind == "list") repo.api.restoreList(u.id) else repo.api.restoreNotebook(u.id)
            _message.value = r.code() ?: "restored"
            repo.touched("lists", "tasks", "notebooks", "notes")
        }
    }

    companion object {
        private val sharedUndo = MutableStateFlow<Undo?>(null)
    }

    /** The people of a list who may be picked (assign): its owner and members. */
    fun pickable(l: NList): List<NotizenLists.Filter.Person> =
        NotizenLists.people(l).mapNotNull { p -> p.id?.let { NotizenLists.Filter.Person(it, p.name ?: "") } }
}
