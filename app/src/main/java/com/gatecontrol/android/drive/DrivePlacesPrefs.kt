package com.gatecontrol.android.drive

import android.content.Context
import androidx.core.content.edit
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.familie.PluginDirectory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The switches of places and people: the person's on the server, and this phone's "Nur beim Laden". */
data class PlacesSettings(
    /** The apps send where photos were taken (server, default on). */
    val keepGeo: Boolean = true,
    /** Links and GateDrop without GPS data; null: the server does not offer it (the switch is hidden). */
    val stripGeoOnShare: Boolean? = null,
    /** Faces are recognised (server, default off). */
    val people: Boolean = false,
    /** The children's folders too (server, default off). */
    val peopleKids: Boolean = false,
    /** This phone recognises faces only while charging (default on). */
    val chargingOnly: Boolean = true,
    /** The server's switches were read at least once for this login. */
    val known: Boolean = false,
)

/**
 * Places and people on this phone (Drive 1.16): the person's switches as the server last said them (for the backup and
 * the background jobs, which must not ask every time), the device's "Nur beim Laden", the photos the backup just sent
 * whose faces are still to be found, and how far "Orte nachtragen" got. Kept per login.
 */
@Singleton
class DrivePlacesPrefs @Inject constructor(
    @ApplicationContext context: Context,
    private val setup: SetupRepository,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun owner() = PluginDirectory.ownerKey(setup.getServerUrl(), setup.getApiToken())

    private fun mine() {
        if (prefs.getString(KEY_OWNER, null) == owner()) return
        prefs.edit {
            clear()
            putString(KEY_OWNER, owner())
        }
    }

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<PlacesSettings> = _settings.asStateFlow()

    /** "Orte nachtragen": [running], photos looked at, places found. */
    data class GeoProgress(val running: Boolean = false, val looked: Int = 0, val located: Int = 0, val lastRunAt: Long = 0L)

    private val _geo = MutableStateFlow(readGeo())
    val geo: StateFlow<GeoProgress> = _geo.asStateFlow()

    private fun read(): PlacesSettings {
        if (prefs.getString(KEY_OWNER, null) != owner()) return PlacesSettings()
        return PlacesSettings(
            keepGeo = prefs.getBoolean(KEY_KEEP_GEO, true),
            stripGeoOnShare = if (prefs.contains(KEY_STRIP)) prefs.getBoolean(KEY_STRIP, true) else null,
            people = prefs.getBoolean(KEY_PEOPLE, false),
            peopleKids = prefs.getBoolean(KEY_KIDS, false),
            chargingOnly = prefs.getBoolean(KEY_CHARGING, true),
            known = prefs.getBoolean(KEY_KNOWN, false),
        )
    }

    private fun readGeo(): GeoProgress = if (prefs.getString(KEY_OWNER, null) != owner()) GeoProgress() else GeoProgress(
        looked = prefs.getInt(KEY_GEO_LOOKED, 0),
        located = prefs.getInt(KEY_GEO_LOCATED, 0),
        lastRunAt = prefs.getLong(KEY_GEO_LAST, 0L),
    )

    /** Another login since: the values of this one. */
    fun reload() {
        _settings.value = read()
        _geo.value = readGeo()
    }

    /** The server's answer of `photos/settings` (only the fields it has). */
    fun took(s: DPhotoSettings) {
        mine()
        prefs.edit {
            s.keepGeo?.let { putBoolean(KEY_KEEP_GEO, it) }
            if (s.stripGeoOnShare != null) putBoolean(KEY_STRIP, s.stripGeoOnShare) else remove(KEY_STRIP)
            s.people?.let { putBoolean(KEY_PEOPLE, it) }
            s.peopleKids?.let { putBoolean(KEY_KIDS, it) }
            putBoolean(KEY_KNOWN, true)
            if (s.people == false) remove(KEY_PENDING)
        }
        _settings.value = read()
    }

    fun setChargingOnly(on: Boolean) {
        mine()
        prefs.edit { putBoolean(KEY_CHARGING, on) }
        _settings.value = read()
    }

    // ─── faces of photos the backup just sent ───────────────────────

    /** A photo just backed up: Drive's node and the phone's media store id (its original is here). */
    data class Pending(val nodeId: Long, val mediaId: Long)

    fun addPending(nodeId: Long, mediaId: Long) {
        mine()
        val list = pending().filter { it.nodeId != nodeId } + Pending(nodeId, mediaId)
        prefs.edit { putString(KEY_PENDING, list.takeLast(PENDING_MAX).joinToString(";") { "${it.nodeId}:${it.mediaId}" }) }
    }

    fun pending(): List<Pending> {
        if (prefs.getString(KEY_OWNER, null) != owner()) return emptyList()
        return prefs.getString(KEY_PENDING, null).orEmpty().split(';').mapNotNull { e ->
            val (n, m) = e.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            Pending(n.toLongOrNull() ?: return@mapNotNull null, m.toLongOrNull() ?: return@mapNotNull null)
        }
    }

    fun donePending(nodeId: Long) {
        val list = pending().filter { it.nodeId != nodeId }
        prefs.edit { if (list.isEmpty()) remove(KEY_PENDING) else putString(KEY_PENDING, list.joinToString(";") { "${it.nodeId}:${it.mediaId}" }) }
    }

    // ─── the backfill's place in Drive's lists ───────────────────────

    fun cursor(key: String): String? = if (prefs.getString(KEY_OWNER, null) == owner()) prefs.getString(key, null) else null

    fun setCursor(key: String, value: String?) {
        mine()
        prefs.edit { if (value == null) remove(key) else putString(key, value) }
    }

    fun passAt(key: String): Long = if (prefs.getString(KEY_OWNER, null) == owner()) prefs.getLong("$key.at", 0L) else 0L

    fun setPassAt(key: String, at: Long) {
        mine()
        prefs.edit { putLong("$key.at", at) }
    }

    // ─── "Orte nachtragen" ──────────────────────────────────────────

    /** A run of "Orte nachtragen" starts or ends; [fresh]: started by the person (counts and place in the list anew). */
    fun geoRunning(on: Boolean, fresh: Boolean = false) {
        if (on) {
            mine()
            if (fresh) {
                prefs.edit {
                    putInt(KEY_GEO_LOOKED, 0)
                    putInt(KEY_GEO_LOCATED, 0)
                    remove(CURSOR_GEO)
                }
            }
        } else {
            prefs.edit { putLong(KEY_GEO_LAST, System.currentTimeMillis()) }
        }
        _geo.value = readGeo().copy(running = on)
    }

    fun geoLooked(located: Boolean) {
        prefs.edit {
            putInt(KEY_GEO_LOOKED, prefs.getInt(KEY_GEO_LOOKED, 0) + 1)
            if (located) putInt(KEY_GEO_LOCATED, prefs.getInt(KEY_GEO_LOCATED, 0) + 1)
        }
        _geo.value = readGeo().copy(running = _geo.value.running)
    }

    companion object {
        private const val PREFS = "drive_places"
        private const val KEY_OWNER = "owner"
        private const val KEY_KEEP_GEO = "keep_geo"
        private const val KEY_STRIP = "strip_geo"
        private const val KEY_PEOPLE = "people"
        private const val KEY_KIDS = "people_kids"
        private const val KEY_CHARGING = "charging"
        private const val KEY_KNOWN = "known"
        private const val KEY_PENDING = "pending"
        private const val KEY_GEO_LOOKED = "geo_looked"
        private const val KEY_GEO_LOCATED = "geo_located"
        private const val KEY_GEO_LAST = "geo_last"
        const val CURSOR_FACES = "faces_cursor"
        const val CURSOR_GEO = "geo_cursor"
        private const val PENDING_MAX = 500
    }
}
