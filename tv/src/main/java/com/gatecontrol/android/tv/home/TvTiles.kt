package com.gatecontrol.android.tv.home

import com.gatecontrol.android.door.DOOR_PLUGIN
import com.gatecontrol.android.layout.StartEntry
import com.gatecontrol.android.layout.StartLayout
import com.gatecontrol.android.quickactions.QuickActionControlItem
import com.gatecontrol.android.quickactions.QuickActionDeviceGroup
import com.gatecontrol.android.quickactions.QuickActionDevices
import com.gatecontrol.android.quickactions.QuickActionItem
import kotlin.math.abs
import kotlin.math.roundToLong

/** One tile of the start page's row "Schnellaktionen". [key] stays the same across refreshes (focus, pending runs). */
sealed interface TvTile {
    val key: String
    val title: String

    /** A quick action: OK runs it (a switch toggles). [door]: the door plugin's (TV PIN, confirm). */
    data class Action(val item: QuickActionItem) : TvTile {
        override val key: String get() = "a:" + item.key
        override val title: String get() = item.title
        val door: Boolean get() = item.plugin == DOOR_PLUGIN
    }

    /**
     * A device with a widget (light, heating, air conditioner): OK switches its power action; a heating's target
     * temperature ([setpoint]) is set with ◀ ▶ once OK on "Ziel" opened it ([TvSetpoint]).
     */
    data class Device(val group: QuickActionDeviceGroup) : TvTile {
        override val key: String get() = "d:" + group.key
        override val title: String get() = group.title
        val power: QuickActionItem? get() = group.powerAction
        val carrier: QuickActionItem? get() = group.carrier

        /** The first numeric control (stepper/slider), e.g. "Ziel 22°". */
        val setpoint: QuickActionControlItem? get() = group.widget?.controls?.firstOrNull { it.isNumeric && !it.disabled }

        /** A choice control (mode of an air conditioner). */
        val choice: QuickActionControlItem? get() = group.widget?.controls?.firstOrNull { it.kind == QuickActionControlItem.KIND_CHOICE && !it.disabled }
    }
}

/** The start page's tiles from the server's start layout. Pure, unit-tested. */
object TvTiles {
    /** Tiles in one row (the stick's 2 GB: a long row is never all composed, still it should stay short). */
    const val MAX = 24

    /**
     * The row in the order of the start layout ([start], `tile:` and `widget:` entries as on the phone — with the
     * scope "user" or "global" the server keeps it for every device of the person): a `tile:` entry is its action, a
     * `widget:` entry its device. Entries the TV cannot show (Info, usage, contacts, agenda) and actions no longer
     * listed are left out. Without a usable layout (scope "device", an older server) every listed action shows, a
     * device with a widget as one tile, in the server's order.
     */
    fun build(start: List<String>, actions: List<QuickActionItem>): List<TvTile> {
        val devices = QuickActionDevices.group(actions)
        val byAction = actions.associateBy { it.key }
        val byDevice = devices.associateBy { it.key }
        val fromLayout = start.mapNotNull { raw ->
            when (val entry = StartLayout.decode(raw)) {
                is StartEntry.Tile -> byAction[entry.action]?.let { TvTile.Action(it) }
                is StartEntry.Widget -> byDevice[entry.device]?.takeIf { it.widget != null }?.let { TvTile.Device(it) }
                else -> null
            }
        }.distinctBy { it.key }
        if (fromLayout.isNotEmpty()) return fromLayout.take(MAX)
        return devices.flatMap { group ->
            if (group.widget != null) listOf(TvTile.Device(group))
            else group.actions.map { TvTile.Action(it) }
        }.distinctBy { it.key }.take(MAX)
    }

    /**
     * Where the focus goes after the row changed (refresh, a run's answer): the tile with the same [key] if it is
     * still there, else the one now at the old [index] (or the last); -1 for an empty row.
     */
    fun focusAfter(tiles: List<TvTile>, key: String?, index: Int): Int {
        if (tiles.isEmpty()) return -1
        val same = key?.let { k -> tiles.indexOfFirst { it.key == k } } ?: -1
        if (same >= 0) return same
        return index.coerceIn(0, tiles.lastIndex)
    }
}

/** ◀ ▶ on a target temperature: one step per press, within the control's range. Pure, unit-tested. */
object TvSetpoint {
    /** [value] moved by [presses] steps (negative: down), snapped to the step grid from [QuickActionControlItem.min]. */
    fun moved(control: QuickActionControlItem, value: Double, presses: Int): Double {
        val raw = value + presses * control.step
        val snapped = control.min + ((raw - control.min) / control.step).roundToLong() * control.step
        val clamped = snapped.coerceIn(control.min, control.max)
        // 21.499999 → 21.5: as many decimals as the step has
        val factor = if (control.decimals == 0) 1.0 else 10.0
        return Math.round(clamped * factor) / factor
    }

    /** The value differs from what the server has (worth sending). */
    fun changed(control: QuickActionControlItem, value: Double): Boolean = abs(value - control.number) > control.step / 1000
}
