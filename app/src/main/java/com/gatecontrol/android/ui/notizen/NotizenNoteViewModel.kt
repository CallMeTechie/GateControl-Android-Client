package com.gatecontrol.android.ui.notizen

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.notizen.NAttachment
import com.gatecontrol.android.notizen.NMe
import com.gatecontrol.android.notizen.NNote
import com.gatecontrol.android.notizen.NNotebook
import com.gatecontrol.android.notizen.NResult
import com.gatecontrol.android.notizen.NVault
import com.gatecontrol.android.notizen.NoteMd
import com.gatecontrol.android.notizen.NotizenJson
import com.gatecontrol.android.notizen.NotizenRepository
import com.gatecontrol.android.notizen.NotizenVaultSession
import com.gatecontrol.android.drive.DVaultDevice
import com.google.gson.JsonObject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One note (mockup Notiz): the text drawn or edited with the toolbar, autosaved (with `rev`: a change made elsewhere
 * meanwhile is not overwritten), pinned, coloured, check boxes ticked, a line made a task, pictures and files. In a
 * Tresor the title and text are decrypted in memory only and sent back encrypted.
 */
@HiltViewModel
class NotizenNoteViewModel @Inject constructor(
    private val repo: NotizenRepository,
    private val vault: NotizenVaultSession,
    saved: SavedStateHandle,
) : ViewModel() {
    private var id: Long = saved.get<String>("id")?.toLongOrNull() ?: 0L
    private val notebookArg: Long? = saved.get<String>("notebook")?.toLongOrNull()?.takeIf { it > 0 }
    private val startText: String = saved.get<String>("text").orEmpty()

    data class Ui(
        val note: NNote? = null,
        val me: NMe? = null,
        val title: String = "",
        val body: String = "",
        val editing: Boolean = false,
        val dirty: Boolean = false,
        val saving: Boolean = false,
        val savedOnce: Boolean = false,
        val loading: Boolean = true,
        val offline: Boolean = false,
        /** The note is in a Tresor that is locked on this phone. */
        val locked: Boolean = false,
        val vaultId: String? = null,
        val conflict: Boolean = false,
        val gone: Boolean = false,
        /** attachment id → its bytes (pictures shown in the text; Tresor pictures decrypted in memory). */
        val pictures: Map<Long, ByteArray> = emptyMap(),
        val notebooks: List<NNotebook> = emptyList(),
    )

    private val _ui = MutableStateFlow(Ui(editing = id == 0L))
    val ui: StateFlow<Ui> = _ui.asStateFlow()
    private val _message = MutableStateFlow<NMessage?>(null)
    val message: StateFlow<NMessage?> = _message.asStateFlow()

    private var saveJob: Job? = null

    init {
        load()
    }

    fun consumeMessage() { _message.value = null }

    fun load() {
        viewModelScope.launch {
            val me = repo.meOrCached()
            val area = repo.currentArea()
            val nbs = repo.nav(area).valueOrNull()?.value?.notebooks.orEmpty()
            _ui.update { it.copy(me = me, notebooks = nbs) }
            if (id == 0L) {
                val (t, b) = if (startText.isNotBlank()) NoteMd.splitTitle(startText) else ("" to "")
                val nb = nbs.firstOrNull { it.id == notebookArg }
                _ui.update { it.copy(loading = false, title = t, body = b, dirty = startText.isNotBlank(), vaultId = nb?.vault?.id) }
                return@launch
            }
            when (val r = repo.note(id)) {
                is NResult.Ok -> show(r.value.value, r.value.offline)
                is NResult.Failed -> {
                    _ui.update { it.copy(loading = false, gone = r.http == 404) }
                    _message.value = r.code()
                }
                else -> _ui.update { it.copy(loading = false, offline = true) }
            }
        }
    }

    private fun vaultIdOf(n: NNote): String? = _ui.value.notebooks.firstOrNull { it.id == n.summary.notebookId }?.vault?.id

    private fun show(n: NNote, offline: Boolean) {
        val vid = if (n.summary.encrypted) vaultIdOf(n) else null
        if (n.summary.encrypted) {
            val title = vid?.let { vault.name(it, n.summary.titleCipher) }
            val body = vid?.let { vault.text(it, n.cipher) }
            if (title == null && body == null) {
                _ui.update { it.copy(note = n, loading = false, offline = offline, locked = true, vaultId = vid) }
                return
            }
            _ui.update { it.copy(note = n, loading = false, offline = offline, locked = false, vaultId = vid, title = if (it.dirty) it.title else title.orEmpty().trim(), body = if (it.dirty) it.body else body.orEmpty()) }
        } else {
            _ui.update { it.copy(note = n, loading = false, offline = offline, locked = false, title = if (it.dirty) it.title else n.summary.title.orEmpty(), body = if (it.dirty) it.body else n.body.orEmpty()) }
        }
        loadPictures(n)
    }

    private fun loadPictures(n: NNote) {
        val wanted = NoteMd.attachments(_ui.value.body).toSet()
        val pics = n.attachments.filter { it.id in wanted && (it.encrypted || it.mime?.startsWith("image/") == true) && it.id !in _ui.value.pictures }
        if (pics.isEmpty()) return
        viewModelScope.launch {
            for (a in pics.take(20)) {
                val raw = a.url?.let { repo.api.download(it) } ?: continue
                val bytes = if (a.encrypted) _ui.value.vaultId?.let { vault.openFile(it, raw) } ?: continue else raw
                _ui.update { it.copy(pictures = it.pictures + (a.id to bytes)) }
            }
        }
    }

    /** After the Tresor was opened on the vault screen. */
    fun unlocked() = _ui.value.note?.let { show(it, _ui.value.offline) } ?: load()

    fun startEditing() {
        if (_ui.value.offline || _ui.value.locked || _ui.value.me?.readOnly == true) {
            _message.value = if (_ui.value.offline) "offline_read" else if (_ui.value.locked) "locked" else "read_only"
            return
        }
        _ui.update { it.copy(editing = true) }
    }

    fun stopEditing() {
        _ui.update { it.copy(editing = false) }
        saveNow()
    }

    fun setTitle(t: String) {
        _ui.update { it.copy(title = t.take(300), dirty = true) }
        saveSoon()
    }

    fun setBody(b: String) {
        _ui.update { it.copy(body = b, dirty = true) }
        saveSoon()
    }

    private fun saveSoon() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(AUTOSAVE_MS)
            save()
        }
    }

    fun saveNow() {
        saveJob?.cancel()
        viewModelScope.launch { save() }
    }

    private suspend fun save() {
        val u = _ui.value
        if (!u.dirty || u.saving) return
        if (u.title.isBlank() && u.body.isBlank()) return
        _ui.update { it.copy(saving = true) }
        val vid = u.vaultId
        val r: NResult<NNote> = if (id == 0L) {
            val nbId = notebookArg
            if (vid != null && nbId != null) {
                val sealed = vault.seal(vid, u.title, u.body) ?: return locked()
                repo.api.createNote(NotizenJson.vaultNoteBody(nbId, sealed.first, sealed.second, sealed.third))
            } else {
                repo.api.createNote(NotizenJson.noteBody(nbId, repo.currentArea(), u.title.trim(), u.body))
            }
        } else {
            val body = JsonObject().apply {
                u.note?.summary?.rev?.let { addProperty("rev", it) }
                if (vid != null) {
                    val sealed = vault.seal(vid, u.title, u.body) ?: return locked()
                    addProperty("titleCipher", sealed.first)
                    addProperty("cipher", sealed.second)
                    addProperty("keyVersion", sealed.third)
                } else {
                    addProperty("title", u.title.trim())
                    addProperty("body", u.body)
                }
            }
            repo.api.patchNote(id, body)
        }
        when (r) {
            is NResult.Ok -> {
                id = r.value.summary.id
                // only what was saved is clean: typing meanwhile stays dirty
                _ui.update { it.copy(note = r.value, saving = false, savedOnce = true, dirty = it.title != u.title || it.body != u.body, conflict = false) }
                repo.touched("notes")
            }
            is NResult.Failed -> {
                _ui.update { it.copy(saving = false, conflict = r.http == 409 && r.code == "conflict") }
                _message.value = if (r.http == 409 && r.code == "conflict") "note_conflict" else r.code()
            }
            else -> {
                _ui.update { it.copy(saving = false) }
                _message.value = "offline_write"
            }
        }
    }

    private fun locked(): Unit {
        _ui.update { it.copy(saving = false, locked = true) }
        _message.value = "locked"
    }

    /** The other device's text wins: the note as it is now (this phone's text is offered to copy first). */
    fun takeTheirs() {
        _ui.update { it.copy(dirty = false, conflict = false) }
        load()
    }

    /** This phone's text wins: saved over the newer one. */
    fun keepMine() {
        viewModelScope.launch {
            val r = repo.api.note(id)
            if (r is NResult.Ok) {
                _ui.update { it.copy(note = r.value.first, conflict = false, dirty = true) }
                save()
            }
        }
    }

    private fun patch(body: JsonObject) {
        if (id == 0L) return
        viewModelScope.launch {
            val r = repo.api.patchNote(id, body)
            if (r is NResult.Ok) {
                _ui.update { it.copy(note = r.value) }
                repo.touched("notes")
            } else {
                _message.value = r.code()
            }
        }
    }

    fun setPinned(v: Boolean) = patch(JsonObject().apply { addProperty("pinned", v) })
    fun setColor(c: String?) = patch(JsonObject().apply { if (c == null) addProperty("color", "none") else addProperty("color", c) })
    fun moveTo(notebookId: Long) = patch(JsonObject().apply { addProperty("notebookId", notebookId) })

    fun delete(done: () -> Unit) {
        if (id == 0L) { done(); return }
        viewModelScope.launch {
            val r = repo.api.deleteNote(id)
            if (r is NResult.Ok) { repo.touched("notes"); done() } else _message.value = r.code()
        }
    }

    /** A check box of the text ticked (the server ticks a line that became a task too). */
    fun check(line: Int, done: Boolean) {
        val u = _ui.value
        val local = NoteMd.toggle(u.body, line, done) ?: return
        if (u.vaultId != null || id == 0L || u.dirty) {
            setBody(local)
            return
        }
        _ui.update { it.copy(body = local) }
        viewModelScope.launch {
            val r = repo.api.checkLine(id, line, done)
            if (r is NResult.Ok) _ui.update { it.copy(note = r.value, body = r.value.body ?: local) } else {
                _message.value = r.code()
                load()
            }
        }
    }

    /** „Zeile als Aufgabe übernehmen“: the line becomes a task, the line links it („[Aufgabe](task:<id>)“). */
    fun lineToTask(line: Int) {
        val u = _ui.value
        if (u.vaultId != null) { _message.value = "vault_no_task"; return }
        if (NoteMd.lineText(u.body, line) == null) { _message.value = "line_empty"; return }
        viewModelScope.launch {
            if (u.dirty || id == 0L) save()
            if (id == 0L) return@launch
            val r = repo.api.lineToTask(id, line)
            _message.value = r.code() ?: "line_task"
            if (r is NResult.Ok) {
                _ui.update { it.copy(dirty = false) }
                repo.touched("tasks", "notes")
                load()
            }
        }
    }

    /** A picture (taken or picked) or a file → an attachment, linked in the text at the cursor. */
    fun attach(bytes: ByteArray, mime: String, name: String, cursor: Int, ocr: (suspend (ByteArray) -> String?)? = null) {
        viewModelScope.launch {
            val u = _ui.value
            val limit = (u.me?.attachMB ?: 25) * 1024L * 1024L
            if (bytes.size > limit) { _message.value = "too_large"; return@launch }
            if (id == 0L) save()
            if (id == 0L) { _message.value = "save_first"; return@launch }
            _ui.update { it.copy(saving = true) }
            val vid = u.vaultId
            val up: NResult<String>
            var nameCipher: String? = null
            if (vid != null) {
                val sealed = vault.sealFile(vid, bytes, name) ?: run { _ui.update { it.copy(saving = false) }; locked(); return@launch }
                nameCipher = sealed.second
                up = repo.api.upload(sealed.first, "application/octet-stream")
            } else {
                up = repo.api.upload(bytes, mime)
            }
            val r = if (up is NResult.Ok) repo.api.attach(NotizenJson.attachBody("note", id, up.value, if (vid == null) name else null, nameCipher)) else up.let { NResult.Failed(it.code() ?: "failed", 0) }
            _ui.update { it.copy(saving = false) }
            if (r is NResult.Ok) {
                val a: NAttachment = r.value
                val label = name.replace(Regex("[\\[\\]()]"), "").take(80).ifEmpty { "Bild" }
                val snippet = if (mime.startsWith("image/")) "![$label](att:${a.id})" else "[$label](att:${a.id})"
                _ui.update { it.copy(pictures = if (mime.startsWith("image/")) it.pictures + (a.id to bytes) else it.pictures) }
                setBody(NoteMd.insertLine(_ui.value.body, cursor, snippet).text)
                // the text the phone recognises in a picture goes to the search (never for a Tresor)
                if (vid == null && ocr != null && mime.startsWith("image/")) ocr(bytes)?.takeIf { it.isNotBlank() }?.let { repo.api.attachmentText(a.id, it) }
            } else {
                _message.value = r.code()
            }
        }
    }

    suspend fun download(a: NAttachment): ByteArray? {
        val url = a.url ?: repo.api.attachment(a.id).valueOrNull()?.url ?: return null
        return repo.api.download(url)
    }

    override fun onCleared() {
        // what was typed last goes out even when the screen closes before the autosave
        if (_ui.value.dirty) repo.saveLater { save() }
    }

    private companion object {
        const val AUTOSAVE_MS = 1_500L
    }
}

/** A Tresor notebook on this phone: open it (device confirmation), ask for access, recover, approve, a new key. */
@HiltViewModel
class NotizenVaultViewModel @Inject constructor(
    private val repo: NotizenRepository,
    val session: NotizenVaultSession,
    saved: SavedStateHandle,
) : ViewModel() {
    val vaultId: String = saved.get<String>("vault").orEmpty()

    data class Ui(
        val vault: NVault? = null,
        val access: NotizenVaultSession.Access? = null,
        val requests: List<DVaultDevice> = emptyList(),
        val busy: Boolean = false,
        val name: String? = null,
        val loading: Boolean = true,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()
    private val _message = MutableStateFlow<NMessage?>(null)
    val message: StateFlow<NMessage?> = _message.asStateFlow()

    init {
        load()
        viewModelScope.launch { repo.vaultEvents.collect { if (it == vaultId) load() } }
    }

    fun consumeMessage() { _message.value = null }

    fun load() {
        viewModelScope.launch {
            val v = repo.api.vault(vaultId)
            val vault = v.valueOrNull()
            val open = session.unlocked(vaultId)
            _ui.update {
                it.copy(
                    vault = vault ?: it.vault,
                    loading = false,
                    access = if (open) NotizenVaultSession.Access.Open else it.access,
                    name = vault?.let { x -> session.name(x.id, x.nameCipher) },
                )
            }
            if (open && (vault?.pending ?: 0) > 0) _ui.update { it.copy(requests = session.requests(vaultId)) }
            if (v !is NResult.Ok) _message.value = v.code()
        }
    }

    /** Right after the device confirmation. */
    fun open(after: () -> Unit = {}) {
        val v = _ui.value.vault ?: return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val r = session.open(v)
            _ui.update { it.copy(busy = false, access = r.valueOrNull() ?: it.access) }
            if (r is NResult.Ok && r.value == NotizenVaultSession.Access.Open) {
                load()
                after()
            } else if (r !is NResult.Ok) {
                _message.value = r.code()
            }
        }
    }

    fun ask() {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val r = session.ask(vaultId)
            _ui.update { it.copy(busy = false, access = r.valueOrNull() ?: it.access) }
            _message.value = r.code()
        }
    }

    fun recover(text: String, after: () -> Unit = {}) {
        val v = _ui.value.vault ?: return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val r = session.recover(v, text)
            _ui.update { it.copy(busy = false, access = r.valueOrNull() ?: it.access) }
            if (r is NResult.Ok) { load(); after() } else _message.value = r.code()
        }
    }

    fun approve(d: DVaultDevice) {
        viewModelScope.launch {
            _message.value = session.approve(vaultId, d).code() ?: "approved"
            load()
        }
    }

    fun rotate() {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val r = session.rotate(vaultId)
            _ui.update { it.copy(busy = false) }
            _message.value = r.code() ?: "rotated"
            repo.touched("notebooks")
            load()
        }
    }

    fun lock() {
        session.lock()
        _ui.update { it.copy(access = null, name = null) }
    }
}
