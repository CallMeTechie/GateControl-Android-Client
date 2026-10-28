package com.gatecontrol.android.drive.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.drive.DMe
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveJson
import com.gatecontrol.android.drive.DriveLive
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.DrivePreviews
import com.gatecontrol.android.drive.DriveSource
import com.gatecontrol.android.drive.DriveTakenAt
import com.gatecontrol.android.drive.backup.DriveFolderBackupLogic.Action
import com.gatecontrol.android.drive.backup.DriveFolderBackupLogic.Base
import com.gatecontrol.android.drive.backup.DriveFolderBackupLogic.Local
import com.gatecontrol.android.drive.backup.DriveFolderBackupLogic.Pending
import com.gatecontrol.android.drive.backup.DriveFolderBackupLogic.State
import com.gatecontrol.android.familie.PluginDirectory
import com.gatecontrol.android.support.CrashReports
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Ordner sichern" (Drive 1.7 app side, like "Nur sichern" of the Windows app): folders of the phone the person picked
 * (WhatsApp's media, a download folder, documents) go one way into a Drive folder each. New and changed files go up
 * (a changed one as a new version), sub folders are made alike. A file deleted on the phone stays in Drive for
 * [DriveFolderBackupLogic.DELETE_DAYS] days, then goes into Drive's trash — Drive keeps the time itself
 * (`DELETE nodes/<id>?after=`, feature `deleteAfter`); a file that comes back calls it off (`PATCH { deleteAt: null }`).
 * Without `deleteAfter` nothing in Drive is deleted. Many deletes at once wait for the person (the guard of the Windows
 * app); a folder that cannot be read counts as nothing deleted. Drive's ransomware protection (`423 device_locked`)
 * stops the run until the stop is lifted. What changes in Drive is never written back to the phone.
 *
 * Stored: the folder list and the settings in the preferences `drive_folders`, the state of each folder (the last
 * agreed files with size, time, node and SHA-256, the marks) in `files/drive-folders/<id>.json`. A lost or broken
 * state starts the folder fresh: everything is compared by content (`uploads/check`), nothing is deleted.
 */
@Singleton
class DriveFolderBackup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: DriveApi,
    private val setup: SetupRepository,
    private val previews: DrivePreviews,
    private val live: DriveLive,
    private val dates: DriveTakenAt,
) {
    /** The run in progress: folder [folder], [done] of [total] files, [current] the one going up. */
    data class Status(val running: Boolean = false, val folder: String? = null, val done: Int = 0, val total: Int = 0, val current: String? = null)

    enum class Outcome { DONE, RETRY, STOPPED }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val runLock = Mutex()
    private val random = SecureRandom()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)

    private val _folders = MutableStateFlow(readFolders())
    val folders: StateFlow<List<DriveBackupFolder>> = _folders.asStateFlow()
    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<DriveFolderSettings> = _settings.asStateFlow()
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** The person's answer to a mass delete, per folder id: true "Löschen übertragen", false "In Drive behalten". */
    private val answers = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    // ─── settings ───────────────────────────────────────────────────

    private fun owner() = PluginDirectory.ownerKey(setup.getServerUrl(), setup.getApiToken())

    private fun mine() = prefs.getString(KEY_OWNER, null) == owner()

    private fun readSettings(): DriveFolderSettings = if (!mine()) {
        DriveFolderSettings()
    } else {
        DriveFolderSettings(wifiOnly = prefs.getBoolean(KEY_WIFI, true), chargingOnly = prefs.getBoolean(KEY_CHARGING, false))
    }

    private fun readFolders(): List<DriveBackupFolder> {
        if (!mine()) return emptyList()
        val raw = prefs.getString(KEY_FOLDERS, null) ?: return emptyList()
        return runCatching { JsonParser.parseString(raw).asJsonArray.mapNotNull { folderOf(it as? JsonObject) } }.getOrDefault(emptyList())
    }

    private fun write(folders: List<DriveBackupFolder>) {
        val arr = JsonArray().apply { folders.forEach { add(jsonOf(it)) } }
        prefs.edit {
            if (!mine()) {
                // another login: its folders are not this one's
                clear()
                putString(KEY_OWNER, owner())
            }
            putString(KEY_FOLDERS, arr.toString())
        }
        _folders.value = folders
    }

    private fun change(id: String, f: (DriveBackupFolder) -> DriveBackupFolder) = synchronized(this) {
        write(readFolders().map { if (it.id == id) f(it) else it })
    }

    fun setSettings(f: (DriveFolderSettings) -> DriveFolderSettings) {
        val next = f(_settings.value)
        prefs.edit {
            if (!mine()) {
                clear()
                putString(KEY_OWNER, owner())
            }
            putBoolean(KEY_WIFI, next.wifiOnly)
            putBoolean(KEY_CHARGING, next.chargingOnly)
        }
        _settings.value = next
        _folders.value = readFolders()
        DriveFolderBackupWork.apply(context, active(), next)
    }

    /** Whether any folder is backed up (not paused). */
    fun active(): Boolean = _folders.value.any { !it.paused }

    /** The tree URIs the app holds (the ones of the folders backed up). */
    private fun held(): Set<String> = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()

    /**
     * Adds the picked [tree] (its lasting read permission is taken here) with the Drive folder [targetId]; a pass
     * starts. Null when it was added, else a code: `overlap` (the folder or one around it is backed up already),
     * `no_access` (the permission could not be kept).
     */
    fun add(tree: Uri, name: String, targetId: Long, targetLabel: String): String? = synchronized(this) {
        val doc = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return "no_access"
        val others = readFolders().mapNotNull { f -> runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(f.treeUri)) }.getOrNull() }
        if (DriveFolderBackupLogic.overlaps(doc, others)) return "overlap"
        val kept = runCatching { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
        if (!kept) return "no_access"
        val id = ByteArray(8).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val folder = DriveBackupFolder(
            id = id,
            treeUri = tree.toString(),
            name = DriveLogic.safeName(name) ?: name.take(100),
            targetId = targetId,
            targetLabel = targetLabel,
            addedAt = System.currentTimeMillis(),
        )
        write(readFolders() + folder)
        DriveFolderBackupWork.apply(context, true, _settings.value)
        DriveFolderBackupWork.runNow(context)
        null
    }

    /**
     * Takes [id] out of the backup: its files stay in Drive, Drive's pending deletes of it are called off (best
     * effort, after a run in progress), the permission on the phone folder ends.
     */
    fun remove(id: String) {
        val folder = _folders.value.firstOrNull { it.id == id } ?: return
        synchronized(this) { write(readFolders().filter { it.id != id }) }
        DriveFolderBackupWork.apply(context, active(), _settings.value)
        scope.launch {
            runLock.withLock {
                unmarkAll(readState(id))
                stateFile(id).delete()
            }
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(folder.treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    fun setPaused(id: String, paused: Boolean) {
        change(id) { it.copy(paused = paused) }
        DriveFolderBackupWork.apply(context, active(), _settings.value)
        if (!paused) DriveFolderBackupWork.runNow(context)
    }

    /**
     * Another Drive folder for [id]: the state starts fresh there (Drive's pending deletes of the old place are called
     * off; what was backed up stays where it is).
     */
    fun retarget(id: String, targetId: Long, targetLabel: String) {
        change(id) { it.copy(targetId = targetId, targetLabel = targetLabel, confirm = null, error = null) }
        scope.launch {
            runLock.withLock {
                unmarkAll(readState(id))
                stateFile(id).delete()
                change(id) { it.copy(files = 0, pendingDeletes = 0, skipped = 0) }
            }
            DriveFolderBackupWork.runNow(context)
        }
    }

    /** The answer to "many files deleted at once": [transfer] the deletes (Drive keeps them 30 days) or keep them in Drive. */
    fun answerDeletes(id: String, transfer: Boolean) {
        answers[id] = transfer
        change(id) { it.copy(confirm = null) }
        DriveFolderBackupWork.runNow(context)
    }

    // ─── run ────────────────────────────────────────────────────────

    /** One pass over every folder that is not paused. */
    suspend fun run(onProgress: (Status) -> Unit = {}): Outcome = runLock.withLock {
        withContext(Dispatchers.IO) {
            try {
                var outcome = Outcome.DONE
                _folders.value = readFolders()
                for (f in _folders.value.filter { !it.paused }) {
                    currentCoroutineContext().ensureActive()
                    when (pass(f, onProgress)) {
                        Outcome.RETRY -> outcome = Outcome.RETRY
                        Outcome.STOPPED -> if (outcome == Outcome.DONE) outcome = Outcome.STOPPED
                        Outcome.DONE -> Unit
                    }
                }
                outcome
            } finally {
                _status.value = Status()
            }
        }
    }

    /** What stops a folder's run: a code for the person ([STOP]), or try again later ([RETRY]). */
    private class Halt(val code: String, val retry: Boolean) : Exception(code)

    private suspend fun pass(f: DriveBackupFolder, onProgress: (Status) -> Unit): Outcome {
        val tree = Uri.parse(f.treeUri)
        // Stopped by Drive's ransomware protection: nothing is tried until it is lifted (that starts a pass).
        if (live.locked.value) return stop(f, DriveJson.DEVICE_LOCKED)
        if (f.treeUri !in held()) return stop(f, "no_access")
        val me = live.current() ?: return Outcome.RETRY
        if (!me.licenceValid || me.seat == null) return stop(f, if (!me.licenceValid) "not_licensed" else "no_seat")
        when (val target = api.node(f.targetId)) {
            is DResult.Ok -> if (!target.value.isDir) return stop(f, "no_folder")
            is DResult.Failed -> return if (target.code == "not_found") stop(f, "no_folder") else after(f, target.code)
            DResult.Offline -> return Outcome.RETRY
            DResult.NotConfigured -> return stop(f, "not_configured")
        }
        val scan = try {
            scan(tree)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Halt) {
            return stop(f, e.code)
        } catch (e: Exception) {
            Timber.d("Drive folders: cannot read (%s)", e.javaClass.simpleName)
            return stop(f, "no_access")
        }
        var state = readState(f.id)
        val deleteAfter = me.has(DMe.FEATURE_DELETE_AFTER)
        var plan = DriveFolderBackupLogic.plan(state, scan.local, scan.unreadable, deleteAfter, System.currentTimeMillis())
        val answer = answers.remove(f.id)
        var confirm: Int? = null
        if (answer == false) {
            plan = DriveFolderBackupLogic.keepInDrive(plan)
        } else if (answer != true && DriveFolderBackupLogic.massDelete(plan.deletes, plan.files)) {
            Timber.i("Drive folders: %d of %d files deleted on the phone at once — waiting for the person", plan.deletes, plan.files)
            confirm = plan.deletes
            plan = DriveFolderBackupLogic.withoutMarks(plan)
        }

        val uploads = plan.actions.count { it is Action.Upload }
        var done = 0
        var skipped = 0
        val ctx = Ctx(f, tree, scan.local, me)
        var since = 0
        try {
            for (a in plan.actions) {
                currentCoroutineContext().ensureActive()
                if (a is Action.Upload) {
                    _status.value = Status(true, f.name, done, uploads, DriveFolderBackupLogic.nameOf(a.path)).also(onProgress)
                }
                state = try {
                    act(ctx, state, a)
                } catch (e: Skip) {
                    skipped++
                    state
                }
                if (a is Action.Upload) done++
                if (++since >= SAVE_EVERY) {
                    writeState(f.id, state)
                    since = 0
                }
            }
        } catch (e: Halt) {
            writeState(f.id, state)
            record(f.id, state, skipped, confirm)
            if (e.code == DriveJson.DEVICE_LOCKED) live.stopped()
            return if (e.retry) Outcome.RETRY.also { Timber.i("Drive folders: will retry (%s)", e.code) } else stop(f, e.code)
        }
        writeState(f.id, state)
        record(f.id, state, skipped, confirm, finished = true)
        return Outcome.DONE
    }

    private class Ctx(val folder: DriveBackupFolder, val tree: Uri, val local: Map<String, Local>, val me: DMe)

    /** A file left out (counted, the run goes on). */
    private class Skip(code: String) : Exception(code)

    /** Does [a]; the state after it. Throws [Halt] to stop the run, [Skip] for a file left out. */
    private suspend fun act(ctx: Ctx, state: State, a: Action): State = when (a) {
        is Action.Forget -> DriveFolderBackupLogic.forget(state, a.keys, a.key)
        is Action.Unmark -> when (val r = api.unmark(a.nodeId)) {
            is DResult.Ok -> state.copy(pending = state.pending - a.key)
            // gone in Drive or not marked any more: nothing to call off
            is DResult.Failed -> if (r.code == "not_found" || r.code == "invalid") DriveFolderBackupLogic.forget(state, below(state, a.key), a.key) else fail(r)
            else -> fail(r)
        }
        is Action.Mkdir -> mkdir(ctx, state, a)
        is Action.Upload -> upload(ctx, state, a)
        is Action.Mark -> when (val r = api.deleteAfter(a.nodeId, DriveFolderBackupLogic.DELETE_SECONDS, ctx.me.deviceId)) {
            is DResult.Ok -> {
                val now = System.currentTimeMillis()
                val at = r.value.deleteAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                    ?: (now + DriveFolderBackupLogic.DELETE_SECONDS * 1000)
                state.copy(pending = state.pending + (a.key to Pending(a.nodeId, now, at)))
            }
            // gone in Drive already: nothing to keep
            is DResult.Failed -> if (r.code == "not_found") DriveFolderBackupLogic.forget(state, a.keys, a.key) else fail(r)
            else -> fail(r)
        }
    }

    private fun below(state: State, key: String) = state.base.keys.filter { DriveFolderBackupLogic.under(it, key) }

    /** The Drive folder of [path]'s parent: the target for the top, else the base's node. */
    private fun parentNode(ctx: Ctx, state: State, path: String): Long? {
        val parent = DriveFolderBackupLogic.parentOf(path)
        if (parent.isEmpty()) return ctx.folder.targetId
        return state.base[DriveFolderBackupLogic.keyOf(parent)]?.takeIf { it.dir }?.nodeId
    }

    private suspend fun mkdir(ctx: Ctx, state: State, a: Action.Mkdir): State {
        val parent = parentNode(ctx, state, a.path) ?: throw Skip("parent_missing")
        val name = DriveFolderBackupLogic.nameOf(a.path)
        val local = ctx.local[a.key]
        val node = when (val r = api.createFolder(parent, name, rename = false)) {
            is DResult.Ok -> r.value.id
            is DResult.Failed -> when (r.code) {
                // there already (an earlier run, or made by hand): taken over
                "exists", "conflict" -> api.folder(parent, limit = 1000).valueOrNull()?.items
                    ?.firstOrNull { it.isDir && DriveFolderBackupLogic.keyOf(it.name) == DriveFolderBackupLogic.keyOf(name) }?.id
                    ?: throw Skip("exists")
                "not_found" -> return lostParent(state, a.path)
                else -> fail(r)
            }
            else -> fail(r)
        }
        return state.copy(base = state.base + (a.key to Base(a.path, true, 0, local?.mtime ?: 0, node)), pending = state.pending - a.key)
    }

    /** The Drive folder above [path] is gone (deleted in Drive): the state lets it go, the next run makes it again. */
    private fun lostParent(state: State, path: String): State {
        val parent = DriveFolderBackupLogic.parentOf(path)
        if (parent.isEmpty()) throw Halt("no_folder", retry = false)
        val key = DriveFolderBackupLogic.keyOf(parent)
        return DriveFolderBackupLogic.forget(state, below(state, key), key)
    }

    private suspend fun upload(ctx: Ctx, state: State, a: Action.Upload): State {
        val l = ctx.local[a.key] ?: throw Skip("gone")
        val doc = l.doc ?: throw Skip("read")
        val parent = parentNode(ctx, state, a.path) ?: throw Skip("parent_missing")
        val name = DriveFolderBackupLogic.nameOf(a.path)
        ctx.me.maxBytes?.takeIf { it > 0 && l.size > it }?.let { throw Skip("too_large") }
        // still being written: the next run takes it
        if (System.currentTimeMillis() - l.mtime in 0 until SETTLE_MS) throw Skip("busy")
        val uri = DocumentsContract.buildDocumentUriUsingTree(ctx.tree, doc)
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val (sha, length) = try {
            hash(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw Skip("read")
        }
        val record = { nodeId: Long ->
            state.copy(base = state.base + (a.key to Base(a.path, false, l.size, l.mtime, nodeId, sha)), pending = state.pending - a.key)
        }
        if (length == 0L) {
            if (!ctx.me.has(DMe.FEATURE_EMPTY_FILES)) throw Skip("empty")
            return when (val r = api.commitEmpty(parent, name, mime, DriveLogic.ON_CONFLICT_VERSION)) {
                is DResult.Ok -> record(r.value.first.id)
                else -> failOrLost(r, state, a.path)
            }
        }
        when (val check = api.uploadCheck(parent, name, sha, length)) {
            is DResult.Ok -> check.value.node?.takeIf { check.value.same }?.let { return record(it.id) }
            else -> return failOrLost(check, state, a.path)
        }
        val media = api.upload(DriveSource.of(context, uri, length), mime)
        if (media !is DResult.Ok) return failOrLost(media, state, a.path)
        previews.uploadOwn(media.value, uri, mime)
        val takenAt = if (ctx.me.has(DMe.FEATURE_TAKEN_AT)) dates.of(uri, mime, name) else null
        // a changed file — or one of that name in the folder already — becomes a new version: nothing is lost
        return when (val r = api.commit(media.value, parent, name, l.mtime.takeIf { it > 0 }, DriveLogic.ON_CONFLICT_VERSION, takenAt = takenAt)) {
            is DResult.Ok -> record(r.value.first.id)
            else -> failOrLost(r, state, a.path)
        }
    }

    private fun failOrLost(r: DResult<*>, state: State, path: String): State =
        if (r is DResult.Failed && r.code == "not_found") lostParent(state, path) else fail(r)

    /** A failed call: stop or retry the run, or leave the file out (as the photo backup does). */
    private fun fail(r: DResult<*>): Nothing {
        val code = when (r) {
            is DResult.Failed -> r.code
            DResult.Offline -> "offline"
            DResult.NotConfigured -> "not_configured"
            is DResult.Ok -> "invalid"
        }
        when (DriveBackupLogic.next(code)) {
            DriveBackupLogic.Next.RETRY -> throw Halt(code, retry = true)
            DriveBackupLogic.Next.STOP -> throw Halt(code, retry = false)
            DriveBackupLogic.Next.SKIP, DriveBackupLogic.Next.TRY -> throw Skip(code)
        }
    }

    /** A failure before the run: retry or stop. */
    private fun after(f: DriveBackupFolder, code: String): Outcome =
        if (DriveBackupLogic.next(code) == DriveBackupLogic.Next.RETRY) Outcome.RETRY else stop(f, code)

    private fun stop(f: DriveBackupFolder, code: String): Outcome {
        Timber.i("Drive folders: stopped (%s)", code)
        change(f.id) { it.copy(error = code) }
        return Outcome.STOPPED
    }

    private fun record(id: String, state: State, skipped: Int, confirm: Int?, finished: Boolean = false) {
        change(id) {
            it.copy(
                files = state.base.values.count { b -> !b.dir },
                pendingDeletes = state.pending.size,
                skipped = skipped,
                confirm = confirm,
                lastRunAt = if (finished) System.currentTimeMillis() else it.lastRunAt,
                error = if (finished) null else it.error,
            )
        }
    }

    /** Calls off Drive's pending deletes of a state (a folder taken out of the backup deletes nothing). */
    private suspend fun unmarkAll(state: State) {
        for (p in state.pending.values.take(5_000)) {
            if (api.unmark(p.nodeId) is DResult.Offline) return
        }
    }

    // ─── the phone folder ───────────────────────────────────────────

    private class Scan(val local: Map<String, Local>, val unreadable: Set<String>)

    /** Everything below [tree] (left out: see [DriveFolderBackupLogic.excluded]); a folder that cannot be listed is noted. */
    private fun scan(tree: Uri): Scan {
        val root = DocumentsContract.getTreeDocumentId(tree)
        val resolver = context.contentResolver
        // The folder itself must answer: a removed SD card or a lost permission must not look like "all deleted".
        val there = resolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, root), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { it.moveToFirst() } == true
        if (!there) throw Halt("no_access", retry = false)
        val local = LinkedHashMap<String, Local>()
        val unreadable = HashSet<String>()
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val stack = ArrayDeque<Triple<String, String, Int>>()
        stack.addLast(Triple(root, "", 0))
        while (stack.isNotEmpty()) {
            val (doc, path, depth) = stack.removeLast()
            val listed = try {
                resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, doc), cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val dir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                        if (DriveFolderBackupLogic.excluded(name, dir) != null) continue
                        val rel = DriveFolderBackupLogic.join(path, name)
                        val key = DriveFolderBackupLogic.keyOf(rel)
                        if (key in local) continue
                        if (local.size >= MAX_ENTRIES) throw Halt("too_many", retry = false)
                        local[key] = Local(rel, dir, if (dir || c.isNull(3)) 0L else c.getLong(3), if (c.isNull(4)) 0L else c.getLong(4), id)
                        if (dir && depth < MAX_DEPTH) stack.addLast(Triple(id, rel, depth + 1))
                    }
                    true
                } ?: false
            } catch (e: Halt) {
                throw e
            } catch (e: Exception) {
                Timber.d("Drive folders: a folder could not be listed (%s)", e.javaClass.simpleName)
                false
            }
            if (!listed) {
                if (path.isEmpty()) throw Halt("no_access", retry = false)
                unreadable += DriveFolderBackupLogic.keyOf(path)
            }
        }
        return Scan(local, unreadable)
    }

    private fun hash(uri: Uri): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        var length = 0L
        val input = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
        input.use {
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                length += n
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } to length
    }

    // ─── stored state ───────────────────────────────────────────────

    private fun stateFile(id: String) = File(File(context.filesDir, "drive-folders"), "$id.json")

    private fun readState(id: String): State = runCatching {
        val file = stateFile(id)
        if (!file.isFile) return State()
        val o = JsonParser.parseString(file.readText()).asJsonObject
        val base = HashMap<String, Base>()
        o.getAsJsonArray("base")?.forEach { e ->
            val b = e as? JsonObject ?: return@forEach
            val path = b.get("p")?.asString ?: return@forEach
            base[DriveFolderBackupLogic.keyOf(path)] = Base(
                path = path,
                dir = b.get("d")?.asBoolean ?: false,
                size = b.get("s")?.asLong ?: 0L,
                mtime = b.get("m")?.asLong ?: 0L,
                nodeId = b.get("n")?.asLong ?: return@forEach,
                sha256 = b.get("h")?.asString,
            )
        }
        val pending = HashMap<String, Pending>()
        o.getAsJsonArray("pending")?.forEach { e ->
            val p = e as? JsonObject ?: return@forEach
            val key = p.get("k")?.asString ?: return@forEach
            pending[key] = Pending(p.get("n")?.asLong ?: return@forEach, p.get("a")?.asLong ?: 0L, p.get("t")?.asLong ?: 0L)
        }
        State(base, pending)
    }.getOrElse {
        Timber.w("Drive folders: state not read, starting fresh (%s)", it.javaClass.simpleName)
        State()
    }

    private fun writeState(id: String, state: State) {
        runCatching {
            val o = JsonObject()
            o.addProperty("v", 1)
            o.add(
                "base",
                JsonArray().apply {
                    state.base.values.forEach { b ->
                        add(
                            JsonObject().apply {
                                addProperty("p", b.path)
                                if (b.dir) addProperty("d", true)
                                addProperty("s", b.size)
                                addProperty("m", b.mtime)
                                addProperty("n", b.nodeId)
                                b.sha256?.let { addProperty("h", it) }
                            },
                        )
                    }
                },
            )
            o.add(
                "pending",
                JsonArray().apply {
                    state.pending.forEach { (k, p) ->
                        add(
                            JsonObject().apply {
                                addProperty("k", k)
                                addProperty("n", p.nodeId)
                                addProperty("a", p.at)
                                addProperty("t", p.deleteAt)
                            },
                        )
                    }
                },
            )
            val file = stateFile(id)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "$id.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Timber.w("Drive folders: state not written (%s)", it.javaClass.simpleName) }
    }

    // ─── the folder list as JSON ────────────────────────────────────

    private fun jsonOf(f: DriveBackupFolder) = JsonObject().apply {
        addProperty("id", f.id)
        addProperty("tree", f.treeUri)
        addProperty("name", f.name)
        addProperty("target", f.targetId)
        addProperty("targetLabel", f.targetLabel)
        addProperty("paused", f.paused)
        addProperty("addedAt", f.addedAt)
        addProperty("lastRunAt", f.lastRunAt)
        f.error?.let { addProperty("error", it) }
        addProperty("files", f.files)
        addProperty("pendingDeletes", f.pendingDeletes)
        f.confirm?.let { addProperty("confirm", it) }
        addProperty("skipped", f.skipped)
    }

    private fun folderOf(o: JsonObject?): DriveBackupFolder? {
        if (o == null) return null
        return runCatching {
            DriveBackupFolder(
                id = o.get("id").asString,
                treeUri = o.get("tree").asString,
                name = o.get("name")?.asString ?: "",
                targetId = o.get("target").asLong,
                targetLabel = o.get("targetLabel")?.asString ?: "",
                paused = o.get("paused")?.asBoolean ?: false,
                addedAt = o.get("addedAt")?.asLong ?: 0L,
                lastRunAt = o.get("lastRunAt")?.asLong ?: 0L,
                error = o.get("error")?.asString,
                files = o.get("files")?.asInt ?: 0,
                pendingDeletes = o.get("pendingDeletes")?.asInt ?: 0,
                confirm = o.get("confirm")?.asInt,
                skipped = o.get("skipped")?.asInt ?: 0,
            )
        }.getOrNull()
    }

    companion object {
        private const val PREFS = "drive_folders"
        private const val KEY_OWNER = "owner"
        private const val KEY_FOLDERS = "folders"
        private const val KEY_WIFI = "wifi"
        private const val KEY_CHARGING = "charging"
        /** The state is written every so many actions (and at the end): a killed run loses little. */
        private const val SAVE_EVERY = 50
        private const val MAX_ENTRIES = 200_000
        private const val MAX_DEPTH = 40
        /** Files changed in the last seconds are still being written. */
        private const val SETTLE_MS = 3_000L
    }
}
