package com.gatecontrol.android.drive

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.gatecontrol.android.support.CrashReports
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uploads and downloads of the Drive tab. Uploads run one after the other in the app's scope, so they go on while the
 * person browses elsewhere: a picked file is copied to the cache (its SHA-256 on the way), `uploads/check` skips a
 * file that is there already, a name that is taken waits for the person's choice (new version, keep both, skip),
 * then the file goes up (resumable) and `uploads/commit` makes it a file. Downloads land in
 * `cacheDir/drive-open/<node>-<rev>/<name>` for opening or sharing through the FileProvider (pruned after a day).
 */
@Singleton
class DriveTransfers @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: DriveApi,
    private val previews: DrivePreviews,
    private val local: DriveLocal,
) {
    enum class State { QUEUED, RUNNING, CONFLICT, DONE, SAME, SKIPPED, FAILED }

    data class Upload(
        val id: Long,
        val name: String,
        val folderId: Long,
        val total: Long,
        val sent: Long = 0,
        val state: State = State.QUEUED,
        val code: String? = null,
    )

    /**
     * One upload: a picked [uri] (copied first) or a [file] written through the Files app. [onExists] answers a taken
     * name without asking (`version` | `rename`); [onDone] hears the new file (null: failed or skipped).
     */
    private class Job(
        val id: Long,
        val uri: Uri?,
        val folderId: Long,
        var file: File? = null,
        var name: String = "",
        var mime: String? = null,
        var mtime: Long? = null,
        var sha: String? = null,
        val onExists: String? = null,
        val onDone: ((DNode?) -> Unit)? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)
    private val mutex = Mutex()
    private val ids = AtomicLong(0)
    private val queue = ArrayDeque<Job>()
    private val waiting = HashMap<Long, Job>()
    @Volatile private var running = false
    /** The person's answer to a taken name, by upload id. */
    private val conflictChoice = java.util.concurrent.ConcurrentHashMap<Long, String>()

    private val _uploads = MutableStateFlow<List<Upload>>(emptyList())
    val uploads: StateFlow<List<Upload>> = _uploads.asStateFlow()

    /** A folder whose content changed through an upload here (reload it). */
    private val _changed = MutableSharedFlow<Long>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changed: SharedFlow<Long> = _changed.asSharedFlow()

    private fun set(id: Long, f: (Upload) -> Upload) = _uploads.update { list -> list.map { if (it.id == id) f(it) else it } }

    /** Queues [uris] for the folder [folderId]. */
    fun upload(uris: List<Uri>, folderId: Long) {
        if (uris.isEmpty()) return
        scope.launch {
            mutex.withLock {
                for (uri in uris) {
                    // Now, while the picker's grant holds: "delete on this device" needs the file later.
                    local.hold(uri)
                    val id = ids.incrementAndGet()
                    val (name, size) = describe(uri)
                    queue.addLast(Job(id, uri, folderId, name = name))
                    _uploads.update { it + Upload(id, name, folderId, size) }
                }
            }
            pump()
        }
    }

    /**
     * A [file] written through the Files app (it is taken over and deleted afterwards): goes up as [name] into
     * [folderId]. The same content is skipped; a taken name becomes [onExists] (`version` | `rename`) without asking.
     */
    fun uploadWritten(file: File, name: String, mime: String?, folderId: Long, onExists: String, onDone: (DNode?) -> Unit = {}) {
        scope.launch {
            val sha = runCatching { sha256(file) }.getOrNull()
            mutex.withLock {
                val id = ids.incrementAndGet()
                queue.addLast(
                    Job(id, null, folderId, file = file, name = name, mime = mime, mtime = System.currentTimeMillis(), sha = sha, onExists = onExists, onDone = onDone),
                )
                _uploads.update { it + Upload(id, name, folderId, file.length()) }
            }
            pump()
        }
    }

    /** The person's answer to a taken name: `version`, `rename` (keep both) or null (skip). */
    fun resolve(id: Long, onConflict: String?) {
        scope.launch {
            val job = mutex.withLock { waiting.remove(id) } ?: return@launch
            if (onConflict == null) {
                job.file?.delete()
                set(id) { it.copy(state = State.SKIPPED) }
                return@launch
            }
            set(id) { it.copy(state = State.QUEUED) }
            conflictChoice[id] = onConflict
            mutex.withLock { queue.addFirst(job) }
            pump()
        }
    }

    /** Removes finished rows from the list. */
    fun clearFinished() = _uploads.update { list -> list.filter { it.state == State.QUEUED || it.state == State.RUNNING || it.state == State.CONFLICT } }

    private suspend fun pump() {
        if (mutex.withLock { if (running) true else { running = true; false } }) return
        try {
            while (true) {
                val job = mutex.withLock { queue.removeFirstOrNull() } ?: break
                runCatching { run(job) }.onFailure { e ->
                    if (e is CancellationException) throw e
                    Timber.d("Drive: upload failed (%s)", e.javaClass.simpleName)
                    job.file?.delete()
                    set(job.id) { it.copy(state = State.FAILED, code = "failed") }
                    job.onDone?.invoke(null)
                }
            }
        } finally {
            mutex.withLock { running = false }
        }
    }

    private suspend fun run(job: Job) {
        set(job.id) { it.copy(state = State.RUNNING) }
        // 1. copy (once; a conflict keeps the copy)
        var sha: String? = job.sha
        if (job.file == null) {
            val copied = copy(job) ?: run {
                set(job.id) { it.copy(state = State.FAILED, code = "read") }
                return
            }
            job.file = copied.first
            sha = copied.second
            job.sha = sha
            set(job.id) { it.copy(total = copied.first.length()) }
        }
        val file = job.file!!
        var choice = conflictChoice.remove(job.id)
        // 2. check, unless the person already chose
        if (choice == null) {
            when (val check = api.uploadCheck(job.folderId, job.name, sha, file.length())) {
                is DResult.Ok -> {
                    if (check.value.same) {
                        check.value.node?.let { n -> job.uri?.let { local.remember(n.id, sha, it) } }
                        file.delete()
                        set(job.id) { it.copy(state = State.SAME, sent = it.total) }
                        job.onDone?.invoke(check.value.node)
                        return
                    }
                    if (check.value.exists && job.onExists != null) {
                        choice = job.onExists
                    } else if (check.value.exists) {
                        mutex.withLock { waiting[job.id] = job }
                        set(job.id) { it.copy(state = State.CONFLICT) }
                        return
                    }
                }
                is DResult.Failed -> return fail(job, check.code)
                DResult.Offline -> return fail(job, "offline")
                DResult.NotConfigured -> return fail(job, "not_configured")
            }
        }
        // 3. upload and commit
        val media = api.upload(file, job.mime) { sent -> set(job.id) { it.copy(sent = sent) } }
        if (media !is DResult.Ok) return fail(job, (media as? DResult.Failed)?.code ?: "offline")
        // HEIC / video: the server cannot make a preview — send the phone's own before the commit (best effort)
        job.uri?.let { previews.uploadOwn(media.value, it, job.mime) }
        val onConflict = choice ?: DriveLogic.ON_CONFLICT_RENAME
        when (val r = api.commit(media.value, job.folderId, job.name, job.mtime, onConflict)) {
            is DResult.Ok -> {
                job.uri?.let { local.remember(r.value.first.id, sha, it) }
                file.delete()
                set(job.id) { it.copy(state = State.DONE, sent = it.total) }
                _changed.tryEmit(job.folderId)
                job.onDone?.invoke(r.value.first)
            }
            is DResult.Failed -> fail(job, r.code)
            else -> fail(job, "offline")
        }
    }

    private fun fail(job: Job, code: String) {
        job.file?.delete()
        set(job.id) { it.copy(state = State.FAILED, code = code) }
        job.onDone?.invoke(null)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { inp ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Display name and size of a picked file (0 when unknown). */
    private fun describe(uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        return (DriveLogic.safeName(name ?: uri.lastPathSegment) ?: "Datei") to size
    }

    /** The picked file in the cache and its SHA-256 (hex); null when it cannot be read. */
    private suspend fun copy(job: Job): Pair<File, String>? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "drive-up").apply { mkdirs() }
        val target = File(dir, "${job.id}.part")
        try {
            val uri = job.uri ?: return@withContext null
            job.mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            job.mtime = lastModified(uri)
            val digest = MessageDigest.getInstance("SHA-256")
            val input = context.contentResolver.openInputStream(uri) ?: return@withContext null
            input.use { inp ->
                target.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
            target to digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: CancellationException) {
            target.delete()
            throw e
        } catch (e: Exception) {
            Timber.d("Drive: cannot read picked file (%s)", e.javaClass.simpleName)
            target.delete()
            null
        }
    }

    private fun lastModified(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf("last_modified"), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0).takeIf { it > 0 } else null
        }
    }.getOrNull()

    // ─── downloads ──────────────────────────────────────────────────

    private val _downloading = MutableStateFlow<Map<Long, Float>>(emptyMap())
    /** Node id → progress 0..1 of the files being fetched for opening. */
    val downloading: StateFlow<Map<Long, Float>> = _downloading.asStateFlow()

    /**
     * The file [node] (or its [versionId]) in the cache, under its own name, ready for the FileProvider
     * (`cache-path drive_open`). Reuses a copy of the same revision. Failed(code) when it cannot be fetched.
     */
    suspend fun fetch(node: DNode, versionId: Long? = null): DResult<File> = withContext(Dispatchers.IO) {
        prune()
        val dir = File(File(context.cacheDir, "drive-open"), "${node.id}-${versionId?.let { "v$it" } ?: "r${node.rev}"}")
        val name = DriveLogic.safeName(node.name) ?: "file"
        val target = File(dir, name)
        if (target.isFile && (versionId != null || target.length() == node.size)) {
            target.setLastModified(System.currentTimeMillis())
            return@withContext DResult.Ok(target)
        }
        _downloading.update { it + (node.id to 0f) }
        try {
            when (val r = api.download(node.id, versionId)) {
                is DResult.Ok -> {
                    dir.mkdirs()
                    val part = File(dir, "$name.part")
                    val body = r.value.body() ?: return@withContext DResult.Failed(DriveApi.CODE_INVALID, 0)
                    body.use { b ->
                        val total = b.contentLength().takeIf { it > 0 } ?: node.size
                        b.byteStream().use { inp ->
                            part.outputStream().use { out ->
                                val buf = ByteArray(256 * 1024)
                                var done = 0L
                                while (true) {
                                    val n = inp.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    if (total > 0) _downloading.update { it + (node.id to (done.toFloat() / total).coerceIn(0f, 1f)) }
                                }
                            }
                        }
                    }
                    if (!part.renameTo(target)) {
                        part.delete()
                        return@withContext DResult.Failed("write", 0)
                    }
                    DResult.Ok(target)
                }
                is DResult.Failed -> r
                DResult.Offline -> DResult.Offline
                DResult.NotConfigured -> DResult.NotConfigured
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            DResult.Offline
        } finally {
            _downloading.update { it - node.id }
        }
    }

    /** Copies older than a day go. */
    private fun prune() {
        val root = File(context.cacheDir, "drive-open")
        val old = System.currentTimeMillis() - 24 * 3_600_000L
        root.listFiles()?.forEach { d -> if (d.lastModified() < old && (d.listFiles()?.all { it.lastModified() < old } != false)) d.deleteRecursively() }
    }
}
