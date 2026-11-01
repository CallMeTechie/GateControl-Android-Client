package com.gatecontrol.android.ui.drive

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.drive.DMe
import com.gatecontrol.android.drive.DMemoryCard
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DOverview
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveLive
import com.gatecontrol.android.drive.DriveOverviewLogic
import com.gatecontrol.android.drive.DrivePlacesLogic
import com.gatecontrol.android.drive.DrivePlacesPrefs
import com.gatecontrol.android.drive.DriveTransfers
import com.gatecontrol.android.drive.backup.DriveBackup
import com.gatecontrol.android.drive.faces.DrivePeopleWork
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import javax.inject.Inject

/**
 * The overview of Drive (Drive 1.18): `GET overview` in one call, or — on a server without `overview` — built from
 * the deadlines, the memories, the people, the places and the review count. Changes of the change log reload it,
 * at most every [DriveOverviewLogic.LIVE_THROTTLE_MS].
 */
@HiltViewModel
class DriveOverviewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: DriveApi,
    val live: DriveLive,
    val backup: DriveBackup,
    private val transfers: DriveTransfers,
    private val placesPrefs: DrivePlacesPrefs,
) : ViewModel() {

    private val _state = MutableStateFlow<DriveLoad<DOverview>>(DriveLoad.Loading)
    val state: StateFlow<DriveLoad<DOverview>> = _state.asStateFlow()

    /** The photos of a memory shown full screen (null: none). */
    private val _viewing = MutableStateFlow<List<DNode>?>(null)
    val viewing: StateFlow<List<DNode>?> = _viewing.asStateFlow()

    private var job: Job? = null
    private var started = false

    /** Loads once and from then on follows the change log (throttled). */
    fun start() {
        if (started) return
        started = true
        load()
        viewModelScope.launch {
            live.changed.conflate().collect {
                load()
                delay(DriveOverviewLogic.LIVE_THROTTLE_MS)
            }
        }
    }

    /** Again (on resume, a change, "Erneut versuchen"); what shows stays until the new answer is there. */
    fun load() {
        job?.cancel()
        if (_state.value !is DriveLoad.Ready) _state.value = DriveLoad.Loading
        job = viewModelScope.launch {
            val me = live.current()
            if (me == null) {
                if (_state.value !is DriveLoad.Ready) _state.value = DriveLoad.Error("offline")
                return@launch
            }
            sideEffects(me)
            val r = if (me.has(DMe.FEATURE_OVERVIEW)) api.overview() else DResult.Ok(assemble(me))
            when {
                r is DResult.Ok -> _state.value = DriveLoad.Ready(r.value)
                // keep what shows when a reload fails
                _state.value !is DriveLoad.Ready -> _state.value = r.toLoad { it }
            }
        }
    }

    /** What the Photos page's head did: the places' settings for this phone, the faces of new photos in the background. */
    private suspend fun sideEffects(me: DMe) {
        val places = me.has(DMe.FEATURE_PHOTO_PLACES)
        val people = me.has(DMe.FEATURE_PHOTO_PEOPLE)
        if (!places && !people) return
        api.photoSettings().valueOrNull()?.let(placesPrefs::took)
        if (people) DrivePeopleWork.request(context)
    }

    /** A server without `overview`: the parts from their own endpoints (side by side). */
    private suspend fun assemble(me: DMe): DOverview = coroutineScope {
        val today = LocalDate.now()
        val deadlines = async { if (me.has(DMe.FEATURE_DOC_AI)) api.deadlines(from = today.toString()).valueOrNull() else null }
        val classes = async { if (me.has(DMe.FEATURE_PHOTO_CLASSES)) api.photoClasses().valueOrNull() else null }
        val memories = async { if (me.has(DMe.FEATURE_PHOTOS)) api.memories(today.toString()).valueOrNull() else null }
        val people = async { if (me.has(DMe.FEATURE_PHOTO_PEOPLE)) api.people().valueOrNull() else null }
        val places = async { if (me.has(DMe.FEATURE_PHOTO_PLACES)) api.photoPlaces().valueOrNull() else null }
        DriveOverviewLogic.assemble(
            today = today,
            deadlines = deadlines.await(),
            classes = classes.await(),
            memories = memories.await(),
            people = people.await(),
            places = places.await(),
            storage = DriveOverviewLogic.storageOf(me.areas),
        )
    }

    /** "Wer ist das?": the person without a name with most photos ([onPerson] gets it; nothing when there is none). */
    fun whoIsThat(onPerson: (Long) -> Unit) {
        val known = (_state.value as? DriveLoad.Ready)?.value?.people?.firstUnnamed
        if (known != null) return onPerson(known)
        viewModelScope.launch {
            val people = api.people().valueOrNull()?.people ?: return@launch
            DrivePlacesLogic.groups(people).unnamed.firstOrNull()?.let { onPerson(it.id) }
        }
    }

    /** "Nächste Person" after naming [after]: the next one without a name from `GET people` afresh (null: none left). */
    fun nextToName(after: Long, onNext: (Long?) -> Unit) {
        viewModelScope.launch {
            val people = api.people().valueOrNull()?.people.orEmpty()
            onNext(DrivePlacesLogic.groups(people).unnamed.firstOrNull { it.id != after }?.id)
        }
    }

    /** A memory full screen: its photos (fetched when the overview only had the cover). */
    fun openMemory(card: DMemoryCard) {
        if (card.items.size >= card.count) {
            _viewing.value = card.items
            return
        }
        viewModelScope.launch {
            val groups = api.memories(LocalDate.now().toString()).valueOrNull().orEmpty()
            val items = groups.firstOrNull { it.yearsAgo == card.yearsAgo }?.items?.takeIf { it.isNotEmpty() }
            _viewing.value = items ?: card.items.ifEmpty { listOf(card.cover) }
        }
    }

    fun closeMemory() {
        _viewing.value = null
    }

    suspend fun fetch(node: DNode): DResult<File> = transfers.fetch(node)
}
