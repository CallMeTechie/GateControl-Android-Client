package com.gatecontrol.android.drive

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gatecontrol.android.data.SetupRepository
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A copy of a file that is "offline verfügbar" on this phone (or one that is to come). [roots] are the marks it comes
 * from (the file itself, or marked folders above it). [file] is the name of the stored copy in its folder of the store
 * (null: none yet), [storedRev] / [storedSha256] what that copy is — an [State.OUTDATED] copy still opens until the new
 * one is there. [reason] says why a copy waits (`wifi`, `space`, `device_space`) or failed (a Drive code).
 */
data class DriveOfflineCopy(
    val id: Long,
    val name: String,
    val mime: String?,
    val size: Long,
    val rev: Int,
    val sha256: String?,
    val path: String?,
    val roots: Set<Long>,
    val state: State,
    val reason: String? = null,
    val file: String? = null,
    val storedRev: Int? = null,
    val storedSha256: String? = null,
    val storedAt: Long? = null,
) {
    enum class State {
        /** On this phone, the version Drive has. */
        STORED,

        /** Not on this phone yet: queued, or waiting ([reason] `wifi`, `space`, `device_space`). */
        PENDING,

        /** On this phone in an older version; the new one loads with the next run (the old one opens meanwhile). */
        OUTDATED,

        /** Could not be loaded ([reason]: the Drive code); tried again with the next run. */
        FAILED,
    }

    /** There is a copy on this phone (current or older). */
    val hasFile: Boolean get() = file != null

    /** As a node of Drive, for the rows and the apps that open it without a connection (no area, no media). */
    fun asNode(): DNode = DNode(
        id = id, areaId = "", parentId = null, name = name, isDir = false, size = size, mime = mime, mediaId = null,
        rev = storedRev ?: rev, mtime = null, updatedAt = null, updatedBy = null, path = path, sha256 = storedSha256 ?: sha256,
    )
}

/** What "offline verfügbar" asks for: a file of Drive under the marks [roots] (shown with its folder [path]). */
data class DriveOfflineWant(val node: DNode, val roots: Set<Long>, val path: String?)

/** Pure rules of the offline copies (unit-tested). */
object DriveOfflineStoreLogic {
    /** At most this many files are kept for the marks (a marked folder with very many files stops there). */
    const val MAX_FILES = 2_000

    /** At most this many folders are looked into below marked folders. */
    const val MAX_FOLDERS = 500

    /** This much has to stay free on the phone besides the copies. */
    const val DEVICE_RESERVE_BYTES = 500L * 1024 * 1024

    /** Waiting for WLAN, for space within the limit, for space on the phone. */
    const val WAIT_WIFI = "wifi"
    const val WAIT_SPACE = "space"
    const val WAIT_DEVICE_SPACE = "device_space"

    data class Plan(
        /** The copies after the run as far as known now (downloads still to come are PENDING / OUTDATED). */
        val copies: Map<Long, DriveOfflineCopy>,
        /** The ids to load, in this order. */
        val download: List<Long>,
        /** Copies whose files go (no longer marked, over the limit, blocked by the virus scan). */
        val remove: List<DriveOfflineCopy>,
    )

    /** The stored copy of [c] is the version of [node] (same revision and, where both are known, the same content). */
    fun current(c: DriveOfflineCopy, node: DNode): Boolean =
        c.file != null && c.storedRev == node.rev &&
            (node.sha256 == null || c.storedSha256 == null || node.sha256.equals(c.storedSha256, ignoreCase = true))

    /**
     * The run: [wanted] (newest marks first) against what this phone [have]s. The limit [limitBytes] is filled in the
     * order of [wanted]; what does not fit waits (`space`) and an older copy of it goes. A file blocked by the virus
     * scan is never kept. Vault files never come here (the caller leaves them out).
     */
    fun plan(wanted: List<DriveOfflineWant>, have: Map<Long, DriveOfflineCopy>, limitBytes: Long): Plan {
        val out = LinkedHashMap<Long, DriveOfflineCopy>()
        val download = ArrayList<Long>()
        val remove = ArrayList<DriveOfflineCopy>()
        var used = 0L
        for (w in wanted) {
            val n = w.node
            if (n.isDir || n.encrypted || out.containsKey(n.id)) continue
            val h = have[n.id]
            val base = DriveOfflineCopy(
                id = n.id, name = n.name, mime = n.mime, size = n.size, rev = n.rev, sha256 = n.sha256, path = w.path,
                roots = w.roots, state = DriveOfflineCopy.State.PENDING,
                file = h?.file, storedRev = h?.storedRev, storedSha256 = h?.storedSha256, storedAt = h?.storedAt,
            )
            if (n.blocked) {
                if (h?.file != null) remove += h
                out[n.id] = base.copy(state = DriveOfflineCopy.State.FAILED, reason = n.scan?.refusalCode ?: "scan_blocked", file = null, storedRev = null, storedSha256 = null, storedAt = null)
                continue
            }
            val size = n.size.coerceAtLeast(0)
            if (used + size > limitBytes) {
                if (h?.file != null) remove += h
                out[n.id] = base.copy(reason = WAIT_SPACE, file = null, storedRev = null, storedSha256 = null, storedAt = null)
                continue
            }
            used += size
            out[n.id] = when {
                h != null && current(h, n) -> base.copy(state = DriveOfflineCopy.State.STORED)
                h?.file != null -> base.copy(state = DriveOfflineCopy.State.OUTDATED).also { download += n.id }
                else -> base.also { download += n.id }
            }
        }
        for ((id, h) in have) if (!out.containsKey(id) && h.file != null) remove += h
        return Plan(out, download, remove)
    }

    /** The copies without the mark [root] (taken off): those only it held go (returned as the second part). */
    fun without(have: Map<Long, DriveOfflineCopy>, root: Long): Pair<Map<Long, DriveOfflineCopy>, List<DriveOfflineCopy>> {
        val keep = LinkedHashMap<Long, DriveOfflineCopy>()
        val gone = ArrayList<DriveOfflineCopy>()
        for ((id, c) in have) {
            if (root !in c.roots && id != root) {
                keep[id] = c
                continue
            }
            val left = c.roots - root
            if (left.isEmpty() || id == root && c.roots == setOf(root)) gone += c else keep[id] = c.copy(roots = left)
        }
        return keep to gone
    }

    /** May a copy load now: always, or with "nur im WLAN" only on an unmetered network. */
    fun mayLoad(wifiOnly: Boolean, unmetered: Boolean): Boolean = !wifiOnly || unmetered

    /** The phone has room for [size] more bytes and still keeps [DEVICE_RESERVE_BYTES] free. */
    fun deviceRoom(usable: Long, size: Long): Boolean = usable - size.coerceAtLeast(0) >= DEVICE_RESERVE_BYTES

    /** Bytes on the phone. */
    fun used(copies: Collection<DriveOfflineCopy>): Long = copies.filter { it.file != null }.sumOf { it.size.coerceAtLeast(0) }

    /** How a mark stands: [stored] of [total] files on this phone (a file mark: 0 or 1 of 1), the worst state of them. */
    data class MarkState(val total: Int, val stored: Int, val outdated: Int, val pending: Int, val failed: Int, val reason: String?)

    fun markState(root: Long, copies: Collection<DriveOfflineCopy>): MarkState? {
        val mine = copies.filter { root in it.roots || it.id == root }
        if (mine.isEmpty()) return null
        val waiting = mine.filter { it.state == DriveOfflineCopy.State.PENDING }
        return MarkState(
            total = mine.size,
            stored = mine.count { it.state == DriveOfflineCopy.State.STORED },
            outdated = mine.count { it.state == DriveOfflineCopy.State.OUTDATED },
            pending = waiting.size,
            failed = mine.count { it.state == DriveOfflineCopy.State.FAILED },
            reason = waiting.firstNotNullOfOrNull { it.reason } ?: mine.firstOrNull { it.state == DriveOfflineCopy.State.FAILED }?.reason,
        )
    }

    // --- the index on disk ----------------------------------------------------

    fun toJson(server: String, copies: Collection<DriveOfflineCopy>): String = JsonObject().apply {
        addProperty("v", 1)
        addProperty("server", server)
        add("copies", JsonArray().apply {
            copies.forEach { c ->
                add(JsonObject().apply {
                    addProperty("id", c.id)
                    addProperty("name", c.name)
                    c.mime?.let { addProperty("mime", it) }
                    addProperty("size", c.size)
                    addProperty("rev", c.rev)
                    c.sha256?.let { addProperty("sha256", it) }
                    c.path?.let { addProperty("path", it) }
                    add("roots", JsonArray().apply { c.roots.forEach { add(it) } })
                    addProperty("state", c.state.name)
                    c.reason?.let { addProperty("reason", it) }
                    c.file?.let { addProperty("file", it) }
                    c.storedRev?.let { addProperty("storedRev", it) }
                    c.storedSha256?.let { addProperty("storedSha256", it) }
                    c.storedAt?.let { addProperty("storedAt", it) }
                })
            }
        })
    }.toString()

    /** The index → (server, copies); null when it cannot be read (then the store starts empty). */
    fun fromJson(text: String): Pair<String, List<DriveOfflineCopy>>? = runCatching {
        val o = JsonParser.parseString(text).asJsonObject
        if (o.get("v")?.asInt != 1) return null
        val server = o.get("server")?.asString ?: return null
        val list = o.getAsJsonArray("copies")?.mapNotNull { e ->
            runCatching {
                val c = e.asJsonObject
                fun str(k: String) = c.get(k)?.takeIf { it.isJsonPrimitive }?.asString
                val file = str("file")?.takeIf { DriveLogic.safeName(it) == it }
                DriveOfflineCopy(
                    id = c.get("id").asLong,
                    name = str("name") ?: return@runCatching null,
                    mime = str("mime"),
                    size = c.get("size")?.asLong ?: 0L,
                    rev = c.get("rev")?.asInt ?: 0,
                    sha256 = str("sha256"),
                    path = str("path"),
                    roots = c.getAsJsonArray("roots")?.map { it.asLong }?.toSet().orEmpty(),
                    state = runCatching { DriveOfflineCopy.State.valueOf(str("state") ?: "") }.getOrDefault(DriveOfflineCopy.State.PENDING),
                    reason = str("reason"),
                    file = file,
                    storedRev = if (file == null) null else c.get("storedRev")?.asInt,
                    storedSha256 = if (file == null) null else str("storedSha256"),
                    storedAt = if (file == null) null else c.get("storedAt")?.asLong,
                )
            }.getOrNull()
        }.orEmpty()
        server to list
    }.getOrNull()
}

/**
 * "Offline verfügbar" on this phone: the files the person marked (and the files below marked folders) are kept as
 * copies in the app's own storage (`filesDir/drive-offline/<id>/<name>`, never backed up, gone with the app, never
 * shared by the FileProvider itself — [handOut] links the one file into the opened files' cache) and open, show and
 * share from there without a connection. A run ([sync], in the background through [DriveOfflineWork])
 * compares the marks with what is here: new files load (at home from the home gateway's cache, the same way as
 * opening a file — [DriveApi.download]), changed ones load again (the old copy opens until then), files no longer
 * marked, over the limit or blocked by the virus scan go. "Nur im WLAN" and the limit are Drive settings on this phone.
 *
 * Vault files are not kept here: their content is ciphertext the vault opens in memory only, the vault's pages do not
 * offer the mark, and the vault's "offline" is the home gateway's cache (ciphertext only, Drive's own rule).
 */
@Singleton
class DriveOfflineStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: DriveApi,
    private val live: DriveLive,
    private val settings: DriveSettings,
    private val network: DriveUploadNetwork,
    private val setup: SetupRepository,
) {
    enum class Outcome { DONE, RETRY }

    private val lock = Mutex()
    private val root: File get() = File(context.filesDir, DIR)
    private val indexFile: File get() = File(context.filesDir, INDEX)

    @Volatile private var server: String = ""
    private val _copies = MutableStateFlow<Map<Long, DriveOfflineCopy>>(emptyMap())
    /** The copies by node id (also those still to come). */
    val copies: StateFlow<Map<Long, DriveOfflineCopy>> = _copies.asStateFlow()

    private val _running = MutableStateFlow(false)
    /** A run is going on. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _progress = MutableStateFlow<Pair<Long, Float>?>(null)
    /** The file loading now with its progress 0..1. */
    val progress: StateFlow<Pair<Long, Float>?> = _progress.asStateFlow()

    init {
        runCatching {
            if (indexFile.isFile) {
                DriveOfflineStoreLogic.fromJson(indexFile.readText())?.let { (s, list) ->
                    server = s
                    _copies.value = list.associateBy { it.id }
                }
            }
        }.onFailure { Timber.w("Drive offline: index not read (%s)", it.javaClass.simpleName) }
    }

    /** Bytes of the copies on this phone. */
    fun usedBytes(): Long = DriveOfflineStoreLogic.used(_copies.value.values)

    private fun sameServer(): Boolean = server.isNotEmpty() && server == setup.getServerUrl()

    private fun fileOf(c: DriveOfflineCopy): File? =
        c.file?.let { File(File(root, c.id.toString()), it) }?.takeIf { it.isFile }

    /** The copy of [node] on this phone when it is the version Drive has (null: none, or an older one). */
    fun current(node: DNode): File? {
        if (node.isDir || node.encrypted || node.blocked || !sameServer()) return null
        val c = _copies.value[node.id] ?: return null
        return if (DriveOfflineStoreLogic.current(c, node)) fileOf(c) else null
    }

    /** The copy [id] on this phone, current or older (the offline list without a connection), ready to hand out. */
    fun stored(id: Long): File? {
        if (!sameServer()) return null
        val c = _copies.value[id] ?: return null
        if (c.state == DriveOfflineCopy.State.FAILED) return null
        val f = fileOf(c) ?: return null
        return handOut(f, id, "r${c.storedRev ?: c.rev}", DriveLogic.safeName(c.name) ?: f.name)
    }

    /**
     * [kept] (a file of the store, which the FileProvider never shares) as the file an app may open or get:
     * `cacheDir/drive-open/<id>-<tag>/<name>` — a hard link (no second copy on the phone), or a copy where linking does
     * not go. Null when neither works.
     */
    fun handOut(kept: File, id: Long, tag: String, name: String): File? = runCatching {
        val dir = File(File(context.cacheDir, "drive-open"), "$id-$tag")
        val target = File(dir, name)
        if (target.isFile && target.length() == kept.length()) {
            target.setLastModified(System.currentTimeMillis())
            return target
        }
        dir.mkdirs()
        target.delete()
        val linked = runCatching { android.system.Os.link(kept.path, target.path) }.isSuccess
        if (!linked) kept.copyTo(target, overwrite = true)
        target.setLastModified(System.currentTimeMillis())
        target
    }.onFailure { Timber.w("Drive offline: copy not handed out (%s)", it.javaClass.simpleName) }.getOrNull()

    /** A run soon (in the background); the regular run takes the settings over ("nur im WLAN"). */
    fun request() {
        DriveOfflineWork.now(context)
        keptUp = null
        keepUp(_copies.value.isNotEmpty())
    }

    @Volatile private var keptUp: Boolean? = null

    /** The regular run while copies are kept: scheduled (or stopped) when that changes. */
    private fun keepUp(on: Boolean) {
        if (keptUp == on) return
        keptUp = on
        DriveOfflineWork.keepUp(context, on)
    }

    /** [node] seen with another version than the copy here: the next run loads it again (soon). */
    fun noticeChanged(node: DNode) {
        val c = _copies.value[node.id] ?: return
        if (c.file == null || DriveOfflineStoreLogic.current(c, node)) return
        _copies.value = _copies.value + (node.id to c.copy(state = DriveOfflineCopy.State.OUTDATED, rev = node.rev, sha256 = node.sha256, size = node.size))
        DriveOfflineWork.now(context)
    }

    /** The mark [rootId] was taken off: the copies only it held go now (the next run checks the rest). */
    suspend fun unmarked(rootId: Long) = lock.withLock {
        val (keep, gone) = DriveOfflineStoreLogic.without(_copies.value, rootId)
        withContext(Dispatchers.IO) { gone.forEach(::deleteFiles) }
        put(keep)
    }

    /** One node deleted everywhere ("auf allen Geräten gelöscht"): its copy goes at once. */
    suspend fun drop(nodeId: Long) = lock.withLock {
        val c = _copies.value[nodeId] ?: return@withLock
        withContext(Dispatchers.IO) { deleteFiles(c) }
        put(_copies.value - nodeId)
    }

    /** Everything goes (another server, Drive gone, the marks no longer readable for this person). */
    suspend fun wipe() = lock.withLock { wipeLocked() }

    private suspend fun wipeLocked() {
        withContext(Dispatchers.IO) { runCatching { root.deleteRecursively() } }
        put(emptyMap())
    }

    private fun deleteFiles(c: DriveOfflineCopy) {
        runCatching { File(root, c.id.toString()).deleteRecursively() }
    }

    private suspend fun put(map: Map<Long, DriveOfflineCopy>) {
        _copies.value = map
        withContext(Dispatchers.IO) {
            runCatching {
                if (map.isEmpty()) {
                    indexFile.delete()
                } else {
                    val tmp = File(indexFile.path + ".tmp")
                    tmp.writeText(DriveOfflineStoreLogic.toJson(server, map.values))
                    if (!tmp.renameTo(indexFile)) tmp.delete()
                }
            }.onFailure { Timber.w("Drive offline: index not written (%s)", it.javaClass.simpleName) }
        }
        keepUp(map.isNotEmpty())
    }

    /**
     * One run: the marks of Drive, the files below marked folders, the plan and the downloads. RETRY when Drive could
     * not be reached (nothing changes then); copies are only dropped on a complete answer.
     */
    suspend fun sync(): Outcome = lock.withLock {
        _running.value = true
        try {
            syncLocked()
        } finally {
            _running.value = false
            _progress.value = null
        }
    }

    private suspend fun syncLocked(): Outcome {
        val url = setup.getServerUrl()
        if (url.isBlank() || !setup.isConfigured()) {
            if (_copies.value.isNotEmpty()) wipeLocked()
            return Outcome.DONE
        }
        if (server != url) {
            if (_copies.value.isNotEmpty() || server.isNotEmpty()) wipeLocked()
            server = url
        }
        val me = live.current() ?: return Outcome.RETRY
        if (!me.has(DMe.FEATURE_OFFLINE)) {
            if (_copies.value.isNotEmpty()) wipeLocked()
            return Outcome.DONE
        }
        val marks = when (val r = api.offline()) {
            is DResult.Ok -> r.value
            is DResult.Failed -> {
                // Drive says no (no access, no seat, the plugin gone): nothing of it stays on this phone
                if (r.http == 401 || r.http == 403 || r.http == 404) wipeLocked()
                return if (r.http in 500..599) Outcome.RETRY else Outcome.DONE
            }
            DResult.Offline -> return Outcome.RETRY
            DResult.NotConfigured -> return Outcome.DONE
        }
        val wanted = expand(marks) ?: return Outcome.RETRY
        val s = settings.current
        val plan = DriveOfflineStoreLogic.plan(wanted, _copies.value, s.offlineLimitMb.toLong() * 1024 * 1024)
        withContext(Dispatchers.IO) { plan.remove.forEach(::deleteFiles) }
        put(plan.copies)
        var retry = false
        val nodes = wanted.associateBy({ it.node.id }, { it.node })
        for (id in plan.download) {
            currentCoroutineContext().ensureActive()
            val c = _copies.value[id] ?: continue
            if (!DriveOfflineStoreLogic.mayLoad(s.offlineWifiOnly, network.unmetered())) {
                set(c.copy(state = if (c.file != null) DriveOfflineCopy.State.OUTDATED else DriveOfflineCopy.State.PENDING, reason = DriveOfflineStoreLogic.WAIT_WIFI))
                continue
            }
            val usable = runCatching { context.filesDir.usableSpace }.getOrDefault(0L)
            if (!DriveOfflineStoreLogic.deviceRoom(usable, c.size)) {
                set(c.copy(reason = DriveOfflineStoreLogic.WAIT_DEVICE_SPACE))
                continue
            }
            val node = nodes[id] ?: continue
            when (val r = load(node, c)) {
                is DResult.Ok -> set(r.value)
                DResult.Offline -> {
                    retry = true
                    set(c.copy(reason = null))
                    break
                }
                is DResult.Failed -> set(c.copy(state = if (c.file != null) DriveOfflineCopy.State.OUTDATED else DriveOfflineCopy.State.FAILED, reason = r.code))
                DResult.NotConfigured -> break
            }
        }
        return if (retry) Outcome.RETRY else Outcome.DONE
    }

    private suspend fun set(c: DriveOfflineCopy) = put(_copies.value + (c.id to c))

    /** The marks → the files they ask for (folders looked into); null when a listing could not be read completely. */
    private suspend fun expand(marks: List<DNode>): List<DriveOfflineWant>? {
        val roots = LinkedHashMap<Long, Pair<DNode, LinkedHashSet<Long>>>()
        val paths = HashMap<Long, String?>()
        fun add(n: DNode, root: Long, path: String?) {
            val e = roots.getOrPut(n.id) { n to LinkedHashSet() }
            e.second += root
            if (!paths.containsKey(n.id)) paths[n.id] = path
        }
        var folders = 0
        for (m in marks) {
            if (m.encrypted) continue
            if (!m.isDir) {
                add(m, m.id, m.path?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() })
                continue
            }
            val queue = ArrayDeque<Pair<Long, String>>()
            queue += m.id to m.name
            while (queue.isNotEmpty() && roots.size < DriveOfflineStoreLogic.MAX_FILES && folders < DriveOfflineStoreLogic.MAX_FOLDERS) {
                val (dir, path) = queue.removeFirst()
                folders++
                var offset = 0
                while (true) {
                    val page = when (val r = api.folder(dir, offset = offset, limit = PAGE)) {
                        is DResult.Ok -> r.value
                        // a folder that went or is no longer mine (404 / 403) is simply not there
                        is DResult.Failed -> if (r.http == 403 || r.http == 404) break else return null
                        else -> return null
                    }
                    for (n in page.items) {
                        if (n.encrypted) continue
                        if (n.isDir) queue += n.id to "$path/${n.name}" else if (roots.size < DriveOfflineStoreLogic.MAX_FILES) add(n, m.id, path)
                    }
                    offset = page.next ?: break
                }
            }
        }
        return roots.values.map { (n, r) -> DriveOfflineWant(n, r, paths[n.id]) }
    }

    /** [node] into its folder of the store, checked against Drive's SHA-256; the copy after, or why not. */
    private suspend fun load(node: DNode, c: DriveOfflineCopy): DResult<DriveOfflineCopy> = withContext(Dispatchers.IO) {
        val dir = File(root, node.id.toString())
        val name = DriveLogic.safeName(node.name) ?: "file"
        val part = File(dir, ".part")
        try {
            _progress.value = node.id to 0f
            val progress: (Long, Long) -> Unit = { done, total -> if (total > 0) _progress.value = node.id to (done.toFloat() / total).coerceIn(0f, 1f) }
            when (val r = api.download(node.id, expectedSha256 = node.sha256, onProgress = progress)) {
                is DResult.Ok -> {
                    dir.mkdirs()
                    val digest = MessageDigest.getInstance("SHA-256")
                    var bytes = 0L
                    val body = r.value.body() ?: return@withContext DResult.Failed(DriveApi.CODE_INVALID, 0)
                    body.use { b ->
                        val total = b.contentLength().takeIf { it > 0 } ?: node.size
                        b.byteStream().use { inp ->
                            part.outputStream().use { out ->
                                val buf = ByteArray(256 * 1024)
                                while (true) {
                                    val n = inp.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    digest.update(buf, 0, n)
                                    bytes += n
                                    progress(bytes, total)
                                }
                            }
                        }
                    }
                    val sha = digest.digest().joinToString("") { "%02x".format(it) }
                    if (node.sha256 != null && !node.sha256.equals(sha, ignoreCase = true)) {
                        part.delete()
                        return@withContext DResult.Failed("sha256", 0)
                    }
                    val target = File(dir, name)
                    // the older copy (maybe under another name) goes once the new one is complete
                    dir.listFiles()?.forEach { if (it.name != part.name && it.name != name) it.delete() }
                    if (!part.renameTo(target)) {
                        part.delete()
                        return@withContext DResult.Failed("write", 0)
                    }
                    DResult.Ok(
                        c.copy(
                            state = DriveOfflineCopy.State.STORED, reason = null, file = name, storedRev = node.rev,
                            storedSha256 = sha, storedAt = System.currentTimeMillis(), size = bytes,
                        ),
                    )
                }
                is DResult.Failed -> r
                DResult.Offline -> DResult.Offline
                DResult.NotConfigured -> DResult.NotConfigured
            }
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: IOException) {
            part.delete()
            DResult.Offline
        }
    }

    private companion object {
        const val DIR = "drive-offline"
        const val INDEX = "drive-offline-index.json"
        const val PAGE = 500
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DriveOfflineStoreEntryPoint {
    fun driveOfflineStore(): DriveOfflineStore
}

class DriveOfflineWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val store = EntryPointAccessors.fromApplication(applicationContext, DriveOfflineStoreEntryPoint::class.java).driveOfflineStore()
        return when (store.sync()) {
            DriveOfflineStore.Outcome.DONE -> Result.success()
            DriveOfflineStore.Outcome.RETRY -> if (runAttemptCount < 5) Result.retry() else Result.success()
        }
    }
}

/** The runs of "Offline verfügbar": at once after a change, and every few hours while something is marked. */
object DriveOfflineWork {
    private const val NOW = "drive-offline-now"
    private const val PERIODIC = "drive-offline-periodic"
    private const val EVERY_HOURS = 6L

    private fun constraints(context: Context) = Constraints.Builder()
        .setRequiredNetworkType(if (DriveSettings.offlineWifiOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresStorageNotLow(true)
        .build()

    /** A run soon (a mark set or taken off, the screen opened, a setting changed, a newer version seen). */
    fun now(context: Context) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<DriveOfflineWorker>()
                .setConstraints(constraints(context))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
        }.onFailure { Timber.w("Drive offline: not scheduled (%s)", it.javaClass.simpleName) }
    }

    /** The regular run while copies are kept ([on]); without any it stops. */
    fun keepUp(context: Context, on: Boolean) {
        runCatching {
            val wm = WorkManager.getInstance(context)
            if (!on) {
                wm.cancelUniqueWork(PERIODIC)
                return
            }
            val request = PeriodicWorkRequestBuilder<DriveOfflineWorker>(EVERY_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints(context))
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }.onFailure { Timber.w("Drive offline: regular run not scheduled (%s)", it.javaClass.simpleName) }
    }
}
