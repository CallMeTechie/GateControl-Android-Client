package com.gatecontrol.android.drive.backup

import com.gatecontrol.android.drive.DriveLogic
import java.text.Normalizer
import java.util.Locale

/**
 * A folder of the phone that is backed up ("Ordner sichern", mode "Nur sichern" as in the Windows app): its files go
 * one way, phone → Drive, into [targetId]. [treeUri] is the folder the person picked (the system's folder picker,
 * with a lasting read permission).
 */
data class DriveBackupFolder(
    val id: String,
    val treeUri: String,
    /** The phone folder's name ("WhatsApp Media"). */
    val name: String,
    val targetId: Long,
    /** "Meine Dateien › Handy › WhatsApp". */
    val targetLabel: String,
    val paused: Boolean = false,
    val addedAt: Long = 0L,
    val lastRunAt: Long = 0L,
    /** Why the last run stopped (a code), null when it went through. */
    val error: String? = null,
    /** Files in Drive (backed up). */
    val files: Int = 0,
    /** Files deleted on the phone that Drive keeps until their time ([DriveFolderBackupLogic.DELETE_DAYS]). */
    val pendingDeletes: Int = 0,
    /** Many files deleted on the phone at once: the run waits for the person (this many files). */
    val confirm: Int? = null,
    /** Files left out in the last run (too large, unreadable, a name Drive does not take). */
    val skipped: Int = 0,
)

/** When the folder backup runs (the same conditions as the photo backup). */
data class DriveFolderSettings(val wifiOnly: Boolean = true, val chargingOnly: Boolean = false)

/** Pure part of the folder backup (unit-tested): what is left out, the guard against mass deletes, the planner. */
internal object DriveFolderBackupLogic {

    /** "Nur sichern": a file deleted on the phone goes into Drive's trash after 30 days. */
    const val DELETE_DAYS = 30
    const val DELETE_SECONDS = DELETE_DAYS * 86_400L
    private const val MASS_DELETE_COUNT = 500
    private const val MASS_DELETE_SHARE = 0.2
    private const val MASS_DELETE_MIN = 10

    /** A file or folder on the phone now. [doc] is its document id in the picked tree. */
    data class Local(val path: String, val dir: Boolean, val size: Long, val mtime: Long, val doc: String? = null)

    /** What both sides had after the last run: the phone's size and time, the node in Drive. */
    data class Base(val path: String, val dir: Boolean, val size: Long, val mtime: Long, val nodeId: Long, val sha256: String? = null)

    /** Deleted on the phone, marked in Drive: into the trash at [deleteAt] (ms). */
    data class Pending(val nodeId: Long, val at: Long, val deleteAt: Long)

    /** A folder's state between runs. */
    data class State(val base: Map<String, Base> = emptyMap(), val pending: Map<String, Pending> = emptyMap())

    sealed class Action {
        abstract val key: String

        /** A folder new on the phone: made in Drive. */
        data class Mkdir(override val key: String, val path: String) : Action()

        /** A file new or changed on the phone ([changed]: a new version of the one in Drive). */
        data class Upload(override val key: String, val path: String, val changed: Boolean) : Action()

        /** Deleted on the phone: Drive moves it into the trash later (a folder with its [files] at once; [keys] its base). */
        data class Mark(override val key: String, val path: String, val nodeId: Long, val dir: Boolean, val files: Int, val keys: List<String>) : Action()

        /** Back on the phone, unchanged: Drive keeps it. */
        data class Unmark(override val key: String, val nodeId: Long) : Action()

        /** Gone (and its time in Drive over, or nothing to do there): the state lets [keys] go. */
        data class Forget(override val key: String, val keys: List<String>) : Action()
    }

    /** [deletes]: files that would be marked (the guard); [files]: the files of the base (the folder's size). */
    data class Plan(val actions: List<Action>, val deletes: Int, val files: Int)

    // ─── paths ──────────────────────────────────────────────────────

    /** "WhatsApp/Media/a.jpg" → the key (NFC, lower case): Android's shared storage and Drive both ignore case. */
    fun keyOf(path: String): String = Normalizer.normalize(path, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    fun parentOf(path: String): String = path.substringBeforeLast('/', "")

    fun nameOf(path: String): String = path.substringAfterLast('/')

    fun join(parent: String, name: String): String = if (parent.isEmpty()) name else "$parent/$name"

    /** Is [key] the folder [dir] itself or below it? */
    fun under(key: String, dir: String): Boolean = key == dir || key.startsWith("$dir/")

    private fun depth(key: String): Int = if (key.isEmpty()) 0 else key.count { it == '/' } + 1

    // ─── what is left out ───────────────────────────────────────────

    private val TEMP_NAMES = setOf("thumbs.db", "desktop.ini", ".ds_store", "ehthumbs.db")

    /**
     * Why a file or folder named [name] is left out, or null: hidden ones (".thumbnails", ".nomedia", Android's
     * ".trashed-…" / ".pending-…" files of the gallery's trash and of unfinished downloads, WhatsApp's ".Statuses"),
     * temporary files (`~$…`, `*.tmp`, `*.part`, `*.crdownload`) and names Drive does not take.
     */
    fun excluded(name: String, dir: Boolean): String? {
        val n = name.lowercase(Locale.ROOT)
        return when {
            n.isEmpty() || n.startsWith(".") -> "hidden"
            !dir && (n in TEMP_NAMES || n.startsWith("~$") || n.endsWith(".tmp") || n.endsWith(".part") || n.endsWith(".crdownload")) -> "temp"
            DriveLogic.safeName(name) != name -> "name"
            else -> null
        }
    }

    // ─── mass deletes ───────────────────────────────────────────────

    /** Deleting [count] of the folder's [total] files at once: ask first? (> 500, or > 20 % and at least 10) */
    fun massDelete(count: Int, total: Int): Boolean =
        count > MASS_DELETE_COUNT || (count >= MASS_DELETE_MIN && count > MASS_DELETE_SHARE * total)

    // ─── planning ───────────────────────────────────────────────────

    private fun same(l: Local, b: Base) = l.size == b.size && l.mtime == b.mtime

    /**
     * What to do so Drive has what the phone has.
     *
     * - New folders are made, new and changed files go up (a changed one as a new version).
     * - Deleted on the phone ([deleteAfter], Drive 1.5): marked — Drive moves it into the trash after
     *   [DELETE_DAYS] days; a folder deleted with all its content at once as one mark. Without `deleteAfter` nothing
     *   in Drive is deleted: the state lets it go.
     * - Back on the phone while marked: unchanged → the mark ends; changed → a new version (that ends it, too).
     * - A mark whose time is over ([now] after its `deleteAt`): Drive put it into the trash, the state lets it go.
     * - Below a folder that could not be read ([unreadable]) nothing counts as deleted.
     */
    fun plan(
        state: State,
        local: Map<String, Local>,
        unreadable: Set<String> = emptySet(),
        deleteAfter: Boolean,
        now: Long,
    ): Plan {
        val base = state.base
        val pending = state.pending
        val out = ArrayList<Action>()
        val covered = ArrayList<String>()
        var deletes = 0
        val files = base.values.count { !it.dir }
        val keys = (base.keys + local.keys + pending.keys).sortedWith(compareBy<String>({ depth(it) }, { it }))
        val hidden = { k: String -> unreadable.any { u -> k != u && under(k, u) } }
        val below = { k: String -> base.keys.filter { under(it, k) } }
        val expired = { p: Pending -> now >= p.deleteAt }

        for (k in keys) {
            if (covered.any { under(k, it) }) continue
            val l = local[k]
            val b = base[k]
            val p = pending[k]

            // A file became a folder (or the other way round): the old one is let go, the new one goes up.
            if (l != null && b != null && l.dir != b.dir) {
                out += Action.Forget(k, below(k))
                out += if (l.dir) Action.Mkdir(k, l.path) else Action.Upload(k, l.path, changed = false)
                continue
            }
            when {
                l != null && b == null -> out += if (l.dir) Action.Mkdir(k, l.path) else Action.Upload(k, l.path, changed = false)
                l != null && b != null -> when {
                    !l.dir && !same(l, b) -> out += Action.Upload(k, l.path, changed = true)
                    p != null -> out += Action.Unmark(k, p.nodeId)
                }
                l == null && b != null -> {
                    if (hidden(k)) continue
                    when {
                        p != null -> {
                            if (expired(p)) out += Action.Forget(k, below(k))
                            // marked whole: what is below goes with it
                            if (b.dir) covered += k
                        }
                        !deleteAfter -> {
                            out += Action.Forget(k, below(k))
                            if (b.dir) covered += k
                        }
                        b.dir -> {
                            val sub = below(k)
                            // a folder goes as one mark when nothing below it is still on the phone
                            if (local.keys.none { it != k && under(it, k) }) {
                                val n = sub.count { base[it]?.dir == false }
                                deletes += n
                                out += Action.Mark(k, b.path, b.nodeId, dir = true, files = n, keys = sub)
                                covered += k
                            }
                        }
                        else -> {
                            deletes += 1
                            out += Action.Mark(k, b.path, b.nodeId, dir = false, files = 1, keys = listOf(k))
                        }
                    }
                }
                // neither here nor in the base: a mark the state still had
                p != null -> out += Action.Forget(k, emptyList())
            }
        }
        val order = { a: Action ->
            when (a) {
                is Action.Forget, is Action.Unmark -> 0
                is Action.Mkdir -> 1
                is Action.Upload -> 2
                is Action.Mark -> 3
            }
        }
        val sorted = out.withIndex().sortedWith(compareBy({ order(it.value) }, { if (it.value is Action.Mkdir) depth(it.value.key) else 0 }, { it.index }))
        return Plan(sorted.map { it.value }, deletes, files)
    }

    /** "Keep in Drive" after a mass delete: the marks become a forget (nothing in Drive is deleted). */
    fun keepInDrive(plan: Plan): Plan =
        plan.copy(actions = plan.actions.map { if (it is Action.Mark) Action.Forget(it.key, it.keys) else it }, deletes = 0)

    /** Without the person's yes: the marks are left out of the run. */
    fun withoutMarks(plan: Plan): Plan = plan.copy(actions = plan.actions.filter { it !is Action.Mark }, deletes = 0)

    /** The state after [action] went through ([base] / [pending] entries it adds; for a Forget the keys go). */
    fun forget(state: State, keys: Collection<String>, key: String): State =
        State(state.base - keys.toSet() - key, state.pending - keys.toSet() - key)

    // ─── suggestions ────────────────────────────────────────────────

    /** Folders worth backing up, as paths of the shared storage (only a help when picking). */
    data class Suggestion(val id: String, val path: String)

    val SUGGESTIONS = listOf(
        Suggestion("whatsapp", "Android/media/com.whatsapp/WhatsApp/Media"),
        Suggestion("download", "Download"),
        Suggestion("documents", "Documents"),
    )

    /** Does [tree] (a document id like `primary:Documents/Rechnungen`) overlap one of [others]? */
    fun overlaps(tree: String, others: List<String>): Boolean {
        val a = keyOf(tree.trimEnd('/'))
        return others.map { keyOf(it.trimEnd('/')) }.any { b -> a == b || a.startsWith("$b/") || b.startsWith("$a/") || rootOf(a, b) }
    }

    /** `primary:` (a whole volume) and `primary:Documents` overlap too. */
    private fun rootOf(a: String, b: String): Boolean =
        (a.endsWith(":") && b.startsWith(a)) || (b.endsWith(":") && a.startsWith(b))
}
