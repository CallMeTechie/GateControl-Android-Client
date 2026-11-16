package com.gatecontrol.android.remote

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.gatecontrol.android.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The bridge to the accessibility service of Fernzugriff stage 6 („GateControl-Fernsteuerung“). The service itself
 * exists **only in the GitHub build** (src/screencontrol, merged by Gradle when gc.distribution=github; decision 4: the
 * Google Play build only shows the screen). It registers itself here while Android has it bound; the main code never
 * names its class, so the Play build has neither the service nor its declaration — [available] stays false there.
 *
 * The service carries input out only while [ScreenShareService] asks it to (a session the person allowed with
 * „Ansehen und steuern“, mode control in force). It reads nothing but the focused input field (to put text at its
 * cursor) and sends nothing anywhere.
 */
object ScreenControl {
    /** The class of the service in src/screencontrol (only a name here: the Play build has no such class). */
    const val SERVICE_CLASS = "com.gatecontrol.android.remote.control.GcScreenControlService"

    /** What the bound service can do (implemented in src/screencontrol). All calls on the main thread. */
    interface Executor {
        /** The display in pixels the shared picture shows (the whole display, current rotation). */
        fun displaySize(): Pair<Int, Int>

        /** One stroke; [done] true when Android carried it out. */
        fun gesture(plan: GesturePlan, done: (Boolean) -> Unit)

        /** back | home | recents → a global action. */
        fun global(key: String): Boolean

        /** Text, Enter or Backspace into the focused input field → null when done, else a reason of [ScreenInput.FAIL_REASONS]. */
        fun edit(input: ControlInput): String?

        /** The red frame and the badge „%s steuert · Steuern beenden“ over every app ([onStop]: the person took control away). */
        fun showOverlay(viewerName: String, onStop: () -> Unit)

        fun hideOverlay()
    }

    @Volatile private var executor: Executor? = null
    private val _connected = MutableStateFlow(false)

    /** The service is bound right now (it can carry input out). */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** This build can control at all (the GitHub APK). */
    val supported: Boolean get() = BuildConfig.SCREEN_CONTROL

    /** The service is there and bound. */
    val available: Boolean get() = supported && executor != null

    fun executor(): Executor? = if (supported) executor else null

    /** The service is bound (onServiceConnected) — only the service in src/screencontrol calls this. */
    fun attach(e: Executor) {
        if (!supported) return
        executor = e
        _connected.value = true
    }

    /** The service is unbound (onUnbind / onDestroy, the person switched it off). */
    fun detach(e: Executor) {
        if (executor === e) executor = null
        _connected.value = executor != null
    }

    /** The component of the service, as Android's settings name it. */
    fun component(context: Context): ComponentName = ComponentName(context.packageName, SERVICE_CLASS)

    /** The person switched „GateControl-Fernsteuerung“ on in Android's accessibility settings (bound or not yet). */
    fun enabledInSettings(context: Context): Boolean {
        if (!supported) return false
        val list = runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) }.getOrNull() ?: return false
        val me = component(context)
        return list.split(':').any { entry ->
            ComponentName.unflattenFromString(entry.trim())?.let { it.packageName == me.packageName && it.className == me.className } == true
        }
    }
}
