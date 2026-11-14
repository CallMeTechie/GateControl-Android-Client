package com.gatecontrol.android.notizen

import android.content.Context
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.familie.PluginDirectory
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
 * notes and tasks opened) — read again with [NotizenJson], never by reflection — and the outbox of what was made or
 * changed without a connection ([NotizenOutbox]). Tresor notes are kept as the server has them: ciphertext only, never the plaintext. Per login (server +
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

    /** The outbox ([NotizenOutbox.State]: what waits, the ids the server gave things made offline). */
    suspend fun outbox(): NotizenOutbox.State = io { dir -> read(File(dir, "outbox.json"))?.let { NotizenOutbox.stateFromJson(it) } } ?: NotizenOutbox.State()

    suspend fun putOutbox(state: NotizenOutbox.State) {
        io { dir -> write(File(dir, "outbox.json"), NotizenOutbox.stateToJson(state)) }
    }

    /** The reminders the phone rings for by itself ([ReminderState]: `GET reminders` as kept, the alarms set, the tags seen). */
    suspend fun reminders(): ReminderState = ReminderState.fromJson(io { dir -> read(File(dir, "reminders.json")) })

    suspend fun putReminders(state: ReminderState) {
        io { dir -> write(File(dir, "reminders.json"), state.toJson()) }
    }

    /** `POST places/watch` as it came last (plugin 1.5.0): the places to watch and what their reminders say — also without a connection. */
    suspend fun watch(): NWatch = io { dir -> read(File(dir, "places-watch.json"))?.let { NotizenPlacesJson.watch(it) } } ?: NWatch()

    suspend fun putWatch(raw: JsonObject?) {
        io { dir -> File(dir, "places-watch.json").let { f -> if (raw == null) f.delete() else write(f, raw) } }
    }

    /** What the phone remembers about its places ([PlaceState]). */
    suspend fun placeState(): PlaceState = PlaceState.fromJson(io { dir -> read(File(dir, "places-state.json")) })

    suspend fun putPlaceState(state: PlaceState) {
        io { dir -> write(File(dir, "places-state.json"), state.toJson()) }
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
