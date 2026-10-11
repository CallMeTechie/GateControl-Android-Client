package com.gatecontrol.android.widget

import com.gatecontrol.android.notify.model.PushMessage
import com.gatecontrol.android.tunnel.TunnelState
import java.time.Instant

/** VPN state as the home-screen widget shows it. */
enum class WidgetVpn { CONNECTED, CONNECTING, DISCONNECTING, DISCONNECTED, ERROR }

/** What the widget shows and offers. */
data class WidgetModel(
    val vpn: WidgetVpn = WidgetVpn.DISCONNECTED,
    /** Server host, shown under the state on the wide widget. */
    val host: String? = null,
    val unread: Int = 0,
    /**
     * What a tap on the toggle does: true connects, false disconnects, null
     * offers nothing (switching over, or the client policy keeps the VPN on).
     */
    val toggleConnects: Boolean? = true,
)

fun widgetModel(state: TunnelState, host: String?, unread: Int, canDisconnect: Boolean): WidgetModel {
    val vpn = when (state) {
        is TunnelState.Connected -> WidgetVpn.CONNECTED
        TunnelState.Connecting, is TunnelState.Reconnecting -> WidgetVpn.CONNECTING
        TunnelState.Disconnecting -> WidgetVpn.DISCONNECTING
        is TunnelState.Error -> WidgetVpn.ERROR
        TunnelState.Disconnected -> WidgetVpn.DISCONNECTED
    }
    val toggle = when (vpn) {
        // Client policy "always on": no manual disconnect, same as the tile and the app.
        WidgetVpn.CONNECTED, WidgetVpn.CONNECTING -> if (canDisconnect) false else null
        WidgetVpn.DISCONNECTING -> null
        WidgetVpn.DISCONNECTED, WidgetVpn.ERROR -> true
    }
    return WidgetModel(vpn = vpn, host = host?.takeIf { it.isNotBlank() }, unread = unread.coerceAtLeast(0), toggleConnects = toggle)
}

/** Unread, not yet expired messages: the same count as the bell on the start screen. */
fun unreadCount(items: List<PushMessage>, now: Instant = Instant.now()): Int = items.count { m ->
    m.isUnread && m.expiresAt?.let { runCatching { Instant.parse(it).isAfter(now) }.getOrDefault(true) } ?: true
}
