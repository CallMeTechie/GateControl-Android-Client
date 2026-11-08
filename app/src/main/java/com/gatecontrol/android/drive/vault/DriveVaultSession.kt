package com.gatecontrol.android.drive.vault

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DVault
import com.gatecontrol.android.drive.DVaultDevice
import com.gatecontrol.android.drive.DVaultWrap
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveJson
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.DriveOfflineStore
import com.gatecontrol.android.drive.DFolder
import com.gatecontrol.android.drive.notOk
import com.gatecontrol.android.drive.vault.DriveVaultCrypto.DriveVaultException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The vault folders of Drive 1.9 on this phone: the vault keys (TK) — only in memory, unwrapped with the device key
 * ([DriveVaultDeviceKey]) or the recovery key —, the device's approval, and every encrypted step: names, uploads
 * (streamed in 1 MiB chunks into a ciphertext file, then the usual upload), downloads (decrypted into the app cache,
 * [wipeOpened] removes them after use). A `409 stale_key` loads the keys again and tries once more.
 */
@Singleton
class DriveVaultSession @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: DriveApi,
    private val deviceKey: DriveVaultDeviceKey,
    private val vaultNames: DriveVaultNames,
    /** "Tresor-Dateien offline": the ciphertext copies and what unlocking needs without a connection (wrapped keys). */
    private val offline: DriveOfflineStore,
) {
    /** vault id → key version → TK. Never written anywhere. */
    private val keys = ConcurrentHashMap<String, ConcurrentHashMap<Int, ByteArray>>()
    /** vault id → its current key version (from the server). */
    private val current = ConcurrentHashMap<String, Int>()
    /** nameCipher → name, for the names already decrypted. */
    private val names = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 2_000
    }

    init {
        // decrypted copies of an earlier version of the app (before Drive 1.22 there were some) and ciphertexts go
        runCatching { openDir().deleteRecursively() }
        runCatching { upDir().deleteRecursively() }
        wipeViewed()
        DriveVaultShares.clear()
    }

    fun deviceId(): String = deviceKey.deviceId()

    fun safetyNumber(): String = deviceKey.safetyNumber()

    fun deviceName(): String = "${Build.MANUFACTURER.orEmpty()} ${Build.MODEL.orEmpty()}".trim().ifEmpty { "Android" }

    private fun deviceBody() = DriveJson.deviceBody(DriveVaultCrypto.b64u(deviceKey.publicKey()), deviceName(), PLATFORM)

    fun unlocked(vaultId: String): Boolean = keys[vaultId]?.isNotEmpty() == true

    fun keyVersion(vaultId: String): Int? = current[vaultId] ?: keys[vaultId]?.keys?.maxOrNull()

    private fun tk(vaultId: String, version: Int): ByteArray? = keys[vaultId]?.get(version)

    private val _locks = MutableSharedFlow<Unit>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The vaults were locked ([lock]): an open vault page closes its content and asks again. */
    val locks: SharedFlow<Unit> = _locks.asSharedFlow()

    /** Some vault's keys are in memory. */
    fun anyUnlocked(): Boolean = keys.values.any { it.isNotEmpty() }

    /**
     * Locks every vault (Drive 1.11: after a while without use, when the app goes to the background, or by hand): the
     * keys in memory are overwritten and forgotten, the decrypted names forgotten; what was handed to another app
     * ([DriveVaultShares]) and the ciphertexts of the viewer go. An open viewer closes on [locks] (it drops its plaintext).
     */
    fun lock() {
        val gone = keys.values.toList().also { keys.clear() }
        gone.forEach { m -> m.values.forEach { it.fill(0) } }
        synchronized(names) { names.clear() }
        DriveVaultShares.clear()
        wipeOpened()
        _locks.tryEmit(Unit)
    }

    /** Whether this device is approved for [vault] (the vault's own list; opening still asks for the confirmation). */
    fun approved(vault: DVault): Boolean {
        val me = runCatching { deviceId() }.getOrNull() ?: return false
        return vault.myDevices.any { it.deviceId == me && it.state == "approved" }
    }

    /** The vault's name for the menu: decrypted while it is open, else the one this phone remembers. */
    fun label(vault: DVault): String? = nameOf(vault) ?: vaultNames.get(vault.id)

    /** Only these vaults are still there (the remembered names of others go). */
    fun keepNames(vaultIds: Set<String>) = vaultNames.keep(vaultIds)

    /**
     * The device key under the protection the setting wants (Drive 1.11: behind the confirmation; the switch
     * "Mit Fingerabdruck öffnen"). Called right after the person confirmed; failure leaves the key as it was.
     */
    suspend fun protectDeviceKey(withAuth: Boolean): Boolean = withContext(Dispatchers.IO) {
        runCatching { deviceKey.protect(withAuth) }
            .onFailure { Timber.w("Drive vault: device key not moved (%s)", it.javaClass.simpleName) }
            .isSuccess
    }

    private fun keep(vaultId: String, version: Int, tk: ByteArray) {
        keys.getOrPut(vaultId) { ConcurrentHashMap() }.put(version, tk)?.takeIf { it !== tk }?.fill(0)
    }

    // ─── approval and keys ──────────────────────────────────────────

    /** Where this device stands with a vault. */
    sealed class Access {
        /** The keys are in memory. */
        data object Open : Access()
        /** Not asked yet (or the request was declined / the device removed). */
        data class Unknown(val revoked: Boolean) : Access()
        /** Asked: an approved device compares [safetyNumber] and approves. */
        data class Pending(val safetyNumber: String) : Access()
    }

    /**
     * Fetches this device's keys of [vaultId] (`GET keys`) and unwraps them. Ok(Open) when they are there, else what
     * the device must do. [vault] gives the current key version.
     */
    suspend fun open(vault: DVault): DResult<Access> = withContext(Dispatchers.IO) {
        current[vault.id] = vault.keyVersion
        if (unlocked(vault.id) && tk(vault.id, vault.keyVersion) != null) return@withContext DResult.Ok(Access.Open)
        val did = deviceId()
        when (val r = api.vaultKeys(vault.id, did)) {
            is DResult.Ok -> {
                val k = r.value
                r.value.keyVersion?.let { current[vault.id] = it }
                if (k.state != "approved") {
                    // no longer approved (or never): what this phone keeps of the vault offline goes
                    offline.vaultRevoked(vault.id)
                    return@withContext DResult.Ok(if (k.state == "pending") Access.Pending(safetyNumber()) else Access.Unknown(revoked = k.state == "revoked"))
                }
                // the wrapped keys (never the keys themselves) for opening without a connection, while files are offline
                offline.keepVaultKeys(vault, did, k.keys)
                try {
                    unwrapAll(vault.id, k.keys)
                } catch (e: DriveVaultDeviceKey.AuthRequired) {
                    // the device key waits for the person's confirmation (Drive 1.11)
                    return@withContext DResult.Failed(CODE_AUTH, 0)
                } catch (e: DriveVaultDeviceKey.NoScreenLock) {
                    return@withContext DResult.Failed(CODE_NO_LOCK, 0)
                } catch (e: DriveVaultDeviceKey.Unavailable) {
                    return@withContext DResult.Failed("decrypt", 0)
                }
                if (unlocked(vault.id)) DResult.Ok(Access.Open) else DResult.Failed("decrypt", 0)
            }
            is DResult.Failed -> {
                // the device or the vault unknown, or the vault no longer mine: nothing of it stays offline
                if (r.code == "not_found" || r.http == 404 || r.http == 403) offline.vaultRevoked(vault.id)
                if (r.code == "not_found") DResult.Ok(Access.Unknown(revoked = false)) else r
            }
            DResult.Offline -> openOffline(vault.id)
            else -> r.notOk()
        }
    }

    /**
     * No connection: the vault opens with the wrapped keys this phone kept for its offline files (sealed to the device
     * key, which only the Keystore opens — after the person's confirmation like always). Offline without them.
     */
    private fun openOffline(vaultId: String): DResult<Access> {
        val e = offline.vaultEntry(vaultId, runCatching { deviceKey.storedDeviceId() }.getOrNull()) ?: return DResult.Offline
        try {
            unwrapAll(vaultId, e.keys)
        } catch (x: DriveVaultDeviceKey.AuthRequired) {
            return DResult.Failed(CODE_AUTH, 0)
        } catch (x: DriveVaultDeviceKey.NoScreenLock) {
            return DResult.Failed(CODE_NO_LOCK, 0)
        } catch (x: DriveVaultDeviceKey.Unavailable) {
            return DResult.Failed("decrypt", 0)
        }
        current.putIfAbsent(vaultId, e.vault.keyVersion)
        return if (unlocked(vaultId)) DResult.Ok(Access.Open) else DResult.Failed("decrypt", 0)
    }

    /** The vault as this phone kept it for its offline files (no connection); null when there is nothing usable. */
    fun offlineVault(vaultId: String): DVault? = offline.vaultEntry(vaultId, runCatching { deviceKey.storedDeviceId() }.getOrNull())?.vault

    /** The listing of [folderId] as last seen (no connection), for a vault with offline files. */
    fun offlineFolder(vaultId: String, folderId: Long): DFolder? =
        offline.vaultEntry(vaultId, runCatching { deviceKey.storedDeviceId() }.getOrNull())?.folders?.get(folderId)

    /** The name of the folder [folderId] of the vault as last seen, decrypted with the keys in memory (no connection). */
    fun offlineFolderName(vaultId: String, folderId: Long): String? {
        val e = offline.vaultEntry(vaultId, runCatching { deviceKey.storedDeviceId() }.getOrNull()) ?: return null
        val cipher = DriveVaultOfflineLogic.folderCipher(e, folderId) ?: return null
        return nameOfCipher(vaultId, cipher)
    }

    /** A listing of the vault as shown: kept for opening it without a connection while files of it are offline. */
    fun keepListing(vaultId: String, folder: DFolder) = offline.keepVaultFolder(vaultId, folder)

    /** The wrapped keys again (after a new key version), kept for the offline files; nothing is unwrapped. */
    suspend fun keepKeys(vaultId: String) = withContext(Dispatchers.IO) {
        if (!offline.hasVault(vaultId)) return@withContext
        val v = api.vault(vaultId).valueOrNull() ?: return@withContext
        val did = deviceId()
        val k = api.vaultKeys(vaultId, did).valueOrNull() ?: return@withContext
        if (DriveVaultOfflineLogic.revoked(k.state)) offline.vaultRevoked(vaultId) else offline.keepVaultKeys(v, did, k.keys)
    }

    private fun unwrapAll(vaultId: String, wraps: List<DVaultWrap>) {
        val pair = deviceKey.keyPair()
        try {
            for (w in wraps) {
                try {
                    keep(vaultId, w.keyVersion, DriveVaultCrypto.unwrapKey(w.wrap, pair, vaultId, w.keyVersion))
                } catch (e: DriveVaultException) {
                    Timber.w("Drive vault: key version %d not readable (%s)", w.keyVersion, e.code)
                }
            }
        } finally {
            pair.wipe()
        }
    }

    /** The keys again (after `stale_key`, a rotation or an approval). */
    suspend fun reload(vaultId: String): DResult<Access> {
        val v = api.vault(vaultId)
        if (v !is DResult.Ok) return v.notOk()
        keys.remove(vaultId)?.values?.forEach { it.fill(0) }
        return open(v.value)
    }

    /** This device asks for the vault: pending, with its safety number. */
    suspend fun ask(vaultId: String): DResult<Access> = withContext(Dispatchers.IO) {
        when (val r = api.vaultAsk(vaultId, deviceBody())) {
            is DResult.Ok -> DResult.Ok(if (r.value.state == "approved") reload(vaultId).valueOrNull() ?: Access.Pending(safetyNumber()) else Access.Pending(safetyNumber()))
            else -> r.notOk()
        }
    }

    /**
     * Opens the vault with the written-down recovery key [text]: its wraps give the keys, then this device approves
     * itself (`via: recovery` with the key's proof). Failed(`recovery_key`) for a wrong key.
     */
    suspend fun recover(vault: DVault, text: String): DResult<Access> = withContext(Dispatchers.IO) {
        val key = try {
            DriveVaultCrypto.parseRecoveryKey(text)
        } catch (e: DriveVaultException) {
            return@withContext DResult.Failed("recovery_key", 0)
        }
        val pair = DriveVaultCrypto.recoveryKeyPair(key)
        val auth = DriveVaultCrypto.recoveryAuth(key)
        key.fill(0)
        try {
            val rec = api.vaultRecovery(vault.id)
            if (rec !is DResult.Ok) return@withContext rec.notOk()
            val pub = rec.value.recoveryPub ?: vault.recoveryPub
            if (pub == null || pub != DriveVaultCrypto.b64u(pair.publicKey)) return@withContext DResult.Failed("recovery_key", 0)
            val tks = rec.value.keys.mapNotNull { w ->
                runCatching { w.keyVersion to DriveVaultCrypto.unwrapKey(w.wrap, pair, vault.id, w.keyVersion) }.getOrNull()
            }
            if (tks.isEmpty()) return@withContext DResult.Failed("recovery_key", 0)
            tks.forEach { (v, tk) -> keep(vault.id, v, tk) }
            rec.value.keyVersion?.let { current[vault.id] = it }
            // this device is the vault's from now on: asked, then approved with the recovery key's proof
            val ask = api.vaultAsk(vault.id, deviceBody())
            if (ask !is DResult.Ok) return@withContext ask.notOk()
            if (ask.value.state != "approved") {
                val mine = deviceKey.publicKey()
                val wraps = tks.map { (v, tk) -> DVaultWrap(v, DriveVaultCrypto.wrapKey(tk, mine, vault.id, v)) }
                val ok = api.vaultApprove(vault.id, deviceId(), wraps, recoveryAuth = auth)
                if (ok !is DResult.Ok) return@withContext ok.notOk()
            }
            DResult.Ok(Access.Open)
        } finally {
            pair.wipe()
        }
    }

    /**
     * Approves [device] of another phone or computer: every key version this device holds, wrapped for its public key.
     * The person compared [DriveVaultCrypto.safetyNumber] of [DVaultDevice.publicKey] before.
     */
    suspend fun approve(vaultId: String, device: DVaultDevice): DResult<DVaultDevice> = withContext(Dispatchers.IO) {
        val have = keys[vaultId]?.toMap().orEmpty()
        if (have.isEmpty()) return@withContext DResult.Failed("locked", 0)
        val pub = try {
            DriveVaultCrypto.unb64u(device.publicKey)
        } catch (e: DriveVaultException) {
            return@withContext DResult.Failed("invalid", 0)
        }
        val wraps = have.entries.sortedBy { it.key }.map { (v, tk) -> DVaultWrap(v, DriveVaultCrypto.wrapKey(tk, pub, vaultId, v)) }
        val r = api.vaultApprove(vaultId, device.deviceId, wraps)
        // the server wants the current version too: one this device lacks means its keys are old
        if (r is DResult.Failed && r.code == "invalid") reload(vaultId)
        r
    }

    // ─── a new key, a new recovery key ──────────────────────────────

    /** The keys of [vault] are in memory, the current version among them (loaded when not). */
    private suspend fun ensureKeys(vault: DVault): Boolean {
        current[vault.id] = vault.keyVersion
        if (tk(vault.id, vault.keyVersion) == null) {
            keys.remove(vault.id)?.values?.forEach { it.fill(0) }
            if (open(vault).valueOrNull() != Access.Open) return false
        }
        return tk(vault.id, vault.keyVersion) != null
    }

    /** A new vault key is in place; [recoveryKey]: a new recovery key had to be made (shown once). */
    data class Rotated(val vault: DVault, val recoveryKey: String?)

    /**
     * A new vault key (`POST vaults/<id>/rotate`, the write right): version n+1 for exactly every approved device and
     * for the recovery key. `409 { devices }` (the list changed) loads it again and tries once more; `409
     * recovery_required` (the holder of the recovery key no longer reads the area) makes a new recovery key with the
     * wraps of every version — this device must hold them all (else `old_keys`).
     */
    suspend fun rotate(vaultId: String): DResult<Rotated> = withContext(Dispatchers.IO) {
        var newRecovery = false
        var last: DResult<Rotated> = DResult.Failed("conflict", 409)
        for (attempt in 0 until ROTATE_TRIES) {
            val v = api.vault(vaultId)
            if (v !is DResult.Ok) return@withContext v.notOk()
            val vault = v.value
            if (!ensureKeys(vault)) return@withContext DResult.Failed("locked", 0)
            val devices = api.vaultDevices(vaultId)
            if (devices !is DResult.Ok) return@withContext devices.notOk()
            val next = vault.keyVersion + 1
            val tk = DriveVaultCrypto.newVaultKey()
            val recoveryKey = if (newRecovery || vault.recoveryPub == null) DriveVaultCrypto.randomBytes(32) else null
            var kept = false
            try {
                val body = try {
                    val recovery = recoveryKey?.let { key ->
                        DriveVaultRotation.recovery(key, vaultId, keys[vaultId]?.toMap().orEmpty() + (next to tk), next)
                    }
                    DriveVaultRotation.rotateBody(tk, vaultId, next, devices.value, vault.recoveryPub, recovery)
                } catch (e: DriveVaultException) {
                    return@withContext DResult.Failed(if (e.code == "old_keys") "old_keys" else "invalid", 0)
                }
                when (val r = api.vaultRotate(vaultId, body)) {
                    is DResult.Ok -> {
                        keep(vaultId, next, tk)
                        kept = true
                        current[vaultId] = r.value.keyVersion
                        return@withContext DResult.Ok(Rotated(r.value, recoveryKey?.let(DriveVaultCrypto::formatRecoveryKey)))
                    }
                    is DResult.Failed -> {
                        last = r
                        if (r.http != 409) return@withContext r
                        when {
                            DriveJson.recoveryRequired(r.body) -> newRecovery = true
                            // another device list, or another device made a new key meanwhile: load it again
                            DriveJson.rotateDevices(r.body) != null -> Unit
                            DriveJson.staleKeyVersion(r.body) != null -> reload(vaultId)
                            else -> return@withContext r
                        }
                    }
                    else -> return@withContext r.notOk()
                }
            } finally {
                if (!kept) tk.fill(0)
                recoveryKey?.fill(0)
            }
        }
        last
    }

    /**
     * A new recovery key (`PUT vaults/<id>/recovery`, the write right): every key version wrapped for it, the old one
     * stops working. → the key to write down (shown once). This device must hold every version (else `old_keys`).
     */
    suspend fun newRecovery(vaultId: String): DResult<String> = withContext(Dispatchers.IO) {
        val v = api.vault(vaultId)
        if (v !is DResult.Ok) return@withContext v.notOk()
        if (!ensureKeys(v.value)) return@withContext DResult.Failed("locked", 0)
        val key = DriveVaultCrypto.randomBytes(32)
        try {
            val part = try {
                DriveVaultRotation.recovery(key, vaultId, keys[vaultId]?.toMap().orEmpty(), v.value.keyVersion)
            } catch (e: DriveVaultException) {
                return@withContext DResult.Failed(if (e.code == "old_keys") "old_keys" else "invalid", 0)
            }
            when (val r = api.vaultSetRecovery(vaultId, DriveJson.recoveryBody(part))) {
                is DResult.Ok -> DResult.Ok(DriveVaultCrypto.formatRecoveryKey(key))
                else -> r.notOk()
            }
        } finally {
            key.fill(0)
        }
    }

    // ─── a new vault ────────────────────────────────────────────────

    /** A new vault and the recovery key the person writes down (shown once). */
    data class Created(val vault: DVault, val node: DNode?, val recoveryKey: String)

    suspend fun create(parentId: Long, name: String): DResult<Created> = withContext(Dispatchers.IO) {
        val id = DriveVaultCrypto.newVaultId()
        val tk = DriveVaultCrypto.newVaultKey()
        val recovery = DriveVaultCrypto.randomBytes(32)
        val rec = DriveVaultCrypto.recoveryKeyPair(recovery)
        try {
            val body = DriveJson.vaultBody(
                id = id,
                parentId = parentId,
                nameCipher = DriveVaultCrypto.encryptName(tk, id, 1, name.trim()),
                recoveryPub = DriveVaultCrypto.b64u(rec.publicKey),
                recoveryAuth = DriveVaultCrypto.recoveryAuth(recovery),
                recoveryWrap = DriveVaultCrypto.wrapKey(tk, rec.publicKey, id, 1),
                device = deviceBody(),
                wrap = DriveVaultCrypto.wrapKey(tk, deviceKey.publicKey(), id, 1),
            )
            when (val r = api.createVault(body)) {
                is DResult.Ok -> {
                    keep(id, 1, tk)
                    current[id] = r.value.vault.keyVersion
                    vaultNames.put(r.value.vault.id, name.trim())
                    DResult.Ok(Created(r.value.vault, r.value.node, DriveVaultCrypto.formatRecoveryKey(recovery)))
                }
                else -> {
                    tk.fill(0)
                    r.notOk()
                }
            }
        } finally {
            recovery.fill(0)
            rec.wipe()
        }
    }

    // ─── names ──────────────────────────────────────────────────────

    /** The name of a vault node, decrypted with the keys in memory; null when they are not there. */
    fun nameOf(node: DNode): String? {
        val vaultId = node.vaultId ?: return null
        val cipher = node.nameCipher ?: return null
        synchronized(names) { names[cipher] }?.let { return it }
        if (!unlocked(vaultId)) return null
        return try {
            DriveVaultCrypto.decryptName({ tk(vaultId, it) }, vaultId, cipher).also { n -> synchronized(names) { names[cipher] = n } }
        } catch (e: DriveVaultException) {
            null
        }
    }

    private fun nameOfCipher(vaultId: String, cipher: String): String? {
        synchronized(names) { names[cipher] }?.let { return it }
        if (!unlocked(vaultId)) return null
        return runCatching { DriveVaultCrypto.decryptName({ tk(vaultId, it) }, vaultId, cipher) }.getOrNull()?.also { n -> synchronized(names) { names[cipher] = n } }
    }

    /** The name of the vault itself (its folder's nameCipher); remembered for the menu ([label]). */
    fun nameOf(vault: DVault): String? {
        val cipher = vault.nameCipher ?: return null
        synchronized(names) { names[cipher] }?.let { return it }
        if (!unlocked(vault.id)) return null
        return runCatching { DriveVaultCrypto.decryptName({ tk(vault.id, it) }, vault.id, cipher) }.getOrNull()
            ?.also { n ->
                synchronized(names) { names[cipher] = n }
                vaultNames.put(vault.id, n)
            }
    }

    private fun encryptName(vaultId: String, name: String): Pair<String, Int>? {
        val v = keyVersion(vaultId) ?: return null
        val tk = tk(vaultId, v) ?: return null
        return DriveVaultCrypto.encryptName(tk, vaultId, v, name) to v
    }

    /** Tries [block] with the current key; after `409 stale_key` once more with the keys loaded again. */
    private suspend fun <T> fresh(vaultId: String, block: suspend () -> DResult<T>): DResult<T> {
        val r = block()
        if (r is DResult.Failed && r.http == 409 && DriveJson.staleKey(r.body)) {
            DriveJson.staleKeyVersion(r.body)?.let { current[vaultId] = it }
            val again = reload(vaultId)
            if (again !is DResult.Ok || again.value != Access.Open) return again.notOk()
            return block()
        }
        return r
    }

    suspend fun createFolder(vaultId: String, parentId: Long, name: String): DResult<DNode> = fresh(vaultId) {
        val (cipher, v) = encryptName(vaultId, name.trim()) ?: return@fresh DResult.Failed("locked", 0)
        api.createVaultFolder(parentId, cipher, v)
    }

    suspend fun rename(node: DNode, name: String): DResult<DNode> {
        val vaultId = node.vaultId ?: return DResult.Failed("invalid", 0)
        return fresh(vaultId) {
            val (cipher, _) = encryptName(vaultId, name.trim()) ?: return@fresh DResult.Failed("locked", 0)
            api.renameVault(node.id, cipher)
        }
    }

    // ─── contents ───────────────────────────────────────────────────

    private fun upDir() = File(context.cacheDir, "drive-vault-up")

    /** Where older versions of the app put decrypted copies (before Drive 1.22; nothing goes there any more). */
    private fun openDir() = File(context.cacheDir, "drive-vault-open")

    /**
     * Encrypts the picked [uri] (streamed, 1 MiB chunks) into a ciphertext file of the cache, uploads it and makes it a
     * file of folder [parentId] of the vault. [onProgress] gets 0..1.
     */
    suspend fun upload(
        vaultId: String,
        parentId: Long,
        uri: Uri,
        /** The name in the vault (else the picked file's own). */
        name: String? = null,
        /** A new version of this file of the vault (its opaque name and `onConflict: version`). */
        version: DNode? = null,
        onProgress: (Float) -> Unit = {},
    ): DResult<DNode> = withContext(Dispatchers.IO) {
        val (picked, size) = describe(uri) ?: return@withContext DResult.Failed("read", 0)
        val name = name?.let { DriveLogic.safeName(it) } ?: picked
        val opaque = version?.takeIf { it.vaultId == vaultId && !it.isDir }?.name?.takeIf { OPAQUE.matches(it) }
        if (version != null && opaque == null) return@withContext DResult.Failed("invalid", 0)
        fresh(vaultId) {
            val v = keyVersion(vaultId) ?: return@fresh DResult.Failed("locked", 0)
            val tk = tk(vaultId, v) ?: return@fresh DResult.Failed("locked", 0)
            val dir = upDir().apply { mkdirs() }
            val tmp = File.createTempFile("up", ".gcdv", dir)
            try {
                try {
                    val stream = context.contentResolver.openInputStream(uri) ?: return@fresh DResult.Failed("read", 0)
                    stream.use { inp ->
                        tmp.outputStream().buffered(256 * 1024).use { out ->
                            DriveVaultCrypto.encryptContent(tk, vaultId, v, inp, size, out, onProgress = { done ->
                                if (size > 0) onProgress(0.5f * done / size)
                            })
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    return@fresh DResult.Failed("read", 0)
                }
                val total = tmp.length().coerceAtLeast(1)
                val media = api.upload(tmp, "application/octet-stream") { sent -> onProgress(0.5f + 0.5f * sent / total) }
                if (media !is DResult.Ok) return@fresh media.notOk()
                val cipher = DriveVaultCrypto.encryptName(tk, vaultId, v, name)
                api.commitVault(media.value, parentId, cipher, v, null, opaqueName = opaque).let { r -> if (r is DResult.Ok) DResult.Ok(r.value.first) else r.notOk() }
            } finally {
                tmp.delete()
            }
        }
    }

    /** Name and size of a picked file; null when it cannot be read. */
    private fun describe(uri: Uri): Pair<String, Long>? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val name = c.getString(0)?.let { DriveLogic.safeName(it) } ?: "file"
            val size = if (c.isNull(1)) -1L else c.getLong(1)
            name to size
        }
    }.getOrNull()?.let { (name, size) ->
        if (size >= 0) name to size
        else runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull()?.takeIf { it >= 0 }?.let { name to it }
    }

    // ─── viewing (Drive plugin 1.22): in memory only ─────────────────

    /** The ciphertext of a video being watched (encrypted, as the server keeps it); [wipeViewed] empties it. */
    private fun viewDir() = File(context.cacheDir, "drive-vault-view")

    /**
     * Downloads [node]'s ciphertext and decrypts it into [output] (the caller's memory). `locked`, `stale_key` (a key
     * version this device did not hold: loaded now, try once more), `decrypt`, `invalid`, `too_large`.
     */
    private suspend fun decryptInto(node: DNode, output: OutputStream): DResult<Unit> {
        val vaultId = node.vaultId ?: return DResult.Failed("invalid", 0)
        if (!unlocked(vaultId)) return DResult.Failed("locked", 0)
        // "offline verfügbar": the ciphertext on this phone (the version Drive has; without a connection an older one)
        offline.vaultCipher(node)?.let { return decryptFile(vaultId, it, output) }
        val r = api.download(node.id, expectedSha256 = node.sha256)
        if (r is DResult.Offline) offline.vaultCipher(node, anyVersion = true)?.let { return decryptFile(vaultId, it, output) }
        if (r !is DResult.Ok) return r.notOk()
        offline.noticeChanged(node)
        return decryptStream(vaultId, output) { r.value.body() ?: throw DriveVaultException("invalid", "no body") }
    }

    /** The kept ciphertext [file] decrypted into [output] — read from the disk, decrypted in memory. */
    private suspend fun decryptFile(vaultId: String, file: File, output: OutputStream): DResult<Unit> =
        decryptStream(vaultId, output) { file.inputStream().buffered(256 * 1024) }.let { r -> if (r is DResult.Offline) DResult.Failed("failed", 0) else r }

    private suspend fun decryptStream(vaultId: String, output: OutputStream, open: () -> java.io.Closeable): DResult<Unit> {
        return try {
            var missing = false
            val src = open()
            src.use { s ->
                val inp = when (s) {
                    is okhttp3.ResponseBody -> s.byteStream()
                    is java.io.InputStream -> s
                    else -> throw DriveVaultException("invalid", "source")
                }
                inp.use { DriveVaultCrypto.decryptContent({ v -> tk(vaultId, v).also { if (it == null) missing = true } }, vaultId, it, output) }
            }
            if (missing) DResult.Failed("stale_key", 0) else DResult.Ok(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveVaultTooLarge) {
            DResult.Failed("too_large", 0)
        } catch (e: DriveVaultException) {
            if (e.message == "no key for this version") {
                reload(vaultId)
                DResult.Failed("stale_key", 0)
            } else {
                DResult.Failed(if (e.code == "decrypt") "decrypt" else "invalid", 0)
            }
        } catch (e: IOException) {
            DResult.Offline
        }
    }

    /** [node] decrypted into a byte array (a picture, a text) of at most [limit] bytes — never into a file. */
    suspend fun decryptToMemory(node: DNode, limit: Long): DResult<ByteArray> = withContext(Dispatchers.IO) {
        if (node.size > limit + 4096) return@withContext DResult.Failed("too_large", 0)
        val buf = PlainBuffer(DriveVaultCrypto.plainSizeOf(node.size).coerceAtMost(limit).toInt(), limit)
        when (val r = decryptInto(node, buf)) {
            is DResult.Ok -> DResult.Ok(buf.bytes())
            else -> { buf.wipe(); r.notOk() }
        }
    }

    /** [node] decrypted into an anonymous memory file (a PDF for PdfRenderer, a file for another app) — never a path. */
    suspend fun decryptToMemFile(node: DNode, limit: Long): DResult<DriveVaultMemFile> = withContext(Dispatchers.IO) {
        if (node.size > limit + 4096) return@withContext DResult.Failed("too_large", 0)
        val file = try {
            DriveVaultMemFile.create()
        } catch (e: Exception) {
            return@withContext DResult.Failed("unsupported", 0)
        }
        val out = file.output()
        val bounded = object : OutputStream() {
            var n = 0L
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                n += len
                if (n > limit) throw DriveVaultTooLarge()
                out.write(b, off, len)
            }
        }
        val r = try {
            decryptInto(node, bounded)
        } catch (e: Throwable) {
            file.close()
            throw e
        }
        if (r is DResult.Ok) DResult.Ok(file) else { file.close(); r.notOk() }
    }

    /**
     * A video being watched: its ciphertext in the cache (downloaded, or a link to the offline copy) and the chunks it
     * opens — the plaintext only while it plays. [owned]: the file goes on close (never the offline copy itself).
     */
    class Cipher(val file: File, val content: DriveVaultCrypto.Content, private val owned: Boolean = true) : java.io.Closeable {
        override fun close() {
            content.close()
            if (owned) file.delete()
        }
    }

    /**
     * The kept ciphertext [kept] for playing: a hard link in the viewer's folder (a newer version the background run
     * puts in place does not change what plays), else the copy itself. Never a second copy, never plaintext.
     */
    private fun openKept(vaultId: String, kept: File): DResult<Cipher> {
        val dir = viewDir().apply { mkdirs() }
        val link = File(dir, "kept-" + System.nanoTime() + ".gcdv")
        val linked = runCatching { android.system.Os.link(kept.path, link.path) }.isSuccess
        val file = if (linked) link else kept
        return try {
            val head = ByteArray(DriveVaultCrypto.HEADER)
            java.io.RandomAccessFile(file, "r").use { it.readFully(head) }
            val content = DriveVaultCrypto.openContent({ v -> tk(vaultId, v) }, vaultId, head, file.length())
            DResult.Ok(Cipher(file, content, owned = linked))
        } catch (e: DriveVaultException) {
            if (linked) link.delete()
            DResult.Failed(if (e.message == "no key for this version") "stale_key" else if (e.code == "decrypt") "decrypt" else "invalid", 0)
        } catch (e: IOException) {
            if (linked) link.delete()
            DResult.Failed("failed", 0)
        }
    }

    /** [node]'s ciphertext into the cache (encrypted, as on the server), opened for playing. [onProgress] gets 0..1. */
    suspend fun fetchCipher(node: DNode, onProgress: (Float) -> Unit = {}): DResult<Cipher> = withContext(Dispatchers.IO) {
        val vaultId = node.vaultId ?: return@withContext DResult.Failed("invalid", 0)
        if (!unlocked(vaultId)) return@withContext DResult.Failed("locked", 0)
        // "offline verfügbar": the ciphertext is on this phone already (streamed chunk by chunk from there)
        offline.vaultCipher(node)?.let { return@withContext openKept(vaultId, it) }
        val dir = viewDir().apply { mkdirs() }
        if (dir.usableSpace in 1 until node.size + 64L * 1_048_576) return@withContext DResult.Failed("no_space", 0)
        val file = File.createTempFile("view", ".gcdv", dir)
        var keep = false
        try {
            val r = api.download(node.id, expectedSha256 = node.sha256) { done, total -> if (total > 0) onProgress(0.5f * done / total) }
            if (r is DResult.Offline) offline.vaultCipher(node, anyVersion = true)?.let { return@withContext openKept(vaultId, it) }
            if (r !is DResult.Ok) return@withContext r.notOk()
            offline.noticeChanged(node)
            val body = r.value.body() ?: return@withContext DResult.Failed(DriveApi.CODE_INVALID, 0)
            val total = body.contentLength().takeIf { it > 0 } ?: node.size
            body.use { b ->
                b.byteStream().use { inp ->
                    file.outputStream().buffered(256 * 1024).use { out ->
                        val buf = ByteArray(256 * 1024)
                        var done = 0L
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress((done.toFloat() / total).coerceAtMost(1f))
                        }
                    }
                }
            }
            val head = ByteArray(DriveVaultCrypto.HEADER)
            java.io.RandomAccessFile(file, "r").use { it.readFully(head) }
            val content = try {
                DriveVaultCrypto.openContent({ v -> tk(vaultId, v) }, vaultId, head, file.length())
            } catch (e: DriveVaultException) {
                if (e.message == "no key for this version") { reload(vaultId); return@withContext DResult.Failed("stale_key", 0) }
                return@withContext DResult.Failed(if (e.code == "decrypt") "decrypt" else "invalid", 0)
            }
            keep = true
            DResult.Ok(Cipher(file, content))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            DResult.Offline
        } finally {
            if (!keep) file.delete()
        }
    }

    /** The ciphertexts of the viewer go (closed, locked, at the start). */
    fun wipeViewed() {
        runCatching { viewDir().deleteRecursively() }
    }

    /** Decrypted copies of older versions of the app (cacheDir/drive-vault-open, before Drive 1.22) go. */
    fun wipeOpened() {
        runCatching { openDir().deleteRecursively() }
    }

    companion object {
        /** `open`: the device key needs the person's confirmation first. */
        const val CODE_AUTH = "auth_required"
        /** `open`: the phone has no screen lock, the vault cannot be opened. */
        const val CODE_NO_LOCK = "no_screen_lock"
        private const val PLATFORM = "android"
        private const val ROTATE_TRIES = 3
        /** The opaque name of a vault node (32 hex characters, plugin `vault.js` OPAQUE_RE). */
        private val OPAQUE = Regex("^[0-9a-f]{32}$")
    }
}
