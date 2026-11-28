package com.gatecontrol.android.drive.vault

import android.content.Context
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DVault
import com.gatecontrol.android.drive.DVaultLocal
import com.gatecontrol.android.drive.DVaultLocalItem
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveJson
import com.gatecontrol.android.drive.DriveOfflineStore
import com.gatecontrol.android.drive.notOk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Nur auf diesem Gerät" (Drive 1.32, vertrag-apps 6): moving a folder of a vault onto this phone and back, as jobs
 * that go on where they stopped. A move fetches every file of the server's manifest (its ciphertext, checked against
 * the SHA-256 there; a "zusätzlich offline" copy of the same revision is taken instead), decrypts it completely (every
 * tag) and encrypts it again with the folder's own key LK, checks the new file once more and only then — every file
 * done — proves this device (the server's challenge opened with the device key) and lets the server take the content
 * away. The way back uploads every file with the vault's current key and ends the same way. While a job runs the vault
 * stays open ([DriveVaultLock.busy]) and the lease on the server is renewed every 5 minutes; the job itself runs in
 * the foreground work [DriveVaultLocalWork] with its notification.
 */
@Singleton
class DriveVaultLocalJobs internal constructor(
    private val api: DriveApi,
    private val store: DriveVaultLocalStore,
    private val session: Session,
    private val offline: Offline,
    private val work: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val tmpDir: () -> File,
    /** The server this phone is signed in to (written with the folder). */
    private val serverUrl: () -> String = { "" },
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        api: DriveApi,
        store: DriveVaultLocalStore,
        vault: DriveVaultSession,
        offlineStore: DriveOfflineStore,
        marks: com.gatecontrol.android.drive.DriveOffline,
        lock: DriveVaultLock,
        setup: com.gatecontrol.android.data.SetupRepository,
    ) : this(
        api = api,
        store = store,
        session = object : Session {
            override fun deviceId() = vault.deviceId()
            override fun vaultKey(vaultId: String, version: Int) = vault.vaultKey(vaultId, version)
            override fun keyVersion(vaultId: String) = vault.keyVersion(vaultId)
            override suspend fun <T> busy(block: suspend () -> T): T = lock.busy(block)
        },
        offline = object : Offline {
            override fun cipher(node: DNode): File? = offlineStore.vaultCipher(node)
            override suspend fun drop(ids: List<Long>) = ids.forEach { offlineStore.drop(it) }
            override suspend fun keep(node: DNode): Boolean = marks.set(node, true) == null
        },
        work = { DriveVaultLocalWork.start(context) },
        tmpDir = { File(context.cacheDir, "drive-vault-local") },
        serverUrl = { setup.getServerUrl() },
    )

    /** What a job needs of the open vault. */
    interface Session {
        fun deviceId(): String
        fun vaultKey(vaultId: String, version: Int): ByteArray?
        fun keyVersion(vaultId: String): Int?
        suspend fun <T> busy(block: suspend () -> T): T
    }

    /** The "zusätzlich offline" copies (Drive 1.20). */
    interface Offline {
        /** The ciphertext copy of [node] in that very revision; null when there is none. */
        fun cipher(node: DNode): File?
        suspend fun drop(ids: List<Long>)
        /** "Offline verfügbar" on for [node] (the copy on the way back stays as an extra). */
        suspend fun keep(node: DNode): Boolean
    }

    enum class Kind { MOVE, BACK }

    /** The three steps of the card (A4): fetch, check and encrypt here, remove from the server. */
    enum class Step { DOWNLOAD, CHECK, FINISH }

    /**
     * How a job stands: [done] of [total] files fetched (on the way back: uploaded), [checked] of them checked and
     * encrypted again, [bytes] fetched. [error] when it stopped (a code; [file] the name of the file that failed),
     * [needsAuth] when the device key needs the person first, [finished] when it is through.
     */
    data class Progress(
        val kind: Kind,
        val vaultId: String,
        val nodeId: Long,
        val name: String,
        val step: Step = Step.DOWNLOAD,
        val done: Int = 0,
        val checked: Int = 0,
        val total: Int = 0,
        val bytes: Long = 0,
        val error: String? = null,
        val file: String? = null,
        val needsAuth: Boolean = false,
        val finished: Boolean = false,
    ) {
        val running: Boolean get() = error == null && !needsAuth && !finished
    }

    private val _progress = MutableStateFlow<Map<Long, Progress>>(emptyMap())

    /** The jobs of this run of the app, by the folder's node id. */
    val progress: StateFlow<Map<Long, Progress>> = _progress.asStateFlow()

    /** A job asked for and not yet run by the work: the device's key pair is taken for it now (just confirmed). */
    private class Request(val kind: Kind, val vault: DVault, val node: DNode, val name: String, val pair: DriveVaultCrypto.KeyPair, val keepOffline: Boolean)

    private val queue = ArrayDeque<Request>()
    private val running = HashMap<Long, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun show(p: Progress) = _progress.update { it + (p.nodeId to p) }

    private fun set(nodeId: Long, change: (Progress) -> Progress) = _progress.update { m -> m[nodeId]?.let { m + (nodeId to change(it)) } ?: m }

    /** The person saw a finished or failed job: its card goes. */
    fun dismiss(nodeId: Long) = _progress.update { it - nodeId }

    /**
     * Moves [node] (a folder of [vault], its decrypted [name]) onto this phone. [pair]: the device key, read just now
     * after the person confirmed (it proves this device at the end and is overwritten then).
     */
    fun move(vault: DVault, node: DNode, name: String, pair: DriveVaultCrypto.KeyPair) =
        enqueue(Request(Kind.MOVE, vault, node, name, pair, keepOffline = false))

    /** Back to the server (the placeholder [node]); [keepOffline]: the copy stays here as "zusätzlich offline". */
    fun back(vault: DVault, node: DNode, name: String, pair: DriveVaultCrypto.KeyPair, keepOffline: Boolean) =
        enqueue(Request(Kind.BACK, vault, node, name, pair, keepOffline))

    @Synchronized
    private fun enqueue(r: Request) {
        if (running.containsKey(r.node.id) || queue.any { it.node.id == r.node.id }) {
            r.pair.wipe()
            return
        }
        queue.addLast(r)
        show(Progress(r.kind, r.vault.id, r.node.id, r.name, total = 0))
        work()
    }

    /** The foreground work: runs every job asked for, one after the other; [onProgress] for its notification. */
    suspend fun drain(onProgress: (Progress) -> Unit = {}) {
        while (true) {
            val r = synchronized(this) { queue.removeFirstOrNull() } ?: return
            val job = scope.launch { run(r) }
            synchronized(this) { running[r.node.id] = job }
            val watch = scope.launch {
                progress.collect { m -> m[r.node.id]?.let(onProgress) }
            }
            try {
                job.join()
            } finally {
                watch.cancel()
                synchronized(this) { running.remove(r.node.id) }
            }
        }
    }

    /**
     * A move or a way back of an earlier run of the app that did not end ([f] still `moving` / `returning` here): its
     * card shows "Weiter" (on where it stopped) and "Abbrechen". A way back is a folder kept here again until then (the
     * server's lease ran out or runs out: it is `local` there as well).
     */
    fun stalled(f: DriveVaultLocalFolder, name: String) {
        synchronized(this) { if (running.containsKey(f.nodeId) || queue.any { it.node.id == f.nodeId }) return }
        if (_progress.value.containsKey(f.nodeId) || f.state == DriveVaultLocalFolder.State.LOCAL) return
        val kind = if (f.state == DriveVaultLocalFolder.State.MOVING) Kind.MOVE else Kind.BACK
        if (kind == Kind.BACK) store.update(f.nodeId) { it.copy(state = DriveVaultLocalFolder.State.LOCAL) }
        val files = f.files
        show(Progress(kind, f.vaultId, f.nodeId, name, total = 0, done = if (kind == Kind.MOVE) files.size else files.count { it.up != null }, error = "stopped"))
    }

    /** "Abbrechen" (A4: until step 3 without consequences): the move stops, the server forgets it, the copies here go. */
    fun cancel(nodeId: Long) {
        val job = synchronized(this) {
            queue.removeAll { (it.node.id == nodeId).also { hit -> if (hit) it.pair.wipe() } }
            running[nodeId]
        }
        scope.launch {
            job?.cancel()
            job?.join()
            val f = store.get(nodeId)
            if (f != null && f.state == DriveVaultLocalFolder.State.MOVING) {
                api.vaultLocalCancel(f.vaultId, nodeId, f.deviceId)
                store.remove(nodeId)
            }
            dismiss(nodeId)
        }
    }

    private suspend fun run(r: Request) {
        try {
            session.busy {
                withContext(Dispatchers.IO) {
                    when (r.kind) {
                        Kind.MOVE -> runMove(r)
                        Kind.BACK -> runBack(r)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Drive vault local: job failed (%s)", e.javaClass.simpleName)
            set(r.node.id) { it.copy(error = "failed") }
        } finally {
            r.pair.wipe()
            runCatching { tmpDir().listFiles()?.forEach { it.delete() } }
        }
    }

    /** Keeps the lease on the server while [block] runs. */
    private suspend fun <T> leased(vaultId: String, nodeId: Long, deviceId: String, block: suspend () -> T): T {
        val lease = scope.launch {
            while (isActive) {
                delay(DriveVaultLocalLogic.LEASE_EVERY_MS)
                api.vaultLocalLease(vaultId, nodeId, deviceId)
            }
        }
        try {
            return block()
        } finally {
            lease.cancel()
        }
    }

    /** A failed call → the code for the card (`offline` without a connection). */
    private fun code(r: DResult<*>): String = when (r) {
        is DResult.Failed -> r.code
        DResult.Offline -> "offline"
        DResult.NotConfigured -> "not_configured"
        is DResult.Ok -> "failed"
    }

    /** The proof of this device for the next commit (a new challenge each time). */
    private suspend fun proof(vaultId: String, deviceId: String, pair: DriveVaultCrypto.KeyPair): DResult<String> {
        val c = api.vaultLocalChallenge(vaultId, deviceId)
        if (c !is DResult.Ok) return c.notOk()
        return try {
            DResult.Ok(DriveVaultCrypto.openProof(c.value, pair, vaultId, deviceId))
        } catch (e: DriveVaultCrypto.DriveVaultException) {
            DResult.Failed("proof", 0)
        }
    }

    // ─── the move ───────────────────────────────────────────────────

    private class Stop(val code: String, val file: String? = null) : Exception(code)

    private suspend fun runMove(r: Request) {
        val vaultId = r.vault.id
        val nodeId = r.node.id
        val deviceId = session.deviceId()
        val start = api.vaultLocalStart(vaultId, nodeId, deviceId)
        if (start !is DResult.Ok) {
            set(nodeId) { it.copy(error = code(start)) }
            return
        }
        // the folder here: what an earlier try fetched stays (the same revision is not fetched again)
        var folder = store.get(nodeId)?.takeIf { it.vaultId == vaultId && it.state == DriveVaultLocalFolder.State.MOVING }
        val lk = folder?.let { store.key(nodeId) } ?: run {
            folder?.let { store.remove(nodeId) }
            folder = DriveVaultLocalFolder(
                server = serverUrl(), vaultId = vaultId, nodeId = nodeId, parentId = r.node.parentId, areaId = r.node.areaId, nameCipher = r.node.nameCipher,
                deviceId = deviceId, state = DriveVaultLocalFolder.State.MOVING, since = now(),
            )
            store.put(folder!!)
            store.newKey(vaultId, nodeId, r.pair.publicKey, r.vault.recoveryPub)
        }
        var manifest = start.value.items
        try {
            leased(vaultId, nodeId, deviceId) {
                var tries = 0
                while (true) {
                    folder = fetchAll(r, store.get(nodeId)!!, manifest, lk)
                    val p = proof(vaultId, deviceId, r.pair)
                    if (p !is DResult.Ok) throw Stop(code(p))
                    set(nodeId) { it.copy(step = Step.FINISH) }
                    val have = DriveVaultLocalLogic.commitManifest(folder!!.items)
                    when (val c = api.vaultLocalCommit(vaultId, nodeId, deviceId, p.value, have)) {
                        is DResult.Ok -> {
                            finishMove(nodeId, manifest, c.value.local)
                            return@leased
                        }
                        is DResult.Failed -> {
                            val changed = if (c.http == 409) DriveJson.vaultLocalChanged(c.body) else null
                            if (changed != null && ++tries < DriveVaultLocalLogic.COMMIT_TRIES) {
                                manifest = DriveVaultLocalLogic.merge(manifest, changed.changed, changed.added, changed.removed)
                                set(nodeId) { it.copy(step = Step.DOWNLOAD) }
                                continue
                            }
                            throw Stop(if (changed != null) "changed" else c.code)
                        }
                        else -> {
                            // the answer got lost: the server says whether it is through
                            val s = api.vaultLocal(vaultId, nodeId, deviceId)
                            if (s is DResult.Ok && s.value.local?.state == DVaultLocal.STATE_LOCAL) {
                                finishMove(nodeId, manifest, s.value.local)
                                return@leased
                            }
                            throw Stop(code(c))
                        }
                    }
                }
            }
        } catch (e: Stop) {
            when (e.code) {
                // nothing changes on the server until the commit: a broken file ends the move (nothing deleted)
                "decrypt", "verify", "changed", "proof", "no_space" -> {
                    api.vaultLocalCancel(vaultId, nodeId, deviceId)
                    store.remove(nodeId)
                }
                // no connection, the server busy: what is here stays, "Weiter" goes on
                else -> Unit
            }
            set(nodeId) { it.copy(error = e.code, file = e.file) }
        }
    }

    /**
     * Fetches and checks every node of [manifest] not here yet, removes what the server no longer has; the index is
     * written after each file (the move goes on there). → the folder with every entry of the manifest.
     */
    private suspend fun fetchAll(r: Request, start: DriveVaultLocalFolder, manifest: List<DVaultLocalItem>, lk: ByteArray): DriveVaultLocalFolder {
        val vaultId = start.vaultId
        val nodeId = start.nodeId
        val plan = DriveVaultLocalLogic.plan(manifest, start.items)
        var folder = start
        if (plan.drop.isNotEmpty()) {
            val gone = plan.drop.mapTo(HashSet()) { it.id }
            folder = store.update(nodeId) { f -> f.copy(items = f.items.filter { it.id !in gone }) }!!
            store.tidy(folder)
        }
        val tkKeys: (Int) -> ByteArray? = { v -> session.vaultKey(vaultId, v) }
        val total = manifest.count { !it.isDir }
        val done0 = total - plan.fetch.size
        set(nodeId) { it.copy(total = total, done = done0, checked = done0, step = Step.DOWNLOAD) }
        // folders: only their names (the vault key → LK)
        val haveIds = folder.items.mapTo(HashSet()) { it.id }
        val newDirs = plan.dirs.filter { it.id !in haveIds }.map { d ->
            val name = d.nameCipher ?: throw Stop("invalid")
            DriveVaultLocalItem(
                id = d.id, parentId = d.parentId ?: nodeId, isDir = true,
                name = try { DriveVaultLocalCrypt.transcodeName(tkKeys, vaultId, name, lk, DriveVaultCrypto.LOCAL_KEY_VERSION) } catch (e: DriveVaultCrypto.DriveVaultException) { throw Stop(e.code) },
                mtime = now(),
            )
        }
        if (newDirs.isNotEmpty()) folder = store.update(nodeId) { f -> f.copy(items = f.items + newDirs) }!!
        var done = done0
        for (m in plan.fetch) {
            val nameCipher = m.nameCipher ?: throw Stop("invalid")
            val plainName = runCatching { DriveVaultCrypto.decryptName(tkKeys, vaultId, nameCipher) }.getOrNull() ?: throw Stop("decrypt")
            if (store.usableSpace() < m.size * 2 + 64L * 1_048_576) throw Stop("no_space", plainName)
            val src = source(vaultId, m, plainName)
            val out = store.tempFile(folder)
            try {
                done++
                set(nodeId) { it.copy(done = done, bytes = it.bytes + m.size) }
                val plain = try {
                    src.file.inputStream().buffered(256 * 1024).use { inp ->
                        out.outputStream().buffered(256 * 1024).use { o -> DriveVaultLocalCrypt.transcode(tkKeys, vaultId, inp, lk, DriveVaultCrypto.LOCAL_KEY_VERSION, o) }
                    }
                } catch (e: DriveVaultCrypto.DriveVaultException) {
                    throw Stop("decrypt", plainName)
                }
                // the check: the new file opens with LK and gives the very same plaintext
                val back = try {
                    out.inputStream().buffered(256 * 1024).use { DriveVaultLocalCrypt.digest({ v -> if (v == DriveVaultCrypto.LOCAL_KEY_VERSION) lk else null }, vaultId, it) }
                } catch (e: DriveVaultCrypto.DriveVaultException) {
                    throw Stop("verify", plainName)
                }
                if (back != plain) throw Stop("verify", plainName)
                store.place(folder, m.id, out)
                val item = DriveVaultLocalItem(
                    id = m.id, parentId = m.parentId ?: nodeId, isDir = false,
                    name = DriveVaultCrypto.encryptName(lk, vaultId, DriveVaultCrypto.LOCAL_KEY_VERSION, plainName),
                    size = plain.size,
                    mtime = m.mtime?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: now(),
                    srcRev = m.rev, srcSha256 = m.sha256,
                )
                folder = store.update(nodeId) { f -> f.copy(items = f.items.filter { it.id != m.id } + item) }!!
                set(nodeId) { it.copy(checked = it.checked + 1, step = Step.CHECK) }
            } finally {
                out.delete()
                if (src.owned) src.file.delete()
            }
        }
        return folder
    }

    private class Source(val file: File, val owned: Boolean)

    /** The ciphertext of [m]: the "zusätzlich offline" copy of that revision, else downloaded — checked against its SHA-256. */
    private suspend fun source(vaultId: String, m: DVaultLocalItem, name: String): Source {
        val asNode = DNode(
            id = m.id, areaId = "", parentId = m.parentId, name = "", isDir = false, size = m.size, mime = null, mediaId = null, rev = m.rev,
            mtime = null, updatedAt = null, updatedBy = null, sha256 = m.sha256, vaultId = vaultId, nameCipher = m.nameCipher, keyVersion = m.keyVersion,
        )
        offline.cipher(asNode)?.let { kept ->
            val ok = m.sha256 == null || runCatching { kept.inputStream().buffered(256 * 1024).use(DriveVaultLocalCrypt::sha256) }.getOrNull() == m.sha256
            if (ok) return Source(kept, owned = false)
        }
        val dir = tmpDir().apply { mkdirs() }
        val tmp = File.createTempFile("fetch", ".gcdv", dir)
        try {
            val r = api.download(m.id, expectedSha256 = m.sha256)
            if (r !is DResult.Ok) throw Stop(code(r), name)
            val body = r.value.body() ?: throw Stop("invalid", name)
            body.use { b -> b.byteStream().use { inp -> tmp.outputStream().buffered(256 * 1024).use { inp.copyTo(it, 256 * 1024) } } }
            if (m.sha256 != null) {
                val sha = tmp.inputStream().buffered(256 * 1024).use(DriveVaultLocalCrypt::sha256)
                if (sha != m.sha256) throw Stop("verify", name)
            }
            return Source(tmp, owned = true)
        } catch (e: IOException) {
            tmp.delete()
            throw Stop("offline", name)
        } catch (e: Stop) {
            tmp.delete()
            throw e
        }
    }

    private suspend fun finishMove(nodeId: Long, manifest: List<DVaultLocalItem>, local: DVaultLocal?) {
        val at = now()
        val purge = local?.purgeAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: (at + GRACE_MS)
        val f = store.update(nodeId) { it.copy(state = DriveVaultLocalFolder.State.LOCAL, since = at, changedAt = at, purgeAt = purge) }
        f?.let(store::tidy)
        // the "zusätzlich offline" copies of the folder: the copy here counts now
        runCatching { offline.drop(manifest.filter { !it.isDir }.map { it.id }) }
        set(nodeId) { it.copy(finished = true, step = Step.FINISH, checked = it.total, done = it.total) }
    }

    // ─── the way back ───────────────────────────────────────────────

    private suspend fun runBack(r: Request) {
        val vaultId = r.vault.id
        val nodeId = r.node.id
        val deviceId = session.deviceId()
        val start = store.get(nodeId) ?: run {
            set(nodeId) { it.copy(error = "not_found") }
            return
        }
        val lk = store.key(nodeId) ?: run {
            set(nodeId) { it.copy(error = "locked") }
            return
        }
        val ret = api.vaultLocalReturn(vaultId, nodeId, deviceId)
        if (ret !is DResult.Ok) {
            set(nodeId) { it.copy(error = code(ret)) }
            return
        }
        store.update(nodeId) { it.copy(state = DriveVaultLocalFolder.State.RETURNING) }
        val lkKeys: (Int) -> ByteArray? = { v -> if (v == DriveVaultCrypto.LOCAL_KEY_VERSION) lk else null }
        try {
            leased(vaultId, nodeId, deviceId) {
                val all = DriveVaultLocalLogic.reachable(start)
                val files = all.filter { !it.isDir }
                set(nodeId) { it.copy(total = files.size, done = files.count { f -> f.up != null }, checked = files.count { f -> f.up != null }) }
                // the folders first, top down (their server ids are written as they come)
                val order = all.filter { it.isDir }.sortedBy { DriveVaultLocalLogic.path(start, it.id).size }
                for (d in order) {
                    val cur = store.get(nodeId)!!
                    if (cur.items.first { it.id == d.id }.up != null) continue
                    val parent = serverParent(cur, d.parentId) ?: throw Stop("invalid")
                    val v = session.keyVersion(vaultId) ?: throw Stop("locked")
                    val tk = session.vaultKey(vaultId, v) ?: throw Stop("locked")
                    val name = try { DriveVaultLocalCrypt.transcodeName(lkKeys, vaultId, d.name, tk, v) } catch (e: DriveVaultCrypto.DriveVaultException) { throw Stop("decrypt") }
                    val made = api.createVaultFolder(parent, name, v)
                    if (made !is DResult.Ok) throw Stop(code(made))
                    store.update(nodeId) { f -> f.copy(items = f.items.map { if (it.id == d.id) it.copy(up = made.value.id) else it }) }
                }
                for (fi in files) {
                    val cur = store.get(nodeId)!!
                    if (cur.items.first { it.id == fi.id }.up != null) continue
                    val parent = serverParent(cur, fi.parentId) ?: throw Stop("invalid")
                    val up = upload(vaultId, cur, fi, parent, lkKeys)
                    store.update(nodeId) { f -> f.copy(items = f.items.map { if (it.id == fi.id) it.copy(up = up) else it }) }
                    set(nodeId) { it.copy(done = it.done + 1, checked = it.checked + 1, bytes = it.bytes + fi.size, step = Step.CHECK) }
                }
                set(nodeId) { it.copy(step = Step.FINISH) }
                val p = proof(vaultId, deviceId, r.pair)
                if (p !is DResult.Ok) throw Stop(code(p))
                val c = api.vaultLocalReturnCommit(vaultId, nodeId, deviceId, p.value, files.size)
                if (c !is DResult.Ok) {
                    val s = api.vaultLocal(vaultId, nodeId, deviceId)
                    // a lost answer: a plain folder again (not_found) means through
                    if (!(c is DResult.Offline && s is DResult.Failed && s.code == "not_found")) throw Stop(if (c is DResult.Failed && DriveJson.conflictReason(c.body) == "count") "count" else code(c))
                }
            }
        } catch (e: Stop) {
            // the folder stays here as it was; what is on the server already is skipped next time
            store.update(nodeId) { it.copy(state = DriveVaultLocalFolder.State.LOCAL) }
            set(nodeId) { it.copy(error = e.code, file = e.file) }
            return
        }
        if (r.keepOffline) runCatching { offline.keep(r.node.copy(local = null)) }
        store.remove(nodeId)
        set(nodeId) { it.copy(finished = true, step = Step.FINISH, done = it.total, checked = it.total) }
    }

    /** The server id of a local folder (the placeholder at the top). */
    private fun serverParent(f: DriveVaultLocalFolder, parentId: Long): Long? =
        if (parentId == f.nodeId) f.nodeId else f.items.firstOrNull { it.id == parentId }?.up

    /** One file back with the vault's current key: encrypted into the cache, uploaded, made a file of the vault, checked. */
    private suspend fun upload(vaultId: String, f: DriveVaultLocalFolder, item: DriveVaultLocalItem, parent: Long, lkKeys: (Int) -> ByteArray?): Long {
        val v = session.keyVersion(vaultId) ?: throw Stop("locked")
        val tk = session.vaultKey(vaultId, v) ?: throw Stop("locked")
        val name = runCatching { DriveVaultCrypto.decryptName(lkKeys, vaultId, item.name) }.getOrNull() ?: throw Stop("decrypt")
        val src = store.fileOf(f, item.id)?.takeIf { it.isFile } ?: throw Stop("failed", name)
        val dir = tmpDir().apply { mkdirs() }
        val tmp = File.createTempFile("upload", ".gcdv", dir)
        try {
            try {
                src.inputStream().buffered(256 * 1024).use { inp -> tmp.outputStream().buffered(256 * 1024).use { o -> DriveVaultLocalCrypt.transcode(lkKeys, vaultId, inp, tk, v, o) } }
            } catch (e: DriveVaultCrypto.DriveVaultException) {
                throw Stop("decrypt", name)
            }
            val sha = tmp.inputStream().buffered(256 * 1024).use(DriveVaultLocalCrypt::sha256)
            val media = api.upload(tmp, "application/octet-stream")
            if (media !is DResult.Ok) throw Stop(code(media), name)
            val cipher = DriveVaultCrypto.encryptName(tk, vaultId, v, name)
            val c = api.commitVault(media.value, parent, cipher, v, item.mtime.takeIf { it > 0 })
            if (c !is DResult.Ok) throw Stop(code(c), name)
            if (!DriveVaultMove.verified(c.value.first, tmp.length(), sha)) throw Stop("verify", name)
            return c.value.first.id
        } finally {
            tmp.delete()
        }
    }

    // ─── undo, forget ───────────────────────────────────────────────

    /** "Rückgängig" (within 24 h, nothing changed here): the server brings the content back, the copy here goes. */
    suspend fun undo(nodeId: Long): DResult<Unit> {
        val f = store.get(nodeId) ?: return DResult.Failed("not_found", 0)
        if (!DriveVaultLocalLogic.canUndo(f, now())) return DResult.Failed("changed_here", 0)
        val r = api.vaultLocalUndo(f.vaultId, nodeId, f.deviceId)
        if (r !is DResult.Ok) return r.notOk()
        store.remove(nodeId)
        dismiss(nodeId)
        return DResult.Ok(Unit)
    }

    /**
     * "Ordner löschen" of a folder kept only here: the server removes the placeholder (proof of this device), then the
     * copy here goes. [pair]: the device key, read just now (overwritten after).
     */
    suspend fun deleteHere(nodeId: Long, pair: DriveVaultCrypto.KeyPair): DResult<Unit> = try {
        val f = store.get(nodeId) ?: return DResult.Failed("not_found", 0)
        val p = proof(f.vaultId, f.deviceId, pair)
        if (p !is DResult.Ok) return p.notOk()
        val r = api.vaultLocalForget(f.vaultId, nodeId, f.deviceId, p.value)
        // the placeholder is gone already (an earlier try, or removed on another device): the copy here goes as well
        if (r !is DResult.Ok && !(r is DResult.Failed && r.code == "not_found")) return r.notOk()
        store.remove(nodeId)
        DResult.Ok(Unit)
    } finally {
        pair.wipe()
    }

    companion object {
        /** The server keeps the hidden content 24 hours ("Rückgängig"). */
        const val GRACE_MS = 24 * 60 * 60_000L
    }
}
