package com.gatecontrol.android.ui.drive

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.gatecontrol.android.drive.DAlbum
import com.gatecontrol.android.drive.DArea
import com.gatecontrol.android.drive.DFolder
import com.gatecontrol.android.drive.DLink
import com.gatecontrol.android.drive.DMe
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DOpCheck
import com.gatecontrol.android.drive.DOpResults
import com.gatecontrol.android.drive.DOpBlocked
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveBulk
import com.gatecontrol.android.drive.DriveLive
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.DriveOps
import com.gatecontrol.android.drive.DriveTransfers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * The selection of a Drive screen and what it does with several items at once (Drive 1.12): send, move or copy to a
 * chosen folder (with the one question for names taken there), to the trash, save on the phone, a ZIP, one link for
 * all, into an album. The jobs run on the server ([DriveOps]); without Drive 1.12 a move and a delete run here, one
 * call per item. Held by the screen's view model ([scope]); [items] are the nodes on screen, [here] the folder shown
 * (null for the search and the photos), [changed] reloads after a job.
 */
internal class DriveBulkActions(
    private val context: Context,
    private val scope: CoroutineScope,
    private val api: DriveApi,
    val ops: DriveOps,
    private val transfers: DriveTransfers,
    private val live: DriveLive,
    private val items: () -> List<DNode>,
    private val here: () -> DFolder? = { null },
    private val changed: () -> Unit = {},
    /** A vault's own pages: moves stay within it ([vaultId]), nothing leaves it. */
    private val vaultId: String? = null,
    /** The names of a vault's nodes (decrypted here); else their own names. */
    private val nameOf: (DNode) -> String = { it.name },
    /** The vault's own folder (the target never goes above it). */
    private val vaultRoot: () -> Long? = { null },
) {
    private val me: DMe? get() = live.me.value

    // ─── the selection ──────────────────────────────────────────────

    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()

    /** The ticked nodes, in the order of the screen. */
    fun nodes(): List<DNode> {
        val s = _selected.value
        if (s.isEmpty()) return emptyList()
        return items().filter { it.id in s }.distinctBy { it.id }
    }

    fun toggle(node: DNode) = _selected.update { DriveBulk.toggle(it, node.id) }

    /** A long press: the selection starts with [node] (another long press while selecting ticks too). */
    fun select(node: DNode) = _selected.update { it + node.id }

    /** "Alle" / "Keine". */
    fun allOrNone() {
        val ids = items().map { it.id }
        _selected.update { DriveBulk.allOrNone(it, ids) }
    }

    fun set(ids: Set<Long>) { _selected.value = ids }

    fun clear() { _selected.value = emptySet() }

    /** After a reload: what is gone is no longer ticked. */
    fun keepPresent() = _selected.update { DriveBulk.keep(it, items().map { n -> n.id }) }

    fun can(action: DriveBulk.Action, nodes: List<DNode>, where: DriveBulk.Where): DriveBulk.Can = DriveBulk.can(action, nodes, where, me)

    /** A short message (a Drive code or `bulk_*`); the screen shows and clears it. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() { _message.value = null }

    // ─── the snackbar after a job ───────────────────────────────────

    /**
     * After a job: [kind] (`move`, `copy`, `trash`, `undo`), [count] items, the target's name; [failed] could not go,
     * [skipped] were there already; [undoId] when "Rückgängig" works.
     */
    data class Snack(
        val kind: String,
        val count: Int,
        val target: String? = null,
        val failed: Int = 0,
        val skipped: Int = 0,
        val undoId: String? = null,
        val cancelled: Boolean = false,
        val stamp: Long = System.nanoTime(),
    )

    private val _snack = MutableStateFlow<Snack?>(null)
    val snack: StateFlow<Snack?> = _snack.asStateFlow()

    fun snackShown() { _snack.value = null }

    /**
     * Waits for the job [id] to end, then the snackbar: how many ([count], the server's `items` when it says), where to;
     * the new items at the target are marked "neu hier" ([moved]: a move keeps the ids).
     */
    private fun follow(id: String, kind: String, count: Int, target: String?, moved: List<Long>) {
        scope.launch {
            val job = ops.await(id)
            val op = job.op
            val failed = op.results?.failedCount ?: 0
            val skipped = op.results?.skipped ?: 0
            if (kind == "move" || kind == "copy") ops.markFresh(op.results?.resultIds?.takeIf { it.isNotEmpty() } ?: if (kind == "move") moved else emptyList())
            val undo = if (!job.local && DriveBulk.canUndo(op, System.currentTimeMillis())) op.id else null
            val n = op.items.takeIf { it > 0 } ?: count
            _snack.value = Snack(kind, n, target ?: op.targetName, failed, skipped, undo, cancelled = op.state == "cancelled")
            if (op.state == "failed") _message.value = op.error?.let { "bulk_error:$it" } ?: "bulk_failed"
            ops.forget(id)
            changed()
        }
    }

    /** "Rückgängig" of the snackbar. */
    fun undo(id: String) {
        scope.launch {
            when (val r = ops.undo(id)) {
                is DResult.Ok -> {
                    val done = ops.await(r.value.id)
                    _snack.value = Snack("undo", done.op.results?.done ?: done.op.done, skipped = done.op.results?.skipped ?: 0, failed = done.op.results?.failedCount ?: 0)
                    ops.forget(r.value.id)
                    changed()
                }
                else -> _message.value = r.errorCode()
            }
        }
    }

    // ─── move and copy: the target ──────────────────────────────────

    /**
     * Choosing the target (full screen): [mode] move or copy, the items and where they are from ([from]); the areas as
     * chips, the folder shown with its path; the folders used last.
     */
    data class Target(
        val mode: DriveBulk.Mode,
        val nodes: List<DNode>,
        val from: String?,
        val areas: List<DArea>,
        val folder: DriveLoad<DFolder>?,
        val recent: List<DriveBulk.Recent>,
        /** Copy works (Drive 1.12 on GateControl 1.215, `copyServer`); else the switch says why not ([copyWhy]). */
        val copyOk: Boolean,
        val busy: Boolean = false,
        val copyWhy: DriveBulk.Why? = null,
        /** Within a vault: its own folder (no path above it, names decrypted). */
        val vaultRoot: Long? = null,
    ) {
        val ready: DFolder? get() = (folder as? DriveLoad.Ready)?.value
        val selectedIds: Set<Long> get() = nodes.mapTo(HashSet()) { it.id }
        /** Why the folder shown cannot take the job; null: it can. */
        val block: DriveBulk.Block?
            get() = ready?.let { DriveBulk.targetBlock(it, selectedIds, nodes.mapTo(HashSet()) { n -> n.parentId }, mode, vaultIdOf(nodes)) }
    }

    private val _target = MutableStateFlow<Target?>(null)
    val target: StateFlow<Target?> = _target.asStateFlow()

    private val prefs = context.getSharedPreferences("drive_ui", Context.MODE_PRIVATE)

    private fun recent(): List<DriveBulk.Recent> = DriveBulk.decodeRecent(prefs.getString(KEY_RECENT, null))

    private fun remember(r: DriveBulk.Recent) {
        if (vaultId != null) return
        prefs.edit().putString(KEY_RECENT, DriveBulk.encodeRecent(DriveBulk.pushRecent(recent(), r))).apply()
    }

    /** Opens the target for the ticked items (or [only] one of them, the menu of a row). */
    fun openTarget(mode: DriveBulk.Mode, only: List<DNode>? = null) {
        val nodes = only ?: nodes()
        if (nodes.isEmpty()) return
        val copyCan = DriveBulk.can(DriveBulk.Action.COPY, nodes, DriveBulk.Where(writable = { true }), me)
        val copyOk = copyCan.ok
        val start = here()
        _target.value = Target(
            mode = if (mode == DriveBulk.Mode.COPY && !copyOk) DriveBulk.Mode.MOVE else mode,
            nodes = nodes,
            from = start?.let { folderName(it) },
            areas = emptyList(),
            folder = DriveLoad.Loading,
            recent = if (vaultId == null) recent() else emptyList(),
            copyOk = copyOk,
            copyWhy = copyCan.why,
            vaultRoot = if (vaultId != null) vaultRoot() else null,
        )
        scope.launch {
            val areas = if (vaultId == null) DriveLogic.sortAreas(api.areas().valueOrNull().orEmpty().filter { !it.archived }) else emptyList()
            _target.update { it?.copy(areas = areas) }
            val first = start?.node?.id ?: nodes.first().parentId ?: areas.firstOrNull { it.writable }?.rootId
            if (first != null) browse(first) else _target.update { it?.copy(folder = null) }
        }
    }

    fun setMode(mode: DriveBulk.Mode) = _target.update { t -> if (t == null || (mode == DriveBulk.Mode.COPY && !t.copyOk)) t else t.copy(mode = mode) }

    private var browseJob: Job? = null

    fun browse(folderId: Long) {
        if (_target.value == null) return
        _target.update { it?.copy(folder = DriveLoad.Loading) }
        browseJob?.cancel()
        browseJob = scope.launch {
            val r = api.folder(folderId).toLoad { it }
            _target.update { it?.copy(folder = r) }
        }
    }

    fun closeTarget() {
        browseJob?.cancel()
        _target.value = null
    }

    /** "Neuer Ordner hier": made in the folder shown, then shown itself. */
    suspend fun newFolderHere(name: String): DResult<DNode> {
        val f = _target.value?.ready ?: return DResult.Failed("invalid", 0)
        val r = if (vaultId != null) newVaultFolder?.invoke(f.node.id, name) ?: DResult.Failed("invalid", 0) else api.createFolder(f.node.id, name.trim())
        if (r is DResult.Ok) browse(r.value.id)
        return r
    }

    /** A vault makes its folders with an encrypted name (set by the vault's page). */
    var newVaultFolder: (suspend (parentId: Long, name: String) -> DResult<DNode>)? = null

    /** The area and folders of [f] ("Meine Dateien › Dokumente › Rechnungen"). */
    fun pathOf(f: DFolder, areas: List<DArea>): String {
        // a vault: only its decrypted names (the server's are opaque)
        if (vaultId != null) return folderName(f)
        val area = areas.firstOrNull { it.id == f.node.areaId }?.label
        val names = f.crumbs.drop(1).map { it.name } + listOfNotNull(f.node.name.takeIf { f.node.parentId != null && it.isNotEmpty() })
        return (listOf(area ?: f.crumbs.firstOrNull()?.name ?: "…") + names).joinToString(" › ")
    }

    private fun folderName(f: DFolder): String =
        if (f.node.parentId == null) live.me.value?.areas?.firstOrNull { it.id == f.node.areaId }?.label ?: f.node.name else nameOf(f.node)

    // ─── the question about names taken at the target ───────────────

    /** "N Namen gibt es dort schon": the lines, the names with the same content, whether a new version is possible. */
    data class Ask(
        val mode: DriveBulk.Mode,
        val targetName: String,
        val rows: List<DriveBulk.ConflictRow>,
        val same: List<String>,
        val versionOk: Boolean,
        val answer: CompletableDeferred<String?>,
    ) {
        /** The names taken by another content (they ask). */
        val asking: Int get() = rows.count { !it.same }
    }

    private val _ask = MutableStateFlow<Ask?>(null)
    val ask: StateFlow<Ask?> = _ask.asStateFlow()

    /** The person's answer: `version`, `rename`, `skip`, or null (Abbrechen). */
    fun answer(choice: String?) {
        val a = _ask.value ?: return
        _ask.value = null
        a.answer.complete(choice)
    }

    /** "Hierher verschieben / kopieren": checks the target, asks about taken names, then the job runs. */
    fun confirmTarget() {
        val t = _target.value ?: return
        val f = t.ready ?: return
        if (t.block != null || t.busy) return
        _target.value = t.copy(busy = true)
        scope.launch {
            try {
                val label = folderName(f)
                val path = pathOf(f, t.areas)
                if (me?.has(DMe.FEATURE_BULK) == true) runBulk(t, f, label, path) else runLocalMove(t, f, label, path)
            } finally {
                _target.update { it?.copy(busy = false) }
            }
        }
    }

    private suspend fun runBulk(t: Target, f: DFolder, label: String, path: String) {
        val ids = t.nodes.map { it.id }
        val check = when (val r = api.opCheck(t.mode.op, ids, f.node.id)) {
            is DResult.Ok -> r.value
            else -> {
                _message.value = r.errorCode()
                return
            }
        }
        if (check.tooMany) {
            _message.value = "too_large"
            return
        }
        if (DriveBulk.quotaShort(check)) {
            _message.value = "quota"
            return
        }
        val allowed = DriveBulk.allowed(ids, check)
        if (allowed.isEmpty()) {
            _message.value = "bulk_blocked:" + (check.blocked.firstOrNull()?.reason ?: "forbidden")
            return
        }
        var onConflict: String? = null
        if (DriveBulk.needsQuestion(check)) {
            onConflict = askAbout(t.mode, label, check, t.nodes, versionOk = true) ?: return
        }
        val nodes = t.nodes.filter { it.id in allowed }
        when (val r = ops.start(t.mode.op, allowed, f.node.id, onConflict, nodes.firstOrNull()?.let(nameOf), label)) {
            is DResult.Ok -> {
                remember(DriveBulk.Recent(f.node.id, path))
                closeTarget()
                clear()
                if (check.blocked.isNotEmpty()) _message.value = "bulk_some_blocked:" + check.blocked.size
                follow(r.value.id, t.mode.op, nodes.size, label, allowed)
            }
            else -> _message.value = r.errorCode()
        }
    }

    private suspend fun askAbout(mode: DriveBulk.Mode, label: String, check: DOpCheck, nodes: List<DNode>, versionOk: Boolean): String? {
        val a = Ask(mode, label, DriveBulk.conflictRows(check, nodes.associateBy { it.id }), DriveBulk.sameNames(check), versionOk, CompletableDeferred())
        _ask.value = a
        return a.answer.await()
    }

    /**
     * Without Drive 1.12 (only a move): the target's names are looked at here, the question asked once, then each item
     * goes by itself ([DriveBulk.fallbackPlan]); a job card shows how far it got.
     */
    private suspend fun runLocalMove(t: Target, f: DFolder, label: String, path: String) {
        if (t.mode != DriveBulk.Mode.MOVE) return
        val there = api.folder(f.node.id).valueOrNull()?.items ?: f.items
        val check = DriveBulk.localCheck(t.nodes, there)
        var onConflict = DriveBulk.ON_RENAME
        if (DriveBulk.needsQuestion(check)) onConflict = askAbout(t.mode, label, check, t.nodes, versionOk = false) ?: return
        // a vault's names are opaque on the server: never renamed here (a taken one stays where it is)
        val plan = DriveBulk.fallbackPlan(t.nodes, check, there, if (vaultId != null) DriveBulk.ON_SKIP else onConflict)
        remember(DriveBulk.Recent(f.node.id, path))
        closeTarget()
        clear()
        val id = ops.startLocal("move", t.nodes.size, t.nodes.firstOrNull()?.let(nameOf), label)
        scope.launch {
            var done = 0
            var skipped = 0
            val failed = ArrayList<DOpBlocked>()
            val moved = ArrayList<Long>()
            for ((i, n) in t.nodes.withIndex()) {
                if (!ops.progressLocal(id, i)) break
                val step = plan[n.id]
                if (step == DriveBulk.ON_SKIP) {
                    skipped++
                    continue
                }
                val r = if (step != null) api.renameAndMove(n.id, step, f.node.id) else api.move(n.id, f.node.id)
                if (r is DResult.Ok) {
                    done++
                    moved += n.id
                } else {
                    failed += DOpBlocked(n.id, r.errorCode() ?: "failed")
                }
            }
            ops.finishLocal(id, DOpResults(done, skipped, failed))
            follow(id, "move", done, label, moved)
        }
    }

    // ─── the trash ──────────────────────────────────────────────────

    private val _confirmDelete = MutableStateFlow<List<DNode>?>(null)
    /** Without Drive 1.12 there is no "Rückgängig": the app asks first. */
    val confirmDelete: StateFlow<List<DNode>?> = _confirmDelete.asStateFlow()

    /** "Löschen" of the bar: with Drive 1.12 at once (and "Rückgängig"), else after a question. */
    fun delete(only: List<DNode>? = null) {
        val nodes = only ?: nodes()
        if (nodes.isEmpty()) return
        if (me?.has(DMe.FEATURE_BULK) == true) {
            scope.launch {
                when (val r = ops.start("trash", nodes.map { it.id }, null, null, nameOf(nodes.first()), null)) {
                    is DResult.Ok -> {
                        clear()
                        follow(r.value.id, "trash", nodes.size, null, emptyList())
                    }
                    else -> _message.value = r.errorCode()
                }
            }
        } else {
            _confirmDelete.value = nodes
        }
    }

    fun cancelDelete() { _confirmDelete.value = null }

    /** The question said yes: each item into the trash by itself. */
    fun deleteConfirmed() {
        val nodes = _confirmDelete.value ?: return
        _confirmDelete.value = null
        clear()
        val id = ops.startLocal("trash", nodes.size, nameOf(nodes.first()), null)
        scope.launch {
            var done = 0
            val failed = ArrayList<DOpBlocked>()
            for ((i, n) in nodes.withIndex()) {
                if (!ops.progressLocal(id, i)) break
                val r = api.delete(n.id)
                if (r is DResult.Ok) done++ else failed += DOpBlocked(n.id, r.errorCode() ?: "failed")
            }
            ops.finishLocal(id, DOpResults(done, 0, failed))
            follow(id, "trash", done, null, emptyList())
        }
    }

    // ─── send, save, ZIP ────────────────────────────────────────────

    /** Something is being fetched for sending or saving: what ([kind] `send`, `save`, `zip`), how far. */
    data class Busy(val kind: String, val done: Int, val total: Int)

    private val _busy = MutableStateFlow<Busy?>(null)
    val busy: StateFlow<Busy?> = _busy.asStateFlow()
    private var busyJob: Job? = null

    fun cancelBusy() {
        busyJob?.cancel()
        _busy.value = null
    }

    /** "Senden": the files to another app; with folders, everything as one ZIP. */
    fun send(only: List<DNode>? = null) {
        val nodes = only ?: nodes()
        if (nodes.isEmpty() || _busy.value != null) return
        if (nodes.any { it.isDir }) return sendZip(nodes)
        busyJob = scope.launch {
            try {
                val files = fetchAll(nodes, "send") ?: return@launch
                share(files.map { it.first to it.second })
                clear()
            } finally {
                _busy.value = null
            }
        }
    }

    /** "Als ZIP senden": everything in one file. */
    fun sendZip(only: List<DNode>? = null) {
        val nodes = only ?: nodes()
        if (nodes.isEmpty() || _busy.value != null) return
        busyJob = scope.launch {
            try {
                _busy.value = Busy("zip", 0, 1)
                val file = zipFile(nodes) ?: return@launch
                share(listOf(null to file))
                clear()
            } finally {
                _busy.value = null
            }
        }
    }

    /** "Auf dem Handy speichern": files one by one into Downloads, folders as one ZIP. */
    fun save(only: List<DNode>? = null) {
        val nodes = only ?: nodes()
        if (nodes.isEmpty() || _busy.value != null) return
        busyJob = scope.launch {
            try {
                var saved = 0
                val files = nodes.filter { !it.isDir }
                val dirs = nodes.filter { it.isDir }
                if (files.isNotEmpty()) {
                    val got = fetchAll(files, "save") ?: return@launch
                    for ((n, f) in got) if (toDownloads(f, f.name, mimeOf(n, f))) saved++
                }
                // each folder as a ZIP of its own (the server zips one folder at a time)
                for (d in dirs) {
                    _busy.value = Busy("zip", 0, 1)
                    val zip = zipFile(listOf(d)) ?: return@launch
                    if (toDownloads(zip, zip.name, "application/zip")) saved++
                }
                _message.value = if (saved > 0) "bulk_saved:$saved" else "bulk_save_failed"
                if (saved > 0) clear()
            } finally {
                _busy.value = null
            }
        }
    }

    /** The files of [nodes] in the cache, one after the other; null when one failed (the message says why). */
    private suspend fun fetchAll(nodes: List<DNode>, kind: String): List<Pair<DNode, File>>? {
        val out = ArrayList<Pair<DNode, File>>()
        for ((i, n) in nodes.withIndex()) {
            _busy.value = Busy(kind, i, nodes.size)
            when (val r = fetch(n)) {
                is DResult.Ok -> out += n to r.value
                else -> {
                    _message.value = r.errorCode()
                    return null
                }
            }
        }
        return out
    }

    /** How a file is fetched ([DriveTransfers.fetch]; a vault decrypts it). */
    var fetch: suspend (DNode) -> DResult<File> = { transfers.fetch(it) }

    /** The server's ZIP of [nodes] in the cache; null when it failed (the message says why). */
    private suspend fun zipFile(nodes: List<DNode>): File? {
        val z = when (val r = api.zip(nodes.map { it.id })) {
            is DResult.Ok -> r.value
            else -> {
                // one folder, or 1…20 files: anything else the server refuses with its reason
                _message.value = when (if (r is DResult.Failed) com.gatecontrol.android.drive.DriveJson.reason(r.body) else null) {
                    "mixed" -> "zip_mixed"
                    "too_many" -> "too_many"
                    else -> r.errorCode()
                }
                return null
            }
        }
        val body = when (val r = api.zipStream(z)) {
            is DResult.Ok -> r.value.body()
            else -> {
                _message.value = r.errorCode()
                return null
            }
        } ?: return null
        return withContext(Dispatchers.IO) {
            val dir = File(File(context.cacheDir, "drive-open"), "zip-" + System.currentTimeMillis()).apply { mkdirs() }
            val target = File(dir, z.name)
            try {
                body.use { b ->
                    val total = b.contentLength()
                    b.byteStream().use { inp ->
                        target.outputStream().use { out ->
                            val buf = ByteArray(256 * 1024)
                            var done = 0L
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) _busy.value = Busy("zip", (done * 100 / total).toInt(), 100)
                            }
                        }
                    }
                }
                target
            } catch (e: CancellationException) {
                dir.deleteRecursively()
                throw e
            } catch (e: IOException) {
                dir.deleteRecursively()
                _message.value = "offline"
                null
            }
        }
    }

    private fun uriOf(file: File) = FileProvider.getUriForFile(context, context.packageName + ".provider", file)

    private fun mimeOf(node: DNode?, file: File): String =
        node?.mime?.takeIf { it.contains('/') && it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"

    /** One or several files to another app (share sheet). */
    private fun share(files: List<Pair<DNode?, File>>) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { uriOf(it.second) })
        val types = files.map { mimeOf(it.first, it.second) }.distinct()
        val type = types.singleOrNull() ?: types.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType(type).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** A copy of [file] in the phone's Downloads ("Download/GateControl"); a taken name gets a number there. */
    private suspend fun toDownloads(file: File, name: String, mime: String): Boolean = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GateControl")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return@withContext false
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 256 * 1024) } } ?: error("no stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            true
        } catch (e: CancellationException) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        } catch (e: Exception) {
            Timber.d("Drive: save failed (%s)", e.javaClass.simpleName)
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    // ─── one link for all ───────────────────────────────────────────

    /** "Link teilen" for several files (feature `multiLink`): one link with an expiry and a PIN. */
    data class MultiLink(val nodes: List<DNode>, val created: DLink? = null, val error: String? = null, val busy: Boolean = false)

    private val _link = MutableStateFlow<MultiLink?>(null)
    val link: StateFlow<MultiLink?> = _link.asStateFlow()

    fun openLink(only: List<DNode>? = null) {
        val nodes = only ?: nodes()
        if (nodes.isNotEmpty()) _link.value = MultiLink(nodes)
    }

    fun closeLink() { _link.value = null }

    fun createLink(mode: String, expires: String, pin: String?) {
        val l = _link.value ?: return
        if (l.busy) return
        _link.value = l.copy(busy = true, error = null)
        scope.launch {
            val ids = l.nodes.map { it.id }
            val r = if (ids.size == 1) api.createLink(ids.first(), mode, expires, pin) else api.createMultiLink(ids, mode, expires, pin)
            _link.update { cur ->
                cur?.copy(
                    busy = false,
                    created = r.valueOrNull(),
                    // folders mixed in, or too many: Drive takes 2…20 files
                    error = when (if (r is DResult.Failed) com.gatecontrol.android.drive.DriveJson.reason(r.body) else null) {
                        "mixed" -> "link_mixed"
                        "too_many" -> "too_many"
                        else -> r.errorCode()
                    },
                )
            }
        }
    }

    // ─── into an album ──────────────────────────────────────────────

    private val _albums = MutableStateFlow<DriveLoad<List<DAlbum>>?>(null)
    /** Choosing the album (null: closed). */
    val albums: StateFlow<DriveLoad<List<DAlbum>>?> = _albums.asStateFlow()
    private var albumNodes: List<DNode> = emptyList()

    fun openAlbums(only: List<DNode>? = null) {
        albumNodes = only ?: nodes()
        if (albumNodes.isEmpty()) return
        _albums.value = DriveLoad.Loading
        scope.launch { _albums.value = api.albums().toLoad { l -> l.filter { it.canEdit }.sortedByDescending { it.updatedAt.orEmpty() } } }
    }

    fun closeAlbums() { _albums.value = null }

    fun addToAlbum(album: DAlbum) {
        val ids = albumNodes.map { it.id }
        _albums.value = null
        scope.launch {
            val r = api.addToAlbum(album.id, ids)
            _message.value = if (r is DResult.Ok) "photos_added:${ids.size}" else r.errorCode()
            if (r is DResult.Ok) clear()
        }
    }

    /** A new album with the items. */
    fun newAlbum(name: String, areaId: String?) {
        val ids = albumNodes.map { it.id }
        _albums.value = null
        scope.launch {
            when (val a = api.createAlbum(name.trim().take(DrivePhotosViewModel.NAME_MAX), areaId)) {
                is DResult.Ok -> {
                    val r = api.addToAlbum(a.value.id, ids)
                    _message.value = if (r is DResult.Ok) "photos_added:${ids.size}" else r.errorCode()
                    if (r is DResult.Ok) clear()
                }
                else -> _message.value = a.errorCode()
            }
        }
    }

    fun albumAreas(): List<DArea> = DriveLogic.sortAreas(me?.areas.orEmpty().filter { it.writable })

    private companion object {
        const val KEY_RECENT = "recent_targets"

        /** The vault the nodes are of (moves stay inside it); null outside a vault. */
        fun vaultIdOf(nodes: List<DNode>): String? = nodes.firstNotNullOfOrNull { it.vaultId }
    }
}
