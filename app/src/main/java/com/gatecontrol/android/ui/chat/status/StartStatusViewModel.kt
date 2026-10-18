package com.gatecontrol.android.ui.chat.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.chat.ChatExtrasRepository
import com.gatecontrol.android.chat.ChatRepository
import com.gatecontrol.android.chat.ChatStatus
import com.gatecontrol.android.chat.status
import com.gatecontrol.android.chat.stories
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The start tab's part of Messenger 1.4: whether the avatar opens "Dein Status", and the own status. */
data class StartStatusUi(
    val enabled: Boolean = false,
    val stories: Boolean = false,
    val status: ChatStatus? = null,
    /** Own active stories (ring around the avatar). */
    val ownStories: Int = 0,
)

@HiltViewModel
class StartStatusViewModel @Inject constructor(
    repository: ChatRepository,
    private val extras: ChatExtrasRepository,
) : ViewModel() {
    val ui: StateFlow<StartStatusUi> = combine(repository.available, repository.profile, extras.feed) { on, p, f ->
        StartStatusUi(
            enabled = on && p?.features?.status == true,
            stories = on && p?.features?.stories == true,
            status = f?.me?.status ?: p?.status,
            ownStories = if (on) f?.me?.stories?.size ?: 0 else 0,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartStatusUi())

    init {
        viewModelScope.launch {
            if (repository.available.value) {
                if (repository.profile.value == null) repository.refreshProfile()
                if (repository.profile.value?.features?.status == true) extras.refreshFeedIfStale()
            }
        }
    }
}
