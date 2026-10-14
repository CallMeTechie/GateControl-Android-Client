package com.gatecontrol.android.familie

import android.content.Context
import android.util.AtomicFile
import com.gatecontrol.android.data.SetupRepository
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.lang.reflect.Type
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small JSON files of the Familie feature in the app's private storage
 * (`files/familie/`): the profile, the shopping lists with their outbox,
 * the registered geofences. Each file is written atomically. Nothing here
 * is a secret; it is excluded from backups like the rest of the app.
 */
@Singleton
class FamilieStorage @Inject constructor(
    @ApplicationContext private val context: Context,
    private val setupRepository: SetupRepository,
) {
    private val gson = Gson()
    private val dir: File get() = File(context.filesDir, "familie").apply { mkdirs() }

    /** Login (server + token) the stored data belongs to. */
    fun ownerKey(): String = PluginDirectory.ownerKey(setupRepository.getServerUrl(), setupRepository.getApiToken())

    @Synchronized
    fun <T> read(name: String, type: Type): T? = try {
        val file = AtomicFile(File(dir, name))
        if (!file.baseFile.exists()) null
        else gson.fromJson<T>(String(file.readFully(), Charsets.UTF_8), type)
    } catch (e: Exception) {
        null
    }

    @Synchronized
    fun write(name: String, value: Any) {
        val file = AtomicFile(File(dir, name))
        val out = file.startWrite()
        try {
            out.write(gson.toJson(value).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
        }
    }

    @Synchronized
    fun delete(name: String) {
        AtomicFile(File(dir, name)).delete()
    }
}
