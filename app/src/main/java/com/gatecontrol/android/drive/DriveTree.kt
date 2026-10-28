package com.gatecontrol.android.drive

/**
 * The folder tree of the Drive menu (Drive 1.11, pure and unit-tested): each area with its folders, opened one level at
 * a time. A folder's subfolders are loaded only when it is opened ([DTreeDir.hasDirs] tells whether there are any; null
 * while unknown — an older server without `vaultsApart` — so the arrow shows until its folders are loaded). The opened
 * folders are kept on the device ([encode] / [decode]).
 */
internal object DriveTree {

    /** One row of the menu's tree. [depth] 0 is the area itself (its top folder [id]). */
    data class Row(
        val id: Long,
        val name: String,
        val depth: Int,
        /** The area the folder lies in. */
        val areaId: String,
        /** Set on the area's own row (depth 0). */
        val area: DArea?,
        /** The arrow: it has (or may have) subfolders. */
        val expandable: Boolean,
        val expanded: Boolean,
        /** Opened, its folders on the way. */
        val loading: Boolean,
    )

    /**
     * The rows of [roots] (the areas, in their order): an opened folder is followed by its loaded subfolders, one level
     * deeper. A folder opened but not loaded yet shows as [Row.loading] (its id is in [needs]).
     */
    fun flatten(roots: List<DArea>, expanded: Set<Long>, children: Map<Long, List<DTreeDir>>): List<Row> {
        val out = ArrayList<Row>()
        fun walk(areaId: String, dirs: List<DTreeDir>, depth: Int, seen: Set<Long>) {
            for (d in dirs) {
                if (d.id in seen) continue // never in a loop, whatever the server answers
                val kids = children[d.id]
                val expandable = when {
                    kids != null -> kids.isNotEmpty()
                    else -> d.hasDirs != false
                }
                val open = expandable && d.id in expanded
                out += Row(d.id, d.name, depth, areaId, null, expandable, open, loading = open && kids == null)
                if (open && kids != null) walk(areaId, kids, depth + 1, seen + d.id)
            }
        }
        for (a in roots) {
            val kids = children[a.rootId]
            val expandable = kids == null || kids.isNotEmpty()
            val open = expandable && a.rootId in expanded
            out += Row(a.rootId, a.label, 0, a.id, a, expandable, open, loading = open && kids == null)
            if (open && kids != null) walk(a.id, kids, 1, setOf(a.rootId))
        }
        return out
    }

    /** The opened folders whose subfolders are not loaded yet (shown as loading). */
    fun needs(rows: List<Row>): List<Long> = rows.filter { it.loading }.map { it.id }

    /** [id] opened or closed. */
    fun toggle(expanded: Set<Long>, id: Long): Set<Long> = if (id in expanded) expanded - id else expanded + id

    /** The persisted form of the opened folders (a string set of ids). */
    fun encode(expanded: Set<Long>): Set<String> = expanded.take(MAX_KEPT).map { it.toString() }.toSet()

    fun decode(raw: Set<String>?): Set<Long> = raw.orEmpty().mapNotNull { it.toLongOrNull()?.takeIf { id -> id > 0 } }.toSet()

    /**
     * The folders of a `children` answer for the tree: folders only, never a vault folder (Drive 1.11 leaves them out
     * itself with `vaultsApart`; an older Drive lists them), sorted by name.
     */
    fun dirsOf(items: List<DNode>, hasDirs: Map<Long, Boolean> = emptyMap()): List<DTreeDir> =
        items.filter { it.isDir && !it.encrypted }
            .map { DTreeDir(it.id, it.name, hasDirs[it.id]) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    /** The "Persönlich" group: Meine Dateien. */
    fun personal(areas: List<DArea>): List<DArea> = areas.filter { it.kind == "mine" }

    /** The "Gemeinsam" group: the family, the children's folders, the team folders. */
    fun shared(areas: List<DArea>): List<DArea> = areas.filter { it.kind != "mine" }

    private const val MAX_KEPT = 500
}

/** A folder of the menu's tree: [hasDirs] whether it has subfolders (null: not known before they are loaded). */
data class DTreeDir(val id: Long, val name: String, val hasDirs: Boolean?)
