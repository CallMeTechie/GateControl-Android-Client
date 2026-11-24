package com.gatecontrol.android.tv.gatedrop

import android.content.Context
import androidx.core.content.edit
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.gatedrop.GATEDROP_PLUGIN
import com.gatecontrol.android.gatedrop.GateDropApi
import com.gatecontrol.android.gatedrop.GateDropJson
import com.gatecontrol.android.gatedrop.GdEntry
import com.gatecontrol.android.gatedrop.GdRecipients
import com.gatecontrol.android.gatedrop.GdResult
import com.gatecontrol.android.gatedrop.GdSendItem
import com.gatecontrol.android.notify.model.PushMessage
import org.json.JSONArray
import org.json.JSONObject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** How far the files of [sendId] have come onto the TV. */
data class TvDropProgress(val sendId: String, val done: Long, val total: Long) {
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/** Where a send to another device stands. */
sealed interface TvDropSend {
    data object Busy : TvDropSend
    data class Done(val to: String) : TvDropSend
    data class Failed(val code: String?) : TvDropSend
}

/**
 * GateDrop on the TV over the server (the plugin's app API, shared with the phone app): a send to the TV is asked
 * about (mockup 5), its files then come into the app's own storage — a TV has no gallery and no file manager — and
 * stay listed on the page GateDrop. From the TV, Drive files and received files go to the person's other devices or to
 * people. The direct way in the home network (protocol C) is not on the TV: an offer is answered at once with
 * `unsupported`, the plugin then delivers over the server.
 */
@Singleton
class TvGateDrop @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: GateDropApi,
    private val drive: DriveApi,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir: File get() = File(context.filesDir, DIR).apply { mkdirs() }
    private val index: File get() = File(dir, INDEX)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Mutex()

    private val _entries = MutableStateFlow(readIndex())

    /** What the TV keeps, newest first. */
    val entries: StateFlow<List<TvDropEntry>> = _entries.asStateFlow()

    private val _offer = MutableStateFlow<TvDropOffer?>(null)

    /** The send asked about now (one at a time; the next one after an answer). */
    val offer: StateFlow<TvDropOffer?> = _offer.asStateFlow()

    private val _waiting = MutableStateFlow<List<GdEntry>>(emptyList())

    /** Sends answered with "Später": on the page, to take later (they stay on the server until they expire). */
    val waiting: StateFlow<List<GdEntry>> = _waiting.asStateFlow()

    private val _receiving = MutableStateFlow<String?>(null)

    /** The send whose files are coming now. */
    val receiving: StateFlow<String?> = _receiving.asStateFlow()

    private val _progress = MutableStateFlow<TvDropProgress?>(null)

    /** The bytes of the send coming now (null: nothing comes). */
    val progress: StateFlow<TvDropProgress?> = _progress.asStateFlow()

    private val _available = MutableStateFlow<Boolean?>(null)

    /** GateDrop is there for this person (null: not asked yet). */
    val available: StateFlow<Boolean?> = _available.asStateFlow()

    private val asked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun handled(): Set<String> = prefs.getStringSet(KEY_HANDLED, emptySet()).orEmpty()

    private fun markHandled(sendId: String) {
        val now = (handled() + sendId).toList().takeLast(MAX_HANDLED).toSet()
        prefs.edit { putStringSet(KEY_HANDLED, now) }
    }

    // ─── pushes ─────────────────────────────────────────────────────

    private fun ours(message: PushMessage): Boolean = message.topic.startsWith("plugin:$GATEDROP_PLUGIN:")

    /**
     * A transfer offer (direct in the home network) is answered at once — the TV does not listen — so the plugin
     * delivers over the server; true: handled, not shown. A data push naming a send is fetched like a delivery.
     */
    fun consume(message: PushMessage): Boolean {
        if (!ours(message)) return false
        val data = GateDropJson.pushData(message.dataJson) ?: return false
        if (data.has("transfer")) {
            GateDropJson.transferIdOf(data)?.let { id ->
                scope.launch { runCatching { api.transferResult(id, false, error = DIRECT_UNSUPPORTED) } }
            }
            return true
        }
        if (message.isData) {
            GateDropJson.noticeSendId(data)?.let(::ask)
            return true
        }
        return false
    }

    /** A delivery push (`{ gatedrop: { send } }`): the question comes up. */
    fun onNew(message: PushMessage) {
        if (!ours(message)) return
        GateDropJson.noticeSendId(GateDropJson.pushData(message.dataJson))?.let(::ask)
    }

    private fun ask(sendId: String) {
        if (sendId in handled() || !asked.add(sendId)) return
        scope.launch {
            val send = api.sendDetail(sendId).let { (it as? GdResult.Ok)?.value } ?: run {
                asked.remove(sendId)
                return@launch
            }
            offer(send)
        }
    }

    private fun offer(send: GdEntry) {
        if (!TvDropLogic.shouldAsk(send, handled(), _entries.value)) return
        // one question at a time: the others wait on the page
        if (_offer.value == null) _offer.value = TvDropOffer(send) else _waiting.update { w -> if (w.any { it.id == send.id }) w else w + send }
    }

    /** The app came to the front: what was sent while it was away (`GET pending`). */
    fun poll() {
        scope.launch {
            when (val r = api.pending()) {
                is GdResult.Ok -> {
                    _available.value = true
                    for (send in r.value) {
                        if (asked.add(send.id)) offer(send)
                    }
                }
                is GdResult.Failed -> if (r.http == 404 || r.http == 403) _available.value = false
                else -> Unit
            }
        }
    }

    // ─── answers ────────────────────────────────────────────────────

    /** "Später": not taken now; it waits on the page (and on the server). */
    fun later() {
        val o = _offer.value ?: return
        _offer.value = null
        // asked once: it is not asked about again by itself, only taken from the page
        shownLater += o.entry.id
        _waiting.update { w -> if (w.any { it.id == o.entry.id }) w else w + o.entry }
        nextOffer()
    }

    private fun nextOffer() {
        if (_offer.value != null) return
        val done = handled()
        val next = _waiting.value.firstOrNull { it.id !in done && it.id !in shownLater } ?: return
        shownLater += next.id
        _waiting.update { w -> w.filter { it.id != next.id } }
        _offer.value = TvDropOffer(next)
    }

    private val shownLater = HashSet<String>()

    /**
     * "Annehmen": the files of [send] onto the TV, then the receipt (`opened`); the entry that was kept, or null when
     * nothing could be fetched (it stays on the server and on the page).
     */
    suspend fun accept(send: GdEntry): TvDropEntry? = lock.withLock {
        if (_offer.value?.entry?.id == send.id) _offer.value = null
        _waiting.update { w -> w.filter { it.id != send.id } }
        _receiving.value = send.id
        val total = send.items.filter(TvDropLogic::fetchable).sumOf { it.bytes }
        var before = 0L
        _progress.value = TvDropProgress(send.id, 0, total)
        try {
            val folder = File(dir, safeId(send.id)).apply { mkdirs() }
            val files = send.items.mapIndexed { i, item ->
                val path = if (TvDropLogic.fetchable(item)) {
                    val base = before
                    fetch(item.mediaId!!, File(folder, TvDropLogic.fileName(i, item.name))) { n ->
                        _progress.value = TvDropProgress(send.id, (base + n).coerceAtMost(total), total)
                    }.also { before += item.bytes }
                } else {
                    null
                }
                TvDropFile(item.name, item.mime, item.bytes, path?.relativeTo(dir)?.path)
            }
            if (send.items.isNotEmpty() && files.none { it.path != null } && send.text == null && send.link == null) return@withLock null
            val entry = TvDropEntry(send.id, send.from, send.at ?: System.currentTimeMillis(), send.text, send.link, files)
            keep(entry)
            // received: it leaves `pending` (and the other devices see it picked up)
            api.opened(send.id)
            markHandled(send.id)
            entry
        } finally {
            _receiving.value = null
            _progress.value = null
            nextOffer()
        }
    }

    private suspend fun fetch(mediaId: String, target: File, onBytes: (Long) -> Unit = {}): File? = withContext(Dispatchers.IO) {
        val response = api.download(mediaId) ?: return@withContext null
        val body = response.body()
        if (!response.isSuccessful || body == null) {
            response.errorBody()?.close()
            return@withContext null
        }
        val tmp = File(target.path + ".part")
        try {
            body.use { b -> b.byteStream().use { input -> tmp.outputStream().use { out -> copy(input, out, onBytes) } } }
            if (!tmp.renameTo(target)) throw IOException("rename")
            target
        } catch (e: CancellationException) {
            tmp.delete()
            throw e
        } catch (e: Exception) {
            Timber.d("GateDrop TV: file not received (%s)", e.javaClass.simpleName)
            tmp.delete()
            null
        }
    }

    /** [input] to [out], telling [onBytes] how much came (at most every 256 KB). */
    private fun copy(input: java.io.InputStream, out: java.io.OutputStream, onBytes: (Long) -> Unit) {
        val buf = ByteArray(64 * 1024)
        var n = 0L
        var told = 0L
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            out.write(buf, 0, r)
            n += r
            if (n - told >= 256 * 1024) {
                told = n
                onBytes(n)
            }
        }
        onBytes(n)
    }

    private suspend fun keep(entry: TvDropEntry) {
        val (kept, gone) = TvDropLogic.prune(_entries.value, entry)
        gone.forEach { removeFiles(it) }
        _entries.value = kept
        writeIndex(kept)
    }

    /** "Gesehen": opened on the TV once (the page's "NEU" goes). */
    fun seen(sendId: String) {
        val now = _entries.value.map { if (it.sendId == sendId) it.copy(seen = true) else it }
        if (now == _entries.value) return
        _entries.value = now
        scope.launch { writeIndex(now) }
    }

    /** "Löschen" on the TV: its files go (on the server it stays until it expires). */
    fun delete(sendId: String) {
        val e = _entries.value.firstOrNull { it.sendId == sendId } ?: return
        val now = _entries.value - e
        _entries.value = now
        scope.launch {
            removeFiles(e)
            writeIndex(now)
        }
    }

    /** The file [i] of [entry] on the TV; null when it was not taken. */
    fun file(entry: TvDropEntry, i: Int): File? = entry.files.getOrNull(i)?.path?.let { File(dir, it) }?.takeIf { it.isFile && it.canonicalPath.startsWith(dir.canonicalPath) }

    /** "Kopplung aufheben": everything of that account goes. */
    fun clear() {
        _entries.value = emptyList()
        _offer.value = null
        _waiting.value = emptyList()
        asked.clear()
        prefs.edit { clear() }
        scope.launch { dir.deleteRecursively() }
    }

    // ─── sending ────────────────────────────────────────────────────

    /** Where the TV can send to (own devices, people); null when GateDrop does not answer. */
    suspend fun targets(): List<TvDropTarget>? = when (val r = api.targets()) {
        is GdResult.Ok -> TvDropLogic.targets(r.value, null).also { _available.value = true }
        is GdResult.Failed -> {
            if (r.http == 404 || r.http == 403) _available.value = false
            null
        }
        else -> null
    }

    /**
     * „Am Handy weiterschreiben“: the person's own devices only (a draft is no message to others), online first; null
     * when GateDrop does not answer.
     */
    suspend fun ownDevices(): List<TvDropTarget>? = when (val r = api.targets()) {
        is GdResult.Ok -> TvDropLogic.ownDevices(r.value).also { _available.value = true }
        is GdResult.Failed -> {
            if (r.http == 404 || r.http == 403) _available.value = false
            null
        }
        else -> null
    }

    /** A text (a draft begun on the TV, its link to the chat or mail on the last line) to the own device [to]. */
    suspend fun sendText(text: String, to: TvDropTarget): TvDropSend {
        if (!to.device) return TvDropSend.Failed("not_allowed")
        val clean = GateDropJson.text(text) ?: return TvDropSend.Failed("empty")
        return when (val s = api.send(emptyList(), clean, null, null, GdRecipients(devices = listOf(to.id)))) {
            is GdResult.Ok -> TvDropSend.Done(to.name)
            is GdResult.Failed -> TvDropSend.Failed(s.code)
            else -> TvDropSend.Failed("offline")
        }
    }

    /** A Drive file to [to]: loaded into the cache, uploaded to GateDrop, sent; the copy goes right after. */
    suspend fun sendDrive(node: DNode, to: TvDropTarget): TvDropSend = withContext(Dispatchers.IO) {
        if (node.isDir || node.encrypted || node.blocked) return@withContext TvDropSend.Failed("not_allowed")
        val tmp = File(context.cacheDir, "gatedrop-out").apply { mkdirs() }.let { File(it, TvDropLogic.fileName(0, node.name)) }
        try {
            val r = drive.download(node.id, expectedSha256 = node.sha256)
            if (r !is DResult.Ok) return@withContext TvDropSend.Failed((r as? DResult.Failed)?.code ?: "offline")
            r.value.body()?.use { b -> b.byteStream().use { input -> tmp.outputStream().use { out -> input.copyTo(out, 64 * 1024) } } }
                ?: return@withContext TvDropSend.Failed("empty")
            send(tmp, node.name, node.mime ?: GateDropJson.DEFAULT_MIME, to)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("GateDrop TV: Drive file not sent (%s)", e.javaClass.simpleName)
            TvDropSend.Failed("error")
        } finally {
            tmp.delete()
        }
    }

    /** A file received on the TV, handed on to [to]. */
    suspend fun sendReceived(sendId: String, i: Int, to: TvDropTarget): TvDropSend {
        val entry = _entries.value.firstOrNull { it.sendId == sendId } ?: return TvDropSend.Failed("gone")
        val f = file(entry, i) ?: return TvDropSend.Failed("gone")
        val meta = entry.files[i]
        return send(f, meta.name, meta.mime, to)
    }

    private suspend fun send(file: File, name: String, mime: String, to: TvDropTarget): TvDropSend {
        val media = when (val u = api.upload(file, mime)) {
            is GdResult.Ok -> u.value
            is GdResult.Failed -> return TvDropSend.Failed(u.code)
            else -> return TvDropSend.Failed("offline")
        }
        val recipients = if (to.device) GdRecipients(devices = listOf(to.id)) else GdRecipients(people = listOf(to.id))
        return when (val s = api.send(listOf(GdSendItem(name, mime, file.length(), mediaId = media)), null, null, null, recipients)) {
            is GdResult.Ok -> TvDropSend.Done(to.name)
            is GdResult.Failed -> TvDropSend.Failed(s.code)
            else -> TvDropSend.Failed("offline")
        }
    }

    // ─── storage ────────────────────────────────────────────────────

    private fun readIndex(): List<TvDropEntry> = runCatching {
        val f = File(File(context.filesDir, DIR), INDEX)
        if (!f.isFile || f.length() > INDEX_MAX_BYTES) return emptyList()
        TvDropStoreJson.read(f.readText())
    }.getOrDefault(emptyList())

    private suspend fun writeIndex(list: List<TvDropEntry>) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(dir, "$INDEX.tmp")
            tmp.writeText(TvDropStoreJson.write(list))
            if (!tmp.renameTo(index)) tmp.delete()
        }
    }

    private fun removeFiles(e: TvDropEntry) {
        runCatching { File(dir, safeId(e.sendId)).deleteRecursively() }
    }

    private fun safeId(id: String): String = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64).ifEmpty { "send" }

    companion object {
        private const val DIR = "gatedrop"
        private const val INDEX = "index.json"
        private const val INDEX_MAX_BYTES = 2L * 1024 * 1024
        private const val PREFS = "gatecontrol_tv_gatedrop"
        private const val KEY_HANDLED = "handled"
        private const val MAX_HANDLED = 300

        /** The reason the TV gives for a direct transfer it does not take part in. */
        const val DIRECT_UNSUPPORTED = "unsupported"
    }
}

/** The list of [TvGateDrop] on the disk (org.json: nothing for R8 to keep). */
internal object TvDropStoreJson {
    fun write(list: List<TvDropEntry>): String = JSONArray().apply {
        for (e in list) {
            put(
                JSONObject()
                    .put("sendId", e.sendId)
                    .put("from", e.from)
                    .put("at", e.at)
                    .put("text", e.text)
                    .put("link", e.link)
                    .put("seen", e.seen)
                    .put(
                        "files",
                        JSONArray().apply {
                            for (f in e.files) put(JSONObject().put("name", f.name).put("mime", f.mime).put("bytes", f.bytes).put("path", f.path))
                        },
                    ),
            )
        }
    }.toString()

    fun read(text: String): List<TvDropEntry> {
        val arr = JSONArray(text)
        val out = ArrayList<TvDropEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("sendId").takeIf { it.isNotBlank() } ?: continue
            val files = o.optJSONArray("files")
            out += TvDropEntry(
                sendId = id,
                from = o.str("from"),
                at = o.optLong("at"),
                text = o.str("text"),
                link = o.str("link"),
                seen = o.optBoolean("seen"),
                files = (0 until (files?.length() ?: 0)).mapNotNull { j ->
                    val f = files?.optJSONObject(j) ?: return@mapNotNull null
                    TvDropFile(f.optString("name"), f.optString("mime"), f.optLong("bytes"), f.str("path"))
                },
            )
        }
        return out
    }

    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
}
