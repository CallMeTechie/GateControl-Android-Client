package com.gatecontrol.android.widget

import com.gatecontrol.android.notify.model.PushMessage
import com.gatecontrol.android.notify.model.PushState
import com.gatecontrol.android.tunnel.TunnelState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class WidgetModelTest {

    @Test
    fun `disconnected offers connect, connected offers disconnect`() {
        val off = widgetModel(TunnelState.Disconnected, "gc.example.com", 0, canDisconnect = true)
        assertEquals(WidgetVpn.DISCONNECTED, off.vpn)
        assertEquals(true, off.toggleConnects)
        val on = widgetModel(TunnelState.Connected(), "gc.example.com", 2, canDisconnect = true)
        assertEquals(WidgetVpn.CONNECTED, on.vpn)
        assertEquals(false, on.toggleConnects)
        assertEquals("gc.example.com", on.host)
        assertEquals(2, on.unread)
    }

    @Test
    fun `always-on policy offers no disconnect`() {
        assertNull(widgetModel(TunnelState.Connected(), null, 0, canDisconnect = false).toggleConnects)
        assertNull(widgetModel(TunnelState.Connecting, null, 0, canDisconnect = false).toggleConnects)
        // Still off: connecting is always allowed.
        assertEquals(true, widgetModel(TunnelState.Disconnected, null, 0, canDisconnect = false).toggleConnects)
    }

    @Test
    fun `transitions and errors`() {
        assertEquals(WidgetVpn.CONNECTING, widgetModel(TunnelState.Reconnecting(1, 5), null, 0, true).vpn)
        assertNull(widgetModel(TunnelState.Disconnecting, null, 0, true).toggleConnects)
        val err = widgetModel(TunnelState.Error("x"), " ", -3, true)
        assertEquals(WidgetVpn.ERROR, err.vpn)
        assertEquals(true, err.toggleConnects)
        assertNull(err.host)
        assertEquals(0, err.unread)
    }

    private fun msg(id: Long, state: String = PushState.DELIVERED, expiresAt: String? = null) = PushMessage(
        seq = id, id = id, eventId = "e", topic = "devices", priority = "normal", title = "t", body = "",
        createdAt = null, expiresAt = expiresAt, collapseKey = null, silent = false, route = null, actions = emptyList(),
        state = state,
    )

    @Test
    fun `unread counts unread, unexpired messages`() {
        val now = Instant.parse("2026-10-11T12:00:00Z")
        val items = listOf(
            msg(1),
            msg(2, state = PushState.READ),
            msg(3, expiresAt = "2026-10-11T11:00:00Z"),
            msg(4, expiresAt = "2026-10-12T11:00:00Z"),
            msg(5, expiresAt = "not a date"),
        )
        assertEquals(3, unreadCount(items, now))
    }
}
