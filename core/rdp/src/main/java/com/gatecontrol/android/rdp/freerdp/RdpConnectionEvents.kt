package com.gatecontrol.android.rdp.freerdp

import com.freerdp.freerdpcore.services.LibFreeRDP
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * FreeRDP's connection events (`OnConnectionFailure`, `OnDisconnected`, …) go to ONE static
 * [LibFreeRDP.EventListener] for the whole process. FreeRDP's own app sets it in `GlobalApp.onCreate()`,
 * which never runs here (GateControlApp is not GlobalApp) — without this object a failed connection
 * (host not reachable, NLA refused the login, TLS failed) reached nobody: the session window stayed
 * empty forever, without a message.
 *
 * Every [RdpSessionController] registers its instance here; the events of that instance go to its flow.
 * Only the terminal events are forwarded: `OnPreConnect` / `OnConnectionSuccess` would replace
 * `SettingsChanged` in the conflating StateFlow before the window created its surface. A failure is
 * kept: the `OnDisconnected` FreeRDP sends right after it does not hide it.
 */
object RdpConnectionEvents : LibFreeRDP.EventListener {

    private val flows = ConcurrentHashMap<Long, MutableStateFlow<RdpSessionEvent>>()
    private val failed = ConcurrentHashMap.newKeySet<Long>()
    @Volatile private var installed = false

    /** Route the connection events of [instance] to [flow] (sets FreeRDP's listener the first time). */
    fun register(instance: Long, flow: MutableStateFlow<RdpSessionEvent>) {
        flows[instance] = flow
        failed.remove(instance)
        install()
    }

    fun unregister(instance: Long) {
        flows.remove(instance)
        failed.remove(instance)
    }

    private fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            try {
                LibFreeRDP.setEventListener(this)
            } catch (_: Throwable) {
                // no native library (unit tests): nothing would call us anyway
            }
            installed = true
        }
    }

    override fun OnPreConnect(instance: Long) = Unit

    override fun OnConnectionSuccess(instance: Long) = Unit

    override fun OnConnectionFailure(instance: Long) {
        failed.add(instance)
        flows[instance]?.value = RdpSessionEvent.ConnectionFailure(instance, null)
    }

    override fun OnDisconnecting(instance: Long) = Unit

    override fun OnDisconnected(instance: Long) {
        // the disconnect that follows a failure must not hide it
        if (instance in failed) return
        flows[instance]?.value = RdpSessionEvent.Disconnected(instance)
    }
}
