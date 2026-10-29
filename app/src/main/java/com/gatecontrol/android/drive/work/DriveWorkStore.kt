package com.gatecontrol.android.drive.work

import android.content.Context
import androidx.core.content.edit
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings of "Arbeitsfotos getrennt sichern". [workFolder] … [inMemories] are the person's (Drive 1.13 keeps them on
 * the server for every device; without it only here); the rest is this phone's.
 */
data class WorkSettings(
    /** "Arbeitsfotos nach"; null: not chosen — every photo goes to Kamera-Uploads as before. */
    val workFolder: Long? = null,
    /** The folder's path as shown ("Meine Dateien › Arbeit"). */
    val folderLabel: String? = null,
    val subfolders: Boolean = true,
    val inMemories: Boolean = false,
    /** "Selbst einsortieren, wenn sicher". */
    val autoSort: Boolean = true,
    /** "Bei neuen WLANs fragen". */
    val askNewWifi: Boolean = true,
    /** "Arbeitsfotos nach der Sicherung vom Handy entfernen". */
    val removeAfter: Boolean = false,
    val zones: WifiZones = WifiZones(),
) {
    /** Photos are sorted at all: a work folder is chosen. */
    val active: Boolean get() = workFolder != null
}

/**
 * Everything of the work photos that stays on this phone: the settings ([WorkSettings]), the WLAN timeline and what the
 * AI learned (both in `noBackupFilesDir`, never in a backup), the review marks of a Drive without 1.13, the work photos
 * waiting to be removed from the phone.
 */
@Singleton
class DriveWorkStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val dir: File get() = context.noBackupFilesDir
    private val lock = Any()

    /** This process' session of the WLAN timeline ([WifiSeen.session]). */
    val session: String = UUID.randomUUID().toString().take(12)

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<WorkSettings> = _settings.asStateFlow()

    private val _examples = MutableStateFlow<List<WorkExample>>(emptyList())
    private var examplesRead = false

    private val _learned = MutableStateFlow(DriveWorkLogic.Learned())
    /** What the AI learned so far (read on first use). */
    val learned: StateFlow<DriveWorkLogic.Learned> get() = _learned.also { examples() }

    private val _localReview = MutableStateFlow(readLocalReview())
    /** Without Drive 1.13: the photos marked "zu prüfen" on this phone (node id → hint). */
    val localReview: StateFlow<Map<Long, Int?>> = _localReview.asStateFlow()

    private val _ask = MutableStateFlow(prefs.getString(KEY_ASK, null))
    /** A new WLAN to ask about ("Wo bist du gerade?"); null: none. */
    val ask: StateFlow<String?> = _ask.asStateFlow()

    private val _removals = MutableStateFlow(readRemovals())
    /** Work photos backed up that may go from the phone (media store ids; the person confirms). */
    val removals: StateFlow<List<Long>> = _removals.asStateFlow()

    private val _suggested = MutableStateFlow(prefs.getInt(KEY_SUGGESTED, 0))
    /** How many photos "Aufräumen" suggested the last time it looked (for the hint in the Photos tab). */
    val suggested: StateFlow<Int> = _suggested.asStateFlow()

    // ─── settings ───────────────────────────────────────────────────

    private fun readSettings() = WorkSettings(
        workFolder = prefs.getLong(KEY_FOLDER, 0L).takeIf { it > 0 },
        folderLabel = prefs.getString(KEY_FOLDER_LABEL, null),
        subfolders = prefs.getBoolean(KEY_SUBFOLDERS, true),
        inMemories = prefs.getBoolean(KEY_IN_MEMORIES, false),
        autoSort = prefs.getBoolean(KEY_AUTO, true),
        askNewWifi = prefs.getBoolean(KEY_ASK_NEW, true),
        removeAfter = prefs.getBoolean(KEY_REMOVE, false),
        zones = WifiZones(
            work = prefs.getStringSet(KEY_ZONE_WORK, emptySet()).orEmpty().toSet(),
            home = prefs.getStringSet(KEY_ZONE_HOME, emptySet()).orEmpty().toSet(),
            neither = prefs.getStringSet(KEY_ZONE_NEITHER, emptySet()).orEmpty().toSet(),
        ),
    )

    fun update(f: (WorkSettings) -> WorkSettings) = synchronized(lock) {
        val next = f(_settings.value)
        prefs.edit {
            if (next.workFolder != null) putLong(KEY_FOLDER, next.workFolder) else remove(KEY_FOLDER)
            if (next.folderLabel != null) putString(KEY_FOLDER_LABEL, next.folderLabel) else remove(KEY_FOLDER_LABEL)
            putBoolean(KEY_SUBFOLDERS, next.subfolders)
            putBoolean(KEY_IN_MEMORIES, next.inMemories)
            putBoolean(KEY_AUTO, next.autoSort)
            putBoolean(KEY_ASK_NEW, next.askNewWifi)
            putBoolean(KEY_REMOVE, next.removeAfter)
            putStringSet(KEY_ZONE_WORK, next.zones.work)
            putStringSet(KEY_ZONE_HOME, next.zones.home)
            putStringSet(KEY_ZONE_NEITHER, next.zones.neither)
        }
        _settings.value = next
    }

    fun setZone(ssid: String, zone: WifiZone?) {
        update { it.copy(zones = it.zones.with(ssid, zone)) }
        if (_ask.value == ssid) setAsk(null)
    }

    fun setAsk(ssid: String?) {
        prefs.edit { if (ssid != null) putString(KEY_ASK, ssid) else remove(KEY_ASK) }
        _ask.value = ssid
    }

    // ─── the WLAN timeline ──────────────────────────────────────────

    private var timeline: List<WifiSeen>? = null

    fun timeline(): List<WifiSeen> = synchronized(lock) {
        timeline ?: DriveWifiTimeline.decode(read(FILE_WIFI)).also { timeline = it }
    }

    /** A look at the WLAN now ([ssid] null: none, or its name unknown). */
    fun seen(ssid: String?, at: Long = System.currentTimeMillis()) = synchronized(lock) {
        val before = timeline()
        val next = DriveWifiTimeline.add(before, WifiSeen(at, ssid, session))
        if (next !== before) {
            timeline = next
            write(FILE_WIFI, DriveWifiTimeline.encode(next))
        }
    }

    /** The WLAN at [t] (ms). */
    fun wifiAt(t: Long): String? = DriveWifiTimeline.wifiAt(timeline(), t, session)

    // ─── what the AI learned ────────────────────────────────────────

    fun examples(): List<WorkExample> = synchronized(lock) {
        if (!examplesRead) {
            examplesRead = true
            _examples.value = DriveWorkFeatures.decode(read(FILE_AI))
            _learned.value = DriveWorkFeatures.learned(_examples.value)
        }
        _examples.value
    }

    fun learn(e: WorkExample) = setExamples(DriveWorkFeatures.add(examples(), e))

    /** Takes back what was learned of [keys] (an undone assignment). */
    fun forget(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val set = keys.toSet()
        setExamples(examples().filter { it.key !in set })
    }

    /** "Neu lernen": every example goes. */
    fun resetLearning() {
        setExamples(emptyList())
        synchronized(lock) {
            features.clear()
            File(dir, FILE_FEATURES).delete()
        }
    }

    private fun setExamples(list: List<WorkExample>) = synchronized(lock) {
        _examples.value = list
        _learned.value = DriveWorkFeatures.learned(list)
        write(FILE_AI, DriveWorkFeatures.encode(list))
    }

    // the labels found per photo, so a photo is labeled once
    private val features = LinkedHashMap<String, Map<Int, Float>>(16, 0.75f, true)
    private var featuresRead = false

    fun features(key: String): Map<Int, Float>? = synchronized(lock) {
        readFeatures()
        features[key]
    }

    fun putFeatures(key: String, f: Map<Int, Float>) = synchronized(lock) {
        readFeatures()
        features[key] = f
        while (features.size > MAX_FEATURES) features.remove(features.keys.first())
        val o = JsonObject()
        features.forEach { (k, v) -> o.add(k, JsonObject().apply { v.forEach { (i, x) -> addProperty(i.toString(), x) } }) }
        write(FILE_FEATURES, o.toString())
    }

    private fun readFeatures() {
        if (featuresRead) return
        featuresRead = true
        runCatching {
            val text = read(FILE_FEATURES) ?: return
            JsonParser.parseString(text).asJsonObject.entrySet().forEach { (k, v) ->
                features[k] = v.asJsonObject.entrySet().mapNotNull { (i, x) -> i.toIntOrNull()?.let { it to x.asFloat } }.toMap()
            }
        }
    }

    // ─── review marks without Drive 1.13 ────────────────────────────

    private fun readLocalReview(): Map<Long, Int?> = runCatching {
        val text = prefs.getString(KEY_LOCAL_REVIEW, null) ?: return emptyMap()
        JsonParser.parseString(text).asJsonObject.entrySet().mapNotNull { (k, v) ->
            k.toLongOrNull()?.let { id -> id to v.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(0, 100) }
        }.toMap()
    }.getOrDefault(emptyMap())

    fun setLocalReview(ids: Collection<Long>, review: Boolean, hint: Int?) {
        _localReview.update { cur ->
            val next = if (review) cur + ids.associateWith { id -> hint ?: cur[id] } else cur - ids.toSet()
            val o = JsonObject()
            next.entries.toList().takeLast(MAX_LOCAL_REVIEW).forEach { (id, h) -> if (h != null) o.addProperty(id.toString(), h) else o.add(id.toString(), com.google.gson.JsonNull.INSTANCE) }
            prefs.edit { putString(KEY_LOCAL_REVIEW, o.toString()) }
            next
        }
    }

    // ─── removing work photos from the phone ────────────────────────

    private fun readRemovals(): List<Long> =
        prefs.getString(KEY_REMOVALS, null)?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()

    /** A work photo of the phone to remove ([video]: from the videos; kept as a negative id). */
    fun addRemoval(mediaId: Long, video: Boolean) = addRemoval(if (video) -mediaId else mediaId)

    private fun addRemoval(mediaId: Long) = _removals.update { cur ->
        (cur + mediaId).distinct().takeLast(MAX_REMOVALS).also { prefs.edit { putString(KEY_REMOVALS, it.joinToString(",")) } }
    }

    fun clearRemovals(ids: Collection<Long>) = _removals.update { cur ->
        (cur - ids.toSet()).also { prefs.edit { putString(KEY_REMOVALS, it.joinToString(",")) } }
    }

    fun setSuggested(n: Int) {
        prefs.edit { putInt(KEY_SUGGESTED, n); putLong(KEY_SUGGESTED_AT, System.currentTimeMillis()) }
        _suggested.value = n
    }

    /** When "Aufräumen" last looked (ms; 0: never). */
    fun suggestedAt(): Long = prefs.getLong(KEY_SUGGESTED_AT, 0L)

    // ─── files ──────────────────────────────────────────────────────

    private fun read(name: String): String? = runCatching { File(dir, name).takeIf { it.isFile }?.readText() }.getOrNull()

    private fun write(name: String, text: String) {
        try {
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(File(dir, name))) tmp.delete()
        } catch (e: Exception) {
            Timber.w("Drive work: not saved (%s)", e.javaClass.simpleName)
        }
    }

    private companion object {
        const val PREFS = "drive_work"
        const val FILE_WIFI = "drive-work-wifi.json"
        const val FILE_AI = "drive-work-ai.json"
        const val FILE_FEATURES = "drive-work-features.json"
        const val KEY_FOLDER = "folder"
        const val KEY_FOLDER_LABEL = "folder_label"
        const val KEY_SUBFOLDERS = "subfolders"
        const val KEY_IN_MEMORIES = "in_memories"
        const val KEY_AUTO = "auto"
        const val KEY_ASK_NEW = "ask_new"
        const val KEY_REMOVE = "remove"
        const val KEY_ZONE_WORK = "zone_work"
        const val KEY_ZONE_HOME = "zone_home"
        const val KEY_ZONE_NEITHER = "zone_neither"
        const val KEY_ASK = "ask"
        const val KEY_LOCAL_REVIEW = "local_review"
        const val KEY_REMOVALS = "removals"
        const val KEY_SUGGESTED = "suggested"
        const val KEY_SUGGESTED_AT = "suggested_at"
        const val MAX_FEATURES = 3000
        const val MAX_LOCAL_REVIEW = 5000
        const val MAX_REMOVALS = 2000
    }
}
