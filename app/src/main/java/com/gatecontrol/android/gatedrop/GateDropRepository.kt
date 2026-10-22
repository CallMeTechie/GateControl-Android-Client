package com.gatecontrol.android.gatedrop

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.gatecontrol.android.familie.PluginDirectory
import com.gatecontrol.android.support.CrashReports
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Where a send stands (shown by the share sheet while it is open). */
sealed interface GdJobState {
    data object Preparing : GdJobState
    data class Uploading(val percent: Int?) : GdJobState
    data object Sending : GdJobState
    data class Direct(val percent: Int?) : GdJobState
    data class Done(val sendId: String?, val link: GdLinkResult?) : GdJobState
    data class Failed(val code: String?) : GdJobState
}

/** Result of one run of a send job. */
sealed interface GdJobOutcome {
    data class Done(val sendId: String, val link: GdLinkResult?) : GdJobOutcome

    /** Worth another try later (offline, server busy). */
    data class Retry(val code: String) : GdJobOutcome

    /** Will not work (refused, files gone). */
    data class Failed(val code: String) : GdJobOutcome
}

/**
 * GateDrop on this device: availability, targets, sending (upload or
 * direct, server fallback), receiving (direct or over the server, saved to
 * Downloads/GateControl), presence. The workers and screens call it; the
 * routes are [GateDropApi]'s, the LAN transfer [GateDropDirect]'s.
 */
@Singleton
class GateDropRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    val api: GateDropApi,
    private val direct: GateDropDirect,
    private val saver: GateDropSaver,
    val store: GateDropStore,
    private val notifier: GateDropNotifier,
    pluginDirectory: PluginDirectory,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)

    /** The server lists the GateDrop plugin for this login. */
    val available: StateFlow<Boolean> = pluginDirectory.state.map { it.gatedrop }
        .stateIn(scope, SharingStarted.Eagerly, pluginDirectory.state.value.gatedrop)

    private val _targets = MutableStateFlow<GdTargets?>(null)
    val targets: StateFlow<GdTargets?> = _targets.asStateFlow()

    private val _jobs = MutableStateFlow<Map<String, GdJobState>>(emptyMap())
    /** State of the sends of this process, by job id. */
    val jobs: StateFlow<Map<String, GdJobState>> = _jobs.asStateFlow()

    private val receiving = Mutex()

    fun setJobState(id: String, state: GdJobState) = _jobs.update { it + (id to state) }

    suspend fun refreshTargets(): GdResult<GdTargets> {
        val r = api.targets()
        if (r is GdResult.Ok) _targets.value = r.value
        return r
    }

    // ─── sending ────────────────────────────────────────────────────

    private fun percent(done: Long, total: Long): Int? = if (total <= 0) null else ((done * 100) / total).toInt().coerceIn(0, 100)

    /**
     * One run of [job]: upload first or send the files as local items,
     * `POST sends`, the direct transfers the server hands out, and the
     * server path for every device a direct transfer did not reach.
     * Progress goes to [onProgress] (percent, null = unknown). Safe to run
     * again after a retry: what already happened is kept in the job.
     */
    suspend fun runJob(initial: GdJob, onProgress: (sending: Boolean, Int?) -> Unit = { _, _ -> }): GdJobOutcome {
        var job = initial
        val files = job.files.map { File(it.path) }
        if (files.any { !it.isFile }) return GdJobOutcome.Failed("files_gone")
        val totalBytes = job.files.sumOf { it.size }
        val devices = job.directDevices.map { GdDevice(it, it, online = true, direct = true) }
        val plan = GateDropLogic.plan(files.size, job.to, devices)

        suspend fun uploadAll(): GdResult<List<String>> {
            val ids = job.uploaded.toMutableList()
            var doneBefore = job.files.take(ids.size).sumOf { it.size }
            for (i in ids.size until job.files.size) {
                val f = job.files[i]
                setJobState(job.id, GdJobState.Uploading(percent(doneBefore, totalBytes)))
                val base = doneBefore
                when (val r = api.upload(files[i], f.mime) { sent -> percent(base + sent, totalBytes).let { p -> onProgress(true, p); setJobState(job.id, GdJobState.Uploading(p)) } }) {
                    is GdResult.Ok -> {
                        ids += r.value
                        doneBefore += f.size
                        job = job.copy(uploaded = ids.toList())
                        store.saveJob(job)
                    }
                    is GdResult.Failed -> return r
                    GdResult.Offline -> return GdResult.Offline
                    GdResult.NotConfigured -> return GdResult.NotConfigured
                }
            }
            return GdResult.Ok(ids)
        }

        fun uploadedItems(ids: List<String>) = job.files.mapIndexed { i, f -> GdSendItem(f.name, f.mime, f.size, mediaId = ids[i]) }

        fun failure(r: GdResult<*>): GdJobOutcome = when (r) {
            is GdResult.Failed -> if (r.http >= 500 || r.http == 429) GdJobOutcome.Retry(r.code) else GdJobOutcome.Failed(r.code)
            GdResult.NotConfigured -> GdJobOutcome.Failed("not_configured")
            else -> GdJobOutcome.Retry("offline")
        }

        /** Upload (what is not up yet) and hand the files [indexes] to the send (`POST sends/<id>/items`). */
        suspend fun deliverMissing(sendId: String, indexes: List<Int>): GdJobOutcome? {
            val ids = uploadAll().let { if (it is GdResult.Ok) it.value else return failure(it) }
            val added = api.addItems(sendId, indexes.filter { it in ids.indices }.associateWith { ids[it] })
            return if (added is GdResult.Ok) null else failure(added)
        }

        // An earlier run sent already and only the server path is missing.
        job.sendId?.let { sendId ->
            if (!job.fallback) return GdJobOutcome.Done(sendId, null)
            deliverMissing(sendId, job.missing.ifEmpty { job.files.indices.toList() })?.let { return it }
            return GdJobOutcome.Done(sendId, null)
        }

        suspend fun uploadedItems(): GdResult<List<GdSendItem>> = when (val r = uploadAll()) {
            is GdResult.Ok -> GdResult.Ok(job.files.mapIndexed { i, f -> GdSendItem(f.name, f.mime, f.size, mediaId = r.value[i]) })
            is GdResult.Failed -> r
            GdResult.Offline -> GdResult.Offline
            GdResult.NotConfigured -> GdResult.NotConfigured
        }
        var localItems = plan.localItems
        val items: List<GdSendItem> = when {
            plan.uploadFirst -> uploadedItems().let { if (it is GdResult.Ok) it.value else return failure(it) }
            plan.localItems -> job.files.map { GdSendItem(it.name, it.mime, it.size, local = true) }
            else -> emptyList()
        }
        setJobState(job.id, GdJobState.Sending)
        onProgress(true, null)
        var sent = api.send(items, job.text, job.link, job.note, job.to)
        // A chosen device is not reachable directly after all: the files go up first.
        if (localItems && sent is GdResult.Failed && GateDropLogic.uploadRequired(sent.code, sent.http)) {
            localItems = false
            val uploaded = uploadedItems().let { if (it is GdResult.Ok) it.value else return failure(it) }
            sent = api.send(uploaded, job.text, job.link, job.note, job.to)
        }
        val result = (sent as? GdResult.Ok)?.value ?: return failure(sent)
        job = job.copy(sendId = result.id)
        store.saveJob(job)

        // Direct transfers (own devices in the same network).
        val localFiles = job.files.mapIndexed { i, f -> GdLocalFile(files[i], f.name, f.size) }
        val results = mutableListOf<Pair<Boolean, GdTransferAnswer?>>()
        if (localFiles.isNotEmpty()) {
            for (d in result.direct) {
                setJobState(job.id, GdJobState.Direct(0))
                val outcome = runCatching { transferDirect(d, localFiles) { p -> percent(p, totalBytes).let { onProgress(true, it); setJobState(job.id, GdJobState.Direct(it)) } } }
                    .getOrElse { if (it is CancellationException) throw it else GdDirectOutcome.Failed("error") }
                val ok = outcome is GdDirectOutcome.Done
                val answer = api.transferResult(d.transferId, ok, (outcome as? GdDirectOutcome.Done)?.bytes, (outcome as? GdDirectOutcome.Failed)?.reason)
                results += ok to answer.valueOrNull()
                Timber.i("GateDrop: direct transfer %s", if (ok) "done" else "failed, server path")
            }
        }
        val missing = GateDropLogic.missingAfterDirect(localItems, job.files.size, results)
        if (missing.isNotEmpty()) {
            job = job.copy(fallback = true, missing = missing)
            store.saveJob(job)
            deliverMissing(result.id, missing)?.let { return it }
        }
        return GdJobOutcome.Done(result.id, result.link)
    }

    private suspend fun transferDirect(d: GdDirect, files: List<GdLocalFile>, onProgress: (Long) -> Unit): GdDirectOutcome = when (d.mode) {
        GdMode.PUSH -> direct.push(d, files, onProgress)
        GdMode.PULL -> {
            // The receiver fetches from this device: listen only for this transfer.
            val lan = direct.lanAddresses()
            val port = d.candidates.firstOrNull { c -> lan.any { it.first == c.host } }?.port ?: GateDropDirect.DEFAULT_PORT
            val listener = GateDropListener(port)
            try {
                if (!listener.start()) {
                    GdDirectOutcome.Failed("listen_failed")
                } else {
                    // The plugin learns that this device listens right now.
                    reportPresence(listening = true, force = true)
                    val done = listener.serve(d, files, onProgress)
                    val bytes = withTimeoutOrNull(PULL_WAIT_MS) { runCatching { done.await() }.getOrNull() }
                    if (bytes != null) GdDirectOutcome.Done(bytes) else GdDirectOutcome.Failed("pull_timeout")
                }
            } finally {
                listener.stop()
                reportPresence(listening = false, force = true)
            }
        }
    }

    // ─── receiving ──────────────────────────────────────────────────

    /**
     * A direct transfer offered by a silent push: fetch (pull) or accept
     * (push) the files into Downloads/GateControl, tell the plugin, show
     * the notification. On failure the plugin delivers over the server.
     */
    suspend fun receiveOffer(pushed: GdTransferOffer, onProgress: (Int?) -> Unit = {}): Boolean = receiving.withLock {
        val key = "t:${pushed.transferId}"
        if (store.get(key)?.files?.all { it.uri != null } == true) return true
        // The push left the file list out (too long): `GET transfers/<id>`.
        val offer = if (!pushed.needsDetails) {
            pushed
        } else {
            api.transfer(pushed.transferId).valueOrNull()?.takeIf { !it.needsDetails }
                ?.let { it.copy(from = pushed.from ?: it.from, sendId = it.sendId ?: pushed.sendId) }
                ?: run {
                    api.transferResult(pushed.transferId, false, error = "no_items")
                    return@withLock false
                }
        }
        val total = offer.items.sumOf { it.bytes }
        val pendings = HashMap<Int, GateDropSaver.Pending>()
        val sinkFor: (Int, GdOfferItem) -> OutputStream = { i, item ->
            val p = pendings.getOrPut(i) { saver.create(item.name, item.mime) ?: throw IOException("no download entry") }
            p.open()
        }
        val progress: (Long) -> Unit = { onProgress(percent(it, total)) }
        val outcome = runCatching {
            when (offer.mode) {
                GdMode.PULL -> direct.pull(offer, sinkFor, progress)
                GdMode.PUSH -> {
                    val listener = GateDropListener(GateDropDirect.DEFAULT_PORT)
                    try {
                        if (!listener.start()) {
                            GdDirectOutcome.Failed("listen_failed")
                        } else {
                            reportPresence(listening = true, force = true)
                            val done = listener.expect(offer, sinkFor, progress)
                            val bytes = withTimeoutOrNull(PUSH_WAIT_MS) { runCatching { done.await() }.getOrNull() }
                            if (bytes != null) GdDirectOutcome.Done(bytes) else GdDirectOutcome.Failed("push_timeout")
                        }
                    } finally {
                        listener.stop()
                        reportPresence(listening = false, force = true)
                    }
                }
            }
        }.getOrElse { if (it is CancellationException) throw it else GdDirectOutcome.Failed("error") }

        if (outcome !is GdDirectOutcome.Done || pendings.size != offer.items.size) {
            pendings.values.forEach { it.discard() }
            api.transferResult(offer.transferId, false, error = (outcome as? GdDirectOutcome.Failed)?.reason ?: "incomplete")
            Timber.i("GateDrop: direct receive failed, the server delivers")
            return@withLock false
        }
        val files = offer.items.mapIndexed { i, item ->
            GdReceivedFile(item.name, item.mime, item.bytes, uri = pendings[i]?.publish()?.toString())
        }
        val entry = GdReceived(key = key, sendId = offer.sendId, from = offer.from, at = System.currentTimeMillis(), files = files)
        store.upsert(entry)
        // Shown here: a delivery of the same send over the server (push or `pending`) adds nothing.
        offer.sendId?.let { store.markHandled(it) }
        api.transferResult(offer.transferId, true, bytes = outcome.bytes)
        notifier.received(entry)
        true
    }

    /**
     * A send delivered over the server (`{ gatedrop: { send } }` push, a
     * send of `pending`, or "Speichern"): learn its content, save the files
     * when [save] (or when small enough / on Wi-Fi with [autoSave]) and
     * update the notification [notificationId] when [show]. Acknowledged
     * (`opened`) once everything is on the device; remembered as handled so
     * the push and the poll of `pending` deliver it once. A failed download
     * stays unhandled and is tried again by the next poll.
     */
    suspend fun fetchSend(
        sendId: String,
        save: Boolean,
        autoSave: Boolean,
        show: Boolean,
        notificationId: Int? = null,
        messageId: Long? = null,
        quiet: Boolean = false,
        onProgress: (Int?) -> Unit = {},
    ): Boolean {
        val key = "s:$sendId"
        val ownId = GateDropNotifier.receivedId(key)
        // Processed already (poll of `pending` or an earlier push): only "Speichern" goes on.
        if (!save && store.isHandled(sendId)) {
            // A delivery push's placeholder takes over the notification posted back then.
            if (show && notificationId != null && notificationId != ownId) {
                val known = store.get(key)
                if (known != null) {
                    notifier.received(known, notificationId, messageId, quiet = true)
                    notifier.cancel(ownId)
                } else {
                    notifier.cancel(notificationId)
                }
            }
            return true
        }
        val detail = api.sendDetail(sendId).valueOrNull()
        val known = store.get(key)
        if (detail == null && known == null) return false
        var entry = if (detail != null) {
            GdReceived(
                key = key,
                sendId = sendId,
                from = detail.from ?: known?.from,
                at = detail.at ?: known?.at ?: System.currentTimeMillis(),
                text = detail.text,
                link = detail.link,
                note = detail.note,
                files = detail.items.map { item ->
                    GdReceivedFile(
                        item.name,
                        item.mime,
                        item.bytes,
                        item.mediaId?.takeIf { item.available },
                        known?.files?.firstOrNull { it.mediaId != null && it.mediaId == item.mediaId }?.uri,
                    )
                },
            )
        } else {
            known!!
        }
        store.upsert(entry)
        val nid = notificationId ?: ownId
        // Tried before (a download failed): quiet, and posted again only when something changed.
        val repeat = known != null && !save && notificationId == null
        val wantSave = save || (autoSave && GateDropLogic.autoSave(entry.files.sumOf { it.bytes }, unmetered()))
        val missing = entry.files.indices.filter { entry.files[it].uri == null && entry.files[it].mediaId != null }
        var savedNow = false
        if (wantSave && missing.isNotEmpty()) {
            if (show && !repeat) notifier.received(entry, nid, messageId, quiet = quiet, saving = true)
            val total = missing.sumOf { entry.files[it].bytes }
            var before = 0L
            for (i in missing) {
                val f = entry.files[i]
                val base = before
                val uri = download(f) { onProgress(percent(base + it, total)) } ?: break
                store.markSaved(key, i, uri)
                savedNow = true
                before += f.bytes
            }
            entry = store.get(key) ?: entry
        }
        val outcome = GateDropLogic.receiveOutcome(entry, wantSave)
        if (outcome.ack) api.opened(sendId)
        // Handled once the person was told (or saved it themselves); a quiet fetch leaves that to the push.
        if (outcome.handled && (show || save)) store.markHandled(sendId)
        if (show && (!repeat || savedNow)) notifier.received(entry, nid, messageId, quiet = quiet || repeat || (wantSave && missing.isNotEmpty()))
        return true
    }

    // ─── sends waiting for this device (`pending`) ──────────────────

    private val pendingPoller = GateDropPendingPoller(object : GateDropPendingPoller.Host {
        override suspend fun fetchPending() = api.pending()
        override fun isHandled(sendId: String) = store.isHandled(sendId)
        override fun local(sendId: String) = store.get("s:$sendId") ?: store.bySend(sendId)
        override fun markHandled(sendId: String) = store.markHandled(sendId)
        override suspend fun process(send: GdEntry) {
            GateDropReceiveWorker.enqueueSend(context, send.id, save = false, autoSave = true, show = true)
        }
        override suspend fun ack(sendId: String) = api.opened(sendId) is GdResult.Ok
    })

    @Volatile private var pendingDebounce: Job? = null

    /**
     * Fetches what was sent to this device while it was offline (`GET
     * pending`); [force] skips the short pause after the last answer.
     * Never runs twice at once; an older plugin (404) or a login without
     * a device (409 `no_device`) is ignored.
     */
    suspend fun pollPending(force: Boolean = false): GdPollOutcome {
        if (!available.value) return GdPollOutcome.Unsupported
        val outcome = pendingPoller.poll(force)
        if (outcome is GdPollOutcome.Done && (outcome.processed.isNotEmpty() || outcome.acked.isNotEmpty())) {
            Timber.i("GateDrop: pending — %d to receive, %d acknowledged", outcome.processed.size, outcome.acked.size)
        }
        return outcome
    }

    /** [pollPending] once the triggers (start, stream, network) settled. */
    fun pollPendingSoon(delayMs: Long = PENDING_DEBOUNCE_MS) {
        synchronized(this) {
            pendingDebounce?.cancel()
            pendingDebounce = scope.launch {
                delay(delayMs)
                // Not cut off by the next trigger once it runs.
                withContext(NonCancellable) { runCatching { pollPending() } }
            }
        }
    }

    private suspend fun download(f: GdReceivedFile, onBytes: (Long) -> Unit): String? = withContext(Dispatchers.IO) {
        val mediaId = f.mediaId ?: return@withContext null
        val response = api.download(mediaId) ?: return@withContext null
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            return@withContext null
        }
        val pending = saver.create(f.name, f.mime) ?: return@withContext null
        try {
            var written = 0L
            response.body()?.use { body ->
                pending.open().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            if (f.bytes in 1 until written) throw IOException("longer than announced")
                            onBytes(written)
                        }
                    }
                }
            } ?: throw IOException("no body")
            if (f.bytes > 0 && written != f.bytes) throw IOException("size mismatch")
            pending.publish()?.toString()
        } catch (e: CancellationException) {
            pending.discard()
            throw e
        } catch (e: Exception) {
            Timber.w("GateDrop: file not saved (%s)", e.javaClass.simpleName)
            pending.discard()
            null
        }
    }

    private fun unmetered(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }.getOrDefault(false)

    // ─── presence ───────────────────────────────────────────────────

    @Volatile private var lastPresence: Pair<List<Pair<String, Int>>, Boolean>? = null
    @Volatile private var lastPresenceAt = 0L

    /** Tells the plugin this device's LAN addresses (`PUT presence`); skipped when nothing changed lately. */
    suspend fun reportPresence(listening: Boolean = false, force: Boolean = false) {
        if (!available.value) return
        val lan = direct.lanAddresses()
        val now = System.currentTimeMillis()
        val state = lan to listening
        if (!force && state == lastPresence && now - lastPresenceAt < PRESENCE_MIN_MS) return
        val r = api.presence(lan, GateDropDirect.DEFAULT_PORT, listening)
        if (r is GdResult.Ok) {
            lastPresence = state
            lastPresenceAt = now
        }
    }

    companion object {
        /** How long a pull receiver may take to fetch from this device. */
        private const val PULL_WAIT_MS = 120_000L
        /** How long this device waits for a pushing sender. */
        private const val PUSH_WAIT_MS = 90_000L
        /** Unchanged presence is not repeated more often. */
        private const val PRESENCE_MIN_MS = 4 * 60_000L
        /** Triggers of the poll of `pending` come in bursts. */
        private const val PENDING_DEBOUNCE_MS = 2_000L
    }
}
