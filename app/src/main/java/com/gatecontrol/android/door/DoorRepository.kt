package com.gatecontrol.android.door

import android.content.Context
import android.util.AtomicFile
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.familie.PluginDirectory
import com.gatecontrol.android.notify.model.PushMessage
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * State of the door tab shared by its screens: the last `GET me` (also
 * kept on the device, so the tab shows the locks while offline — never
 * pictures or history, those stay on the server) and a signal when a door
 * push arrives (e.g. "automatisch aufgesperrt") so the tab reloads.
 */
@Singleton
class DoorRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val setupRepository: SetupRepository,
    private val api: DoorApi,
) {
    private val _overview = MutableStateFlow<DoorOverview?>(null)
    val overview: StateFlow<DoorOverview?> = _overview.asStateFlow()

    private val _fromCache = MutableStateFlow(false)
    /** [overview] comes from the device, the server could not be reached. */
    val fromCache: StateFlow<Boolean> = _fromCache.asStateFlow()

    private val _pushes = MutableSharedFlow<DoorPushInfo>(extraBufferCapacity = 8)
    val pushes: SharedFlow<DoorPushInfo> = _pushes.asSharedFlow()

    private val file: AtomicFile get() = AtomicFile(File(File(context.filesDir, "door").apply { mkdirs() }, CACHE_FILE))

    private fun ownerKey(): String = PluginDirectory.ownerKey(setupRepository.getServerUrl(), setupRepository.getApiToken())

    /** The stored `me` of this login, shown until the server answers. */
    suspend fun loadCached(): DoorOverview? {
        _overview.value?.let { return it }
        val cached = withContext(Dispatchers.IO) { readCache() } ?: return null
        if (_overview.value == null) {
            _overview.value = cached
            _fromCache.value = true
        }
        return cached
    }

    /** Asks the server; on success the answer is shown and stored. */
    suspend fun refresh(): DoorResult<DoorOverview> {
        return when (val r = api.meRaw()) {
            is DoorResult.Ok -> {
                val (overview, raw) = r.value
                _overview.value = overview
                _fromCache.value = false
                withContext(Dispatchers.IO) { writeCache(raw) }
                DoorResult.Ok(overview)
            }
            is DoorResult.Failed -> {
                // No right any more, plugin gone or licence ended: forget what the device kept.
                if (r.http == 403 || r.http == 404) clear()
                r
            }
            DoorResult.Offline -> {
                if (_overview.value != null) _fromCache.value = true
                DoorResult.Offline
            }
            DoorResult.NotConfigured -> DoorResult.NotConfigured
        }
    }

    /** A lock as an action answered it: shown right away (and in the next stored `me`). */
    fun updateLock(lock: DoorLock) {
        val current = _overview.value ?: return
        if (current.locks.none { it.id == lock.id }) return
        _overview.value = current.copy(locks = current.locks.map { if (it.id == lock.id) lock else it })
    }

    fun updatePrefs(cameraId: Long, prefs: DoorCameraPrefs) {
        val current = _overview.value ?: return
        _overview.value = current.copy(cameras = current.cameras.map { if (it.id == cameraId) it.copy(prefs = prefs) else it })
    }

    /** A door push arrived on this device. */
    fun onPush(message: PushMessage) {
        val info = DoorPush.parse(message) ?: return
        _pushes.tryEmit(info)
    }

    suspend fun clear() {
        _overview.value = null
        _fromCache.value = false
        withContext(Dispatchers.IO) { runCatching { file.delete() } }
    }

    private fun readCache(): DoorOverview? = try {
        val f = file
        if (!f.baseFile.exists()) null
        else {
            val root = JsonParser.parseString(String(f.readFully(), Charsets.UTF_8)).asJsonObject
            if (root.get("owner")?.asString != ownerKey()) null
            else root.getAsJsonObject("me")?.let(DoorJson::overview)
        }
    } catch (e: Exception) {
        null
    }

    private fun writeCache(raw: JsonObject) {
        val f = file
        val out = f.startWrite()
        try {
            val root = JsonObject().apply {
                addProperty("owner", ownerKey())
                // Only what `GET me` returns: rights, locks, states, cameras — no pictures.
                add("me", raw)
            }
            out.write(root.toString().toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
        }
    }

    companion object {
        private const val CACHE_FILE = "me.json"
    }
}
