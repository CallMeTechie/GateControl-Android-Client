package com.gatecontrol.android.vault

import android.content.Context
import android.os.Build
import com.gatecontrol.android.R
import com.gatecontrol.android.support.CrashReports
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vault on this device: the encrypted copy ([VaultStore]), the keys while unlocked ([VaultSession]) and the server
 * ([VaultApi]). Works offline — saves wait in the outbox and go out with the next sync; a conflict keeps both
 * versions. The decrypted entries exist only in memory and only while unlocked.
 */
@Singleton
class VaultRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: VaultApi,
    private val store: VaultStore,
    private val session: VaultSession,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)
    /** One change of the state (and one network write sequence) at a time. */
    private val mutex = Mutex()

    private val _state = MutableStateFlow(VaultState())
    val state: StateFlow<VaultState> = _state.asStateFlow()

    sealed class Sync {
        data object Idle : Sync()
        data object Running : Sync()
        data object Done : Sync()
        data object Offline : Sync()
        data class Error(val code: String) : Sync()
    }

    private val _sync = MutableStateFlow<Sync>(Sync.Idle)
    val sync: StateFlow<Sync> = _sync.asStateFlow()

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    /** Every entry not purged (trash included), decrypted — only while unlocked. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val _vaults = MutableStateFlow<List<VaultView>>(emptyList())
    val vaults: StateFlow<List<VaultView>> = _vaults.asStateFlow()

    /** Entries that could not be decrypted (wrong key, damaged): counted, never shown as garbage. */
    private val _unreadable = MutableStateFlow(0)
    val unreadable: StateFlow<Int> = _unreadable.asStateFlow()

    /** Short notices for the screen (a conflict copy was made, a save was refused, …). */
    private val _notices = MutableStateFlow<List<String>>(emptyList())
    val notices: StateFlow<List<String>> = _notices.asStateFlow()

    /** Decrypted entries by id with the blob they came from (only redone when the blob changes). */
    private val decrypted = HashMap<String, Pair<String, EntryData>>()

    @Volatile private var loadedOwner: String? = null

    val unlocked: StateFlow<Boolean> get() = session.unlocked

    /** Loads this login's copy (once per login; again after another server or token). */
    fun ensureLoaded() {
        val owner = store.owner()
        if (owner == loadedOwner) return
        loadedOwner = owner
        session.lock()
        decrypted.clear()
        _state.value = store.load()
        rebuild()
    }

    private fun setState(s: VaultState) {
        _state.value = s
        store.save(s)
    }

    fun notice(text: String) {
        _notices.value = (_notices.value + text).takeLast(5)
    }

    fun clearNotices() {
        _notices.value = emptyList()
    }

    /** This person's GateControl user id: the owner of the personal vault. */
    fun myUserId(): Int? = _state.value.vaults.firstOrNull { it.personal }?.ownerId

    private val deviceName: String get() = Build.MODEL?.take(80) ?: "Android"

    // ─── account ────────────────────────────────────────────────────

    /** GET account: is Vault set up here? Offline: what the copy knows. */
    suspend fun refreshAccount(): VResult<VaultCrypto.Account?> {
        ensureLoaded()
        val r = api.account()
        if (r is VResult.Ok) mutex.withLock {
            val s = _state.value
            setState(if (r.value == null) VaultState(known = true) else s.copy(account = r.value, known = true))
        }
        return r
    }

    /**
     * Setup: keys, the personal vault, POST account. Returns the recovery key to show (once) or the failure. The
     * person is unlocked afterwards.
     */
    suspend fun setup(password: String, personalName: String): VResult<String> {
        ensureLoaded()
        val setup = withContext(Dispatchers.Default) { VaultCrypto.createAccount(password, personalName) }
        val r = api.createAccount(setup.body)
        if (r !is VResult.Ok) {
            setup.keys.wipe()
            setup.personalKey.fill(0)
            return r.notOk()
        }
        mutex.withLock {
            setState(VaultState(account = r.value, known = true))
            session.open(setup.keys)
            session.putKey(setup.body.personal.id, 1, setup.personalKey)
            setup.personalKey.fill(0)
        }
        syncNow()
        return VResult.Ok(setup.recoveryKey)
    }

    sealed class Unlock {
        data object Ok : Unlock()
        data object Wrong : Unlock()
        data object NoAccount : Unlock()
    }

    private fun opened(keys: VaultCrypto.Keys): Unlock {
        session.open(keys)
        rebuild()
        syncNow()
        return Unlock.Ok
    }

    /** The master password → unlocked (works offline with the copy). Argon2id runs off the main thread. */
    suspend fun unlock(password: String): Unlock {
        ensureLoaded()
        val account = _state.value.account ?: return Unlock.NoAccount
        val keys = withContext(Dispatchers.Default) {
            try {
                VaultCrypto.unlock(account, password)
            } catch (e: VaultCrypto.VaultCryptoException) {
                null
            }
        } ?: return Unlock.Wrong
        return opened(keys)
    }

    /** The user key from the fingerprint (VaultBiometric). */
    fun unlockWithUserKey(userKey: ByteArray): Unlock {
        ensureLoaded()
        val account = _state.value.account ?: return Unlock.NoAccount
        val keys = try {
            VaultCrypto.keysFrom(account.publicKey, account.privateKeyEnc, userKey)
        } catch (e: VaultCrypto.VaultCryptoException) {
            return Unlock.Wrong
        }
        return opened(keys)
    }

    /** The recovery key → unlocked; the caller then sets a new password with [completeRecovery]. */
    suspend fun unlockWithRecovery(recoveryText: String): Unlock {
        ensureLoaded()
        val account = _state.value.account ?: return Unlock.NoAccount
        val keys = withContext(Dispatchers.Default) {
            try {
                VaultCrypto.unlockWithRecovery(account, recoveryText)
            } catch (e: VaultCrypto.VaultCryptoException) {
                null
            }
        } ?: return Unlock.Wrong
        return opened(keys)
    }

    /** After a recovery: a new password and a new recovery key (returned, to show once). Needs the server. */
    suspend fun completeRecovery(newPassword: String): VResult<String> = updateAccount { account, keys ->
        val (update, recovery) = VaultCrypto.recover(account, keys, newPassword)
        update to recovery
    }

    /** A new master password (the user key stays — nothing else changes). */
    suspend fun changePassword(newPassword: String): VResult<String> = updateAccount { account, keys ->
        VaultCrypto.changePassword(account, keys, newPassword) to ""
    }

    private suspend fun updateAccount(make: (VaultCrypto.Account, VaultCrypto.Keys) -> Pair<VaultCrypto.AccountUpdate, String>): VResult<String> {
        val keys = session.keys() ?: return VResult.Failed("locked", 0)
        repeat(2) {
            val account = _state.value.account ?: return VResult.Failed("no_account", 0)
            val (update, extra) = withContext(Dispatchers.Default) { make(account, keys) }
            when (val r = api.updateAccount(update)) {
                is VResult.Ok -> {
                    mutex.withLock { setState(_state.value.copy(account = r.value)) }
                    return VResult.Ok(extra)
                }
                is VResult.Failed -> {
                    // another device changed it meanwhile: take theirs and do it again on top
                    val current = VaultJson.account(r.body?.obj("account"))
                    if (r.code == "conflict" && current != null) {
                        mutex.withLock { setState(_state.value.copy(account = current)) }
                    } else {
                        return r
                    }
                }
                else -> return r.notOk()
            }
        }
        return VResult.Failed("conflict", 409)
    }

    /** Deletes the account and everything only this person had (needs the server). */
    suspend fun deleteAccount(): VResult<Unit> {
        val r = api.deleteAccount()
        if (r is VResult.Ok) mutex.withLock {
            session.lock()
            decrypted.clear()
            store.clear()
            setState(VaultState(known = true))
            rebuild()
        }
        return r
    }

    fun lock() {
        session.lock()
        decrypted.clear()
        rebuild()
    }

    // ─── decrypted view ─────────────────────────────────────────────

    private fun decryptBlob(vault: VVault, id: String, rev: Int, keyRev: Int, blob: String): EntryData? {
        val key = session.keysOf(vault)[keyRev] ?: return null
        return try {
            EntryData.fromJson(VaultCrypto.decryptItemJson(key, vault.id, id, rev, blob))
        } catch (e: VaultCrypto.VaultCryptoException) {
            null
        }
    }

    /** Redoes the decrypted lists from the copy (cheap: unchanged blobs are not decrypted again). */
    private fun rebuild() {
        if (session.keys() == null) {
            _entries.value = emptyList()
            _vaults.value = emptyList()
            _unreadable.value = 0
            return
        }
        val s = _state.value
        val vaultById = s.vaults.associateBy { it.id }
        val views = s.vaults.mapNotNull { v ->
            val key = session.keysOf(v)[v.keyRev] ?: return@mapNotNull null
            val name = runCatching { VaultCrypto.decryptName(key, v.id, v.nameEnc) }.getOrNull() ?: "…"
            VaultView(v, name)
        }
        val pending = s.outbox.associateBy { it.id }
        var unreadable = 0
        val out = ArrayList<Entry>(s.items.size + pending.size)
        val seen = HashSet<String>()
        for (item in s.items.values) {
            if (item.purged) continue
            val vault = vaultById[item.vaultId] ?: continue
            seen += item.id
            val p = pending[item.id]
            val (blob, rev, keyRev, vid) = if (p != null) Quad(p.blob, p.baseRev + 1, p.keyRev, p.vaultId) else Quad(item.blob ?: continue, item.rev, item.keyRev, item.vaultId)
            val v = vaultById[vid] ?: vault
            val data = decrypted[item.id]?.takeIf { it.first == blob }?.second
                ?: decryptBlob(v, item.id, rev, keyRev, blob)?.also { decrypted[item.id] = blob to it }
            if (data == null) {
                unreadable++
                continue
            }
            out += Entry(item.id, vid, item.rev, item.trashed, item.trashedAt, item.updatedAt, item.updatedBy, item.device, data, pending = p != null)
        }
        for (p in s.outbox) {
            if (p.id in seen) continue
            val v = vaultById[p.vaultId] ?: continue
            val data = decrypted[p.id]?.takeIf { it.first == p.blob }?.second
                ?: decryptBlob(v, p.id, p.baseRev + 1, p.keyRev, p.blob)?.also { decrypted[p.id] = p.blob to it }
                ?: continue
            out += Entry(p.id, p.vaultId, p.baseRev, false, null, null, myUserId(), deviceName, data, pending = true)
        }
        decrypted.keys.retainAll(out.map { it.id }.toSet())
        _vaults.value = views.sortedWith(compareBy({ !it.vault.personal }, { it.name.lowercase() }))
        _entries.value = out.sortedBy { it.data.title.lowercase() }
        _unreadable.value = unreadable
    }

    private data class Quad(val blob: String, val rev: Int, val keyRev: Int, val vaultId: String)

    fun entry(id: String): Entry? = _entries.value.firstOrNull { it.id == id }

    fun vaultView(id: String): VaultView? = _vaults.value.firstOrNull { it.id == id }

    // ─── sync ───────────────────────────────────────────────────────

    /** Starts a sync in the background (no-op while one runs). */
    fun syncNow() {
        scope.launch { sync() }
    }

    /** Sends the outbox, then fetches what changed; afterwards a manager's key changes run. */
    suspend fun sync(): Sync {
        ensureLoaded()
        if (_sync.value == Sync.Running) return Sync.Running
        _sync.value = Sync.Running
        val result = try {
            mutex.withLock { syncLocked() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Vault: sync failed (%s)", e.javaClass.simpleName)
            Sync.Error("internal")
        }
        _sync.value = result
        if (result == Sync.Done && session.keys() != null) runRekeys()
        return result
    }

    private suspend fun syncLocked(): Sync {
        if (_state.value.account == null) {
            when (val r = api.account()) {
                is VResult.Ok -> {
                    if (r.value == null) {
                        setState(VaultState(known = true))
                        rebuild()
                        return Sync.Done
                    }
                    setState(_state.value.copy(account = r.value, known = true))
                }
                VResult.Offline -> return Sync.Offline
                is VResult.Failed -> return Sync.Error(r.code)
                VResult.NotConfigured -> return Sync.Error("not_configured")
            }
        }
        flushLocked()?.let { return it }
        val before = _state.value.vaults.map { it.id }.toSet()
        when (val r = pull(null, _state.value.seq)) {
            null -> Unit
            else -> return r
        }
        // a vault shared with this person since: all its entries (they are older than our counter)
        for (v in _state.value.vaults) if (v.id !in before && before.isNotEmpty()) pull(v.id, 0)?.let { return it }
        rebuild()
        // the outbox again: saves made while we were fetching
        flushLocked()?.let { return it }
        return Sync.Done
    }

    /** All pages of GET sync from [since]; null when done, else why not. */
    private suspend fun pull(vault: String?, since: Long): Sync? {
        var from = since
        var guard = 0
        while (guard++ < 200) {
            when (val r = api.sync(from, vault)) {
                is VResult.Ok -> {
                    var s = _state.value.merge(r.value, System.currentTimeMillis())
                    if (!r.value.more && vault == null) s = s.copy(seq = r.value.next)
                    setState(s)
                    if (!r.value.more) return null
                    from = r.value.next
                }
                VResult.Offline -> return Sync.Offline
                is VResult.Failed -> return if (r.code == "no_account") {
                    setState(VaultState(known = true))
                    session.lock()
                    rebuild()
                    Sync.Done
                } else {
                    Sync.Error(r.code)
                }
                VResult.NotConfigured -> return Sync.Error("not_configured")
            }
        }
        return Sync.Error("too_many_pages")
    }

    /** POST items for the outbox in requests ≤ ~48 KB; null when done (or nothing to do), else why not. */
    private suspend fun flushLocked(): Sync? {
        var rounds = 0
        while (_state.value.outbox.isNotEmpty() && rounds++ < 20) {
            val batch = ArrayList<VaultJson.SaveItem>()
            var bytes = 0
            for (p in _state.value.outbox) {
                val b = VaultJson.saveItemBytes(p)
                if (batch.isNotEmpty() && (bytes + b > BATCH_BYTES || batch.size >= BATCH_ITEMS)) break
                batch += p
                bytes += b
            }
            when (val r = api.save(batch, deviceName)) {
                is VResult.Ok -> applyResults(batch, r.value)
                VResult.Offline -> return Sync.Offline
                is VResult.Failed -> return Sync.Error(r.code)
                VResult.NotConfigured -> return Sync.Error("not_configured")
            }
        }
        return null
    }

    private fun applyResults(batch: List<VaultJson.SaveItem>, results: List<VSaveResult>) {
        var s = _state.value
        val byId = results.associateBy { it.id }
        for (sent in batch) {
            val res = byId[sent.id] ?: continue
            val queued = s.outbox.firstOrNull { it.id == sent.id }
            // the entry was changed again meanwhile: that newer save stays queued on top of the new revision
            val changedAgain = queued != null && queued.blob != sent.blob
            val items = s.items.toMutableMap()
            var outbox = s.outbox
            when {
                res.ok -> {
                    val old = items[sent.id]
                    items[sent.id] = VItem(
                        id = sent.id, vaultId = sent.vaultId, rev = res.rev ?: (sent.baseRev + 1), keyRev = sent.keyRev,
                        blob = sent.blob, state = if (old?.trashed == true) "trash" else "live", trashedAt = old?.trashedAt,
                        updatedAt = old?.updatedAt, updatedBy = myUserId(), device = deviceName, seq = res.seq ?: 0,
                    )
                    outbox = if (changedAgain) outbox.map { if (it.id == sent.id) it.copy(baseRev = res.rev ?: (sent.baseRev + 1)) else it } else outbox.filter { it.id != sent.id }
                }
                res.code == "conflict" && res.current != null -> {
                    // both versions stay: the server's as the entry, ours as a copy
                    items[sent.id] = res.current
                    outbox = outbox.filter { it.id != sent.id }
                    conflictCopy(s.vault(sent.vaultId), queued ?: sent)?.let { copy ->
                        outbox = outbox + copy
                        notice(context.getString(R.string.vault_notice_conflict))
                    }
                }
                res.code == "stale_key" -> {
                    // the vault got a new key: encrypt again with it (sync brought it), else give up on this save
                    val again = reencrypt(s.vault(sent.vaultId), queued ?: sent)
                    outbox = outbox.filter { it.id != sent.id } + listOfNotNull(again)
                }
                else -> {
                    outbox = outbox.filter { it.id != sent.id }
                    notice(context.getString(if (res.code == "limit") R.string.vault_notice_limit else R.string.vault_notice_refused))
                }
            }
            s = s.copy(items = items, outbox = outbox)
        }
        setState(s)
        rebuild()
    }

    private fun plainOf(vault: VVault?, save: VaultJson.SaveItem): EntryData? =
        vault?.let { decryptBlob(it, save.id, save.baseRev + 1, save.keyRev, save.blob) }

    private fun conflictCopy(vault: VVault?, save: VaultJson.SaveItem): VaultJson.SaveItem? {
        val v = vault ?: return null
        val data = plainOf(v, save) ?: return null
        val copy = data.copy(title = data.title + " " + context.getString(R.string.vault_conflict_suffix))
        return encryptSave(v, VaultCrypto.newId(), 0, copy)
    }

    private fun reencrypt(vault: VVault?, save: VaultJson.SaveItem): VaultJson.SaveItem? {
        val v = vault ?: return null
        val data = plainOf(v, save) ?: return null
        return encryptSave(v, save.id, save.baseRev, data)
    }

    /** The save of [data] as entry [id] on top of [baseRev], encrypted with the key a save must use now. */
    private fun encryptSave(vault: VVault, id: String, baseRev: Int, data: EntryData): VaultJson.SaveItem? {
        val keyRev = vault.wantedKeyRev
        val key = session.keysOf(vault)[keyRev] ?: return null
        val blob = VaultCrypto.encryptItemJson(key, vault.id, id, baseRev + 1, data.toJson())
        return VaultJson.SaveItem(id, vault.id, blob, keyRev, baseRev)
    }

    // ─── entries ────────────────────────────────────────────────────

    sealed class Save {
        data class Ok(val id: String) : Save()
        data object Locked : Save()
        data object ReadOnly : Save()
        data object TooLarge : Save()
    }

    /**
     * Saves an entry (new when [id] is null) into [vaultId] — a different vault than before moves it. Goes to the
     * outbox first, so it works offline; the server gets it right away when reachable.
     */
    suspend fun save(id: String?, vaultId: String, data: EntryData): Save {
        val r = mutex.withLock {
            session.keys() ?: return Save.Locked
            val s = _state.value
            val vault = s.vault(vaultId) ?: return Save.ReadOnly
            if (!vault.canWrite) return Save.ReadOnly
            val entryId = id ?: VaultCrypto.newId()
            val queued = s.outbox.firstOrNull { it.id == entryId }
            val baseRev = queued?.baseRev ?: s.items[entryId]?.rev ?: 0
            val save = try {
                encryptSave(vault, entryId, baseRev, data) ?: return Save.Locked
            } catch (e: VaultCrypto.VaultCryptoException) {
                return Save.TooLarge
            }
            setState(s.queue(save))
            rebuild()
            Save.Ok(entryId)
        }
        syncNow()
        return r
    }

    /** Several new entries at once (import): one outbox, sent in batches. */
    suspend fun saveMany(vaultId: String, list: List<EntryData>): Int {
        val n = mutex.withLock {
            session.keys() ?: return 0
            var s = _state.value
            val vault = s.vault(vaultId)?.takeIf { it.canWrite } ?: return 0
            var count = 0
            for (data in list) {
                val save = runCatching { encryptSave(vault, VaultCrypto.newId(), 0, data) }.getOrNull() ?: continue
                s = s.queue(save)
                count++
            }
            setState(s)
            rebuild()
            count
        }
        syncNow()
        return n
    }

    private suspend fun online(call: suspend () -> VResult<VItem>): VResult<Unit> {
        val r = call()
        if (r is VResult.Ok) mutex.withLock {
            val s = _state.value
            setState(s.copy(items = s.items + (r.value.id to r.value)))
            rebuild()
        }
        return r.unit()
    }

    suspend fun trash(id: String) = online { api.trash(id) }
    suspend fun restore(id: String) = online { api.restore(id) }

    /** Deleted for good, also from the trash (needs the server). */
    suspend fun purge(id: String): VResult<Unit> {
        val r = api.purge(id)
        if (r is VResult.Ok || (r is VResult.Failed && r.code == "not_found")) mutex.withLock {
            val s = _state.value
            setState(s.copy(items = s.items - id, outbox = s.outbox.filter { it.id != id }))
            rebuild()
        }
        return r.unit()
    }

    data class Version(val rev: Int, val updatedAt: String?, val device: String?, val data: EntryData)

    /** The earlier versions an entry still has (≤ 10), decrypted. */
    suspend fun history(id: String): VResult<List<Version>> {
        val item = _state.value.items[id] ?: return VResult.Failed("not_found", 0)
        val vault = _state.value.vault(item.vaultId) ?: return VResult.Failed("not_found", 0)
        return when (val r = api.history(id)) {
            is VResult.Ok -> VResult.Ok(r.value.mapNotNull { h -> decryptBlob(vault, id, h.rev, h.keyRev, h.blob)?.let { Version(h.rev, h.updatedAt, h.device, it) } })
            else -> r.notOk()
        }
    }

    // ─── vaults and members ─────────────────────────────────────────

    private suspend fun applyVault(r: VResult<VVault>): VResult<VVault> {
        if (r is VResult.Ok) mutex.withLock {
            val s = _state.value
            setState(s.copy(vaults = s.vaults.filter { it.id != r.value.id } + r.value))
            rebuild()
        }
        return r
    }

    /** A new shared vault ("Familie", "Team"); the creator manages it. */
    suspend fun createVault(name: String): VResult<VVault> {
        val keys = session.keys() ?: return VResult.Failed("locked", 0)
        val id = VaultCrypto.newId()
        val key = VaultCrypto.newVaultKey()
        val r = api.createVault(id, VaultCrypto.encryptName(key, id, name.trim()), VaultCrypto.sealKey(key, keys.publicKey))
        if (r is VResult.Ok) session.putKey(id, r.value.keyRev, key)
        key.fill(0)
        return applyVault(r)
    }

    suspend fun renameVault(vaultId: String, name: String): VResult<VVault> {
        val v = _state.value.vault(vaultId) ?: return VResult.Failed("not_found", 0)
        val key = session.keysOf(v)[v.keyRev] ?: return VResult.Failed("locked", 0)
        return applyVault(api.renameVault(vaultId, VaultCrypto.encryptName(key, vaultId, name.trim())))
    }

    suspend fun deleteVault(vaultId: String): VResult<Unit> {
        val r = api.deleteVault(vaultId)
        if (r is VResult.Ok) dropVault(vaultId)
        return r
    }

    private suspend fun dropVault(vaultId: String) = mutex.withLock {
        val s = _state.value
        setState(s.copy(vaults = s.vaults.filter { it.id != vaultId }, items = s.items.filterValues { it.vaultId != vaultId }, outbox = s.outbox.filter { it.vaultId != vaultId }))
        rebuild()
    }

    /** Everyone one could share with; `publicKey` once they set up Vault. */
    suspend fun people(): VResult<List<VPerson>> = api.people()

    /** Adds a person: the vault key sealed to their public key (compare the safety number first). */
    suspend fun addMember(vaultId: String, person: VPerson, role: String): VResult<VVault> {
        val v = _state.value.vault(vaultId) ?: return VResult.Failed("not_found", 0)
        val pk = person.publicKey ?: return VResult.Failed("no_account", 0)
        val key = session.keysOf(v)[v.keyRev] ?: return VResult.Failed("locked", 0)
        val sealed = try {
            VaultCrypto.sealKey(key, VaultCrypto.unb64(pk))
        } catch (e: VaultCrypto.VaultCryptoException) {
            return VResult.Failed("invalid", 0)
        }
        return applyVault(api.addMember(vaultId, person.id, role, sealed))
    }

    suspend fun setRole(vaultId: String, userId: Int, role: String) = applyVault(api.setRole(vaultId, userId, role))

    /** Removes someone (or leaves, with one's own id); the vault then needs a new key — a manager's app does that. */
    suspend fun removeMember(vaultId: String, userId: Int): VResult<Unit> {
        val r = api.removeMember(vaultId, userId)
        if (r is VResult.Ok) {
            val v = r.value
            if (v == null || userId == myUserId()) dropVault(vaultId) else applyVault(VResult.Ok(v))
            syncNow()
        }
        return r.unit()
    }

    // ─── key change (README › Schlüsselwechsel) ─────────────────────

    private suspend fun runRekeys() {
        for (v in _state.value.vaults) {
            if (!v.canManage || v.personal) continue
            if (!v.needsRekey && v.pending == null) continue
            runCatching { mutex.withLock { rekey(v.id) } }.onFailure { Timber.w("Vault: key change failed (%s)", it.javaClass.simpleName) }
        }
    }

    /**
     * Gives a vault a new key: start (sealed to exactly the current members) → every entry encrypted again with it →
     * finish. Resumes an interrupted change (the new key is in `pending`). Runs with [mutex] held.
     */
    private suspend fun rekey(vaultId: String): Boolean {
        val keys = session.keys() ?: return false
        var v = _state.value.vault(vaultId) ?: return false
        val have = session.keysOf(v)
        val newRev: Int
        val newKey: ByteArray
        val pending = v.pending
        if (pending != null && have[pending.keyRev] != null) {
            newRev = pending.keyRev
            newKey = have.getValue(pending.keyRev)
        } else {
            val people = (api.people() as? VResult.Ok)?.value ?: return false
            val pks = people.associate { it.id to it.publicKey }
            val me = myUserId() ?: return false
            newKey = VaultCrypto.newVaultKey()
            newRev = v.keyRev + 1
            val sealed = HashMap<Int, String>()
            for (m in v.members) {
                val pk = if (m.userId == me) keys.publicKey else pks[m.userId]?.let { runCatching { VaultCrypto.unb64(it) }.getOrNull() } ?: return false
                sealed[m.userId] = VaultCrypto.sealKey(newKey, pk)
            }
            when (val r = api.rekeyStart(vaultId, newRev, sealed)) {
                is VResult.Ok -> {
                    v = r.value
                    session.putKey(vaultId, newRev, newKey)
                    setState(_state.value.let { s -> s.copy(vaults = s.vaults.map { if (it.id == vaultId) v else it }) })
                }
                else -> return false
            }
        }
        // every entry not yet on the new key, as the copy knows it (synced just before)
        for (round in 0 until 10) {
            val left = _state.value.items.values.filter { it.vaultId == vaultId && !it.purged && it.keyRev != newRev }
            if (left.isEmpty()) break
            val oldKeys = session.keysOf(v)
            var batch = ArrayList<VaultJson.SaveItem>()
            var bytes = 0
            suspend fun send() {
                if (batch.isEmpty()) return
                val r = api.save(batch, deviceName)
                if (r is VResult.Ok) applyResults(batch, r.value)
                batch = ArrayList()
                bytes = 0
            }
            for (item in left) {
                val key = oldKeys[item.keyRev] ?: continue
                val json = runCatching { VaultCrypto.decryptItemJson(key, vaultId, item.id, item.rev, item.blob ?: return@runCatching null) }.getOrNull() ?: continue
                val blob = VaultCrypto.encryptItemJson(newKey, vaultId, item.id, item.rev + 1, json)
                val save = VaultJson.SaveItem(item.id, vaultId, blob, newRev, item.rev)
                if (batch.isNotEmpty() && (bytes + VaultJson.saveItemBytes(save) > BATCH_BYTES || batch.size >= BATCH_ITEMS)) send()
                batch += save
                bytes += VaultJson.saveItemBytes(save)
            }
            send()
            // conflicts brought newer versions: fetch and go again
            pull(null, _state.value.seq)
        }
        val currentKey = session.keysOf(v)[v.keyRev] ?: return false
        val name = runCatching { VaultCrypto.decryptName(currentKey, vaultId, v.nameEnc) }.getOrNull() ?: return false
        return when (val r = api.rekeyFinish(vaultId, VaultCrypto.encryptName(newKey, vaultId, name))) {
            is VResult.Ok -> {
                setState(_state.value.let { s -> s.copy(vaults = s.vaults.map { if (it.id == vaultId) r.value else it }) })
                rebuild()
                true
            }
            else -> false
        }
    }

    // ─── emergency access (README › Notfallzugriff) ────────────────

    suspend fun emergency(): VResult<VEmergencyList> = api.emergency()

    /** Names [person] as trusted person: the user key sealed to them, released after [waitHours]. */
    suspend fun addTrusted(person: VPerson, waitHours: Int): VResult<VEmergency> {
        val keys = session.keys() ?: return VResult.Failed("locked", 0)
        val pk = person.publicKey ?: return VResult.Failed("no_account", 0)
        val sealed = try {
            VaultCrypto.sealForEmergency(keys, VaultCrypto.unb64(pk))
        } catch (e: VaultCrypto.VaultCryptoException) {
            return VResult.Failed("invalid", 0)
        }
        return api.putEmergency(person.id, waitHours, sealed)
    }

    suspend fun removeTrusted(id: String) = api.deleteEmergency(id)
    suspend fun emergencyStep(id: String, step: String) = api.emergencyStep(id, step)

    /** What a trusted person sees after the release: the grantor's vaults and entries, read-only. */
    data class EmergencyView(val grantorName: String, val vaults: List<VaultView>, val entries: List<Entry>)

    suspend fun openEmergency(id: String): VResult<EmergencyView> {
        val mine = session.keys() ?: return VResult.Failed("locked", 0)
        val access = when (val r = api.emergencyAccess(id)) {
            is VResult.Ok -> r.value
            else -> return r.notOk()
        }
        val grantor = try {
            withContext(Dispatchers.Default) { VaultCrypto.openEmergency(access.sealedKey, access.grantorPublicKey, access.grantorPrivateKeyEnc, mine) }
        } catch (e: VaultCrypto.VaultCryptoException) {
            return VResult.Failed("decrypt", 0)
        }
        try {
            val keysByVault = access.vaults.associate { v ->
                val m = HashMap<Int, ByteArray>()
                runCatching { m[v.keyRev] = VaultCrypto.openSealed(v.sealedKey, grantor.publicKey, grantor.secretKey) }
                v.pending?.sealedKey?.let { p -> runCatching { m[v.pending.keyRev] = VaultCrypto.openSealed(p, grantor.publicKey, grantor.secretKey) } }
                v.id to m
            }
            val views = access.vaults.mapNotNull { v ->
                val k = keysByVault[v.id]?.get(v.keyRev) ?: return@mapNotNull null
                VaultView(v, runCatching { VaultCrypto.decryptName(k, v.id, v.nameEnc) }.getOrDefault("…"))
            }
            val entries = ArrayList<Entry>()
            var since = 0L
            var guard = 0
            while (guard++ < 100) {
                val page = when (val r = api.emergencyItems(id, since)) {
                    is VResult.Ok -> r.value
                    else -> return r.notOk()
                }
                for (i in page.items) {
                    val k = keysByVault[i.vaultId]?.get(i.keyRev) ?: continue
                    val data = runCatching { EntryData.fromJson(VaultCrypto.decryptItemJson(k, i.vaultId, i.id, i.rev, i.blob ?: return@runCatching null)) }.getOrNull() ?: continue
                    entries += Entry(i.id, i.vaultId, i.rev, i.trashed, i.trashedAt, i.updatedAt, i.updatedBy, i.device, data)
                }
                if (!page.more) break
                since = page.next
            }
            keysByVault.values.forEach { m -> m.values.forEach { it.fill(0) } }
            return VResult.Ok(EmergencyView(access.grantorName, views, entries.sortedBy { it.data.title.lowercase() }))
        } finally {
            grantor.wipe()
        }
    }

    companion object {
        /** An app request is ≤ 64 KB: batches stay well below. */
        private const val BATCH_BYTES = 48_000
        private const val BATCH_ITEMS = 100
    }
}
