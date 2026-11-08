package com.gatecontrol.android.notizen

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer

/** An export the person picked (SAF): read in ranges, never copied whole. */
class UriNsxSource(resolver: ContentResolver, uri: Uri) : Nsx.Source, Closeable {
    private val pfd: ParcelFileDescriptor = resolver.openFileDescriptor(uri, "r") ?: throw Nsx.NsxException("invalid", "not readable")
    private val stream = FileInputStream(pfd.fileDescriptor)
    private val channel = stream.channel

    override val size: Long = pfd.statSize.takeIf { it > 0 } ?: channel.size()

    override fun read(start: Long, end: Long): ByteArray {
        val len = (end.coerceAtMost(size) - start).coerceAtLeast(0)
        if (len > Int.MAX_VALUE - 64) throw Nsx.NsxException("too_large")
        val buf = ByteBuffer.allocate(len.toInt())
        var pos = start
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n < 0) break
            pos += n
        }
        return buf.array().copyOf(buf.position())
    }

    override fun close() {
        runCatching { stream.close() }
        runCatching { pfd.close() }
    }
}

/**
 * The move from DS Note (README "Umzug aus DS Note"), done by the phone: the notebooks once per `ref`, the notes in
 * batches of ten with their attachments uploaded one by one, a notebook's protected notes into a Tresor notebook made
 * here (opened with the person's password; encrypted on the phone, the server never sees them) — or skipped and
 * counted when they do not open.
 */
class NsxImport(
    private val api: NotizenApi,
    private val vault: NotizenVaultSession,
    private val arc: Nsx.Archive,
    private val sum: Nsx.Summary,
    private val area: String,
    private val attachMB: Int,
) {
    data class Stats(var notes: Int = 0, var skipped: Int = 0, var protectedSkipped: Int = 0, var done: Int = 0)

    /** The Tresor of a notebook's protected notes: made before the import (its recovery key shown first). */
    class TresorFor(val notebookId: Long, val vaultId: String)

    /** Whether [password] opens the protected notes (tried on the first one, as the portal does). */
    fun opens(password: String): Boolean {
        if (password.isEmpty()) return false
        val first = sum.noteIds.asSequence().map { Nsx.note(arc, it) }.firstOrNull { it.encrypted } ?: return false
        return runCatching { Nsx.decrypt(first.html, password) }.isSuccess
    }

    /** The notebooks with protected notes (each becomes a Tresor). */
    fun protectedBooks(): List<Nsx.Notebook> = sum.notebooks.filter { it.protectedNotes > 0 }

    suspend fun run(fileName: String, password: String?, tresors: Map<String, TresorFor>, progress: (Stats) -> Unit): NResult<Stats> {
        val stats = Stats()
        val job = api.dsStart(fileName, sum.notebooks.size, sum.notes, sum.bytes)
        if (job !is NResult.Ok) return job.notOk()
        val jobId = job.value.id
        val notes = sum.noteIds.map { Nsx.note(arc, it) }
        for (nb in sum.notebooks) {
            val rows = notes.filter { (it.notebookId ?: "_") == nb.id }
            if (rows.isEmpty()) continue
            val plain = rows.filter { !it.encrypted }
            val enc = rows.filter { it.encrypted }
            if (plain.isNotEmpty()) {
                val nbId = api.dsNotebook(jobId, Nsx.ref(nb.id), nb.title, area)
                if (nbId !is NResult.Ok) return nbId.notOk()
                val r = send(jobId, nbId.value, plain, null, null, stats, progress)
                if (r != null) return r
            }
            if (enc.isNotEmpty()) {
                val t = tresors[nb.id]
                if (t == null || password.isNullOrEmpty()) {
                    stats.protectedSkipped += enc.size
                    stats.done += enc.size
                    progress(stats)
                } else {
                    val r = send(jobId, t.notebookId, enc, t.vaultId, password, stats, progress)
                    if (r != null) return r
                }
            }
        }
        val fin = api.dsFinish(jobId, stats.skipped, stats.protectedSkipped)
        if (fin !is NResult.Ok) return fin.notOk()
        return NResult.Ok(stats)
    }

    private suspend fun send(job: Long, notebookId: Long, list: List<Nsx.Note>, vaultId: String?, password: String?, stats: Stats, progress: (Stats) -> Unit): NResult<Stats>? {
        for (batch in list.chunked(10)) {
            val payload = JsonArray()
            for (n in batch) {
                val att = JsonArray()
                val byRef = HashMap<String, Int>()
                n.attachments.forEachIndexed { k, a ->
                    if (a.entry == null || a.size > attachMB * 1024L * 1024L) return@forEachIndexed
                    val bytes = runCatching { arc.bytes(a.entry) }.getOrNull() ?: run { stats.skipped++; return@forEachIndexed }
                    if (vaultId != null) {
                        val sealed = vault.sealFile(vaultId, bytes, a.name) ?: run { stats.skipped++; return@forEachIndexed }
                        val up = api.upload(sealed.first, "application/octet-stream")
                        if (up is NResult.Ok) att.add(JsonObject().apply { addProperty("n", k); addProperty("mediaId", up.value); addProperty("nameCipher", sealed.second) }) else stats.skipped++
                    } else {
                        val up = api.upload(bytes, a.mime)
                        if (up is NResult.Ok) {
                            att.add(JsonObject().apply { addProperty("n", k); addProperty("mediaId", up.value); addProperty("name", a.name) })
                            a.ref?.let { byRef[it] = k }
                        } else {
                            stats.skipped++
                        }
                    }
                }
                val html = if (vaultId != null) {
                    runCatching { Nsx.decrypt(n.html, password.orEmpty()) }.getOrNull() ?: run {
                        stats.protectedSkipped++
                        stats.done++
                        progress(stats)
                        null
                    }
                } else {
                    n.html
                } ?: continue
                val md = Nsx.toMarkdown(html) { ref -> byRef[ref]?.let { "attnew:$it" } }
                val ref = Nsx.ref(n.id)
                val o = JsonObject().apply {
                    addProperty("ref", ref)
                    n.created?.let { addProperty("created", it) }
                    n.updated?.let { addProperty("updated", it) }
                    add("attachments", att)
                }
                if (vaultId != null) {
                    val text = md + if (n.tags.isNotEmpty()) "\n\n" + n.tags.joinToString(" ") { "#" + it.replace(Regex("\\s+"), "-") } else ""
                    val sealed = vault.seal(vaultId, n.title, text) ?: run { stats.protectedSkipped++; null } ?: continue
                    o.addProperty("titleCipher", sealed.first)
                    o.addProperty("cipher", sealed.second)
                    o.addProperty("keyVersion", sealed.third)
                } else {
                    o.addProperty("title", n.title)
                    o.addProperty("body", md)
                    o.add("tags", JsonArray().apply { n.tags.forEach { add(it) } })
                }
                payload.add(o)
                stats.done++
                progress(stats)
            }
            if (payload.size() == 0) continue
            val r = api.dsNotes(job, JsonObject().apply { addProperty("notebookId", notebookId); add("notes", payload) })
            when (r) {
                is NResult.Ok -> {
                    stats.notes += r.value.first
                    stats.skipped += r.value.second
                    progress(stats)
                }
                else -> return r.notOk()
            }
        }
        return null
    }
}
