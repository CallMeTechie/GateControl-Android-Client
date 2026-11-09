package com.gatecontrol.android.notizen

import android.content.Context
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.familie.PluginDirectory
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The offline copy of „Notizen & Aufgaben“: the server's answers as they came (`me`, the menu, the lists shown, the
 * notes and tasks opened) — read again with [NotizenJson], never by reflection — and the outbox of ticks made without a
 * connection. Tresor notes are kept as the server has them: ciphertext only, never the plaintext. Per login (server +
 * token) in the app's private, not backed-up files; another login or the plugin gone deletes it ([wipe]).
 */
@Singleton
class NotizenCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val setupRepository: SetupRepository,
) {
    private val mutex = Mutex()

    private fun root(): File = File(context.noBackupFilesDir, DIR)

    private fun ownerDir(): File? {
        val key = PluginDirectory.ownerKey(setupRepository.getServerUrl(), setupRepository.getApiToken()).takeIf { it.isNotEmpty() } ?: return null
        return File(root(), key)
    }

    private fun name(key: String): String = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    private suspend fun <T> io(block: (File) -> T?): T? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val dir = ownerDir() ?: return@withLock null
            root().listFiles()?.filter { it.name != dir.name }?.forEach { runCatching { it.deleteRecursively() } }
            runCatching { block(dir) }.getOrNull()
        }
    }

    private fun read(file: File): JsonObject? = file.takeIf { it.isFile }?.let { f ->
        runCatching { JsonParser.parseString(f.readText()).takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()
    }

    private fun write(file: File, o: JsonObject) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** An answer kept under [key] (`me`, `nav:private`, `view:myday:work`, `note:12` …), with the time it came. */
    suspend fun put(key: String, raw: JsonObject) {
        io { dir ->
            val f = File(dir, "a/${name(key)}.json")
            write(f, JsonObject().apply { addProperty("savedAt", System.currentTimeMillis()); add("raw", raw) })
            // at most a few hundred answers: the oldest go
            val all = f.parentFile?.listFiles().orEmpty()
            if (all.size > MAX_FILES) all.sortedBy { it.lastModified() }.take(all.size - MAX_FILES).forEach { it.delete() }
        }
    }

    /** The answer kept under [key] and when it came. */
    suspend fun get(key: String): Pair<JsonObject, Long>? = io { dir ->
        read(File(dir, "a/${name(key)}.json"))?.let { o ->
            val raw = o.get("raw")?.takeIf { it.isJsonObject }?.asJsonObject ?: return@let null
            raw to (o.get("savedAt")?.asLong ?: 0L)
        }
    }

    suspend fun remove(key: String) {
        io { dir -> File(dir, "a/${name(key)}.json").delete() }
    }

    // ── the outbox ──────────────────────────────────────────

    suspend fun outbox(): List<NotizenOutbox.Op> = io { dir ->
        read(File(dir, "outbox.json"))?.getAsJsonArray("ops")?.mapNotNull { NotizenOutbox.fromJson(it) }
    }.orEmpty()

    suspend fun putOutbox(ops: List<NotizenOutbox.Op>) {
        io { dir -> write(File(dir, "outbox.json"), JsonObject().apply { add("ops", JsonArray().apply { ops.forEach { add(NotizenOutbox.toJson(it)) } }) }) }
    }

    /** Everything of this plugin on the phone goes (another login, the plugin gone). */
    suspend fun wipe() {
        withContext(Dispatchers.IO) { mutex.withLock { runCatching { root().deleteRecursively() } } }
    }

    private companion object {
        const val DIR = "notizen-cache"
        const val MAX_FILES = 400
    }
}

/**
 * What the app keeps to send once it is online again (pure, unit-tested). Only what the API takes again safely: a tick
 * of a task or of an item of another plugin (`complete { done }` sets a state — sent twice, it does the same). New tasks
 * and notes and changes of text need a connection (the API has no ids made on the device): the screens say so.
 */
object NotizenOutbox {
    data class Op(val kind: String, val id: Long, val done: Boolean, val at: Long)

    const val TASK = "task"
    const val ITEM = "item"

    fun fromJson(e: com.google.gson.JsonElement): Op? {
        val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val kind = o.get("kind")?.asString?.takeIf { it == TASK || it == ITEM } ?: return null
        return Op(kind, o.get("id")?.asLong?.takeIf { it > 0 } ?: return null, o.get("done")?.asBoolean ?: return null, o.get("at")?.asLong ?: 0)
    }

    fun toJson(op: Op) = JsonObject().apply {
        addProperty("kind", op.kind)
        addProperty("id", op.id)
        addProperty("done", op.done)
        addProperty("at", op.at)
    }

    /** [op] added: the latest tick of the same thing replaces an earlier one (at most [MAX] wait). */
    fun add(ops: List<Op>, op: Op): List<Op> = (ops.filterNot { it.kind == op.kind && it.id == op.id } + op).takeLast(MAX)

    /** The state a waiting tick gives [uid] (`t:<id>` / `s:<id>`); null: none waits. */
    fun pending(ops: List<Op>, uid: String): Boolean? {
        val kind = if (uid.startsWith("t:")) TASK else if (uid.startsWith("s:")) ITEM else return null
        val id = uid.substring(2).toLongOrNull() ?: return null
        return ops.lastOrNull { it.kind == kind && it.id == id }?.done
    }

    /** A view as the person sees it: the waiting ticks laid over the server's copy. */
    fun overlay(view: NView, ops: List<Op>): NView {
        if (ops.isEmpty()) return view
        return view.copy(sections = view.sections.map { s ->
            s.copy(items = s.items.map { e ->
                when (val d = pending(ops, e.uid)) {
                    null -> e
                    else -> when (e) {
                        is NTask -> e.copy(done = d)
                        is NItem -> e.copy(done = d)
                    }
                }
            })
        })
    }

    const val MAX = 500
}
