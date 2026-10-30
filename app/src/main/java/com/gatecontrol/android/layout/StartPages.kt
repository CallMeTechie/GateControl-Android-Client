package com.gatecontrol.android.layout

/**
 * Pages beside the start tab (pure, unit-tested): functions that are a tab
 * otherwise (Chats, Vault, Drive, Netzwerk, Familie) pinned to Start, and
 * widget pages ("Widgets 2", "Widgets 3" …) holding quick actions and
 * widgets like Start does.
 *
 * Kept in the start tab's list ([TabLayout.start]) so the server needs no
 * change: a marker `page:<id>` per page in the order of the pages; what
 * stands before the first marker is the start tab's ([StartLayout]), what
 * follows `page:w<n>` belongs to that widget page. A function page has no
 * entries — whatever follows its marker still belongs to the page before it
 * (an older app keeps the markers as foreign entries, anchored after the
 * entry they followed, and may put its own entries behind them).
 *
 * Older apps skip the markers ([StartLayout.decode] gives null) and show
 * every pinned block on Start again; saving there keeps the markers
 * ([TabLayout.keepForeign]).
 */
object StartPages {
    const val MARKER = "page:"

    const val CHATS = "chats"
    const val VAULT = "vault"
    const val DRIVE = "drive"
    const val NETWORK = "network"
    const val FAMILIE = "familie"

    /** Functions that can be pages, in the order of the tab bar. */
    val FEATURES = listOf(CHATS, VAULT, DRIVE, NETWORK, FAMILIE)

    private val WIDGETS = Regex("^w([0-9]{1,3})$")

    /** One page beside Start: its [id] and, for a widget page, its [entries] (StartLayout ids). */
    data class Page(val id: String, val entries: List<String> = emptyList())

    /** The start list taken apart: the start tab's own [start] entries and the [pages] in their order. */
    data class Split(val start: List<String>, val pages: List<Page>) {
        val ids: List<String> get() = pages.map { it.id }
    }

    fun isMarker(raw: String): Boolean = raw.startsWith(MARKER)

    fun marker(id: String): String = MARKER + id

    fun isFeature(id: String): Boolean = id in FEATURES

    fun isWidgets(id: String): Boolean = widgetsNumber(id) != null

    /** The number of the widget page [id] (`w2` → 2), null for anything else. */
    fun widgetsNumber(id: String): Int? = WIDGETS.matchEntire(id)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it >= 2 }

    fun widgetsId(number: Int): String = "w$number"

    /**
     * [saved] taken apart. The first marker of an id counts; a marker
     * without id is dropped. Ids this version does not know stay pages (so
     * saving keeps them) but have no entries, like a function page.
     */
    fun split(saved: List<String>): Split {
        val start = ArrayList<String>()
        val pages = ArrayList<Page>()
        val entries = HashMap<String, MutableList<String>>()
        var owner: String? = null
        for (raw in saved) {
            if (isMarker(raw)) {
                val id = raw.removePrefix(MARKER)
                if (id.isEmpty() || pages.any { it.id == id }) continue
                pages += Page(id)
                if (isWidgets(id)) {
                    owner = id
                    entries[id] = ArrayList()
                }
                continue
            }
            val target = owner
            if (target == null) start += raw else entries.getValue(target) += raw
        }
        return Split(start, pages.map { p -> entries[p.id]?.let { p.copy(entries = it) } ?: p })
    }

    /** [split] put back into one list: the start entries, then each marker with its page's entries. */
    fun join(split: Split): List<String> = buildList {
        addAll(split.start)
        for (page in split.pages) {
            add(marker(page.id))
            if (isWidgets(page.id)) addAll(page.entries)
        }
    }

    /** The page ids of [saved] in their order. */
    fun pages(saved: List<String>): List<String> = split(saved).ids

    /**
     * The pages to show: function pages only when the function is
     * [available] (its plugin listed, the Netzwerk segments allowed),
     * every widget page; unknown ids are left out.
     */
    fun visible(saved: List<String>, available: Collection<String>): List<String> =
        pages(saved).filter { isWidgets(it) || (isFeature(it) && it in available) }

    /** The entries of the page [page] (null: the start tab). */
    fun entriesOf(saved: List<String>, page: String?): List<String> {
        val split = split(saved)
        return if (page == null) split.start else split.pages.firstOrNull { it.id == page }?.entries.orEmpty()
    }

    /** [saved] with the entries of [page] (null: the start tab) replaced by [entries]; unchanged when the page is missing. */
    fun withEntries(saved: List<String>, page: String?, entries: List<String>): List<String> {
        val split = split(saved)
        if (page == null) return join(split.copy(start = entries))
        if (!isWidgets(page) || split.pages.none { it.id == page }) return saved
        return join(split.copy(pages = split.pages.map { if (it.id == page) it.copy(entries = entries) else it }))
    }

    /** [saved] with [id] as the last page (unchanged when it is a page already). */
    fun pin(saved: List<String>, id: String): List<String> {
        val split = split(saved)
        if (id.isEmpty() || split.pages.any { it.id == id }) return join(split)
        return join(split.copy(pages = split.pages + Page(id)))
    }

    /**
     * [saved] without the page [id]: a function becomes a tab again; a
     * widget page's blocks go back to the end of the start tab (nothing is
     * unpinned, so taking it back restores it as it was).
     */
    fun unpin(saved: List<String>, id: String): List<String> {
        val split = split(saved)
        val page = split.pages.firstOrNull { it.id == id } ?: return join(split)
        return join(Split(split.start + page.entries, split.pages - page))
    }

    /** The id of a new widget page: the smallest number from 2 no page has. */
    fun nextWidgets(saved: List<String>): String {
        val used = pages(saved).mapNotNull(::widgetsNumber).toSet()
        return widgetsId(generateSequence(2) { it + 1 }.first { it !in used })
    }

    /** [saved] with its pages in the order of [order] (ids it lacks keep their order after the others). */
    fun reorder(saved: List<String>, order: List<String>): List<String> {
        val split = split(saved)
        return join(split.copy(pages = StartLayout.ordered(split.pages, order) { it.id }))
    }

    /** [ids] with [id] one place earlier ([delta] -1) or later (+1); unchanged at the ends. */
    fun moveBy(ids: List<String>, id: String, delta: Int): List<String> = TabLayout.moveBy(ids, ids.indexOf(id), delta)

    /** Whether [id] can move by [delta] among [ids] (accessibility "nach oben/unten"). */
    fun canMove(ids: List<String>, id: String, delta: Int): Boolean = moveBy(ids, id, delta) !== ids

    /** [ids] with [fromId] moved to the place of [toId] (dragging). */
    fun move(ids: List<String>, fromId: String, toId: String): List<String> {
        val from = ids.indexOf(fromId)
        val to = ids.indexOf(toId)
        if (from < 0 || to < 0 || from == to) return ids
        return ids.toMutableList().apply { add(to, removeAt(from)) }
    }

    /**
     * The blocks each page shows (null: the start tab): every pin of this
     * device — buttons [tiles] (action keys) and [widgets] — on the page
     * whose entries name it first; pins no page names stay on Start, as
     * before there were pages ([StartLayout.merge]). Info and usage card
     * only ever stand on Start. Every widget page of [saved] is in the map,
     * an empty one with no blocks.
     */
    fun merge(saved: List<String>, tiles: List<String>, widgets: List<StartEntry.Widget>): Map<String?, List<StartEntry>> =
        assign(saved, tiles, widgets, target = null)

    /**
     * The start list after pins changed (pinned, unpinned, another width):
     * every page keeps its blocks in their order; a block no page names yet
     * (pinned just now) joins [target] (a widget page, null: the start tab)
     * where [StartLayout.merge] puts new blocks; gone blocks drop out.
     * Markers, function pages and unknown pages stay as they are.
     */
    fun syncPins(saved: List<String>, tiles: List<String>, widgets: List<StartEntry.Widget>, target: String?): List<String> {
        val split = split(saved)
        val blocks = assign(saved, tiles, widgets, target)
        return join(
            Split(
                start = StartLayout.encodeAll(blocks[null].orEmpty()),
                pages = split.pages.map { page ->
                    if (isWidgets(page.id)) page.copy(entries = StartLayout.encodeAll(blocks[page.id].orEmpty())) else page
                },
            ),
        )
    }

    private fun assign(saved: List<String>, tiles: List<String>, widgets: List<StartEntry.Widget>, target: String?): Map<String?, List<StartEntry>> {
        val split = split(saved)
        val widgetPages = split.pages.filter { isWidgets(it.id) }
        // Which page names a block first (start before the pages, in their order).
        val home = HashMap<String, String?>()
        (listOf<Page?>(null) + widgetPages).forEach { page ->
            val entries = page?.entries ?: split.start
            for (raw in entries) {
                val key = StartLayout.decode(raw)?.key ?: continue
                if (key !in home) home[key] = page?.id
            }
        }
        val newHome = target?.takeIf { t -> widgetPages.any { it.id == t } }
        fun pageOf(key: String): String? = if (key in home) home[key] else newHome
        val result = LinkedHashMap<String?, List<StartEntry>>()
        result[null] = StartLayout.merge(
            split.start,
            tiles.filter { pageOf(StartLayout.TILE + it) == null },
            widgets.filter { pageOf(it.key) == null },
        )
        for (page in widgetPages) {
            result[page.id] = StartLayout.merge(
                page.entries,
                tiles.filter { pageOf(StartLayout.TILE + it) == page.id },
                widgets.filter { pageOf(it.key) == page.id },
                cards = false,
            )
        }
        return result
    }

    /**
     * Saving where the server keeps the order: [local] plus another
     * device's entries ([TabLayout.keepForeign]) — but never a marker this
     * device took away (unpinning would come back from the server's copy).
     */
    fun keepForeign(local: List<String>, remote: List<String>): List<String> =
        TabLayout.keepForeign(local, remote, StartLayout::identity, keep = { !isMarker(it) })
}
