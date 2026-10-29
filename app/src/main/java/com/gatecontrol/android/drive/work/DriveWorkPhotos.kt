package com.gatecontrol.android.drive.work

import android.net.Uri
import com.gatecontrol.android.drive.DArea
import com.gatecontrol.android.drive.DFolder
import com.gatecontrol.android.drive.DMe
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DPhotoSettings
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DReview
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveLive
import com.gatecontrol.android.drive.DriveOps
import com.gatecontrol.android.drive.backup.DriveCameraUploads
import com.gatecontrol.android.drive.work.DriveWorkLogic.Dest
import com.gatecontrol.android.support.CrashReports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Instant
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Arbeitsfotos getrennt sichern" (Drive 1.13, feature `photoClasses`; without it the same on this phone only): the work
 * folder and its settings, where a new photo goes ([placeNew], for the photo backup), the review mark, "Arbeit" /
 * "Privat" / "Zum Prüfen markieren" for photos in Drive ([assign], with [undo]), what the AI learns from (all on the
 * phone: [DriveWorkLabeler], [DriveWorkModel]) and the suggestions of "Aufräumen" ([suggest]).
 */
@Singleton
class DriveWorkPhotos @Inject constructor(
    private val api: DriveApi,
    private val live: DriveLive,
    private val store: DriveWorkStore,
    private val labeler: DriveWorkLabeler,
    private val camera: DriveCameraUploads,
    private val ops: DriveOps,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)

    val settings: StateFlow<WorkSettings> = store.settings
    val learned: StateFlow<DriveWorkLogic.Learned> get() = store.learned
    val localReview: StateFlow<Map<Long, Int?>> = store.localReview

    /** Drive 1.13 keeps the settings and the marks; without it this phone does. */
    fun serverClasses(me: DMe? = live.me.value): Boolean = me?.has(DMe.FEATURE_PHOTO_CLASSES) == true

    // ─── settings ───────────────────────────────────────────────────

    /** The person's settings from Drive 1.13 (nothing without it); a code when they could not be read. */
    suspend fun refreshSettings(): String? {
        if (!live.supports(DMe.FEATURE_PHOTO_CLASSES)) return null
        return when (val r = api.photoSettings()) {
            is DResult.Ok -> null.also { took(r.value) }
            else -> code(r)
        }
    }

    private suspend fun took(p: DPhotoSettings) {
        val areas = live.current()?.areas.orEmpty()
        store.update {
            it.copy(
                workFolder = p.workFolder,
                folderLabel = p.workFolder?.let { _ -> label(p, areas) ?: it.folderLabel },
                subfolders = p.workSubfolders,
                inMemories = p.workInMemories,
            )
        }
    }

    private fun label(p: DPhotoSettings, areas: List<DArea>): String? {
        val area = areas.firstOrNull { it.id == p.folderAreaId }?.label ?: return null
        val parts = p.folderPath.orEmpty().split('/').filter { it.isNotBlank() }
        return (listOf(area) + parts).joinToString(" › ")
    }

    /** "Arbeitsfotos nach": [folderId] (shown as [label]); null when it was taken, else a code (`invalid:<reason>`). */
    suspend fun chooseWorkFolder(folderId: Long, label: String): String? {
        if (live.supports(DMe.FEATURE_PHOTO_CLASSES)) {
            when (val r = api.putPhotoSettings(workFolder = folderId)) {
                is DResult.Ok -> took(r.value)
                is DResult.Failed -> return r.body?.get("reason")?.takeIf { it.isJsonPrimitive }?.asString?.let { "work_folder:$it" } ?: r.code
                else -> return code(r)
            }
        }
        store.update { it.copy(workFolder = folderId, folderLabel = label) }
        parents.clear()
        return null
    }

    suspend fun clearWorkFolder(): String? {
        if (live.supports(DMe.FEATURE_PHOTO_CLASSES)) {
            val r = api.putPhotoSettings(clearFolder = true)
            if (r !is DResult.Ok) return code(r)
        }
        store.update { it.copy(workFolder = null, folderLabel = null) }
        parents.clear()
        return null
    }

    suspend fun setSubfolders(on: Boolean): String? {
        if (live.supports(DMe.FEATURE_PHOTO_CLASSES)) {
            val r = api.putPhotoSettings(workSubfolders = on)
            if (r !is DResult.Ok) return code(r)
        }
        store.update { it.copy(subfolders = on) }
        return null
    }

    /** "Arbeitsfotos nie in Erinnerungen und Fotorahmen" ([on] = they may show): only Drive 1.13 can. */
    suspend fun setInMemories(on: Boolean): String? {
        val r = api.putPhotoSettings(workInMemories = on)
        if (r !is DResult.Ok) return code(r)
        store.update { it.copy(inMemories = on) }
        return null
    }

    fun setAutoSort(on: Boolean) = store.update { it.copy(autoSort = on) }
    fun setAskNewWifi(on: Boolean) = store.update { it.copy(askNewWifi = on) }
    fun setRemoveAfter(on: Boolean) = store.update { it.copy(removeAfter = on) }
    fun setZone(ssid: String, zone: WifiZone?) = store.setZone(ssid, zone)

    /** "Neu lernen". */
    fun resetLearning() {
        store.resetLearning()
        synchronized(this) { modelOf = null; model = null }
    }

    // ─── the AI ─────────────────────────────────────────────────────

    private var model: DriveWorkModel? = null
    private var modelOf: List<WorkExample>? = null

    /** The model of the examples now (trained again when they changed); null until there are a few of both kinds. */
    private suspend fun model(): DriveWorkModel? = withContext(Dispatchers.Default) {
        val ex = store.examples()
        synchronized(this@DriveWorkPhotos) {
            if (ex !== modelOf) {
                modelOf = ex
                model = if (DriveWorkFeatures.learned(ex).usable) DriveWorkModel.train(ex) else null
            }
            model
        }
    }

    /** The features of a photo in Drive (labeled once, then kept). */
    private suspend fun featuresOf(node: DNode): Map<Int, Float>? {
        val key = key(node)
        store.features(key)?.let { return it }
        val labels = labeler.ofNode(node) ?: return null
        return DriveWorkFeatures.of(labels, takenMs(node)).also { store.putFeatures(key, it) }
    }

    /** The AI's probability "work" of a photo in Drive; null while it cannot tell. */
    suspend fun score(node: DNode): Double? {
        val m = model() ?: return null
        return featuresOf(node)?.let { m.predict(it) }
    }

    /** One example per photo in Drive. */
    private fun key(node: DNode) = "n:${node.id}"

    /** Keys of an assignment taken back while it was still being learned. */
    private val undone = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun learnLater(nodes: List<DNode>, work: Boolean, source: WorkExample.Source) {
        val images = nodes.filter { isImage(it) }
        if (images.isEmpty()) return
        images.forEach { undone.remove(key(it)) }
        scope.launch {
            for (n in images) {
                val f = runCatching { featuresOf(n) }.getOrNull() ?: continue
                if (undone.remove(key(n))) continue
                store.learn(WorkExample(key(n), work, source, f, System.currentTimeMillis()))
            }
        }
    }

    private fun isImage(n: DNode) = !n.isDir && !n.encrypted && n.mime?.startsWith("image/") == true

    // ─── the photo backup ───────────────────────────────────────────

    /** Where a new photo goes, and what was found to get there ([features] of the photo, the WLAN [ssid]). */
    data class NewPhoto(val placement: DriveWorkLogic.Placement, val features: Map<Int, Float>?, val ssid: String?)

    /**
     * Where the photo [uri] taken at [takenMs] goes; null while no work folder is chosen (all to Kamera-Uploads). The
     * WLAN at the time it was taken first; else the AI (labels of the phone's thumbnail).
     */
    suspend fun placeNew(uri: Uri, takenMs: Long?, video: Boolean): NewPhoto? {
        val s = store.settings.value
        if (!s.active) return null
        val ssid = takenMs?.takeIf { it > 0 }?.let { store.wifiAt(it) }
        val zone = s.zones.zoneOf(ssid)
        val features = if (video) null else runCatching { labeler.ofUri(uri) }.getOrNull()?.let { DriveWorkFeatures.of(it, takenMs) }
        val needAi = zone == null || zone == WifiZone.NEITHER
        val score = if (needAi && features != null) model()?.predict(features) else null
        val learned = store.examples().let { DriveWorkFeatures.learned(it) }
        return NewPhoto(DriveWorkLogic.place(zone, score, learned.ready, s.autoSort), features, ssid)
    }

    /** The folder of a work photo: the work folder, by year and month when set ([path] from the backup). */
    suspend fun workFolderFor(path: List<String>): DResult<Long> {
        val s = store.settings.value
        val base = s.workFolder ?: return DResult.Failed("no_folder", 0)
        return camera.under(base, if (s.subfolders) path else emptyList())
    }

    /**
     * After the backup put [node] where [p] said (the photo [mediaStoreId] on the phone): what it teaches the AI, the
     * review mark, and — set so — the work photo waits to go from the phone.
     */
    suspend fun afterBackup(node: DNode?, p: NewPhoto, mediaStoreId: Long, video: Boolean) {
        if (node != null) {
            p.features?.let { f ->
                store.putFeatures(key(node), f)
                p.placement.learn?.let { work -> store.learn(WorkExample(key(node), work, WorkExample.Source.WIFI, f, System.currentTimeMillis())) }
            }
            if (p.placement.review) mark(listOf(node.id), true, p.placement.hint)
        }
        if (p.placement.dest == Dest.WORK && store.settings.value.removeAfter) store.addRemoval(mediaStoreId, video)
    }

    /** Sets or clears the review mark: on the server with Drive 1.13 (else, or when it refuses, on this phone). */
    private suspend fun mark(ids: List<Long>, review: Boolean, hint: Int?): Boolean {
        if (ids.isEmpty()) return true
        if (live.supports(DMe.FEATURE_PHOTO_CLASSES)) {
            val r = api.photoReview(ids, review, hint)
            if (r is DResult.Ok) {
                if (!review) store.setLocalReview(ids, false, null)
                return true
            }
            Timber.i("Drive work: review mark not set (%s)", code(r))
            if (review) return false
        }
        store.setLocalReview(ids, review, hint)
        return true
    }

    // ─── assigning photos in Drive ──────────────────────────────────

    /** What an assignment did, to take it back: where each moved photo was, the marks before, what was learned. */
    data class Undo(
        val back: List<Pair<Long, Long>>,
        val marks: Map<Long, DReview?>,
        val learned: List<String>,
    )

    /** Of "Arbeit" / "Privat" / "Zum Prüfen markieren": [count] photos to [to]; [undo] takes it back; [error] a code. */
    data class Assigned(val to: Target, val count: Int, val undo: Undo?, val error: String? = null)

    enum class Target { WORK, PRIVATE, REVIEW }

    private val assignLock = Mutex()

    /**
     * [nodes] are work photos ([Target.WORK]: into the work folder by month, the mark gone), private ([Target.PRIVATE]:
     * out of the work folder into Kamera-Uploads by month, else where they are, the mark gone) or to be reviewed (the
     * mark, with the AI's hint). The AI learns from the first two ([source]).
     */
    suspend fun assign(nodes: List<DNode>, to: Target, source: WorkExample.Source = WorkExample.Source.ASSIGN): Assigned = assignLock.withLock {
        val list = nodes.filter { !it.isDir && !it.encrypted }.distinctBy { it.id }
        if (list.isEmpty()) return@withLock Assigned(to, 0, null, "invalid")
        val s = store.settings.value
        val local = store.localReview.value
        val marksBefore = list.associate { it.id to (it.review ?: if (it.id in local) DReview(local[it.id]) else null) }
        val back = ArrayList<Pair<Long, Long>>()
        when (to) {
            Target.WORK, Target.PRIVATE -> {
                val dest = if (to == Target.WORK) Dest.WORK else Dest.PRIVATE
                if (dest == Dest.WORK && s.workFolder == null) return@withLock Assigned(to, 0, null, "no_work_folder")
                val underWork = HashMap<Long, Boolean>()
                if (dest == Dest.PRIVATE) {
                    for (n in list) if (!n.work && marksBefore[n.id] != null) underWork[n.id] = n.parentId?.let { isUnderWork(it) } == true
                }
                val moves = DriveWorkLogic.moves(list, dest, s.subfolders) { n -> n.work || underWork[n.id] == true }
                for ((path, group) in moves) {
                    val folder = when (val f = if (dest == Dest.WORK) workFolderFor(path) else camera.folder(path)) {
                        is DResult.Ok -> f.value
                        else -> return@withLock Assigned(to, 0, Undo(back, marksBefore, emptyList()).takeIf { back.isNotEmpty() }, code(f))
                    }
                    val moved = moveAll(group, folder)
                    group.filter { it.id in moved }.forEach { n -> n.parentId?.let { back += n.id to it } }
                    if (moved.size < group.size && moved.isEmpty()) {
                        return@withLock Assigned(to, 0, Undo(back, marksBefore, emptyList()).takeIf { back.isNotEmpty() }, "move_failed")
                    }
                }
                mark(list.filter { marksBefore[it.id] != null }.map { it.id }, false, null)
                learnLater(list, work = dest == Dest.WORK, source = source)
            }
            Target.REVIEW -> {
                val hints = HashMap<Long, Int?>()
                for (n in list) hints[n.id] = runCatching { score(n) }.getOrNull()?.let { DriveWorkLogic.percent(it) } ?: marksBefore[n.id]?.hint
                for ((hint, ids) in hints.entries.groupBy({ it.value }, { it.key })) {
                    if (!mark(ids, true, hint)) return@withLock Assigned(to, 0, null, "review_failed")
                }
            }
        }
        Assigned(to, list.size, Undo(back, marksBefore, if (to == Target.REVIEW) emptyList() else list.map { key(it) }))
    }

    /** "Rückgängig": the photos back where they were, their marks as before, the examples forgotten. */
    suspend fun undo(u: Undo): String? = assignLock.withLock {
        undone.addAll(u.learned)
        store.forget(u.learned)
        var error: String? = null
        for ((parent, ids) in u.back.groupBy({ it.second }, { it.first })) {
            val moved = moveAll(ids.map { id -> stub(id) }, parent)
            if (moved.size < ids.size) error = "move_failed"
        }
        val had = u.marks.filterValues { it != null }
        for ((hint, ids) in had.entries.groupBy({ it.value?.hint }, { it.key })) mark(ids, true, hint)
        mark(u.marks.filterValues { it == null }.keys.toList(), false, null)
        error
    }

    private fun stub(id: Long) = DNode(id, "", null, "", false, 0, null, null, 0, null, null, null)

    /** Moves [nodes] into [folder] (Drive 1.12: one job; else one by one); the ids moved. */
    private suspend fun moveAll(nodes: List<DNode>, folder: Long): Set<Long> {
        val ids = nodes.map { it.id }.filter { id -> nodes.first { it.id == id }.parentId != folder }
        if (ids.isEmpty()) return nodes.map { it.id }.toSet()
        if (live.supports(DMe.FEATURE_BULK)) {
            when (val r = ops.start("move", ids, folder, "rename", nodes.firstOrNull()?.name?.takeIf { it.isNotEmpty() }, null)) {
                is DResult.Ok -> {
                    val job = ops.await(r.value.id)
                    ops.forget(r.value.id)
                    val results = job.op.results
                    if (job.op.state != "done" && (results?.done ?: 0) == 0) return nodes.filter { it.parentId == folder }.map { it.id }.toSet()
                    val failed = results?.failed?.map { it.id }?.toSet().orEmpty()
                    return (nodes.map { it.id }.toSet() - failed)
                }
                else -> Timber.i("Drive work: move job not started (%s)", code(r))
            }
        }
        val done = HashSet<Long>()
        nodes.filter { it.parentId == folder }.forEach { done += it.id }
        for (id in ids) if (api.move(id, folder) is DResult.Ok) done += id
        return done
    }

    // whether a folder lies below the work folder (by its path), kept per folder
    private val parents = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()

    private suspend fun isUnderWork(folderId: Long): Boolean {
        val work = store.settings.value.workFolder ?: return false
        if (folderId == work) return true
        parents[folderId]?.let { return it }
        val f = api.folder(folderId, limit = 1).valueOrNull() ?: return false
        return (f.crumbs.any { it.id == work }).also { parents[folderId] = it }
    }

    /** [f] is the work folder or lies below it. */
    fun isWorkFolder(f: DFolder): Boolean {
        val work = store.settings.value.workFolder ?: return false
        return f.node.id == work || f.crumbs.any { it.id == work }
    }

    /**
     * A move of the normal selection: photos into the work folder ([intoWork] true) or out of it (false) teach the AI
     * as well; null: neither.
     */
    fun learnMove(nodes: List<DNode>, intoWork: Boolean?) {
        if (intoWork == null || nodes.isEmpty() || !store.settings.value.active) return
        learnLater(nodes, intoWork, WorkExample.Source.MOVE)
        if (nodes.any { it.review != null || it.id in store.localReview.value }) scope.launch { mark(nodes.map { it.id }, false, null) }
    }

    /** A move of the normal selection from [from] (null: the photos) into [target]: into or out of the work folder. */
    fun learnMoved(nodes: List<DNode>, from: DFolder?, target: DFolder) {
        if (!store.settings.value.active) return
        if (isWorkFolder(target)) {
            learnMove(nodes.filter { !it.work }, true)
        } else {
            val fromWork = from != null && isWorkFolder(from)
            learnMove(nodes.filter { it.work || fromWork }, false)
        }
    }

    // ─── Aufräumen ──────────────────────────────────────────────────

    /**
     * The suggestions of "Aufräumen": the newest [limit] private photos (Kamera-Uploads when Drive knows them), each
     * labeled on the phone and scored; [onProgress] hears how far it got. Empty while the AI cannot tell yet.
     */
    suspend fun suggest(limit: Int = 240, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<DriveWorkLogic.Suggestion> {
        val m = model() ?: return emptyList()
        val photos = ArrayList<DNode>()
        val classes = live.supports(DMe.FEATURE_PHOTO_CLASSES)
        val cameraOnly = live.supports(DMe.FEATURE_PHOTOS_FOLDER)
        var before: String? = null
        while (photos.size < limit) {
            val page = api.photos(before = before, limit = 120, camera = cameraOnly, cls = if (classes) "private" else null).valueOrNull() ?: break
            photos += page.items.filter { isImage(it) && !it.work && it.review == null && it.id !in store.localReview.value }
            before = page.next ?: break
        }
        val list = photos.take(limit)
        val scored = ArrayList<Pair<DNode, Double>>()
        for ((i, n) in list.withIndex()) {
            onProgress(i, list.size)
            featuresOf(n)?.let { scored += n to m.predict(it) }
        }
        onProgress(list.size, list.size)
        return DriveWorkLogic.suggestions(scored).also { s -> store.setSuggested(s.count { it.preselected }) }
    }

    /** "N Fotos nach Arbeit verschieben": [chosen] are work, [left] stay private — the AI learns both. */
    suspend fun cleanup(chosen: List<DNode>, left: List<DNode>): Assigned {
        learnLater(left, work = false, source = WorkExample.Source.CLEANUP)
        val r = if (chosen.isEmpty()) Assigned(Target.WORK, 0, null) else assign(chosen, Target.WORK, WorkExample.Source.CLEANUP)
        if (r.error == null) store.setSuggested(0)
        return r
    }

    /** When "Aufräumen" last looked (ms). */
    fun suggestedAt(): Long = store.suggestedAt()

    /** How many photos it suggested then. */
    val suggested: StateFlow<Int> = store.suggested

    // ─── helpers ────────────────────────────────────────────────────

    private fun takenMs(n: DNode): Long? = (n.takenAt ?: n.mtime)?.let { iso ->
        runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull() ?: runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
    }

    private fun code(r: DResult<*>): String = when (r) {
        is DResult.Failed -> r.code
        DResult.Offline -> "offline"
        DResult.NotConfigured -> "not_configured"
        is DResult.Ok -> "invalid"
    }
}
