package com.gatecontrol.android.gatedrop

import android.content.Context
import androidx.core.content.edit
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.familie.PluginDirectory
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A received file as this device knows it: [uri] once saved in Downloads/GateControl. */
data class GdReceivedFile(
    val name: String,
    val mime: String,
    val bytes: Long,
    val mediaId: String? = null,
    val uri: String? = null,
)

/** Something received on this device (direct or over the server). */
data class GdReceived(
    /** `s:<sendId>` or `t:<transferId>`. */
    val key: String,
    val sendId: String?,
    val from: String?,
    val at: Long,
    val text: String? = null,
    val link: String? = null,
    val note: String? = null,
    val files: List<GdReceivedFile> = emptyList(),
)

/** A file waiting to be sent (copied into the app's cache). */
data class GdJobFile(val path: String, val name: String, val mime: String, val size: Long)

/** One send waiting for (or running in) [GateDropSendWorker]. */
data class GdJob(
    val id: String,
    val files: List<GdJobFile>,
    val text: String?,
    val link: String?,
    val note: String?,
    val to: GdRecipients,
    /** Ids of the chosen devices that were direct-capable when sending. */
    val directDevices: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    /** Media ids of the files uploaded so far (in file order). */
    val uploaded: List<String> = emptyList(),
    /** `POST sends` went through: the send's id. */
    val sendId: String? = null,
    /** A direct transfer failed: the files still have to reach the server (`POST sends/<id>/items`). */
    val fallback: Boolean = false,
    /** Item indexes the server still needs after a failed direct transfer (`missing`). */
    val missing: List<Int> = emptyList(),
)

/**
 * GateDrop's state on this device: what was received (and where it was
 * saved), and the sends waiting for the worker. Small JSON in private
 * preferences/files of this login, tree-built (no reflection). Pure
 * (de)serialisation in [GateDropStoreJson], unit-tested.
 */
@Singleton
class GateDropStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val setupRepository: SetupRepository,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()
    private val _received = MutableStateFlow(read())
    val received: StateFlow<List<GdReceived>> = _received.asStateFlow()

    private fun owner(): String = PluginDirectory.ownerKey(setupRepository.getServerUrl(), setupRepository.getApiToken())

    private fun read(): List<GdReceived> {
        if (prefs.getString(KEY_OWNER, null) != owner()) return emptyList()
        return GateDropStoreJson.receivedList(prefs.getString(KEY_RECEIVED, null))
    }

    /** Another login: start empty. */
    fun reload() {
        synchronized(lock) {
            _received.value = read()
            handled = readHandled()
        }
    }

    // ─── handled sends (dedupe of `pending` and pushes) ─────────────

    /** Ids of the sends received over the server and dealt with, oldest first (at most [GateDropLogic.HANDLED_MAX]). */
    @Volatile private var handled: List<String> = readHandled()

    private fun readHandled(): List<String> {
        if (prefs.getString(KEY_HANDLED_OWNER, null) != owner()) return emptyList()
        return GateDropStoreJson.handledList(prefs.getString(KEY_HANDLED, null))
    }

    /** The send was processed already (notified, saved or waiting for "Speichern"). */
    fun isHandled(sendId: String): Boolean = sendId in handled

    fun markHandled(sendId: String) = synchronized(lock) {
        if (sendId in handled) return@synchronized
        val next = GateDropLogic.remember(handled, sendId)
        prefs.edit {
            putString(KEY_HANDLED_OWNER, owner())
            putString(KEY_HANDLED, GateDropStoreJson.handledJson(next))
        }
        handled = next
    }

    fun get(key: String): GdReceived? = _received.value.firstOrNull { it.key == key }

    fun bySend(sendId: String): GdReceived? = _received.value.firstOrNull { it.sendId == sendId }

    /** Adds or replaces [entry] (newest first, at most [MAX_RECEIVED]). */
    fun upsert(entry: GdReceived) = synchronized(lock) {
        val next = (listOf(entry) + _received.value.filter { it.key != entry.key }).take(MAX_RECEIVED)
        write(next)
    }

    /** File [index] of [key] is saved at [uri]. */
    fun markSaved(key: String, index: Int, uri: String) = synchronized(lock) {
        val next = _received.value.map { e ->
            if (e.key != key) e else e.copy(files = e.files.mapIndexed { i, f -> if (i == index) f.copy(uri = uri) else f })
        }
        write(next)
    }

    private fun write(list: List<GdReceived>) {
        prefs.edit {
            putString(KEY_OWNER, owner())
            putString(KEY_RECEIVED, GateDropStoreJson.receivedJson(list))
        }
        _received.value = list
    }

    // ─── send jobs ──────────────────────────────────────────────────

    private val jobsDir: File get() = File(context.filesDir, "gatedrop/jobs").apply { mkdirs() }

    /** Folder for the copies of the files of [jobId]. */
    fun jobFilesDir(jobId: String): File = File(context.cacheDir, "$OUT_DIR/$jobId").apply { mkdirs() }

    fun saveJob(job: GdJob) {
        File(jobsDir, "${job.id}.json").writeText(GateDropStoreJson.jobJson(job).toString())
    }

    fun loadJob(id: String): GdJob? {
        if (!JOB_ID_RE.matches(id)) return null
        return runCatching { GateDropStoreJson.job(JsonParser.parseString(File(jobsDir, "$id.json").readText()).asJsonObject) }.getOrNull()
    }

    /** The job is finished (sent or given up): its files go. */
    fun dropJob(id: String) {
        if (!JOB_ID_RE.matches(id)) return
        File(jobsDir, "$id.json").delete()
        File(context.cacheDir, "$OUT_DIR/$id").deleteRecursively()
    }

    /** Left-overs older than two days (a job that never ran). */
    fun prune() {
        val cutoff = System.currentTimeMillis() - 2 * 24 * 3600_000L
        jobsDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { f ->
            dropJob(f.nameWithoutExtension)
        }
        File(context.cacheDir, OUT_DIR).listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
    }

    companion object {
        private const val PREFS = "gatedrop"
        private const val KEY_OWNER = "owner"
        private const val KEY_RECEIVED = "received"
        private const val KEY_HANDLED = "handled"
        private const val KEY_HANDLED_OWNER = "handled_owner"
        const val OUT_DIR = "gatedrop-out"
        const val MAX_RECEIVED = 100
        val JOB_ID_RE = Regex("^[A-Za-z0-9_-]{8,64}$")
    }
}

/** (De)serialisation of [GateDropStore]'s data. Pure, unit-tested. */
object GateDropStoreJson {
    private fun JsonObject.s(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.l(key: String): Long? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
    private fun JsonElement.o(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject

    fun receivedJson(list: List<GdReceived>): String = JsonArray().apply {
        list.forEach { e ->
            add(JsonObject().apply {
                addProperty("key", e.key)
                e.sendId?.let { addProperty("sendId", it) }
                e.from?.let { addProperty("from", it) }
                addProperty("at", e.at)
                e.text?.let { addProperty("text", it) }
                e.link?.let { addProperty("link", it) }
                e.note?.let { addProperty("note", it) }
                add("files", JsonArray().apply {
                    e.files.forEach { f ->
                        add(JsonObject().apply {
                            addProperty("name", f.name)
                            addProperty("mime", f.mime)
                            addProperty("bytes", f.bytes)
                            f.mediaId?.let { addProperty("mediaId", it) }
                            f.uri?.let { addProperty("uri", it) }
                        })
                    }
                })
            })
        }
    }.toString()

    fun receivedList(json: String?): List<GdReceived> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = runCatching { JsonParser.parseString(json).asJsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el.o() ?: return@mapNotNull null
            GdReceived(
                key = o.s("key") ?: return@mapNotNull null,
                sendId = o.s("sendId"),
                from = o.s("from"),
                at = o.l("at") ?: 0L,
                text = o.s("text"),
                link = o.s("link"),
                note = o.s("note"),
                files = o.get("files")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { fe ->
                    val f = fe.o() ?: return@mapNotNull null
                    GdReceivedFile(
                        name = f.s("name") ?: return@mapNotNull null,
                        mime = f.s("mime") ?: GateDropJson.DEFAULT_MIME,
                        bytes = f.l("bytes") ?: 0L,
                        mediaId = f.s("mediaId"),
                        uri = f.s("uri"),
                    )
                }.orEmpty(),
            )
        }
    }

    fun handledJson(ids: List<String>): String = JsonArray().apply { ids.forEach { add(it) } }.toString()

    /** The remembered send ids; malformed entries dropped, at most [GateDropLogic.HANDLED_MAX] (the newest). */
    fun handledList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = runCatching { JsonParser.parseString(json).asJsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { GateDropJson.idOf(it) }.distinct().takeLast(GateDropLogic.HANDLED_MAX)
    }

    fun jobJson(job: GdJob): JsonObject = JsonObject().apply {
        addProperty("id", job.id)
        add("files", JsonArray().apply {
            job.files.forEach { f ->
                add(JsonObject().apply {
                    addProperty("path", f.path)
                    addProperty("name", f.name)
                    addProperty("mime", f.mime)
                    addProperty("size", f.size)
                })
            }
        })
        job.text?.let { addProperty("text", it) }
        job.link?.let { addProperty("link", it) }
        job.note?.let { addProperty("note", it) }
        add("to", GateDropJson.sendBody(emptyList(), null, null, null, job.to).get("to"))
        add("directDevices", JsonArray().apply { job.directDevices.forEach { add(it) } })
        addProperty("createdAt", job.createdAt)
        add("uploaded", JsonArray().apply { job.uploaded.forEach { add(it) } })
        job.sendId?.let { addProperty("sendId", it) }
        addProperty("fallback", job.fallback)
        add("missing", JsonArray().apply { job.missing.forEach { add(it) } })
    }

    fun job(o: JsonObject): GdJob? {
        val to = o.get("to")?.o() ?: return null
        fun ids(key: String) = to.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { GateDropJson.idOf(it) }.orEmpty()
        val link = to.get("link")?.o()?.let { l -> GdExpiry.fromWire(l.s("expires"))?.let { GdLinkRequest(it, l.get("pin")?.asBoolean == true) } }
        return GdJob(
            id = o.s("id") ?: return null,
            files = o.get("files")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { fe ->
                val f = fe.o() ?: return@mapNotNull null
                GdJobFile(f.s("path") ?: return@mapNotNull null, f.s("name") ?: return@mapNotNull null, f.s("mime") ?: GateDropJson.DEFAULT_MIME, f.l("size") ?: 0L)
            }.orEmpty(),
            text = o.s("text"),
            link = o.s("link"),
            note = o.s("note"),
            to = GdRecipients(
                people = ids("people"),
                devices = ids("devices"),
                nas = to.get("nas")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { ne ->
                    val n = ne.o() ?: return@mapNotNull null
                    GdNasRef(GateDropJson.idOf(n.get("id")) ?: return@mapNotNull null, n.s("path"))
                }.orEmpty(),
                link = link,
                drive = to.get("drive")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true,
            ),
            directDevices = o.get("directDevices")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { GateDropJson.idOf(it) }.orEmpty(),
            createdAt = o.l("createdAt") ?: 0L,
            uploaded = o.get("uploaded")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { GateDropJson.mediaId(it.takeIf { e -> e.isJsonPrimitive }?.asString) }.orEmpty(),
            sendId = GateDropJson.idOf(o.get("sendId")),
            fallback = o.get("fallback")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true,
            missing = o.get("missing")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt }.orEmpty(),
        )
    }
}
