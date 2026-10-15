package com.gatecontrol.android.ui.profile

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.profile.AvatarEncoder
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
enum class ProfileBusy { LOADING_PHOTO, UPLOAD, REMOVE, COLOR }

/** Profile screen: picture (pick, crop, upload, remove) and favourite colour. */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ProfileRepository,
) : ViewModel() {

    val state: StateFlow<ProfileState> = repository.state

    private val _busy = MutableStateFlow<ProfileBusy?>(null)
    val busy: StateFlow<ProfileBusy?> = _busy.asStateFlow()

    private val _cropSource = MutableStateFlow<Bitmap?>(null)
    /** The picked photo while the person crops it. */
    val cropSource: StateFlow<Bitmap?> = _cropSource.asStateFlow()

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

    /** A photo from the system photo picker. */
    fun onPicked(uri: Uri?) {
        if (uri == null || _busy.value != null) return
        _busy.value = ProfileBusy.LOADING_PHOTO
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) { AvatarEncoder.decode(context, uri) }
            _busy.value = null
            if (bitmap == null) _messages.tryEmit(R.string.profile_photo_unreadable) else _cropSource.value = bitmap
        }
    }

    fun cancelCrop() {
        _cropSource.value = null
    }

    /** Crops, downscales, encodes and uploads the picture. */
    fun confirmCrop(rect: CropRect) {
        val source = _cropSource.value ?: return
        if (_busy.value != null) return
        _busy.value = ProfileBusy.UPLOAD
        viewModelScope.launch {
            val encoded = withContext(Dispatchers.Default) { runCatching { AvatarEncoder.encode(source, rect) }.getOrNull() }
            if (encoded == null) {
                _busy.value = null
                _messages.tryEmit(R.string.profile_photo_unreadable)
                return@launch
            }
            val result = repository.uploadAvatar(encoded.bytes, encoded.mime)
            _busy.value = null
            if (result == ProfileResult.Ok) _cropSource.value = null
            _messages.tryEmit(messageOf(result, R.string.profile_photo_saved))
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

    fun setColor(key: String) {
        if (_busy.value != null || state.value.profile?.color == key) return
        _busy.value = ProfileBusy.COLOR
        viewModelScope.launch {
            val result = repository.setColor(key)
            _busy.value = null
            if (result != ProfileResult.Ok) _messages.tryEmit(messageOf(result, 0))
        }
    }

    override fun onCleared() {
        _cropSource.value = null
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
