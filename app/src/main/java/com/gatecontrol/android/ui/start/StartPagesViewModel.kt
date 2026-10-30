package com.gatecontrol.android.ui.start

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.layout.LayoutAccess
import com.gatecontrol.android.layout.StartArrangement
import com.gatecontrol.android.layout.StartEntry
import com.gatecontrol.android.layout.StartPagesStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The pages beside Start for the bar, the pager, "Seiten anordnen" and
 * "Leiste und Seiten": pin, take off, new widget page, order and the
 * bar switch. A change is refused while the admin fixes the order
 * ([LayoutAccess.locked]); each one hands back how to take it back
 * ("Rückgängig" of the snackbar), which still works after the screen that
 * made it is gone.
 */
@HiltViewModel
class StartPagesViewModel @Inject constructor(
    private val store: StartPagesStore,
    arrangement: StartArrangement,
) : ViewModel() {

    /** All page ids in their order (StartPages.visible picks those shown). */
    val pages: StateFlow<List<String>> = store.pages

    val access: StateFlow<LayoutAccess> = store.access

    /** "Leiste ausblenden, wenn nur Start bleibt" (this device). */
    val hideBar: StateFlow<Boolean> = store.hideBar

    /** The blocks on Start (null) and each widget page (to ask before removing a page with blocks). */
    val blocks: StateFlow<Map<String?, List<StartEntry>>> = arrangement.pages

    /** Undo outlives this view model (the settings may be closed before the snackbar's "Rückgängig"). */
    private val undoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun change(action: suspend () -> Unit, done: (undo: () -> Unit) -> Unit) {
        if (access.value.locked) return
        viewModelScope.launch {
            val before = store.snapshot()
            action()
            done { undoScope.launch { store.restore(before) } }
        }
    }

    /** [id] (a function) becomes a page beside Start. */
    fun pin(id: String, done: (undo: () -> Unit) -> Unit = {}) = change({ store.pin(id) }, done)

    /** The page [id] goes (a function becomes a tab again, a widget page's blocks go back to Start). */
    fun unpin(id: String, done: (undo: () -> Unit) -> Unit = {}) = change({ store.unpin(id) }, done)

    /** A new empty widget page; [done] gets its id. */
    fun addWidgets(done: (id: String, undo: () -> Unit) -> Unit = { _, _ -> }) {
        if (access.value.locked) return
        viewModelScope.launch {
            val before = store.snapshot()
            val id = store.addWidgets()
            done(id) { undoScope.launch { store.restore(before) } }
        }
    }

    /** The pages in a new order (page ids). */
    fun reorder(order: List<String>) {
        if (access.value.locked) return
        viewModelScope.launch { store.reorder(order) }
    }

    fun setHideBar(value: Boolean) {
        viewModelScope.launch { store.setHideBar(value) }
    }
}
