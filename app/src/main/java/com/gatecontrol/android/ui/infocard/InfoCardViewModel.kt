package com.gatecontrol.android.ui.infocard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.infocard.InfoCardLogic
import com.gatecontrol.android.infocard.InfoCardRepository
import com.gatecontrol.android.infocard.InfoCardView
import com.gatecontrol.android.infocard.InfoSelection
import com.gatecontrol.android.infocard.InfoTilesState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The start tab's "Info" card and its picker "Inhalte der Info-Karte". */
@HiltViewModel
class InfoCardViewModel @Inject constructor(
    private val repository: InfoCardRepository,
) : ViewModel() {

    val tiles: StateFlow<InfoTilesState> = repository.tiles

    /** Null until read from storage. */
    val selection: StateFlow<InfoSelection?> = repository.selection
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val view: StateFlow<InfoCardView> = combine(repository.tiles, repository.selection) { tiles, selection ->
        InfoCardLogic.view(tiles, selection)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InfoCardLogic.view(repository.tiles.value, InfoSelection()))

    fun refresh() {
        viewModelScope.launch { repository.refresh() }
    }

    fun setFavorite(id: String) {
        viewModelScope.launch { repository.update { InfoCardLogic.favorite(it, id) } }
    }

    fun toggle(id: String) {
        viewModelScope.launch { repository.update { InfoCardLogic.toggle(it, id) } }
    }

    /** [visible]: the chosen ids the picker shows, in order. */
    fun move(id: String, delta: Int, visible: List<String>) {
        viewModelScope.launch { repository.update { InfoCardLogic.move(it, id, delta, visible) } }
    }
}
