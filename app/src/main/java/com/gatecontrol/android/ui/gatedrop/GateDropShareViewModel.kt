package com.gatecontrol.android.ui.gatedrop

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.gatedrop.GateDropJson
import com.gatecontrol.android.gatedrop.GateDropLogic
import com.gatecontrol.android.gatedrop.GateDropRepository
import com.gatecontrol.android.gatedrop.GateDropSendWorker
import com.gatecontrol.android.gatedrop.GdJob
import com.gatecontrol.android.gatedrop.GdJobFile
import com.gatecontrol.android.gatedrop.GdJobState
import com.gatecontrol.android.gatedrop.GdLinkRequest
import com.gatecontrol.android.gatedrop.GdLinkResult
import com.gatecontrol.android.gatedrop.GdResult
import com.gatecontrol.android.gatedrop.GdSelection
import com.gatecontrol.android.gatedrop.GdTargets
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject

/** A file shared from another app (read only when sending). */
data class GdSource(val uri: Uri, val name: String, val mime: String, val size: Long?)

/** What the share sheet shows. */
enum class GdShareStage { EDIT, LINK_OPTIONS, PREPARING, WAITING, RESULT, DONE, FAILED }

data class GdShareUi(
    val sources: List<GdSource> = emptyList(),
    val text: String? = null,
    /** Files left out: too many, too big or not readable. */
    val skipped: Int = 0,
    val targets: GdTargets? = null,
    val loading: Boolean = true,
    val error: Boolean = false,
    val selection: GdSelection = GdSelection(),
    val note: String = "",
    /** The link options being edited (sub-screen "Link für Externe"). */
    val linkDraft: GdLinkRequest = GdLinkRequest(),
    val stage: GdShareStage = GdShareStage.EDIT,
    /** Upload / transfer progress while [GdShareStage.WAITING]. */
    val percent: Int? = null,
    val link: GdLinkResult? = null,
) {
    val totalBytes: Long get() = sources.sumOf { it.size ?: 0L }
    val canSend: Boolean get() = targets != null && GateDropLogic.canSend(selection, targets, sources.size, !text.isNullOrBlank())
}

/**
 * The GateDrop share sheet ("Teilen mit GateControl", contract F): loads
 * the targets, keeps the multi-selection, copies the shared files into the
 * app's cache (streamed) and hands the send to [GateDropSendWorker]. With a
 * link for externals the sheet waits for the link and shows it.
 */
@HiltViewModel
class GateDropShareViewModel @Inject constructor(
    app: Application,
    private val repository: GateDropRepository,
) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(GdShareUi())
    val ui: StateFlow<GdShareUi> = _ui.asStateFlow()

    private var started = false
    private var waitJob: Job? = null

    fun start(sources: List<GdSource>, text: String?, skipped: Int) {
        if (started) return
        started = true
        _ui.update { it.copy(sources = sources, text = text?.let { t -> GateDropJson.text(t) }, skipped = skipped, targets = repository.targets.value) }
        loadTargets()
    }

    fun loadTargets() {
        _ui.update { it.copy(loading = true, error = false) }
        viewModelScope.launch {
            val r = repository.refreshTargets()
            _ui.update { it.copy(loading = false, error = r !is GdResult.Ok && it.targets == null, targets = (r as? GdResult.Ok)?.value ?: it.targets) }
        }
    }

    fun togglePerson(id: String) = _ui.update { it.copy(selection = it.selection.togglePerson(id)) }
    fun toggleDevice(id: String) = _ui.update { it.copy(selection = it.selection.toggleDevice(id)) }
    fun toggleNas(id: String) = _ui.update { it.copy(selection = it.selection.toggleNas(id)) }
    fun toggleDrive() = _ui.update { it.copy(selection = it.selection.toggleDrive()) }
    fun setNote(value: String) = _ui.update { it.copy(note = value.take(GateDropJson.NOTE_MAX)) }

    fun openLinkOptions() = _ui.update { it.copy(stage = GdShareStage.LINK_OPTIONS, linkDraft = it.selection.link ?: it.linkDraft) }
    fun setLinkDraft(value: GdLinkRequest) = _ui.update { it.copy(linkDraft = value) }
    fun applyLink() = _ui.update { it.copy(stage = GdShareStage.EDIT, selection = it.selection.copy(link = it.linkDraft)) }
    fun removeLink() = _ui.update { it.copy(stage = GdShareStage.EDIT, selection = it.selection.copy(link = null)) }
    fun closeLinkOptions() = _ui.update { it.copy(stage = GdShareStage.EDIT) }

    /** Copies the files, stores the job and starts the worker. */
    fun send() {
        val s = _ui.value
        val targets = s.targets ?: return
        if (!s.canSend || s.stage != GdShareStage.EDIT) return
        _ui.update { it.copy(stage = GdShareStage.PREPARING) }
        viewModelScope.launch {
            val jobId = newId()
            val files = withContext(Dispatchers.IO) { copyAll(jobId, s.sources) }
            if (files == null) {
                repository.store.dropJob(jobId)
                _ui.update { it.copy(stage = GdShareStage.FAILED) }
                return@launch
            }
            val to = GateDropLogic.recipients(s.selection, targets)
            val shareLink = if (files.isEmpty()) GateDropLogic.asLink(s.text) else null
            val job = GdJob(
                id = jobId,
                files = files,
                text = if (shareLink != null) null else s.text,
                link = shareLink,
                note = s.note.trim().ifBlank { null },
                to = to,
                directDevices = targets.allDevices.filter { it.id in to.devices && it.online && it.direct }.map { it.id },
            )
            repository.store.saveJob(job)
            repository.setJobState(jobId, GdJobState.Preparing)
            val names = GateDropLogic.chosenNames(s.selection, targets, getApplication<Application>().getString(com.gatecontrol.android.R.string.gatedrop_drive)).joinToString(", ").ifBlank { null }
            GateDropSendWorker.enqueue(getApplication(), jobId, names)
            if (to.link == null) {
                _ui.update { it.copy(stage = GdShareStage.DONE) }
                return@launch
            }
            _ui.update { it.copy(stage = GdShareStage.WAITING) }
            waitJob = launch {
                repository.jobs.mapNotNull { it[jobId] }.first { st ->
                    when (st) {
                        is GdJobState.Uploading -> _ui.update { it.copy(percent = st.percent) }
                        is GdJobState.Direct -> _ui.update { it.copy(percent = st.percent) }
                        else -> Unit
                    }
                    st is GdJobState.Done || st is GdJobState.Failed
                }.let { st ->
                    when (st) {
                        is GdJobState.Done -> _ui.update { it.copy(stage = if (st.link != null) GdShareStage.RESULT else GdShareStage.DONE, link = st.link) }
                        else -> _ui.update { it.copy(stage = GdShareStage.FAILED) }
                    }
                }
            }
        }
    }

    /** Streams every shared file into the job's folder; null when none could be read. */
    private fun copyAll(jobId: String, sources: List<GdSource>): List<GdJobFile>? {
        if (sources.isEmpty()) return emptyList()
        val dir = repository.store.jobFilesDir(jobId)
        val resolver = getApplication<Application>().contentResolver
        val out = sources.mapIndexedNotNull { i, src ->
            try {
                val target = File(dir, "$i-" + GateDropJson.safeName(src.name).take(80))
                var total = 0L
                resolver.openInputStream(src.uri)?.use { input ->
                    target.outputStream().use { o ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > GateDropLogic.MAX_FILE_BYTES) throw java.io.IOException("too large")
                            o.write(buf, 0, n)
                        }
                    }
                } ?: return@mapIndexedNotNull null
                GdJobFile(target.path, src.name, src.mime, total)
            } catch (e: Exception) {
                Timber.w("GateDrop: shared file not read (%s)", e.javaClass.simpleName)
                null
            }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private fun newId(): String {
        val b = ByteArray(12).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }
}
