package com.gatecontrol.android.ui.familie

import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.internet.InternetLogic
import com.gatecontrol.android.familie.internet.InternetRepository
import com.gatecontrol.android.familie.internet.InternetSnapshot
import com.gatecontrol.android.familie.internet.PauseRequest
import com.gatecontrol.android.familie.push.FamiliePush
import com.gatecontrol.android.familie.push.FamiliePushEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * „Internet zu Hause“: the children's state from [InternetRepository]
 * (shared with the card on the start screen) and the parents' changes.
 * A message of the topic `internet` reloads it.
 */
@HiltViewModel
class InternetViewModel @Inject constructor(
    repository: FamilieRepository,
    private val internet: InternetRepository,
) : FamilieLoadViewModel<InternetSnapshot>(repository) {

    private val _working = MutableStateFlow<Int?>(null)
    /** The child a change is on its way for (its buttons wait). */
    val working: StateFlow<Int?> = _working.asStateFlow()

    init {
        _data.value = internet.snapshot.value
        start()
        // The answer of a change is in the snapshot before the reload is through.
        viewModelScope.launch { internet.snapshot.collect { if (it != null) _data.value = it } }
    }

    override suspend fun load(): FamilieResult<InternetSnapshot> = internet.refresh()

    override fun refreshOn(event: FamiliePushEvent): Boolean = FamiliePush.touchesInternet(event)

    fun pause(childId: Int, request: PauseRequest) = change(
        childId,
        if (request is PauseRequest.Add) R.string.familie_internet_done_pause_longer else R.string.familie_internet_done_paused,
    ) { internet.pause(childId, request) }

    fun resume(childId: Int) = change(childId, R.string.familie_internet_done_resumed) { internet.resume(childId) }

    /** "Heute länger" by [minutes]; [fromNow] instead of the start of the next blocked time. */
    fun extend(childId: Int, minutes: Int, fromNow: Boolean = false) =
        change(childId, R.string.familie_internet_done_extended) { internet.extend(childId, minutes, fromNow) }

    /** "+30 Min." on a running extra time (it counts from its end). */
    fun extendMore(childId: Int) = extend(childId, InternetLogic.ADD_MINUTES)

    fun endExtra(childId: Int) = change(childId, R.string.familie_internet_done_extra_ended) { internet.extend(childId, 0) }

    private fun change(childId: Int, @StringRes info: Int, call: suspend () -> FamilieResult<*>) {
        if (_working.value != null) return
        _working.value = childId
        act(info) { call().also { _working.value = null } }
    }
}
