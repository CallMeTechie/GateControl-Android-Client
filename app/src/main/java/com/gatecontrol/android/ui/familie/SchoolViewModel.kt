package com.gatecontrol.android.ui.familie

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.errorCode
import com.gatecontrol.android.familie.school.SchoolAttachment
import com.gatecontrol.android.familie.school.SchoolMessage
import com.gatecontrol.android.familie.school.SchoolChange
import com.gatecontrol.android.familie.school.SchoolFiles
import com.gatecontrol.android.familie.school.SchoolInbox
import com.gatecontrol.android.familie.school.SchoolLocalFile
import com.gatecontrol.android.familie.school.SchoolLogic
import com.gatecontrol.android.familie.school.SchoolRepository
import com.gatecontrol.android.familie.school.SchoolTimetable
import com.gatecontrol.android.familie.school.SchoolView
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

/** A failed action on [id] (a message or an attachment) with its error [code]. */
data class SchoolActionError(val id: String, val code: String)

/** The tabs of the timetable screen; MESSAGES and ABSENCES only with plugin 1.4+. */
enum class SchoolTab { PLAN, CHANGES, MESSAGES, ABSENCES }

/**
 * Stundenplan screen: this week and next (the range the Familie card
 * keeps, offline too), or — for a push about a later day — the two weeks
 * from that day's Monday; the selected child and day; the change log;
 * messages and absences (plugin 1.4+, [inbox]) with the open message.
 * Opening a message marks it read ([SchoolRepository.markRead]); its read
 * confirmation and attachments (plugin 1.5+) are handled here too.
 */
@HiltViewModel
class SchoolViewModel @Inject constructor(
    private val repository: SchoolRepository,
    private val schoolFiles: SchoolFiles,
) : ViewModel() {

    private val today: LocalDate get() = LocalDate.now()

    private val _windowStart = MutableStateFlow(SchoolLogic.windowStart(today))
    /** First day of the loaded range (a Monday). */
    val windowStart: StateFlow<LocalDate> = _windowStart.asStateFlow()

    /** A range other than the usual one, loaded for a push. */
    private val _other = MutableStateFlow<Pair<LocalDate, SchoolTimetable>?>(null)

    /** The timetable of the shown range; null while there is none. */
    val timetable: StateFlow<SchoolTimetable?> = combine(repository.snapshot, _windowStart, _other) { snap, start, other ->
        if (start == SchoolLogic.windowStart(today)) {
            snap?.takeIf { it.from == start.toString() }?.timetable
        } else {
            other?.takeIf { it.first == start }?.second
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _studentId = MutableStateFlow<Int?>(null)
    val studentId: StateFlow<Int?> = _studentId.asStateFlow()

    private val _date = MutableStateFlow(SchoolLogic.defaultDay(LocalDateTime.now()))
    val date: StateFlow<LocalDate> = _date.asStateFlow()

    /** The day was picked (by the person or a push): the default no longer moves it. */
    private var datePicked = false

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _changes = MutableStateFlow<List<SchoolChange>?>(null)
    val changes: StateFlow<List<SchoolChange>?> = _changes.asStateFlow()

    private val _changesError = MutableStateFlow<String?>(null)
    val changesError: StateFlow<String?> = _changesError.asStateFlow()

    /** Messages and absences (plugin 1.4+); a list is null when the plugin does not offer it. */
    val inbox: StateFlow<SchoolInbox?> = repository.inbox

    private val _tab = MutableStateFlow(SchoolTab.PLAN)
    val tab: StateFlow<SchoolTab> = _tab.asStateFlow()

    private val _messageId = MutableStateFlow<String?>(null)
    /** The message shown in full (tab "Nachrichten"); null = the list. */
    val messageId: StateFlow<String?> = _messageId.asStateFlow()

    private val _confirming = MutableStateFlow<String?>(null)
    /** Message whose read confirmation is being sent. */
    val confirming: StateFlow<String?> = _confirming.asStateFlow()

    private val _confirmError = MutableStateFlow<SchoolActionError?>(null)
    /** The last failed confirmation (message id and error code). */
    val confirmError: StateFlow<SchoolActionError?> = _confirmError.asStateFlow()

    private val _loadingAttachments = MutableStateFlow<Set<String>>(emptySet())
    /** Attachments being fetched (their ids). */
    val loadingAttachments: StateFlow<Set<String>> = _loadingAttachments.asStateFlow()

    private val _attachmentError = MutableStateFlow<SchoolActionError?>(null)
    /** The last attachment that could not be loaded (attachment id and error code). */
    val attachmentError: StateFlow<SchoolActionError?> = _attachmentError.asStateFlow()

    private val _files = MutableSharedFlow<SchoolLocalFile>(extraBufferCapacity = 1)
    /** An attachment is on the device: the screen opens it with another app. */
    val files: SharedFlow<SchoolLocalFile> = _files.asSharedFlow()

    /** The message whose attachments are going into Drive (Schule 1.7). */
    private val _driveSaving = MutableStateFlow<String?>(null)
    val driveSaving: StateFlow<String?> = _driveSaving.asStateFlow()

    /** How saving into Drive went: how many, where the last one went, or the error code. */
    data class DriveNotice(val saved: Int, val where: String?, val code: String?)

    private val _driveNotices = MutableSharedFlow<DriveNotice>(extraBufferCapacity = 2)
    val driveNotices: SharedFlow<DriveNotice> = _driveNotices.asSharedFlow()

    /** Every attachment of [m] into the Drive folder [folderId], one after the other; stops at the first error. */
    fun saveToDrive(m: SchoolMessage, folderId: Long) {
        if (_driveSaving.value != null || m.attachments.isEmpty()) return
        _driveSaving.value = m.id
        viewModelScope.launch {
            var saved = 0
            var where: String? = null
            var code: String? = null
            for (a in m.attachments) {
                when (val r = schoolFiles.toDrive(m.id, a, folderId)) {
                    is FamilieResult.Ok -> {
                        saved++
                        where = r.value.ifBlank { null }
                    }
                    else -> {
                        code = r.errorCode() ?: FamilieApi.CODE_INVALID
                        break
                    }
                }
            }
            _driveSaving.value = null
            _driveNotices.tryEmit(DriveNotice(saved, where, code))
        }
    }

    /** The message `read` was sent for since it was opened (once per opening). */
    private var readSentFor: String? = null

    private var loadJob: Job? = null
    private var opened = false

    init {
        viewModelScope.launch { repository.pushes.collect { refresh() } }
        viewModelScope.launch {
            timetable.collect { tt ->
                if (tt == null) return@collect
                if (!datePicked) _date.value = SchoolLogic.defaultDay(LocalDateTime.now(), SchoolLogic.hasLessons(tt, _studentId.value))
                if (_studentId.value == null || tt.student(_studentId.value) == null) {
                    _studentId.value = SchoolLogic.pickStudent(tt, _studentId.value)?.id
                }
                followMessage()
            }
        }
        // A message opened by a push: show it with one of its children.
        viewModelScope.launch {
            combine(inbox, _messageId) { _, _ -> }.collect { followMessage() }
        }
    }

    private fun followMessage() {
        val id = _messageId.value ?: run {
            readSentFor = null
            return
        }
        val message = inbox.value?.messages?.firstOrNull { it.id == id } ?: return
        SchoolLogic.studentForMessage(message, _studentId.value, timetable.value)?.let { _studentId.value = it }
        // Shown (from the list or a push): read, once per opening.
        if (readSentFor != id) {
            readSentFor = id
            viewModelScope.launch { repository.markRead(id) }
        }
    }

    /**
     * The child, day and view to start on (from the card or a push;
     * [messageId] opens that message). The view model can outlive one
     * screen (wide windows): new arguments apply, the same ones again do nothing.
     */
    fun open(studentId: Int?, date: String?, view: SchoolView = SchoolView.PLAN, messageId: String? = null) {
        val args = OpenArgs(studentId, date, view, messageId)
        if (opened && args == lastArgs) return
        val first = !opened
        opened = true
        lastArgs = args
        if (studentId != null) _studentId.value = studentId
        _tab.value = when (view) {
            SchoolView.PLAN -> SchoolTab.PLAN
            SchoolView.MESSAGES -> SchoolTab.MESSAGES
            SchoolView.ABSENCES -> SchoolTab.ABSENCES
        }
        _messageId.value = messageId.takeIf { view == SchoolView.MESSAGES }
        val d = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val start = _windowStart.value
        if (d != null) {
            _date.value = d
            datePicked = true
            _windowStart.value = SchoolLogic.windowFor(d, today)
        }
        if (first || _windowStart.value != start) {
            viewModelScope.launch {
                repository.cached()
                repository.cachedInbox()
                refresh()
            }
        } else if (view != SchoolView.PLAN) {
            // A push about a message or an absence: fetch it.
            refresh()
        }
    }

    private data class OpenArgs(val studentId: Int?, val date: String?, val view: SchoolView, val messageId: String?)

    private var lastArgs: OpenArgs? = null

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _busy.value = true
            val start = _windowStart.value
            val r = if (start == SchoolLogic.windowStart(today)) {
                repository.refresh(today)
            } else {
                repository.load(start).also { if (it is FamilieResult.Ok) _other.value = start to it.value }
            }
            _error.value = if (r is FamilieResult.Ok) null else r.errorCode()
            when (val c = repository.changes()) {
                is FamilieResult.Ok -> {
                    _changes.value = c.value
                    _changesError.value = null
                }
                else -> _changesError.value = c.errorCode()
            }
            repository.refreshInbox()
            _busy.value = false
        }
    }

    fun selectStudent(id: Int) {
        if (_studentId.value != id) _messageId.value = null
        _studentId.value = id
    }

    fun selectTab(tab: SchoolTab) {
        _tab.value = tab
        if (tab != SchoolTab.MESSAGES) _messageId.value = null
    }

    fun openMessage(id: String) {
        _confirmError.value = null
        _attachmentError.value = null
        _messageId.value = id
    }

    /** Back from a message to the list. */
    fun closeMessage() {
        _messageId.value = null
    }

    /** Sends the read confirmation of message [id] (after the person agreed). */
    fun confirm(id: String) {
        if (_confirming.value != null) return
        _confirming.value = id
        _confirmError.value = null
        viewModelScope.launch {
            val r = repository.confirm(id)
            if (r !is FamilieResult.Ok) _confirmError.value = SchoolActionError(id, r.errorCode() ?: FamilieApi.CODE_INVALID)
            _confirming.value = null
        }
    }

    /** Loads [attachment] of message [messageId] (once at a time) and hands it to the screen to open. */
    fun openAttachment(messageId: String, attachment: SchoolAttachment) {
        if (attachment.id in _loadingAttachments.value) return
        _loadingAttachments.update { it + attachment.id }
        _attachmentError.value = null
        viewModelScope.launch {
            when (val r = schoolFiles.load(messageId, attachment)) {
                is FamilieResult.Ok -> _files.tryEmit(r.value)
                else -> _attachmentError.value = SchoolActionError(attachment.id, r.errorCode() ?: FamilieApi.CODE_INVALID)
            }
            _loadingAttachments.update { it - attachment.id }
        }
    }

    fun selectDate(date: LocalDate) {
        datePicked = true
        _date.value = date
    }

    /** The other week of the range ([weeks] = ±1): its first school day. */
    fun shiftWeek(weeks: Int) {
        val start = _windowStart.value
        val monday = SchoolLogic.monday(_date.value).plusWeeks(weeks.toLong())
        if (monday < start || monday > SchoolLogic.windowEnd(start)) return
        selectDate(SchoolLogic.weekDays(monday, SchoolLogic.hasLessons(timetable.value, _studentId.value)).firstOrNull() ?: monday)
    }
}
