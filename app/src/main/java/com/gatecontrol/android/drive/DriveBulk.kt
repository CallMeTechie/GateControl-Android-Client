package com.gatecontrol.android.drive

import java.util.Locale

/**
 * Pure rules of the selection and the actions on several items at once (Drive 1.12, unit-tested): what is ticked,
 * the summary line, which action is possible and why not, where a move or copy may go, the names taken at the target,
 * and the folders used last.
 */
internal object DriveBulk {

    // ─── the selection ──────────────────────────────────────────────

    /** [id] ticked or no longer. */
    fun toggle(selected: Set<Long>, id: Long): Set<Long> = if (id in selected) selected - id else selected + id

    /** Whether every one of [ids] is ticked (none: no). */
    fun allSelected(selected: Set<Long>, ids: Collection<Long>): Boolean = ids.isNotEmpty() && ids.all { it in selected }

    /** "Alle" / "Keine" of [ids] (a month of the photos, a folder): all ticked → none of them, else all of them. */
    fun allOrNone(selected: Set<Long>, ids: Collection<Long>): Set<Long> =
        if (allSelected(selected, ids)) selected - ids.toSet() else selected + ids

    /** After a reload: only what is still there stays ticked. */
    fun keep(selected: Set<Long>, present: Collection<Long>): Set<Long> {
        if (selected.isEmpty()) return selected
        val here = present.toHashSet()
        return selected.filterTo(LinkedHashSet()) { it in here }
    }

    /**
     * Swiping over photos: everything between [anchor] and [current] (indexes of [ids]) is ticked ([select]) or
     * unticked, on top of what was ticked when the swipe began ([base]). Going back undoes what is no longer covered.
     */
    fun dragRange(base: Set<Long>, ids: List<Long>, anchor: Int, current: Int, select: Boolean): Set<Long> {
        if (ids.isEmpty()) return base
        val a = anchor.coerceIn(0, ids.size - 1)
        val c = current.coerceIn(0, ids.size - 1)
        val range = ids.subList(minOf(a, c), maxOf(a, c) + 1)
        return if (select) base + range else base - range.toSet()
    }

    // ─── the summary ────────────────────────────────────────────────

    /** "2 Dateien, 1 Ordner · 1,6 MB + Ordnerinhalt": files, folders and the bytes of the files (folders not counted). */
    data class Summary(val files: Int, val folders: Int, val bytes: Long) {
        val count: Int get() = files + folders
    }

    fun summary(nodes: Collection<DNode>): Summary =
        Summary(files = nodes.count { !it.isDir }, folders = nodes.count { it.isDir }, bytes = nodes.filter { !it.isDir }.sumOf { it.size.coerceAtLeast(0) })

    // ─── which action, and why not ──────────────────────────────────

    enum class Action { SEND, MOVE, COPY, DELETE, SAVE, ZIP, LINK, RENAME, DETAILS, OFFICE, ALBUM }

    /** Why an action is greyed out (the UI says it in words). */
    enum class Why {
        /** Only the read right. */
        READ_ONLY,
        /** A child's folder looked into by a parent: only to look at. */
        CHILD,
        /** Needs Drive 1.12 (`bulk`). */
        NEEDS_BULK,
        /** A copy needs GateControl 1.215 (the server copies the bytes, `copyServer`). */
        NEEDS_COPY_SERVER,
        /** GateControl's public links are switched off: no ZIP, no link for several (`zip`, `multiLink` missing). */
        LINKS_OFF,
        /** A link for several takes files only. */
        LINK_FILES_ONLY,
        /** At most [MAX_FILES] files at once (a ZIP of files, one link). */
        TOO_MANY,
        /** A ZIP is of one folder, or of files only. */
        ZIP_SHAPE,
        /** Folders go only as a ZIP (and this Drive has none). */
        FOLDERS_NEED_ZIP,
        /** Only with one item ticked. */
        ONLY_ONE,
        /** Only for a file. */
        ONLY_FILES,
        /** Only when all are photos or videos. */
        NOT_MEDIA,
        /** Not in a vault (its content never leaves the phone undecrypted, nor goes into a link or a ZIP). */
        VAULT,
        /** Not an office document (or no office). */
        NO_OFFICE,
        /** Albums are off. */
        NO_ALBUMS,
    }

    data class Can(val ok: Boolean, val why: Why? = null) {
        companion object {
            val YES = Can(true)
            fun no(why: Why) = Can(false, why)
        }
    }

    /**
     * Where the items are: [writable] tells per item whether the person may change it (its folder's or area's role),
     * [child] whether it lies in a child's folder a parent looks into. [vault]: the items are of a vault (its own pages).
     */
    data class Where(
        val writable: (DNode) -> Boolean,
        val child: (DNode) -> Boolean = { false },
        val vault: Boolean = false,
    )

    private fun rights(nodes: Collection<DNode>, where: Where): Can {
        val bad = nodes.firstOrNull { !where.writable(it) } ?: return Can.YES
        return Can.no(if (where.child(bad)) Why.CHILD else Why.READ_ONLY)
    }

    private fun media(n: DNode): Boolean = !n.isDir && DriveLogic.kind(n).let { it == DriveLogic.Kind.IMAGE || it == DriveLogic.Kind.VIDEO }

    /** A ZIP of files takes at most this many, one link for several too (Drive 1.12). */
    const val MAX_FILES = 20

    /**
     * What the server zips: exactly one folder (with all in it), or 1…[MAX_FILES] files — any selection with
     * `zipMixed` ([mixed]).
     */
    fun zipShape(nodes: Collection<DNode>, mixed: Boolean = false): Why? = when {
        mixed || nodes.size == 1 -> null
        nodes.any { it.isDir } -> Why.ZIP_SHAPE
        nodes.size > MAX_FILES -> Why.TOO_MANY
        else -> null
    }

    /** Why there is no ZIP (and no link for several) at all: an older Drive, or the public links are off. */
    private fun zipOff(me: DMe?, feature: String): Why? = when {
        me?.has(DMe.FEATURE_BULK) != true -> Why.NEEDS_BULK
        !me.has(feature) -> Why.LINKS_OFF
        else -> null
    }

    /** Whether [action] works for [nodes] (none ticked: never). */
    fun can(action: Action, nodes: Collection<DNode>, where: Where, me: DMe?): Can {
        if (nodes.isEmpty()) return Can.no(Why.ONLY_ONE)
        val bulk = me?.has(DMe.FEATURE_BULK) == true
        val mixed = me?.has(DMe.FEATURE_ZIP_MIXED) == true
        val one = nodes.size == 1
        val folders = nodes.any { it.isDir }
        val vault = where.vault || nodes.any { it.encrypted }
        return when (action) {
            // folders go as the server's ZIP: one folder alone
            Action.SEND -> when {
                vault -> Can.no(Why.VAULT)
                !folders -> Can.YES
                zipOff(me, DMe.FEATURE_ZIP) == Why.NEEDS_BULK -> Can.no(Why.FOLDERS_NEED_ZIP)
                zipOff(me, DMe.FEATURE_ZIP) != null -> Can.no(zipOff(me, DMe.FEATURE_ZIP)!!)
                zipShape(nodes, mixed) != null -> Can.no(Why.ZIP_SHAPE)
                else -> Can.YES
            }
            Action.MOVE -> rights(nodes, where)
            // a copy needs the read right only (the target's write right is checked there); the server copies the bytes
            Action.COPY -> when {
                !bulk -> Can.no(Why.NEEDS_BULK)
                me?.has(DMe.FEATURE_COPY_SERVER) != true -> Can.no(Why.NEEDS_COPY_SERVER)
                else -> Can.YES
            }
            Action.DELETE -> rights(nodes, where)
            // files one by one, each folder as its own ZIP
            Action.SAVE -> when {
                vault -> Can.no(Why.VAULT)
                !folders -> Can.YES
                zipOff(me, DMe.FEATURE_ZIP) == Why.NEEDS_BULK -> Can.no(Why.FOLDERS_NEED_ZIP)
                zipOff(me, DMe.FEATURE_ZIP) != null -> Can.no(zipOff(me, DMe.FEATURE_ZIP)!!)
                else -> Can.YES
            }
            Action.ZIP -> when {
                vault -> Can.no(Why.VAULT)
                zipOff(me, DMe.FEATURE_ZIP) != null -> Can.no(zipOff(me, DMe.FEATURE_ZIP)!!)
                zipShape(nodes, mixed) != null -> Can.no(zipShape(nodes, mixed)!!)
                else -> Can.YES
            }
            Action.LINK -> when {
                vault -> Can.no(Why.VAULT)
                !rights(nodes, where).ok -> rights(nodes, where)
                one -> Can.YES
                zipOff(me, DMe.FEATURE_MULTI_LINK) != null -> Can.no(zipOff(me, DMe.FEATURE_MULTI_LINK)!!)
                folders && !mixed -> Can.no(Why.LINK_FILES_ONLY)
                nodes.size > MAX_FILES && !mixed -> Can.no(Why.TOO_MANY)
                else -> Can.YES
            }
            Action.RENAME -> when {
                !one -> Can.no(Why.ONLY_ONE)
                else -> rights(nodes, where)
            }
            Action.DETAILS -> when {
                !one -> Can.no(Why.ONLY_ONE)
                nodes.first().isDir -> Can.no(Why.ONLY_FILES)
                vault -> Can.no(Why.VAULT)
                else -> Can.YES
            }
            Action.OFFICE -> when {
                !one -> Can.no(Why.ONLY_ONE)
                !DriveLogic.officeAllowed(me, nodes.first()) -> Can.no(Why.NO_OFFICE)
                else -> Can.YES
            }
            Action.ALBUM -> when {
                me?.has(DMe.FEATURE_ALBUMS) != true -> Can.no(Why.NO_ALBUMS)
                vault -> Can.no(Why.VAULT)
                !nodes.all(::media) -> Can.no(Why.NOT_MEDIA)
                else -> Can.YES
            }
        }
    }

    // ─── where a move or a copy may go ──────────────────────────────

    enum class Mode(val op: String) { MOVE("move"), COPY("copy") }

    /** Why a folder cannot be the target. */
    enum class Block {
        /** A ticked folder itself, or a folder inside it. */
        INTO_ITSELF,
        /** Moving to where all of them are already. */
        SAME_FOLDER,
        READ_ONLY,
        /** Over the border of a vault (into one, out of one, into another one). */
        VAULT,
    }

    /**
     * A folder of the target list: a ticked folder is greyed out ("Wird verschoben – nicht in sich selbst"); null when
     * it may be opened.
     */
    fun rowBlock(dir: DNode, selected: Set<Long>): Block? = if (dir.id in selected) Block.INTO_ITSELF else null

    /** The folders of the target list: folders only, never a vault — unless choosing within that one vault ([vaultId]). */
    fun targetRows(items: List<DNode>, vaultId: String?): List<DNode> =
        items.filter { it.isDir && (if (vaultId == null) !it.encrypted else it.vaultId == vaultId) }

    /**
     * Whether [folder] can take the job: not into a ticked folder (or below one, [DFolder.crumbs]), the write right,
     * not over the border of a vault ([vaultId]: the vault the items are of), and a move not where all of them are.
     */
    fun targetBlock(folder: DFolder, selected: Set<Long>, sources: Set<Long?>, mode: Mode, vaultId: String? = null): Block? {
        val id = folder.node.id
        return when {
            id in selected || folder.crumbs.any { it.id in selected } -> Block.INTO_ITSELF
            vaultId == null && folder.node.encrypted -> Block.VAULT
            vaultId != null && folder.node.vaultId != vaultId -> Block.VAULT
            !folder.writable -> Block.READ_ONLY
            mode == Mode.MOVE && sources.isNotEmpty() && sources.all { it == id } -> Block.SAME_FOLDER
            else -> null
        }
    }

    /** The areas offered as targets: all of them; the ones without the write right greyed out ("nur ansehen"). */
    fun areaEnabled(area: DArea): Boolean = area.writable

    // ─── the folders used last (kept on the device) ─────────────────

    data class Recent(val id: Long, val label: String)

    const val RECENT_MAX = 4

    /** [r] first, each folder once, at most [RECENT_MAX]. */
    fun pushRecent(list: List<Recent>, r: Recent): List<Recent> = (listOf(r) + list.filter { it.id != r.id }).take(RECENT_MAX)

    /** One line per folder: `<id>\t<label>`. */
    fun encodeRecent(list: List<Recent>): String =
        list.joinToString("\n") { "${it.id}\t${it.label.replace('\n', ' ').replace('\t', ' ').take(300)}" }

    fun decodeRecent(s: String?): List<Recent> = s.orEmpty().lineSequence().mapNotNull { line ->
        val id = line.substringBefore('\t').toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        val label = line.substringAfter('\t', "").trim().ifEmpty { return@mapNotNull null }
        Recent(id, label)
    }.distinctBy { it.id }.take(RECENT_MAX).toList()

    // ─── names taken at the target ──────────────────────────────────

    /** The onConflict choices: a new version (the default), keep both ("… (2).pdf"), skip. */
    const val ON_VERSION = "version"
    const val ON_RENAME = "rename"
    const val ON_SKIP = "skip"

    /** One line of the question: what is there, what comes; [same] the content is the same already. */
    data class ConflictRow(
        val name: String,
        val thereSize: Long?,
        val thereTime: String?,
        val newSize: Long?,
        val newTime: String?,
        val same: Boolean,
    )

    /**
     * The lines of the question: what is there, what comes (the server's answer, else what is known of [nodes] by id),
     * a file inside a merged folder by its path; same content last. A folder on a folder is merged (no sizes).
     */
    fun conflictRows(check: DOpCheck, nodes: Map<Long, DNode>): List<ConflictRow> = check.conflicts.map { c ->
        val n = nodes[c.id]
        val dir = c.isDir || n?.isDir == true
        ConflictRow(
            name = c.path ?: c.name,
            thereSize = if (c.existingIsDir) null else c.existingSize,
            thereTime = c.existingMtime,
            newSize = if (dir) null else c.size ?: n?.size,
            newTime = c.mtime ?: n?.mtime ?: n?.updatedAt,
            same = c.same,
        )
    }.sortedBy { it.same }

    /** Whether to ask at all: only when a name is taken by another content (the same content is done anyway). */
    fun needsQuestion(check: DOpCheck): Boolean = check.conflicts.any { !it.same }

    /** The names whose content is there already ("wird in jedem Fall nur einmal behalten"). */
    fun sameNames(check: DOpCheck): List<String> = check.conflicts.filter { it.same }.map { it.name }

    /** What can go at all: the server's `items`, else the ids without the blocked ones. */
    fun allowed(ids: List<Long>, check: DOpCheck): List<Long> {
        val blocked = check.blocked.mapTo(HashSet()) { it.id }
        val items = check.items?.toHashSet()
        return ids.filter { it !in blocked && (items == null || it in items) }
    }

    /** Whether the target has too little space for the job (unknown: it may go). */
    fun quotaShort(check: DOpCheck): Boolean {
        val need = check.quotaNeeded ?: return false
        val free = check.quotaFree ?: return false
        return need > free
    }

    /**
     * Without Drive 1.12 the app looks itself: the names of [nodes] that are in the target's [items] already (case does
     * not count, as on the server); the same content when both have the same SHA-256 (and size).
     */
    fun localCheck(nodes: List<DNode>, items: List<DNode>): DOpCheck {
        val there = items.associateBy { it.name.lowercase(Locale.ROOT) }
        val conflicts = nodes.mapNotNull { n ->
            val e = there[n.name.lowercase(Locale.ROOT)] ?: return@mapNotNull null
            if (e.id == n.id) return@mapNotNull null
            val same = !n.isDir && !e.isDir && n.sha256 != null && n.sha256 == e.sha256 && n.size == e.size
            DOpConflict(n.id, n.name, e.id, if (e.isDir) null else e.size, e.mtime ?: e.updatedAt, same)
        }
        val s = summary(nodes)
        return DOpCheck(s.files, s.folders, s.bytes, conflicts, emptyList())
    }

    /**
     * Without Drive 1.12, what happens to each item of a move: `null` it goes as it is, a name it goes under that name
     * (keep both), [ON_SKIP] it stays (skipped, or the same content is there already; a folder of the same name cannot
     * be merged without Drive 1.12). A new version cannot be made by a move: it keeps both then.
     */
    fun fallbackPlan(nodes: List<DNode>, check: DOpCheck, items: List<DNode>, onConflict: String): Map<Long, String?> {
        val taken = items.map { it.name }.toMutableList()
        val byId = check.conflicts.associateBy { it.id }
        return nodes.associate { n ->
            val c = byId[n.id]
            n.id to when {
                c == null -> null
                c.same -> ON_SKIP
                n.isDir -> ON_SKIP
                onConflict == ON_SKIP -> ON_SKIP
                else -> DriveLogic.freeName(n.name, taken).also { taken += it }
            }
        }
    }

    /** How much of a job is done, 0..1 (by bytes when known, else by items). */
    fun progress(op: DOp): Float = when {
        op.bytesTotal > 0 -> (op.bytesDone.toFloat() / op.bytesTotal).coerceIn(0f, 1f)
        op.total > 0 -> (op.done.toFloat() / op.total).coerceIn(0f, 1f)
        else -> 0f
    }

    /** Whether "Rückgängig" still works for [op] at [nowMs] (a finished move, copy or trash; a stopped one too). */
    fun canUndo(op: DOp, nowMs: Long): Boolean {
        if (op.kind !in setOf("move", "copy", "trash") || (op.state != "done" && op.state != "cancelled")) return false
        val until = op.undoUntil ?: return false
        val at = runCatching { java.time.Instant.parse(until).toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(until).toInstant().toEpochMilli() }.getOrNull()
            ?: return false
        return at > nowMs
    }

    /** The last part of a path ("Meine Dateien › Dokumente › Rechnungen" → "Rechnungen"; "/a/b" → "b"). */
    fun lastPart(path: String?): String? = path?.split('›', '/')?.map { it.trim() }?.lastOrNull { it.isNotEmpty() }
}
