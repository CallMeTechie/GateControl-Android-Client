package com.gatecontrol.android.drive

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The Drive settings kept on this phone (Drive 1.11, screen "Drive-Einstellungen"); the AI per area is the server's. */
data class DriveSettingsValues(
    /** A vault opens only after the fingerprint / face or the phone's PIN (the device key needs it too). */
    val vaultBiometric: Boolean = true,
    /** An open vault locks after this many minutes without use ([DriveSettingsLogic.AUTO_LOCK_CHOICES]). */
    val autoLockMinutes: Int = DriveSettingsLogic.DEFAULT_AUTO_LOCK,
    /** An open vault locks as soon as the app goes to the background. */
    val lockOnLeave: Boolean = true,
    /** Every upload waits for WLAN (the photo and folder backup keep their own setting on top). */
    val wifiOnly: Boolean = false,
    /** Drive pushes shown on this phone: reminders of deadlines, requests to approve a device for a vault. */
    val notifyDeadlines: Boolean = true,
    val notifyVaultRequests: Boolean = true,
)

/** Pure rules of the settings (unit-tested). */
internal object DriveSettingsLogic {
    const val DEFAULT_AUTO_LOCK = 3
    val AUTO_LOCK_CHOICES = listOf(1, 3, 5, 15)

    /** A stored value that is not one of the choices becomes the default. */
    fun autoLock(raw: Int): Int = raw.takeIf { it in AUTO_LOCK_CHOICES } ?: DEFAULT_AUTO_LOCK

    /** Whether a Drive push of [topic] (`deadline`, `vault`, …; without the plugin prefix) is shown. */
    fun showPush(topic: String, s: DriveSettingsValues): Boolean = when (topic) {
        "deadline" -> s.notifyDeadlines
        "vault" -> s.notifyVaultRequests
        else -> true
    }

    /** May an upload go now: always, or with "Hochladen nur im WLAN" only on an unmetered network. */
    fun uploadAllowed(wifiOnly: Boolean, connected: Boolean, unmetered: Boolean): Boolean = connected && (!wifiOnly || unmetered)
}

@Singleton
class DriveSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _values = MutableStateFlow(read())
    val values: StateFlow<DriveSettingsValues> = _values.asStateFlow()

    val current: DriveSettingsValues get() = _values.value

    private fun read() = DriveSettingsValues(
        vaultBiometric = prefs.getBoolean(KEY_BIOMETRIC, true),
        autoLockMinutes = DriveSettingsLogic.autoLock(prefs.getInt(KEY_AUTO_LOCK, DriveSettingsLogic.DEFAULT_AUTO_LOCK)),
        lockOnLeave = prefs.getBoolean(KEY_LEAVE, true),
        wifiOnly = prefs.getBoolean(KEY_WIFI, false),
        notifyDeadlines = prefs.getBoolean(KEY_NOTIFY_DEADLINES, true),
        notifyVaultRequests = prefs.getBoolean(KEY_NOTIFY_VAULT, true),
    )

    fun update(change: (DriveSettingsValues) -> DriveSettingsValues) {
        synchronized(this) {
            val next = change(_values.value).let { it.copy(autoLockMinutes = DriveSettingsLogic.autoLock(it.autoLockMinutes)) }
            prefs.edit()
                .putBoolean(KEY_BIOMETRIC, next.vaultBiometric)
                .putInt(KEY_AUTO_LOCK, next.autoLockMinutes)
                .putBoolean(KEY_LEAVE, next.lockOnLeave)
                .putBoolean(KEY_WIFI, next.wifiOnly)
                .putBoolean(KEY_NOTIFY_DEADLINES, next.notifyDeadlines)
                .putBoolean(KEY_NOTIFY_VAULT, next.notifyVaultRequests)
                .apply()
            _values.value = next
        }
    }

    companion object {
        private const val PREFS = "drive_settings"
        private const val KEY_BIOMETRIC = "vault_biometric"
        private const val KEY_AUTO_LOCK = "vault_auto_lock"
        private const val KEY_LEAVE = "vault_lock_on_leave"
        private const val KEY_WIFI = "upload_wifi_only"
        private const val KEY_NOTIFY_DEADLINES = "notify_deadlines"
        private const val KEY_NOTIFY_VAULT = "notify_vault"

        /** "Hochladen nur im WLAN", for the backups' scheduling (outside of injection). */
        fun wifiOnly(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WIFI, false)
    }
}
