package com.gatecontrol.android.infocard

import com.gatecontrol.android.network.ClientTile
import com.gatecontrol.android.quickactions.QuickActionIcons
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** State colour of a plugin start tile. */
enum class TileState(val wire: String) {
    ON("on"), OFF("off"), GOOD("good"), WARN("warn"), CRIT("crit");

    companion object {
        fun fromWire(value: String?): TileState? = entries.firstOrNull { it.wire == value }
    }
}

/** A plugin start tile as the app keeps it (checked and capped, `GET /api/v1/client/tiles`). */
data class InfoTile(
    /** Stable `<pluginId>/<section>/<title>`. */
    val key: String,
    val plugin: String,
    val pluginName: String,
    val section: String?,
    val title: String,
    val value: String?,
    val unit: String?,
    val state: TileState?,
    /** SVG path `d` (24×24), null = none. */
    val icon: String?,
) {
    companion object {
        private val PLUGIN = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
        private const val MAX_KEY = 200

        /** Drops tiles without a usable key, plugin or title; caps texts; first of a duplicate key wins. */
        fun sanitize(raw: List<ClientTile>): List<InfoTile> = raw.mapNotNull { t ->
            val key = t.key?.let(::clean)?.takeIf { it.isNotEmpty() && it.length <= MAX_KEY } ?: return@mapNotNull null
            val plugin = t.plugin?.takeIf { PLUGIN.matches(it) } ?: return@mapNotNull null
            val title = t.title?.let(::clean)?.take(40)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            InfoTile(
                key = key,
                plugin = plugin,
                pluginName = t.pluginName?.let(::clean)?.take(40)?.takeIf { it.isNotBlank() } ?: plugin,
                section = t.section?.let(::clean)?.take(40)?.takeIf { it.isNotBlank() },
                title = title,
                value = valueText(t.value)?.let(::clean)?.take(24)?.takeIf { it.isNotBlank() },
                unit = t.unit?.let(::clean)?.take(12)?.takeIf { it.isNotBlank() },
                state = TileState.fromWire(t.state),
                icon = QuickActionIcons.sanitize(t.icon),
            )
        }.distinctBy { it.key }

        /** A JSON string or number as text; anything else (object, array, null) is no value. */
        private fun valueText(value: JsonElement?): String? {
            if (value == null || !value.isJsonPrimitive) return null
            return runCatching { value.asJsonPrimitive.asString }.getOrNull()
        }

        private fun clean(s: String): String = s.filter { !it.isISOControl() }.trim()
    }
}

/**
 * What the "Info" card can show, by id. Generic so that built-in contents
 * (e.g. the app's own values) can be added later next to the plugin tiles;
 * today only [PluginTile] exists. The id is what the selection stores.
 */
sealed interface InfoSource {
    val id: String

    /** A start tile of a plugin, by its stable key. */
    data class PluginTile(val key: String) : InfoSource {
        override val id: String get() = TILE_PREFIX + key
    }

    companion object {
        const val TILE_PREFIX = "tile:"

        /** The source of a stored id; null for unknown kinds (e.g. from a newer app version). */
        fun parse(id: String): InfoSource? = when {
            id.startsWith(TILE_PREFIX) -> id.removePrefix(TILE_PREFIX).takeIf { it.isNotEmpty() }?.let(::PluginTile)
            else -> null
        }
    }
}

/** One slide of the card: a source and what it shows. */
sealed interface InfoSlide {
    val source: InfoSource

    data class Tile(val tile: InfoTile) : InfoSlide {
        override val source: InfoSource get() = InfoSource.PluginTile(tile.key)
    }

    val id: String get() = source.id
}

/** The contents chosen for the card (source ids in order) and the slide shown first. */
data class InfoSelection(
    val ids: List<String> = emptyList(),
    val favorite: String? = null,
)

/** Answer of the server for the tiles, kept in memory only. */
data class InfoTilesState(
    val status: Status = Status.UNKNOWN,
    /** Last tiles the server sent (kept while a later refresh fails). */
    val tiles: List<InfoTile> = emptyList(),
) {
    enum class Status { UNKNOWN, LOADED, UNSUPPORTED, FAILED }

    /** Every slide that can be chosen, plugin tiles in the server's order. */
    val slides: List<InfoSlide> get() = tiles.map { InfoSlide.Tile(it) }
}

/** What the start tab shows of the "Info" card (pure, unit-tested). */
sealed interface InfoCardView {
    /** Older server (404), no tiles (yet), or none at all: no card. */
    data object Hidden : InfoCardView

    /** Tiles available, none chosen (or every chosen one gone): only "Inhalte auswählen". */
    data object Choose : InfoCardView

    /** The chosen slides that still exist, in the chosen order; [initialPage] = the favourite. */
    data class Slides(val slides: List<InfoSlide>, val initialPage: Int) : InfoCardView
}

/** Selection rules of the "Info" card (pure, unit-tested). */
object InfoCardLogic {

    const val MAX_SELECTED = 20

    fun view(state: InfoTilesState, selection: InfoSelection): InfoCardView {
        if (state.status == InfoTilesState.Status.UNSUPPORTED) return InfoCardView.Hidden
        val available = state.slides
        if (available.isEmpty()) return InfoCardView.Hidden
        val slides = chosen(available, selection)
        if (slides.isEmpty()) return InfoCardView.Choose
        return InfoCardView.Slides(slides, initialPage(slides, selection.favorite))
    }

    /** The chosen slides that exist, in the chosen order; vanished ones are skipped silently. */
    fun chosen(available: List<InfoSlide>, selection: InfoSelection): List<InfoSlide> {
        val byId = available.associateBy { it.id }
        return selection.ids.distinct().mapNotNull { byId[it] }
    }

    fun initialPage(slides: List<InfoSlide>, favorite: String?): Int =
        slides.indexOfFirst { it.id == favorite }.coerceAtLeast(0)

    /** Adds [id] at the end, or removes it (a removed favourite is no favourite any more). */
    fun toggle(selection: InfoSelection, id: String): InfoSelection =
        if (id in selection.ids) {
            selection.copy(ids = selection.ids - id, favorite = selection.favorite?.takeIf { it != id })
        } else if (selection.ids.size >= MAX_SELECTED) {
            selection
        } else {
            selection.copy(ids = selection.ids + id)
        }

    /**
     * Moves [id] by [delta] places among the [visible] chosen ids (the ones
     * the person sees in the picker); chosen ids whose source is gone keep
     * their place behind them.
     */
    fun move(selection: InfoSelection, id: String, delta: Int, visible: List<String>): InfoSelection {
        val order = selection.ids.filter { it in visible }.toMutableList()
        val from = order.indexOf(id)
        if (from < 0) return selection
        val to = (from + delta).coerceIn(0, order.lastIndex)
        if (to == from) return selection
        order.add(to, order.removeAt(from))
        return selection.copy(ids = order + selection.ids.filter { it !in visible })
    }

    fun favorite(selection: InfoSelection, id: String): InfoSelection =
        if (id in selection.ids) selection.copy(favorite = id) else selection
}

/**
 * Storage format of the card's contents, per login (server + token):
 * `{"v":1,"owners":{"<owner>":{"ids":[…],"fav":"…"}}}`. The most recently
 * written logins are kept, at most [MAX_OWNERS].
 */
object InfoCardStore {

    private const val MAX_OWNERS = 8

    fun read(raw: String?, owner: String): InfoSelection {
        if (owner.isEmpty()) return InfoSelection()
        val entry = owners(raw)?.get(owner)?.takeIf { it.isJsonObject }?.asJsonObject ?: return InfoSelection()
        val ids = runCatching {
            entry.getAsJsonArray("ids")?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() } }
        }.getOrNull().orEmpty().distinct().take(InfoCardLogic.MAX_SELECTED)
        val fav = runCatching { entry.get("fav")?.takeIf { it.isJsonPrimitive }?.asString }.getOrNull()
        return InfoSelection(ids = ids, favorite = fav?.takeIf { it in ids })
    }

    fun write(raw: String?, owner: String, selection: InfoSelection): String {
        val previous = owners(raw) ?: JsonObject()
        val next = JsonObject()
        // Re-inserted last: the oldest logins drop out first.
        previous.entrySet().filter { it.key != owner }.takeLast(MAX_OWNERS - 1).forEach { next.add(it.key, it.value) }
        if (owner.isNotEmpty()) {
            next.add(
                owner,
                JsonObject().apply {
                    add("ids", JsonArray().apply { selection.ids.distinct().forEach { add(it) } })
                    selection.favorite?.takeIf { it in selection.ids }?.let { addProperty("fav", it) }
                },
            )
        }
        return JsonObject().apply {
            addProperty("v", 1)
            add("owners", next)
        }.toString()
    }

    private fun owners(raw: String?): JsonObject? = runCatching {
        if (raw.isNullOrBlank()) null
        else JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject?.get("owners")?.takeIf { it.isJsonObject }?.asJsonObject
    }.getOrNull()
}
