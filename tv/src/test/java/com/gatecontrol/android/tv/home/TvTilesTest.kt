package com.gatecontrol.android.tv.home

import com.gatecontrol.android.common.SplitTunnelMode
import com.gatecontrol.android.quickactions.QuickActionControlItem
import com.gatecontrol.android.quickactions.QuickActionItem
import com.gatecontrol.android.quickactions.QuickActionWidgetItem
import com.gatecontrol.android.tunnel.SplitTunnelConfig
import com.gatecontrol.android.tv.tunnel.TvSplitTunnel
import com.gatecontrol.android.tv.tunnel.TvSplitTunnelSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TvTilesTest {

    private val light = QuickActionItem("gatecontrol-hue", "Licht", "scene-kino", "Szene Kino")
    private val shutters = QuickActionItem("gatecontrol-hue", "Licht", "shutters", "Rollläden")
    private val door = QuickActionItem("gatecontrol-door", "Tür", "lock-1", "Haustür", toggle = true, state = "on")
    private val setpoint = QuickActionControlItem("sp", QuickActionControlItem.KIND_STEPPER, number = 21.5, min = 5.0, max = 30.0, step = 0.5, unit = "°C")
    private val heating = QuickActionItem(
        "gatecontrol-raumklima", "Raumklima", "bath:power", "Heizung", toggle = true, state = "on",
        widget = QuickActionWidgetItem(value = "21,5", unit = "°", controls = listOf(setpoint)),
        deviceId = "bath", deviceTitle = "Heizung Bad",
    )
    private val heatingBoost = QuickActionItem("gatecontrol-raumklima", "Raumklima", "bath:boost", "Boost", deviceId = "bath", deviceTitle = "Heizung Bad")
    private val all = listOf(light, shutters, door, heating, heatingBoost)

    @Test
    fun `the start layout's order, tiles and widgets, unknown entries left out`() {
        val start = listOf("widget:gatecontrol-raumklima/bath", "tile:gatecontrol-door/lock-1", "info", "tile:gone/x", "tile:gatecontrol-hue/scene-kino", "agenda")
        val tiles = TvTiles.build(start, all)
        assertEquals(listOf("d:gatecontrol-raumklima/bath", "a:gatecontrol-door/lock-1", "a:gatecontrol-hue/scene-kino"), tiles.map { it.key })
        val device = tiles[0] as TvTile.Device
        assertEquals("Heizung Bad", device.title)
        assertEquals("bath:power", device.power?.id)
        assertEquals(setpoint, device.setpoint)
        assertTrue((tiles[1] as TvTile.Action).door)
        assertFalse((tiles[2] as TvTile.Action).door)
    }

    @Test
    fun `a wide widget is the same device`() {
        assertEquals(listOf("d:gatecontrol-raumklima/bath"), TvTiles.build(listOf("widget2:gatecontrol-raumklima/bath"), all).map { it.key })
    }

    @Test
    fun `without a usable layout every action, a device with a widget as one tile`() {
        val tiles = TvTiles.build(emptyList(), all)
        assertEquals(
            listOf("a:gatecontrol-hue/scene-kino", "a:gatecontrol-hue/shutters", "a:gatecontrol-door/lock-1", "d:gatecontrol-raumklima/bath"),
            tiles.map { it.key },
        )
        // a layout naming only things the server no longer lists counts as none
        assertEquals(tiles, TvTiles.build(listOf("tile:gone/x", "usage"), all))
        assertEquals(emptyList<TvTile>(), TvTiles.build(listOf("tile:a/b"), emptyList()))
    }

    @Test
    fun `at most one row of tiles`() {
        val many = (1..40).map { QuickActionItem("gatecontrol-hue", "Licht", "a$it", "A$it") }
        assertEquals(TvTiles.MAX, TvTiles.build(emptyList(), many).size)
    }

    @Test
    fun `the focus stays on its tile after a refresh, else at its place`() {
        val tiles = TvTiles.build(emptyList(), all)
        assertEquals(2, TvTiles.focusAfter(tiles, "a:gatecontrol-door/lock-1", 0))
        assertEquals(1, TvTiles.focusAfter(tiles, "a:gone/x", 1))
        assertEquals(tiles.lastIndex, TvTiles.focusAfter(tiles, "a:gone/x", 99))
        assertEquals(0, TvTiles.focusAfter(tiles, null, -3))
        assertEquals(-1, TvTiles.focusAfter(emptyList(), "a:x/y", 0))
    }

    @Test
    fun `left and right move the target in steps within the range`() {
        assertEquals(22.0, TvSetpoint.moved(setpoint, 21.5, 1))
        assertEquals(20.5, TvSetpoint.moved(setpoint, 21.5, -2))
        assertEquals(30.0, TvSetpoint.moved(setpoint, 29.5, 5))
        assertEquals(5.0, TvSetpoint.moved(setpoint, 5.5, -3))
        // off the grid (a value the server rounded differently): back onto it
        assertEquals(21.5, TvSetpoint.moved(setpoint, 21.3, 0))
        val whole = setpoint.copy(step = 1.0, min = 16.0, max = 30.0)
        assertEquals(19.0, TvSetpoint.moved(whole, 18.0, 1))
        assertFalse(TvSetpoint.changed(setpoint, 21.5))
        assertTrue(TvSetpoint.changed(setpoint, 22.0))
    }

    @Test
    fun `split tunnel - every app, only the chosen ones, all but the chosen ones`() {
        val own = "com.gatecontrol.client.tv"
        val allowed = "10.8.0.0/24, 192.168.1.0/24"
        assertEquals(SplitTunnelConfig(), TvSplitTunnel.config(TvSplitTunnelSettings(), own, allowed))
        assertEquals(
            SplitTunnelConfig(SplitTunnelMode.EXCLUDE, emptyList(), listOf("org.example.player")),
            TvSplitTunnel.config(TvSplitTunnelSettings(SplitTunnelMode.EXCLUDE, setOf("org.example.player", own)), own, allowed),
        )
        assertEquals(
            SplitTunnelConfig(SplitTunnelMode.INCLUDE, listOf("10.8.0.0/24", "192.168.1.0/24"), listOf(own, "org.example.media")),
            TvSplitTunnel.config(TvSplitTunnelSettings(SplitTunnelMode.INCLUDE, setOf("org.example.media")), own, allowed),
        )
        // nothing usable chosen: the whole tunnel
        assertEquals(SplitTunnelConfig(), TvSplitTunnel.config(TvSplitTunnelSettings(SplitTunnelMode.INCLUDE, setOf("not a package")), own, allowed))
        assertEquals(SplitTunnelConfig(), TvSplitTunnel.config(TvSplitTunnelSettings(SplitTunnelMode.EXCLUDE, setOf(own)), own, allowed))
        assertEquals(SplitTunnelConfig(), TvSplitTunnel.config(TvSplitTunnelSettings(SplitTunnelMode.INCLUDE, setOf("org.example.media")), own, ""))
    }
}
