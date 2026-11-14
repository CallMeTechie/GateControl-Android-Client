package com.gatecontrol.android.ui.familie

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.familie.FamilieDest
import com.gatecontrol.android.familie.shopping.ShoppingLogic
import com.gatecontrol.android.familie.shopping.ShoppingRepository
import com.gatecontrol.android.notizen.NotizenShopping
import com.gatecontrol.android.notizen.ShoppingHome
import com.gatecontrol.android.ui.notizen.NotizenShoppingScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Decides once where the family's shopping list opens ([ShoppingHome]): Familie says where its lists live (`home` of
 * its `GET shopping`, the kept answer while offline); the hub's list needs the hub's feature and a copy on the phone.
 */
@HiltViewModel
class ShoppingEntryViewModel @Inject constructor(
    private val familie: ShoppingRepository,
    private val hub: NotizenShopping,
) : ViewModel() {

    private val _target = MutableStateFlow<ShoppingHome.Target?>(null)
    /** null: not decided yet. */
    val target: StateFlow<ShoppingHome.Target?> = _target.asStateFlow()

    private var asked: String? = null

    fun decide(listId: String?) {
        val key = listId.orEmpty()
        if (asked == key) return
        asked = key
        _target.value = null
        viewModelScope.launch {
            val own = familie.current()
            _target.value = if (!ShoppingHome.moved(own.server.home)) {
                ShoppingHome.Familie
            } else {
                // never held up for long: without an answer the Familie page opens (it works on its copy)
                val ready = withTimeoutOrNull(READY_MS) { hub.ready() } == true
                val lists = if (ready) ShoppingLogic.view(hub.current()).lists else emptyList()
                ShoppingHome.target(own.server.home, ready, lists, ShoppingLogic.view(own).lists, listId)
            }
        }
    }

    private companion object {
        const val READY_MS = 6_000L
    }
}

/**
 * The shopping list of the Familie tab (the card on its start page, a push, a place reminder with a list): Familie's
 * own page — or, after the move to „Notizen & Aufgaben“ (Familie 1.21 `home: notizen`), the hub's list with the same id.
 */
@Composable
fun ShoppingEntry(listId: String?, onBack: () -> Unit, onOpen: (FamilieDest) -> Unit, vm: ShoppingEntryViewModel = hiltViewModel()) {
    val target by vm.target.collectAsStateWithLifecycle()
    LaunchedEffect(listId) { vm.decide(listId) }
    when (val t = target) {
        null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        ShoppingHome.Familie -> ShoppingScreen(onBack = onBack, initialListId = listId, onOpen = onOpen)
        is ShoppingHome.Hub -> NotizenShoppingScreen(uid = t.uid, onBack = onBack)
    }
}
