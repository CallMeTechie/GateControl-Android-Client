package com.gatecontrol.android.ui.remote

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.familie.PluginDirectory
import com.gatecontrol.android.remote.ScreenShareRepository
import com.gatecontrol.android.remote.ScreenShareUi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The card „Bildschirm freigeben“: the state of [ScreenShareRepository], shown only while the plugin answers this app. */
@HiltViewModel
class ScreenShareViewModel @Inject constructor(
    private val repository: ScreenShareRepository,
    private val plugins: PluginDirectory,
) : ViewModel() {
    val state: StateFlow<ScreenShareUi> = repository.state

    init {
        viewModelScope.launch { plugins.state.map { it.remote }.distinctUntilChanged().collect { repository.setAvailable(it) } }
        plugins.refreshIfStale()
        // a code that ran out while the app was away
        repository.refreshCodeIfStale()
    }

    fun setEnabled(on: Boolean) = repository.setEnabled(on)
    fun setOpen(open: Boolean) = repository.setOpen(open)
    fun refreshCode() = repository.refreshCode()
    suspend fun heartbeat() = repository.heartbeat()
    fun answer(allow: Boolean) = repository.answer(allow)
    fun requestExpired(id: String) = repository.requestExpired(id)
    fun consent(resultCode: Int, data: Intent?) = repository.consent(resultCode, data)
    fun stop() = repository.stop()
    fun clearEnded() = repository.clearEnded()
}
