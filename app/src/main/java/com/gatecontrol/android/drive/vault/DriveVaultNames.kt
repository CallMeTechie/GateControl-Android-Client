package com.gatecontrol.android.drive.vault

import android.content.Context
import com.gatecontrol.android.familie.emergency.AesGcmCipher
import com.gatecontrol.android.familie.emergency.CacheCipher
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The names of the vaults themselves (not of anything inside), so the menu lists a locked vault by its name ("Steuer ·
 * Privat · gesperrt"). Kept on this phone only, encrypted with a Keystore key of its own (`MAGIC ‖ iv ‖ ciphertext` of
 * `{ "<vault id>": "<name>" }` in the no-backup files). Learnt whenever a vault is open; anything unreadable is dropped.
 */
@Singleton
class DriveVaultNames internal constructor(private val file: File, private val cipher: CacheCipher) {

    @Inject constructor(@ApplicationContext context: Context) : this(
        File(File(context.noBackupFilesDir, "drive-vault"), "names.bin"),
        AesGcmCipher { AesGcmCipher.keystoreKey(KEYSTORE_ALIAS) },
    )

    private var cache: MutableMap<String, String>? = null

    @Synchronized
    fun get(vaultId: String): String? = all()[vaultId]

    @Synchronized
    fun put(vaultId: String, name: String) {
        val m = all()
        if (m[vaultId] == name) return
        m[vaultId] = name.take(255)
        write(m)
    }

    /** Only these vaults are still there (the others go). */
    @Synchronized
    fun keep(vaultIds: Set<String>) {
        val m = all()
        if (m.keys.all { it in vaultIds }) return
        m.keys.retainAll(vaultIds)
        write(m)
    }

    private fun all(): MutableMap<String, String> {
        cache?.let { return it }
        val m = HashMap<String, String>()
        if (file.isFile) {
            runCatching {
                val blob = file.readBytes()
                require(blob.size > MAGIC.size && blob.copyOf(MAGIC.size).contentEquals(MAGIC))
                val o = JsonParser.parseString(String(cipher.decrypt(blob.copyOfRange(MAGIC.size, blob.size)), Charsets.UTF_8)).asJsonObject
                o.entrySet().forEach { (k, v) -> if (v.isJsonPrimitive) m[k] = v.asString }
            }.onFailure { file.delete() }
        }
        cache = m
        return m
    }

    private fun write(m: Map<String, String>) {
        runCatching {
            val o = JsonObject().apply { m.forEach { (k, v) -> addProperty(k, v) } }
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(MAGIC + cipher.encrypt(o.toString().toByteArray(Charsets.UTF_8)))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private companion object {
        const val KEYSTORE_ALIAS = "gatecontrol_drive_vault_names"
        val MAGIC = byteArrayOf(0x47, 0x44, 0x56, 0x4e, 0x01) // "GDVN" v1
    }
}
