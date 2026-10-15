package com.gatecontrol.android.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.presence.PresenceReporter
import com.gatecontrol.android.familie.presence.PresenceSense
import com.gatecontrol.android.familie.presence.PresenceState
import com.gatecontrol.android.familie.presence.PresenceStore
import com.gatecontrol.android.profile.AvatarInitials
import com.gatecontrol.android.profile.AvatarRing
import com.gatecontrol.android.profile.ProfileRepository
import com.gatecontrol.android.profile.ProfileState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The own avatar as the start tab header and the settings show it. */
data class MyAvatarUi(
    /** The server has profiles (picture, colour); the profile screen opens either way. */
    val profileAvailable: Boolean = false,
    val name: String? = null,
    /** Null: nothing known about the person → the old header (app name + host). */
    val initials: String? = null,
    val colorHex: String? = null,
    val avatarUrl: String? = null,
    val ring: AvatarRing = AvatarRing.None,
) {
    companion object {
        /**
         * Pure (unit-tested): the core profile when the server has one, else
         * the initials of the Familie name when known. [presence] = my own
         * presence while I share it with the module on, else null.
         */
        fun from(profile: ProfileState, familieName: String?, presence: PresenceState?): MyAvatarUi {
            val p = profile.profile
            if (profile.available && p != null) {
                return MyAvatarUi(
                    profileAvailable = true,
                    name = p.name.ifBlank { null },
                    initials = p.initials,
                    colorHex = p.colorHex,
                    avatarUrl = p.avatarUrl,
                    ring = AvatarRing.of(presence, p.colorHex),
                )
            }
            val name = familieName?.takeIf { it.isNotBlank() } ?: return MyAvatarUi()
            return MyAvatarUi(name = name, initials = AvatarInitials.of(name), ring = AvatarRing.of(presence, null))
        }

        /**
         * My presence for the ring: only while I share it and the module is
         * on; what the phone senses, else what it reported last.
         */
        fun ownPresence(share: Boolean, moduleOn: Boolean, sense: PresenceSense, lastReported: Boolean?): PresenceState? {
            if (!share || !moduleOn) return null
            return when (sense) {
                PresenceSense.HOME -> PresenceState.HOME
                PresenceSense.AWAY -> PresenceState.AWAY
                PresenceSense.UNKNOWN -> when (lastReported) {
                    true -> PresenceState.HOME
                    false -> PresenceState.AWAY
                    null -> null
                }
            }
        }
    }
}

@HiltViewModel
class MyAvatarViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    familieRepository: FamilieRepository,
    presenceReporter: PresenceReporter,
    private val presenceStore: PresenceStore,
) : ViewModel() {

    private val presence = combine(
        presenceReporter.share,
        presenceReporter.sense,
        familieRepository.profile.map { it?.modules?.presence == true },
    ) { share, sense, moduleOn ->
        MyAvatarUi.ownPresence(share, moduleOn, sense, presenceStore.lastReported())
    }

    val ui: StateFlow<MyAvatarUi> = combine(
        profileRepository.state,
        familieRepository.profile.map { it?.me?.name },
        presence,
    ) { profile, familieName, own -> MyAvatarUi.from(profile, familieName, own) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MyAvatarUi.from(profileRepository.state.value, null, null))

    init {
        viewModelScope.launch { familieRepository.cachedProfile() }
        profileRepository.refreshIfStale()
    }
}
