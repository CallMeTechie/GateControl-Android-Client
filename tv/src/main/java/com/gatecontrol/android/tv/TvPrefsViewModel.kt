package com.gatecontrol.android.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.door.DoorApi
import com.gatecontrol.android.door.DoorResult
import com.gatecontrol.android.door.HomeCamera
import com.gatecontrol.android.notify.PushPrefsStore
import com.gatecontrol.android.tv.doorbell.DoorbellSettings
import com.gatecontrol.android.tv.doorbell.TvDoorbell
import com.gatecontrol.android.tv.frame.TvFrameFeed
import com.gatecontrol.android.tv.frame.TvFrameSettings
import com.gatecontrol.android.tv.frame.TvFrameSource
import com.gatecontrol.android.tv.frame.TvFrameStatus
import com.gatecontrol.android.tv.security.TvPinCheck
import com.gatecontrol.android.tv.security.TvPinFunction
import com.gatecontrol.android.tv.security.TvPinState
import com.gatecontrol.android.tv.security.TvPinStore
import com.gatecontrol.android.tv.tunnel.TvSplitTunnelSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** An app on the stick that may use the tunnel or not ("Geteilter Tunnel"). */
data class TvInstalledApp(val packageName: String, val label: String)

/** Einstellungen › Einblendungen, Sicherheit (TV-PIN) and the split tunnel of Verbindung. */
@HiltViewModel
class TvPrefsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: TvSettings,
    private val pins: TvPinStore,
    private val pushStore: PushPrefsStore,
    private val doorbell: TvDoorbell,
    private val doorApi: DoorApi,
    private val frameSource: TvFrameSource,
) : ViewModel() {

    val doorbellSettings: StateFlow<DoorbellSettings> = settings.doorbell
    val splitTunnel: StateFlow<TvSplitTunnelSettings> = settings.splitTunnel
    val pin: StateFlow<TvPinState> = pins.state
    val frame: StateFlow<TvFrameSettings> = settings.frame

    /** Startseite: the rows switched off. */
    val startRows: StateFlow<Set<TvStartRow>> = settings.startRows

    fun setStartRow(row: TvStartRow, on: Boolean) = settings.setStartRow(row, on)

    /** What the photo frame can show (the last `GET frame`). */
    val frameStatus: StateFlow<TvFrameStatus> = frameSource.status

    fun setFrame(value: TvFrameSettings) = settings.setFrame(value)

    /** Einstellungen › Fotorahmen opened: asked again, so a share made a moment ago in the app shows. */
    fun refreshFrame() {
        viewModelScope.launch { frameSource.load(force = true) }
    }

    /** The server's interval and order (null: not known yet), for „Wie am Server (…)“. */
    fun frameServer(): TvFrameFeed? = frameSource.last
    val pushEnabled: StateFlow<Boolean> = pushStore.settings.map { it.enabled }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _apps = MutableStateFlow<List<TvInstalledApp>>(emptyList())
    val apps: StateFlow<List<TvInstalledApp>> = _apps.asStateFlow()

    private val _cameras = MutableStateFlow<List<HomeCamera>>(emptyList())
    val cameras: StateFlow<List<HomeCamera>> = _cameras.asStateFlow()

    fun canDrawOverlays(): Boolean = doorbell.canDrawOverlays()

    fun setDoorbell(value: DoorbellSettings) = settings.setDoorbell(value)

    fun setSplitTunnel(value: TvSplitTunnelSettings) = settings.setSplitTunnel(value)

    fun setPush(on: Boolean) {
        viewModelScope.launch { pushStore.setEnabled(on) }
    }

    suspend fun checkPin(pin: String): TvPinCheck = pins.check(pin)

    fun setPin(pin: String) {
        viewModelScope.launch { pins.setPin(pin) }
    }

    fun removePin() = pins.removePin()

    fun setRequired(function: TvPinFunction, on: Boolean) = pins.setRequired(function, on)

    fun loadCameras() {
        viewModelScope.launch {
            (doorApi.homeCameras() as? DoorResult.Ok)?.value?.takeIf { it.supported }?.let { _cameras.value = it.cameras }
        }
    }

    /** The apps of the launcher (Fire TV and Android TV, also the phone launcher), without GateControl TV itself. */
    fun loadApps() {
        viewModelScope.launch {
            _apps.value = withContext(Dispatchers.IO) {
                val pm = context.packageManager
                listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER).flatMap { category ->
                    runCatching {
                        @Suppress("DEPRECATION")
                        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), PackageManager.MATCH_ALL)
                    }.getOrDefault(emptyList())
                }.mapNotNull { info ->
                    val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                    if (pkg == context.packageName) return@mapNotNull null
                    TvInstalledApp(pkg, runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg))
                }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
            }
        }
    }
}
