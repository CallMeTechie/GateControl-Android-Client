package com.gatecontrol.android.notizen

import android.content.Context
import android.os.Build
import com.gatecontrol.android.drive.DVaultDevice
import com.gatecontrol.android.drive.DVaultWrap
import com.gatecontrol.android.drive.DriveJson
import com.gatecontrol.android.drive.vault.DriveVaultCrypto
import com.gatecontrol.android.drive.vault.DriveVaultCrypto.DriveVaultException
import com.gatecontrol.android.drive.vault.DriveVaultDeviceKey
import com.gatecontrol.android.drive.vault.DriveVaultKeystore
import com.gatecontrol.android.drive.vault.DriveVaultRotation
import com.gatecontrol.android.familie.emergency.AesGcmCipher
import com.google.gson.JsonObject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Tresor notebooks on this phone — Drive's vault code reused with the label prefix `gcnotes-v1`
 * ([NotesVault.PREFIX]): a device key of its own in the Android Keystore ([DriveVaultDeviceKey] with its own file and
 * aliases, behind the device confirmation), HPKE wraps of the notebook keys, the recovery key and a new key after a
 * member left. The notebook keys and every decrypted title and text live in memory only ([lock] forgets them).
 */
@Singleton
class NotizenVaultSession @Inject constructor(
    @ApplicationContext context: Context,
    private val api: NotizenApi,
) {
    private val deviceKey = DriveVaultDeviceKey(
        File(File(context.noBackupFilesDir, "notizen-vault"), "device.key"),
        AesGcmCipher { AesGcmCipher.keystoreKey(KEYSTORE_ALIAS) },
        DriveVaultKeystore.authCipher(AUTH_ALIAS),
        { true },
        { DriveVaultKeystore.delete(KEYSTORE_ALIAS) },
        NotesVault.PREFIX,
    )

    /** vault id → key version → TK. Never written anywhere. */
    private val keys = ConcurrentHashMap<String, ConcurrentHashMap<Int, ByteArray>>()
    private val current = ConcurrentHashMap<String, Int>()
    private val names = ConcurrentHashMap<String, String>()

    @Volatile private var lastUse = 0L

    sealed class Access {
        data object Open : Access()
        data class Unknown(val revoked: Boolean) : Access()
        data class Pending(val safetyNumber: String) : Access()
    }

    fun deviceId(): String = deviceKey.deviceId()
    fun safetyNumber(): String = deviceKey.safetyNumber()
    private fun deviceName(): String = "${Build.MANUFACTURER.orEmpty()} ${Build.MODEL.orEmpty()}".trim().ifEmpty { "Android" }
    private fun deviceBody() = DriveJson.deviceBody(DriveVaultCrypto.b64u(deviceKey.publicKey()), deviceName(), "android")

    private fun tk(vaultId: String, v: Int): ByteArray? = keys[vaultId]?.get(v)
    private fun keyFn(vaultId: String): (Int) -> ByteArray? = { tk(vaultId, it) }

    fun unlocked(vaultId: String): Boolean {
        if (keys.isNotEmpty() && System.currentTimeMillis() - lastUse > IDLE_MS) lock()
        return keys[vaultId]?.isNotEmpty() == true
    }

    private fun keep(vaultId: String, v: Int, tk: ByteArray) {
        keys.getOrPut(vaultId) { ConcurrentHashMap() }.put(v, tk)?.takeIf { it !== tk }?.fill(0)
        lastUse = System.currentTimeMillis()
    }

    /** Forgets every key and decrypted name (after 15 minutes without use, as the portal does, and by hand). */
    fun lock() {
        keys.values.forEach { m -> m.values.forEach { it.fill(0) } }
        keys.clear()
        names.clear()
    }

    fun touch() {
        lastUse = System.currentTimeMillis()
    }

    private fun unwrapAll(vaultId: String, wraps: List<DVaultWrap>) {
        val pair = deviceKey.keyPair()
        try {
            for (w in wraps) {
                try {
                    keep(vaultId, w.keyVersion, DriveVaultCrypto.unwrapKey(w.wrap, pair, vaultId, w.keyVersion, NotesVault.PREFIX))
                } catch (e: DriveVaultException) {
                    Timber.w("Notizen vault: key version %d not readable (%s)", w.keyVersion, e.code)
                }
            }
        } finally {
            pair.wipe()
        }
    }

    /** The keys of [vault] (`GET keys`) unwrapped — call right after the device confirmation (`auth_required` else). */
    suspend fun open(vault: NVault): NResult<Access> = withContext(Dispatchers.IO) {
        current[vault.id] = vault.keyVersion
        if (unlocked(vault.id) && tk(vault.id, vault.keyVersion) != null) return@withContext NResult.Ok(Access.Open)
        when (val r = api.vaultKeys(vault.id, deviceId())) {
            is NResult.Ok -> {
                val k = r.value
                k.keyVersion?.let { current[vault.id] = it }
                if (k.state != "approved") {
                    return@withContext NResult.Ok(if (k.state == "pending") Access.Pending(safetyNumber()) else Access.Unknown(k.state == "revoked"))
                }
                try {
                    unwrapAll(vault.id, k.keys)
                } catch (e: DriveVaultDeviceKey.AuthRequired) {
                    return@withContext NResult.Failed(CODE_AUTH, 0)
                } catch (e: DriveVaultDeviceKey.NoScreenLock) {
                    return@withContext NResult.Failed(CODE_NO_LOCK, 0)
                } catch (e: DriveVaultDeviceKey.Unavailable) {
                    return@withContext NResult.Failed("decrypt", 0)
                }
                if (unlocked(vault.id)) NResult.Ok(Access.Open) else NResult.Failed("decrypt", 0)
            }
            is NResult.Failed -> if (r.code == "not_found") NResult.Ok(Access.Unknown(false)) else r
            else -> r.notOk()
        }
    }

    suspend fun reload(vaultId: String): NResult<Access> {
        val v = api.vault(vaultId)
        if (v !is NResult.Ok) return v.notOk()
        keys.remove(vaultId)?.values?.forEach { it.fill(0) }
        return open(v.value)
    }

    /** This device asks for the Tresor: pending, with its safety number to compare. */
    suspend fun ask(vaultId: String): NResult<Access> = withContext(Dispatchers.IO) {
        when (val r = api.vaultAsk(vaultId, deviceBody())) {
            is NResult.Ok -> NResult.Ok(if (r.value.state == "approved") reload(vaultId).valueOrNull() ?: Access.Pending(safetyNumber()) else Access.Pending(safetyNumber()))
            else -> r.notOk()
        }
    }

    /** Opens the Tresor with the written-down recovery key and approves this device with its proof. */
    suspend fun recover(vault: NVault, text: String): NResult<Access> = withContext(Dispatchers.IO) {
        val key = try {
            DriveVaultCrypto.parseRecoveryKey(text)
        } catch (e: DriveVaultException) {
            return@withContext NResult.Failed("recovery_key", 0)
        }
        val pair = DriveVaultCrypto.recoveryKeyPair(key, NotesVault.PREFIX)
        val auth = DriveVaultCrypto.recoveryAuth(key, NotesVault.PREFIX)
        key.fill(0)
        try {
            val rec = api.vaultRecovery(vault.id)
            if (rec !is NResult.Ok) return@withContext rec.notOk()
            val pub = rec.value.recoveryPub ?: vault.recoveryPub
            if (pub == null || pub != DriveVaultCrypto.b64u(pair.publicKey)) return@withContext NResult.Failed("recovery_key", 0)
            val tks = rec.value.keys.mapNotNull { w -> runCatching { w.keyVersion to DriveVaultCrypto.unwrapKey(w.wrap, pair, vault.id, w.keyVersion, NotesVault.PREFIX) }.getOrNull() }
            if (tks.isEmpty()) return@withContext NResult.Failed("recovery_key", 0)
            tks.forEach { (v, tk) -> keep(vault.id, v, tk) }
            rec.value.keyVersion?.let { current[vault.id] = it }
            val ask = api.vaultAsk(vault.id, deviceBody())
            if (ask !is NResult.Ok) return@withContext ask.notOk()
            if (ask.value.state != "approved") {
                val mine = deviceKey.publicKey()
                val wraps = tks.map { (v, tk) -> DVaultWrap(v, DriveVaultCrypto.wrapKey(tk, mine, vault.id, v, prefix = NotesVault.PREFIX)) }
                val ok = api.vaultApprove(vault.id, deviceId(), wraps, recoveryAuth = auth)
                if (ok !is NResult.Ok) return@withContext ok.notOk()
            }
            NResult.Ok(Access.Open)
        } finally {
            pair.wipe()
        }
    }

    /** The devices asking for [vaultId] (shown with their safety numbers). */
    suspend fun requests(vaultId: String): List<DVaultDevice> = api.vaultDevices(vaultId).valueOrNull().orEmpty().filter { it.state == "pending" }

    /** The safety number of another device (compared on both before approving). */
    fun safetyOf(device: DVaultDevice): String? = runCatching { DriveVaultCrypto.safetyNumber(DriveVaultCrypto.unb64u(device.publicKey), NotesVault.PREFIX) }.getOrNull()

    /** Approves [device]: every key version this phone holds, wrapped for its public key. */
    suspend fun approve(vaultId: String, device: DVaultDevice): NResult<DVaultDevice> = withContext(Dispatchers.IO) {
        val have = keys[vaultId]?.toMap().orEmpty()
        if (have.isEmpty()) return@withContext NResult.Failed("locked", 0)
        val pub = try {
            DriveVaultCrypto.unb64u(device.publicKey)
        } catch (e: DriveVaultException) {
            return@withContext NResult.Failed("invalid", 0)
        }
        if (DriveVaultCrypto.deviceId(pub, NotesVault.PREFIX) != device.deviceId) return@withContext NResult.Failed("invalid", 0)
        val wraps = have.entries.sortedBy { it.key }.map { (v, tk) -> DVaultWrap(v, DriveVaultCrypto.wrapKey(tk, pub, vaultId, v, prefix = NotesVault.PREFIX)) }
        api.vaultApprove(vaultId, device.deviceId, wraps)
    }

    /** A new Tresor notebook and its recovery key (shown once, before anything is stored). */
    data class Created(val vault: NVault, val notebook: NNotebook?, val recoveryKey: String)

    /** Makes the keys of a new Tresor (the recovery key first, so the person can write it down) — nothing is sent yet. */
    class Draft internal constructor(val id: String, internal val tk: ByteArray, internal val recovery: ByteArray) {
        val recoveryText: String get() = DriveVaultCrypto.formatRecoveryKey(recovery)
        fun wipe() {
            tk.fill(0)
            recovery.fill(0)
        }
    }

    fun draft(): Draft = Draft(DriveVaultCrypto.newVaultId(), DriveVaultCrypto.newVaultKey(), DriveVaultCrypto.randomBytes(32))

    /** `POST vaults` of [draft] named [name] in [area] — call right after the device confirmation. */
    suspend fun create(draft: Draft, name: String, area: String): NResult<Created> = withContext(Dispatchers.IO) {
        val rec = DriveVaultCrypto.recoveryKeyPair(draft.recovery, NotesVault.PREFIX)
        try {
            val body = JsonObject().apply {
                addProperty("id", draft.id)
                addProperty("area", area)
                addProperty("nameCipher", NotesVault.encryptName(draft.tk, draft.id, 1, name.trim()))
                addProperty("recoveryPub", DriveVaultCrypto.b64u(rec.publicKey))
                addProperty("recoveryAuth", DriveVaultCrypto.recoveryAuth(draft.recovery, NotesVault.PREFIX))
                addProperty("recoveryWrap", DriveVaultCrypto.wrapKey(draft.tk, rec.publicKey, draft.id, 1, prefix = NotesVault.PREFIX))
                add("device", deviceBody())
                addProperty("wrap", DriveVaultCrypto.wrapKey(draft.tk, deviceKey.publicKey(), draft.id, 1, prefix = NotesVault.PREFIX))
            }
            when (val r = api.createVault(body)) {
                is NResult.Ok -> {
                    keep(draft.id, 1, draft.tk.copyOf())
                    current[draft.id] = r.value.first.keyVersion
                    NResult.Ok(Created(r.value.first, r.value.second, draft.recoveryText))
                }
                else -> r.notOk()
            }
        } finally {
            rec.wipe()
            draft.wipe()
        }
    }

    /** A new key for exactly the approved devices (after a member left: `needsRotation`). */
    suspend fun rotate(vaultId: String): NResult<NVault> = withContext(Dispatchers.IO) {
        val v = api.vault(vaultId)
        if (v !is NResult.Ok) return@withContext v.notOk()
        val vault = v.value
        if (tk(vaultId, vault.keyVersion) == null) return@withContext NResult.Failed("locked", 0)
        val devices = api.vaultDevices(vaultId)
        if (devices !is NResult.Ok) return@withContext devices.notOk()
        val next = vault.keyVersion + 1
        val tk = DriveVaultCrypto.newVaultKey()
        var kept = false
        try {
            val body = try {
                DriveVaultRotation.rotateBody(tk, vaultId, next, devices.value, vault.recoveryPub, null, prefix = NotesVault.PREFIX)
            } catch (e: DriveVaultException) {
                return@withContext NResult.Failed(e.code, 0)
            }
            when (val r = api.vaultRotate(vaultId, body)) {
                is NResult.Ok -> {
                    keep(vaultId, next, tk)
                    kept = true
                    current[vaultId] = r.value.keyVersion
                    r
                }
                else -> r
            }
        } finally {
            if (!kept) tk.fill(0)
        }
    }

    // ── text ────────────────────────────────────────────────

    fun keyVersion(vaultId: String): Int? = current[vaultId] ?: keys[vaultId]?.keys?.maxOrNull()

    /** A name cipher (the notebook's, a note's title, an attachment's) with the keys in memory; null while locked. */
    fun name(vaultId: String, cipher: String?): String? {
        if (cipher == null) return null
        names[cipher]?.let { return it }
        if (!unlocked(vaultId)) return null
        return runCatching { NotesVault.decryptName(keyFn(vaultId), vaultId, cipher) }.getOrNull()?.also { names[cipher] = it; touch() }
    }

    /** A note's Markdown; null while locked or not readable. */
    fun text(vaultId: String, cipher: String?): String? {
        if (cipher == null || !unlocked(vaultId)) return null
        return runCatching { NotesVault.decryptText(keyFn(vaultId), vaultId, cipher) }.getOrNull()?.also { touch() }
    }

    /** Title and text → `{ titleCipher, cipher, keyVersion }` with the current key; null while locked. */
    fun seal(vaultId: String, title: String, body: String): Triple<String, String, Int>? {
        val v = keyVersion(vaultId) ?: return null
        val tk = tk(vaultId, v) ?: return null
        touch()
        return Triple(NotesVault.encryptName(tk, vaultId, v, title.ifBlank { " " }), NotesVault.encryptText(tk, vaultId, v, body), v)
    }

    /** An attachment's bytes and name → ciphertext and name cipher; null while locked. */
    fun sealFile(vaultId: String, bytes: ByteArray, name: String): Pair<ByteArray, String>? {
        val v = keyVersion(vaultId) ?: return null
        val tk = tk(vaultId, v) ?: return null
        touch()
        return NotesVault.encryptBytes(tk, vaultId, v, bytes) to NotesVault.encryptName(tk, vaultId, v, name)
    }

    fun openFile(vaultId: String, cipher: ByteArray): ByteArray? =
        if (!unlocked(vaultId)) null else runCatching { NotesVault.decryptBytes(keyFn(vaultId), vaultId, cipher) }.getOrNull()

    companion object {
        const val CODE_AUTH = "auth_required"
        const val CODE_NO_LOCK = "no_screen_lock"
        private const val KEYSTORE_ALIAS = "gatecontrol_notizen_vault_device"
        private const val AUTH_ALIAS = "gatecontrol_notizen_vault_device_auth"
        /** As the portal: the keys go after 15 minutes without use. */
        private const val IDLE_MS = 15 * 60_000L
    }
}
