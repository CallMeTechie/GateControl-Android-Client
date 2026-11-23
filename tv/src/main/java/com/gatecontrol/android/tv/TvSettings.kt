package com.gatecontrol.android.tv

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.gatecontrol.android.common.SplitTunnelMode
import com.gatecontrol.android.tv.doorbell.DoorbellMode
import com.gatecontrol.android.tv.doorbell.DoorbellSettings
import com.gatecontrol.android.tv.frame.TvFrameOrder
import com.gatecontrol.android.tv.frame.TvFrameSchedule
import com.gatecontrol.android.tv.frame.TvFrameSettings
import com.gatecontrol.android.tv.tunnel.TvSplitTunnel
import com.gatecontrol.android.tv.tunnel.TvSplitTunnelSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings of GateControl TV (Einstellungen › Verbindung, Einblendungen) and the server typed in for the pairing.
 * Plain SharedPreferences: the boot receiver reads them synchronously, nothing in here is secret (the token and the
 * WireGuard config are in core/data's encrypted storage, the TV PIN's hash in its own file).
 */
@Singleton
class TvSettings @Inject constructor(@ApplicationContext context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _autostartTunnel = MutableStateFlow(prefs.getBoolean(KEY_AUTOSTART_TUNNEL, DEFAULT_AUTOSTART_TUNNEL))

    /** "Nach dem Einschalten verbinden": the tunnel comes up after the stick starts (default on). */
    val autostartTunnel: StateFlow<Boolean> = _autostartTunnel.asStateFlow()

    private val _openOnBoot = MutableStateFlow(prefs.getBoolean(KEY_OPEN_ON_BOOT, DEFAULT_OPEN_ON_BOOT))

    /** "GateControl beim Start öffnen": the app instead of the Fire TV home screen (default off). */
    val openOnBoot: StateFlow<Boolean> = _openOnBoot.asStateFlow()

    private val _doorbell = MutableStateFlow(readDoorbell())

    /** Einstellungen › Einblendungen: how a ring shows, quiet hours, motion, the camera of the live picture. */
    val doorbell: StateFlow<DoorbellSettings> = _doorbell.asStateFlow()

    private val _splitTunnel = MutableStateFlow(readSplitTunnel())

    /** "Geteilter Tunnel": which other apps on the stick use the tunnel. Applies from the next connect. */
    val splitTunnel: StateFlow<TvSplitTunnelSettings> = _splitTunnel.asStateFlow()

    private val _frame = MutableStateFlow(readFrame())

    /** The photo frame: the interval and the order (each "as the server" or the TV's own), the clock, the idle start. */
    val frame: StateFlow<TvFrameSettings> = _frame.asStateFlow()

    fun setFrame(value: TvFrameSettings) {
        val v = value.copy(
            seconds = TvFrameSchedule.seconds(value.seconds),
            idleMinutes = TvFrameSchedule.idleMinutes(value.idleMinutes),
        )
        prefs.edit {
            putInt(KEY_FRAME_SECONDS, v.seconds)
            if (v.order == null) remove(KEY_FRAME_ORDER) else putString(KEY_FRAME_ORDER, v.order.wire)
            putInt(KEY_FRAME_IDLE, v.idleMinutes)
            putBoolean(KEY_FRAME_CLOCK, v.clock)
        }
        _frame.value = v
    }

    private fun readFrame() = TvFrameSettings(
        seconds = TvFrameSchedule.seconds(prefs.getInt(KEY_FRAME_SECONDS, 0)),
        order = TvFrameOrder.of(prefs.getString(KEY_FRAME_ORDER, null)),
        idleMinutes = TvFrameSchedule.idleMinutes(prefs.getInt(KEY_FRAME_IDLE, 0)),
        clock = prefs.getBoolean(KEY_FRAME_CLOCK, true),
    )

    fun setAutostartTunnel(on: Boolean) {
        prefs.edit { putBoolean(KEY_AUTOSTART_TUNNEL, on) }
        _autostartTunnel.value = on
    }

    fun setOpenOnBoot(on: Boolean) {
        prefs.edit { putBoolean(KEY_OPEN_ON_BOOT, on) }
        _openOnBoot.value = on
    }

    fun setDoorbell(value: DoorbellSettings) {
        val v = value.copy(
            quietFrom = value.quietFrom.mod(MINUTES_PER_DAY),
            quietTo = value.quietTo.mod(MINUTES_PER_DAY),
        )
        prefs.edit {
            putString(KEY_BELL_MODE, v.mode.wire)
            putBoolean(KEY_BELL_QUIET, v.quiet)
            putInt(KEY_BELL_FROM, v.quietFrom)
            putInt(KEY_BELL_TO, v.quietTo)
            putBoolean(KEY_BELL_MOTION, v.motion)
            if (v.cameraId == null) remove(KEY_BELL_CAMERA) else putString(KEY_BELL_CAMERA, v.cameraId)
        }
        _doorbell.value = v
    }

    fun setSplitTunnel(value: TvSplitTunnelSettings) {
        val apps = value.apps.filter(TvSplitTunnel::isPackageName).take(TvSplitTunnel.MAX_APPS).toSet()
        prefs.edit {
            putString(KEY_SPLIT_MODE, value.mode.wire)
            putStringSet(KEY_SPLIT_APPS, apps)
        }
        _splitTunnel.value = TvSplitTunnelSettings(value.mode, apps)
    }

    private fun readDoorbell() = DoorbellSettings(
        mode = DoorbellMode.fromWire(prefs.getString(KEY_BELL_MODE, null)),
        quiet = prefs.getBoolean(KEY_BELL_QUIET, false),
        quietFrom = prefs.getInt(KEY_BELL_FROM, 22 * 60).mod(MINUTES_PER_DAY),
        quietTo = prefs.getInt(KEY_BELL_TO, 7 * 60).mod(MINUTES_PER_DAY),
        motion = prefs.getBoolean(KEY_BELL_MOTION, false),
        cameraId = prefs.getString(KEY_BELL_CAMERA, null)?.takeIf { it.isNotBlank() },
    )

    private fun readSplitTunnel() = TvSplitTunnelSettings(
        mode = SplitTunnelMode.fromWire(prefs.getString(KEY_SPLIT_MODE, null)),
        apps = prefs.getStringSet(KEY_SPLIT_APPS, emptySet()).orEmpty().filter(TvSplitTunnel::isPackageName).toSet(),
    )

    /** The server typed in once at the TV (https://host[:port]); kept for a new code after the old one ran out. */
    var pairServer: String?
        get() = prefs.getString(KEY_PAIR_SERVER, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit { putString(KEY_PAIR_SERVER, value) }
        }

    companion object {
        private const val FILE = "gatecontrol_tv"
        private const val KEY_AUTOSTART_TUNNEL = "autostart_tunnel"
        private const val KEY_OPEN_ON_BOOT = "open_on_boot"
        private const val KEY_PAIR_SERVER = "pair_server"
        private const val KEY_BELL_MODE = "doorbell_mode"
        private const val KEY_BELL_QUIET = "doorbell_quiet"
        private const val KEY_BELL_FROM = "doorbell_quiet_from"
        private const val KEY_BELL_TO = "doorbell_quiet_to"
        private const val KEY_BELL_MOTION = "doorbell_motion"
        private const val KEY_BELL_CAMERA = "doorbell_camera"
        private const val KEY_SPLIT_MODE = "split_mode"
        private const val KEY_SPLIT_APPS = "split_apps"
        private const val KEY_FRAME_SECONDS = "frame_seconds"
        private const val KEY_FRAME_ORDER = "frame_order"
        private const val KEY_FRAME_IDLE = "frame_idle_minutes"
        private const val KEY_FRAME_CLOCK = "frame_clock"
        private const val MINUTES_PER_DAY = 24 * 60
        const val DEFAULT_AUTOSTART_TUNNEL = true
        const val DEFAULT_OPEN_ON_BOOT = false
    }
}
