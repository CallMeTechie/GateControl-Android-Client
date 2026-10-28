package com.gatecontrol.android.drive

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.gatecontrol.android.R
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.drive.DriveDocs.Doc
import com.gatecontrol.android.familie.PluginDirectory
import com.gatecontrol.android.support.CrashReports
import com.gatecontrol.android.ui.drive.driveText
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Drive in the Files app and in every "open file" / "save as" dialog (Storage Access Framework). One root
 * "GateControl Drive" while the server lists the plugin; its folders are the areas. Listings load in the background
 * (the Files app shows a spinner, then the rows) and are kept for a moment; opening a file downloads it into the
 * cache, writing goes up through [DriveTransfers] when the file is closed (a new version of the file, or a new file
 * for "save as"). Only the system's documents UI may call this provider (`MANAGE_DOCUMENTS`); apps get single
 * files through the URIs the person picks.
 */
class DriveDocumentsProvider : DocumentsProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun driveApi(): DriveApi
        fun driveTransfers(): DriveTransfers
        fun driveLive(): DriveLive
        fun pluginDirectory(): PluginDirectory
        fun setupRepository(): SetupRepository
    }

    // Hilt is not ready when a provider is created (before Application.onCreate): resolve on first use.
    private val deps: Deps by lazy { EntryPointAccessors.fromApplication(requireContext().applicationContext, Deps::class.java) }
    private val api get() = deps.driveApi()
    private val transfers get() = deps.driveTransfers()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)
    private val authority by lazy { authority(requireContext().packageName) }
    /** Close callbacks of written files land here, off the binder threads. */
    private val handler by lazy { Handler(HandlerThread("drive-documents").apply { start() }.looper) }

    private class Listing(val docs: List<Doc>?, val error: String?, val at: Long)

    /** A file created in a "save as" dialog: it exists once it was written and went up. */
    private class Pending(val parentId: Long, val name: String, val mime: String, @Volatile var nodeId: Long? = null)

    private val listings = ConcurrentHashMap<String, Listing>()
    private val loading: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val nodes = ConcurrentHashMap<Long, DNode>()
    private val docs = ConcurrentHashMap<String, Doc>()
    private val pendings = ConcurrentHashMap<Long, Pending>()
    private val seq = AtomicLong(0)
    private val watching = AtomicBoolean(false)
    @Volatile private var areas: List<DArea> = emptyList()
    @Volatile private var areasAt = 0L

    override fun onCreate(): Boolean = true

    // ─── roots ──────────────────────────────────────────────────────

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: ROOT_COLUMNS)
        c.setNotificationUri(requireContext().contentResolver, DocumentsContract.buildRootsUri(authority))
        watch()
        if (!available()) return c
        val host = runCatching { Uri.parse(deps.setupRepository().getServerUrl()).host }.getOrNull()
        c.newRow()
            .add(Root.COLUMN_ROOT_ID, DriveDocs.ROOT)
            .add(Root.COLUMN_DOCUMENT_ID, DriveDocs.ROOT)
            .add(Root.COLUMN_TITLE, requireContext().getString(R.string.drive_files_root))
            .add(Root.COLUMN_SUMMARY, host)
            .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_SEARCH)
        return c
    }

    private fun available(): Boolean = deps.setupRepository().isConfigured() && deps.pluginDirectory().state.value.drive

    /** Once: the root comes and goes with the plugin and the login; uploads in the app refresh their folder. */
    private fun watch() {
        if (!watching.compareAndSet(false, true)) return
        scope.launch {
            combine(deps.pluginDirectory().state.map { it.drive }, deps.setupRepository().revision) { drive, rev -> drive to rev }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    forget()
                    notify(DocumentsContract.buildRootsUri(authority))
                }
        }
        scope.launch { transfers.changed.collect { stale(DriveDocs.node(it)) } }
        // Drive 1.6: changes on the server (another device, the portal) — the folders of those areas load again.
        scope.launch { deps.driveLive().changed.collect(::changedOnServer) }
    }

    private val changedAreas = HashSet<String>()
    private var changedAll = false
    @Volatile private var flushing = false

    /** Collects `changed` events for a moment, then tells the Files app which listings are out of date. */
    private fun changedOnServer(e: DChanged) {
        synchronized(changedAreas) {
            if (e.areas.isEmpty()) changedAll = true else changedAreas += e.areas
            if (flushing) return
            flushing = true
        }
        scope.launch {
            delay(CHANGED_DELAY_MS)
            val (all, areas) = synchronized(changedAreas) {
                flushing = false
                (changedAll to changedAreas.toSet()).also {
                    changedAll = false
                    changedAreas.clear()
                }
            }
            for (key in listings.keys.toList()) {
                val id = DriveDocs.parse(key) as? DriveDocs.Id.Node ?: continue
                val area = nodes[id.id]?.areaId
                if (all || area == null || area in areas) stale(key)
            }
        }
    }

    private fun forget() {
        listings.clear()
        nodes.clear()
        docs.clear()
        pendings.clear()
        areas = emptyList()
        areasAt = 0L
    }

    // ─── documents ──────────────────────────────────────────────────

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: DOC_COLUMNS)
        row(c, docFor(documentId))
        return c
    }

    private fun docFor(documentId: String): Doc = when (val id = DriveDocs.parse(documentId)) {
        DriveDocs.Id.Root -> Doc(DriveDocs.ROOT, requireContext().getString(R.string.drive_files_root), DriveDocs.DIR, null, null, 0)
        is DriveDocs.Id.Pending -> {
            val p = pendings[id.seq] ?: throw FileNotFoundException(documentId)
            p.nodeId?.let { docFor(DriveDocs.node(it)).copy(id = documentId) }
                ?: Doc(documentId, p.name, p.mime, 0L, System.currentTimeMillis(), Document.FLAG_SUPPORTS_WRITE)
        }
        is DriveDocs.Id.Node -> docs[documentId] ?: run {
            val node = blocking(null) { nodeFor(id.id) }
            val all = blocking(null) { areasNow() }
            DriveDocs.doc(node, all.firstOrNull { it.id == node.areaId }, ::mimeOf).also { docs[documentId] = it }
        }
        null -> throw FileNotFoundException(documentId)
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val c = MatrixCursor(projection ?: DOC_COLUMNS)
        c.setNotificationUri(requireContext().contentResolver, DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId))
        watch()
        val listing = listings[parentDocumentId]
        listing?.docs?.forEach { row(c, it) }
        val fresh = listing != null && SystemClock.elapsedRealtime() - listing.at < FRESH_MS
        if (!fresh) load(parentDocumentId)
        c.extras = Bundle().apply {
            if (!fresh) putBoolean(DocumentsContract.EXTRA_LOADING, true)
            if (fresh && listing.docs == null && listing.error != null) putString(DocumentsContract.EXTRA_ERROR, listing.error)
        }
        return c
    }

    /** Loads a folder in the background; the Files app asks again when it is told. */
    private fun load(documentId: String) {
        if (!loading.add(documentId)) return
        scope.launch {
            try {
                listings[documentId] = children(documentId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d("Drive files: listing failed (%s)", e.javaClass.simpleName)
                listings[documentId] = Listing(null, text("failed"), SystemClock.elapsedRealtime())
            } finally {
                loading.remove(documentId)
                notify(DocumentsContract.buildChildDocumentsUri(authority, documentId))
            }
        }
    }

    private suspend fun children(documentId: String): Listing {
        val now = { SystemClock.elapsedRealtime() }
        return when (val id = DriveDocs.parse(documentId)) {
            DriveDocs.Id.Root -> when (val r = api.areas()) {
                is DResult.Ok -> {
                    val sorted = DriveLogic.sortAreas(r.value)
                    areas = sorted
                    areasAt = now()
                    Listing(sorted.map(DriveDocs::doc).onEach { docs[it.id] = it }, null, now())
                }
                else -> Listing(null, text(code(r)), now())
            }
            is DriveDocs.Id.Node -> {
                val items = ArrayList<DNode>()
                var offset = 0
                var folder: DFolder? = null
                for (page in 0 until MAX_PAGES) {
                    val r = api.folder(id.id, offset = offset, limit = PAGE)
                    if (r !is DResult.Ok) return Listing(null, text(code(r)), now())
                    folder = r.value
                    items += r.value.items
                    offset = r.value.next?.takeIf { it > offset } ?: break
                }
                val f = folder ?: return Listing(null, text("failed"), now())
                nodes[f.node.id] = f.node
                val area = (areasNow() as? DResult.Ok)?.value?.firstOrNull { it.id == f.node.areaId }
                val rows = items.map { n ->
                    nodes[n.id] = n
                    DriveDocs.doc(n, area, ::mimeOf, writable = f.writable).also { docs[it.id] = it }
                }
                Listing(DriveDocs.sorted(rows), null, now())
            }
            else -> Listing(emptyList(), null, now())
        }
    }

    override fun querySearchDocuments(rootId: String, query: String, projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: DOC_COLUMNS)
        val q = query.trim().take(100)
        if (q.length < 2) return c
        val found = runBlocking(Dispatchers.IO) {
            val all = (areasNow() as? DResult.Ok)?.value.orEmpty()
            when (val r = api.search(q)) {
                is DResult.Ok -> r.value.map { n ->
                    nodes[n.id] = n
                    DriveDocs.doc(n, all.firstOrNull { it.id == n.areaId }, ::mimeOf)
                }
                else -> null.also { c.extras = Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, text(code(r))) } }
            }
        }
        found?.forEach { row(c, it) }
        return c
    }

    // ─── open / write ───────────────────────────────────────────────

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val bits = ParcelFileDescriptor.parseMode(mode)
        val write = mode.contains('w') || mode.contains('a')
        return when (val id = DriveDocs.parse(documentId)) {
            is DriveDocs.Id.Node -> {
                // The current revision (another device may have changed it); the cached one only offline.
                val node = blocking(signal) { fresh(id.id) }
                if (node.isDir) throw FileNotFoundException("folder")
                if (!write) return ParcelFileDescriptor.open(blocking(signal) { transfers.fetch(node) }, ParcelFileDescriptor.MODE_READ_ONLY)
                val parent = node.parentId ?: throw FileNotFoundException("no folder")
                val temp = tempFile()
                if (bits and ParcelFileDescriptor.MODE_TRUNCATE == 0) blocking(signal) { transfers.fetch(node) }.copyTo(temp, overwrite = true)
                writable(temp, bits) {
                    transfers.uploadWritten(it, node.name, mimeOfNode(node), parent, DriveLogic.ON_CONFLICT_VERSION) {
                        nodes.remove(node.id)
                        docs.remove(documentId)
                        stale(DriveDocs.node(parent))
                    }
                }
            }
            is DriveDocs.Id.Pending -> {
                val p = pendings[id.seq] ?: throw FileNotFoundException(documentId)
                // Written before: from now on it is that file.
                p.nodeId?.let { return openDocument(DriveDocs.node(it), mode, signal) }
                if (!write) throw FileNotFoundException("not written yet")
                // A new file may stay empty: Drive 1.5+ keeps it (feature `emptyFiles`), else it is dropped as before.
                writable(tempFile(), bits, keepEmpty = true) {
                    transfers.uploadWritten(it, p.name, p.mime, p.parentId, DriveLogic.ON_CONFLICT_RENAME, dropEmpty = true) { done ->
                        if (done != null) {
                            p.nodeId = done.id
                            nodes[done.id] = done
                        }
                        stale(DriveDocs.node(p.parentId))
                    }
                }
            }
            else -> throw FileNotFoundException(documentId)
        }
    }

    /**
     * [temp] opened for the caller; when it is closed without error and not empty ([keepEmpty]: also empty), [upload]
     * takes it over.
     */
    private fun writable(temp: File, bits: Int, keepEmpty: Boolean = false, upload: (File) -> Unit): ParcelFileDescriptor =
        ParcelFileDescriptor.open(temp, bits, handler) { e ->
            if (e != null || (temp.length() == 0L && !keepEmpty)) {
                temp.delete()
            } else {
                upload(temp)
            }
        }

    private fun tempFile(): File {
        val dir = File(requireContext().cacheDir, "drive-up").apply { mkdirs() }
        val old = System.currentTimeMillis() - 24 * 3_600_000L
        dir.listFiles { f -> f.name.startsWith("w-") && f.lastModified() < old }?.forEach { it.delete() }
        return File(dir, "w-${UUID.randomUUID()}.part")
    }

    // ─── create / rename / move / delete ────────────────────────────

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = DriveDocs.parse(parentDocumentId) as? DriveDocs.Id.Node ?: throw FileNotFoundException(parentDocumentId)
        val name = DriveDocs.createName(displayName, mimeType, ::extOf, ::mimeOf) ?: throw IllegalArgumentException("name")
        if (mimeType == DriveDocs.DIR) {
            val made = blocking(null) { api.createFolder(parent.id, name) }
            nodes[made.id] = made
            stale(parentDocumentId)
            return DriveDocs.node(made.id)
        }
        val s = seq.incrementAndGet()
        pendings[s] = Pending(parent.id, name, mimeType)
        return DriveDocs.pending(s)
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        val id = DriveDocs.parse(documentId) as? DriveDocs.Id.Node ?: throw FileNotFoundException(documentId)
        val name = DriveLogic.safeName(displayName) ?: throw IllegalArgumentException("name")
        val renamed = blocking(null) { api.rename(id.id, name) }
        nodes[id.id] = renamed
        docs.remove(documentId)
        renamed.parentId?.let { stale(DriveDocs.node(it)) }
        return null
    }

    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String {
        val id = DriveDocs.parse(sourceDocumentId) as? DriveDocs.Id.Node ?: throw FileNotFoundException(sourceDocumentId)
        val target = DriveDocs.parse(targetParentDocumentId) as? DriveDocs.Id.Node ?: throw FileNotFoundException(targetParentDocumentId)
        val moved = blocking(null) { api.move(id.id, target.id) }
        nodes[id.id] = moved
        docs.remove(sourceDocumentId)
        stale(sourceParentDocumentId)
        stale(targetParentDocumentId)
        return sourceDocumentId
    }

    /** Into the trash of the area (restorable in the app). */
    override fun deleteDocument(documentId: String) {
        when (val id = DriveDocs.parse(documentId)) {
            is DriveDocs.Id.Pending -> pendings.remove(id.seq)?.nodeId?.let { deleteDocument(DriveDocs.node(it)) }
            is DriveDocs.Id.Node -> {
                val parent = nodes[id.id]?.parentId
                blocking(null) { api.delete(id.id) }
                nodes.remove(id.id)
                docs.remove(documentId)
                listings.remove(documentId)
                parent?.let { stale(DriveDocs.node(it)) }
                revokeDocumentPermission(documentId)
            }
            else -> throw FileNotFoundException(documentId)
        }
    }

    // ─── helpers ────────────────────────────────────────────────────

    private suspend fun nodeFor(id: Long): DResult<DNode> = nodes[id]?.let { DResult.Ok(it) }
        ?: api.node(id).also { if (it is DResult.Ok) nodes[id] = it.value }

    private suspend fun fresh(id: Long): DResult<DNode> = when (val r = api.node(id)) {
        is DResult.Ok -> r.also { nodes[id] = r.value }
        DResult.Offline -> nodes[id]?.let { DResult.Ok(it) } ?: r
        else -> r
    }

    /** The areas, at most a minute old. */
    private suspend fun areasNow(): DResult<List<DArea>> {
        if (areas.isNotEmpty() && SystemClock.elapsedRealtime() - areasAt < AREAS_MS) return DResult.Ok(areas)
        return api.areas().also {
            if (it is DResult.Ok) {
                areas = DriveLogic.sortAreas(it.value)
                areasAt = SystemClock.elapsedRealtime()
            }
        }
    }

    /** Runs [block] for a binder call; [signal] cancels it. Anything but Ok is a FileNotFoundException. */
    private fun <T> blocking(signal: CancellationSignal?, block: suspend () -> DResult<T>): T {
        val r = runBlocking(Dispatchers.IO) {
            val job = async { block() }
            signal?.setOnCancelListener { job.cancel() }
            try {
                job.await()
            } catch (e: CancellationException) {
                throw OperationCanceledException()
            }
        }
        return (r as? DResult.Ok)?.value ?: throw FileNotFoundException(text(code(r)))
    }

    /** The folder's listing is out of date: load it again when the Files app shows it. */
    private fun stale(documentId: String) {
        listings[documentId]?.let { listings[documentId] = Listing(it.docs, it.error, 0L) }
        notify(DocumentsContract.buildChildDocumentsUri(authority, documentId))
    }

    private fun notify(uri: Uri) = runCatching { requireContext().contentResolver.notifyChange(uri, null) }

    private fun row(c: MatrixCursor, d: Doc) {
        c.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, d.id)
            .add(Document.COLUMN_DISPLAY_NAME, d.name)
            .add(Document.COLUMN_MIME_TYPE, d.mime)
            .add(Document.COLUMN_SIZE, d.size)
            .add(Document.COLUMN_LAST_MODIFIED, d.modified)
            .add(Document.COLUMN_FLAGS, d.flags)
    }

    private fun code(r: DResult<*>): String = when (r) {
        is DResult.Failed -> r.code
        DResult.Offline -> "offline"
        DResult.NotConfigured -> "not_configured"
        is DResult.Ok -> "failed"
    }

    private fun text(code: String): String = driveText(requireContext(), code)

    private fun mimeOfNode(node: DNode): String = DriveDocs.mime(node.mime, node.name, ::mimeOf)

    private fun mimeOf(ext: String): String? = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)

    private fun extOf(mime: String): String? = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)

    companion object {
        fun authority(packageName: String) = "$packageName.drive.documents"

        /** A listing this young is shown without asking the server again. */
        private const val FRESH_MS = 15_000L
        private const val AREAS_MS = 60_000L
        /** `changed` events that come within this time are one reload. */
        private const val CHANGED_DELAY_MS = 1_000L
        private const val PAGE = 500
        private const val MAX_PAGES = 20

        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_ICON, Root.COLUMN_FLAGS,
        )
        private val DOC_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )
    }
}
