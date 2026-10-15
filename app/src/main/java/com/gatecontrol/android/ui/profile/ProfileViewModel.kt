package com.gatecontrol.android.ui.profile

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.profile.AvatarEncoder
import com.gatecontrol.android.profile.AvatarWizard
import com.gatecontrol.android.profile.CropRect
import com.gatecontrol.android.profile.ProfileRepository
import com.gatecontrol.android.profile.ProfileResult
import com.gatecontrol.android.profile.ProfileState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** What the profile screen is busy with. */
enum class ProfileBusy { LOADING_PHOTO, SAVING, REMOVE }

/**
 * Profile screen: picture and favourite colour through the wizard
 * ([AvatarWizard]: photo → crop → ring colour, or the colour alone),
 * removing the picture.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ProfileRepository,
) : ViewModel() {

    val state: StateFlow<ProfileState> = repository.state

    private val _busy = MutableStateFlow<ProfileBusy?>(null)
    val busy: StateFlow<ProfileBusy?> = _busy.asStateFlow()

    private val _wizard = MutableStateFlow<AvatarWizard?>(null)
    /** The open wizard, null when closed. */
    val wizard: StateFlow<AvatarWizard?> = _wizard.asStateFlow()

    private val _cropSource = MutableStateFlow<Bitmap?>(null)
    /** The picked photo while the wizard is open. */
    val cropSource: StateFlow<Bitmap?> = _cropSource.asStateFlow()

    private val _preview = MutableStateFlow<Bitmap?>(null)
    /** The cropped photo for the colour step's preview. */
    val preview: StateFlow<Bitmap?> = _preview.asStateFlow()

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    /** Short results for a toast (string resources). */
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            repository.refresh()
            _refreshing.value = false
        }
    }

    private fun currentColor(): String? = state.value.profile?.let { AvatarWizard.currentColorKey(it) }

    /** A photo from the system photo picker: the wizard opens at the crop step. */
    fun onPicked(uri: Uri?) {
        if (uri == null || _busy.value != null) return
        _busy.value = ProfileBusy.LOADING_PHOTO
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) { AvatarEncoder.decode(context, uri) }
            _busy.value = null
            if (bitmap == null) {
                _messages.tryEmit(R.string.profile_photo_unreadable)
            } else {
                _cropSource.value = bitmap
                _preview.value = null
                _wizard.value = AvatarWizard.forPhoto(currentColor())
            }
        }
    }

    /** The colour badge: the wizard opens at the colour step. */
    fun startColorOnly() {
        if (_busy.value != null || state.value.profile == null) return
        _cropSource.value = null
        _preview.value = null
        _wizard.value = AvatarWizard.colorOnly(currentColor())
    }

    /** "Weiter" after the crop. */
    fun wizardNext(rect: CropRect) {
        val w = _wizard.value ?: return
        val source = _cropSource.value ?: return
        _wizard.value = w.next(rect)
        viewModelScope.launch {
            val cropped = withContext(Dispatchers.Default) {
                runCatching { Bitmap.createBitmap(source, rect.left, rect.top, rect.side, rect.side) }.getOrNull()
            }
            // Still the same photo (not cancelled meanwhile).
            if (_cropSource.value === source) _preview.value = cropped
        }
    }

    fun wizardPick(key: String) {
        _wizard.value = _wizard.value?.pick(key)
    }

    /** Back or cancel at any step: nothing is sent. */
    fun wizardCancel() {
        if (_busy.value == ProfileBusy.SAVING) return
        closeWizard()
    }

    private fun closeWizard() {
        _wizard.value = null
        _cropSource.value = null
        _preview.value = null
    }

    /**
     * "Fertig": upload the cropped picture, then set the colour (each only
     * when there is something to change). A failed upload keeps the wizard
     * open to try again.
     */
    fun wizardFinish() {
        val plan = _wizard.value?.plan() ?: return
        if (_busy.value != null) return
        if (plan.nothing) {
            closeWizard()
            return
        }
        val source = _cropSource.value
        _busy.value = ProfileBusy.SAVING
        viewModelScope.launch {
            var ok = 0
            if (plan.crop != null) {
                val encoded = source?.let { withContext(Dispatchers.Default) { runCatching { AvatarEncoder.encode(it, plan.crop) }.getOrNull() } }
                if (encoded == null) {
                    _busy.value = null
                    _messages.tryEmit(R.string.profile_photo_unreadable)
                    return@launch
                }
                val result = repository.uploadAvatar(encoded.bytes, encoded.mime)
                if (result != ProfileResult.Ok) {
                    _busy.value = null
                    _messages.tryEmit(messageOf(result, 0))
                    return@launch
                }
                ok = R.string.profile_photo_saved
            }
            if (plan.colorKey != null) {
                val result = repository.setColor(plan.colorKey)
                if (result != ProfileResult.Ok) ok = messageOf(result, 0)
                else if (ok == 0) ok = R.string.profile_color_saved
            }
            _busy.value = null
            closeWizard()
            if (ok != 0) _messages.tryEmit(ok)
        }
    }

    fun removeAvatar() {
        if (_busy.value != null) return
        _busy.value = ProfileBusy.REMOVE
        viewModelScope.launch {
            val result = repository.removeAvatar()
            _busy.value = null
            _messages.tryEmit(messageOf(result, R.string.profile_photo_removed))
        }
    }

    override fun onCleared() {
        closeWizard()
    }

    companion object {
        @StringRes
        fun messageOf(result: ProfileResult, @StringRes ok: Int): Int = when (result) {
            ProfileResult.Ok -> ok
            is ProfileResult.Failed -> when (result.code) {
                ProfileRepository.CODE_OFFLINE -> R.string.profile_error_offline
                ProfileRepository.CODE_TOO_LARGE -> R.string.profile_error_too_large
                ProfileRepository.CODE_INVALID -> R.string.profile_error_invalid
                ProfileRepository.CODE_UNSUPPORTED_TYPE -> R.string.profile_error_type
                ProfileRepository.CODE_INVALID_COLOR -> R.string.profile_error_color
                ProfileRepository.CODE_RATE_LIMITED -> R.string.profile_error_rate_limited
                ProfileRepository.CODE_UNSUPPORTED -> R.string.profile_unsupported
                ProfileRepository.CODE_NO_USER -> R.string.profile_no_user
                else -> R.string.profile_error_failed
            }
        }
    }
}
