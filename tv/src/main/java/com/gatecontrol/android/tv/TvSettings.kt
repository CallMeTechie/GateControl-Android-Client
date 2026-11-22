package com.gatecontrol.android.tv

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings of GateControl TV (Einstellungen › Verbindung) and the server typed in for the pairing. Plain
 * SharedPreferences: the boot receiver reads them synchronously, nothing in here is secret (the token and the
 * WireGuard config are in core/data's encrypted storage).
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

    fun setAutostartTunnel(on: Boolean) {
        prefs.edit { putBoolean(KEY_AUTOSTART_TUNNEL, on) }
        _autostartTunnel.value = on
    }

    fun setOpenOnBoot(on: Boolean) {
        prefs.edit { putBoolean(KEY_OPEN_ON_BOOT, on) }
        _openOnBoot.value = on
    }

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
        const val DEFAULT_AUTOSTART_TUNNEL = true
        const val DEFAULT_OPEN_ON_BOOT = false
    }
}
