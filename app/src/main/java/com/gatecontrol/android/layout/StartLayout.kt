package com.gatecontrol.android.layout

/**
 * One block of the start tab below the header: a pinned action as a tile,
 * a device pinned as a widget (half or full width), the Info card or the
 * throughput/data usage card. [key] names the block whatever its width.
 */
sealed interface StartEntry {
    val key: String

    /** [action]: the quick action's key `<plugin>/<id>`. */
    data class Tile(val action: String) : StartEntry {
        override val key: String get() = StartLayout.TILE + action
    }

    /** [device]: the widget pin's device key; [wide] full instead of half width. */
    data class Widget(val device: String, val wide: Boolean = false) : StartEntry {
        override val key: String get() = StartLayout.WIDGET + device
    }

    data object Info : StartEntry {
        override val key: String get() = StartLayout.INFO
    }

    data object Usage : StartEntry {
        override val key: String get() = StartLayout.USAGE
    }
}

/**
 * The start tab's order (pure, unit-tested). Tiles come first as a block
 * of their own (their order among each other), then widgets, Info card and
 * usage card in one common order; half-width widgets side by side share a
 * row ([rows]). Stored as `tile:<plugin>/<id>`, `widget:<device>` (half),
 * `widget2:<device>` (full width), `info`, `usage`.
 */
object StartLayout {
    const val TILE = "tile:"
    const val WIDGET = "widget:"
    const val WIDGET_WIDE = "widget2:"
    const val INFO = "info"
    const val USAGE = "usage"

    fun encode(entry: StartEntry): String = when (entry) {
        is StartEntry.Tile -> TILE + entry.action
        is StartEntry.Widget -> (if (entry.wide) WIDGET_WIDE else WIDGET) + entry.device
        StartEntry.Info -> INFO
        StartEntry.Usage -> USAGE
    }

    fun encodeAll(entries: List<StartEntry>): List<String> = entries.map(::encode)

    fun decode(raw: String): StartEntry? = when {
        raw == INFO -> StartEntry.Info
        raw == USAGE -> StartEntry.Usage
        raw.startsWith(TILE) -> raw.removePrefix(TILE).takeIf { it.isNotEmpty() }?.let { StartEntry.Tile(it) }
        raw.startsWith(WIDGET_WIDE) -> raw.removePrefix(WIDGET_WIDE).takeIf { it.isNotEmpty() }?.let { StartEntry.Widget(it, wide = true) }
        raw.startsWith(WIDGET) -> raw.removePrefix(WIDGET).takeIf { it.isNotEmpty() }?.let { StartEntry.Widget(it, wide = false) }
        else -> null
    }

    /** The block a stored entry names, whatever its width (for [TabLayout.keepForeign]). */
    fun identity(raw: String): String = decode(raw)?.key ?: raw

    /** Widths the stored order gives its widgets: device key → full width. */
    fun widths(saved: List<String>): Map<String, Boolean> =
        saved.mapNotNull { decode(it) as? StartEntry.Widget }.associate { it.device to it.wide }

    /**
     * The blocks to show: what this device has — [tiles] (pinned action
     * keys, in pin order), [widgets] (with their width) and always the Info
     * and usage cards — in the [saved] order. Blocks the order lacks join
     * where they did before arranging: a tile after the other tiles, a
     * widget after the last widget (before the cards when there is none),
     * Info and usage card at the end. Entries for blocks this device does
     * not have are skipped.
     */
    fun merge(saved: List<String>, tiles: List<String>, widgets: List<StartEntry.Widget>): List<StartEntry> {
        val widgetByDevice = widgets.associateBy { it.device }
        val tileSet = tiles.toSet()
        val orderedTiles = ArrayList<StartEntry>()
        val cards = ArrayList<StartEntry>()
        val seen = HashSet<String>()
        for (raw in saved) {
            val entry = decode(raw) ?: continue
            if (!seen.add(entry.key)) continue
            when (entry) {
                is StartEntry.Tile -> if (entry.action in tileSet) orderedTiles += entry
                // The width is the pin's: arranging and the pin sheet keep both in step.
                is StartEntry.Widget -> widgetByDevice[entry.device]?.let { cards += it }
                StartEntry.Info, StartEntry.Usage -> cards += entry
            }
        }
        tiles.filter { seen.add(TILE + it) }.forEach { orderedTiles += StartEntry.Tile(it) }
        for (widget in widgets) {
            if (!seen.add(widget.key)) continue
            val lastWidget = cards.indexOfLast { it is StartEntry.Widget }
            cards.add(if (lastWidget >= 0) lastWidget + 1 else 0, widget)
        }
        if (seen.add(INFO)) cards += StartEntry.Info
        if (seen.add(USAGE)) cards += StartEntry.Usage
        return orderedTiles + cards
    }

    /**
     * [entries] with the block [fromKey] moved to the place of [toKey];
     * a tile only among the tiles, a card only among the cards.
     */
    fun move(entries: List<StartEntry>, fromKey: String, toKey: String): List<StartEntry> {
        val from = entries.indexOfFirst { it.key == fromKey }
        val to = entries.indexOfFirst { it.key == toKey }
        if (from < 0 || to < 0 || from == to) return entries
        if ((entries[from] is StartEntry.Tile) != (entries[to] is StartEntry.Tile)) return entries
        return entries.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** [entries] with the block [key] one place earlier ([delta] -1) or later (+1) within its group. */
    fun moveBy(entries: List<StartEntry>, key: String, delta: Int): List<StartEntry> {
        val from = entries.indexOfFirst { it.key == key }
        if (from < 0) return entries
        val to = from + delta
        if (to !in entries.indices || (entries[to] is StartEntry.Tile) != (entries[from] is StartEntry.Tile)) return entries
        return TabLayout.moveBy(entries, from, delta)
    }

    /** Whether [key] can move by [delta] (for the accessibility actions). */
    fun canMove(entries: List<StartEntry>, key: String, delta: Int): Boolean = moveBy(entries, key, delta) !== entries

    /** [entries] with the widget of [device] switched between half and full width. */
    fun toggleWide(entries: List<StartEntry>, device: String): List<StartEntry> =
        entries.map { if (it is StartEntry.Widget && it.device == device) it.copy(wide = !it.wide) else it }

    /**
     * The cards as rows: two half-width widgets in a row side by side
     * (they get the same height), everything else a row of its own; a half
     * widget without a half neighbour keeps its half width.
     */
    fun rows(cards: List<StartEntry>): List<List<StartEntry>> {
        val rows = ArrayList<List<StartEntry>>()
        var i = 0
        while (i < cards.size) {
            val current = cards[i]
            val next = cards.getOrNull(i + 1)
            if (current.isHalf && next != null && next.isHalf) {
                rows += listOf(current, next)
                i += 2
            } else {
                rows += listOf(current)
                i += 1
            }
        }
        return rows
    }

    /** [items] in the order of [order] (by [key]); items it lacks keep their order after the others. */
    fun <T> ordered(items: List<T>, order: List<String>, key: (T) -> String): List<T> {
        val rank = order.withIndex().associate { (i, k) -> k to i }
        return items.withIndex()
            .sortedWith(compareBy({ rank[key(it.value)] ?: Int.MAX_VALUE }, { it.index }))
            .map { it.value }
    }

    /** A widget of half width (the only blocks that share a row). */
    val StartEntry.isHalf: Boolean get() = this is StartEntry.Widget && !wide
}
