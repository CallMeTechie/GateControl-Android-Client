package com.gatecontrol.android.vault

import android.content.Context
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.familie.PluginDirectory
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device's copy of the server's Vault data — still encrypted, exactly as the server sent it — plus the outbox of
 * saves made offline. Pure and immutable: [VaultStore] persists it, [merge] applies a sync page. Unit-tested.
 */
data class VaultState(
    val account: VaultCrypto.Account? = null,
    /** Vault is set up for this person (known after the first answer of GET account). */
    val known: Boolean = false,
    /** The server's change counter after the last complete sync. */
    val seq: Long = 0,
    val vaults: List<VVault> = emptyList(),
    val items: Map<String, VItem> = emptyMap(),
    /** Saves not yet confirmed by the server, oldest first; one per entry (the latest wins). */
    val outbox: List<VaultJson.SaveItem> = emptyList(),
    val lastSyncMs: Long = 0,
) {
    fun vault(id: String) = vaults.firstOrNull { it.id == id }

    /** Applies one page of GET sync. The vault list is always complete: a vault missing now is gone (with its entries). */
    fun merge(page: VSync, nowMs: Long): VaultState {
        val vaultIds = page.vaults.map { it.id }.toSet()
        val items = this.items.filterValues { it.vaultId in vaultIds }.toMutableMap()
        val purged = mutableSetOf<String>()
        for (i in page.items) {
            val cur = items[i.id]
            if (cur != null && i.seq < cur.seq) continue
            // a tombstone: every copy goes, an unsent change of it too
            if (i.purged) {
                items.remove(i.id)
                purged += i.id
            } else {
                items[i.id] = i
            }
        }
        return copy(
            account = page.account ?: account,
            known = true,
            vaults = page.vaults,
            items = items,
            outbox = outbox.filter { it.vaultId in vaultIds && it.id !in purged },
            lastSyncMs = nowMs,
        )
    }

    /** A save put into the outbox (replacing an earlier one of the same entry, keeping its base revision). */
    fun queue(save: VaultJson.SaveItem): VaultState {
        val earlier = outbox.firstOrNull { it.id == save.id }
        val next = if (earlier != null) save.copy(baseRev = earlier.baseRev) else save
        return copy(outbox = outbox.filter { it.id != save.id } + next)
    }

    fun toJson(owner: String) = JsonObject().apply {
        addProperty("v", 1)
        addProperty("owner", owner)
        addProperty("known", known)
        account?.let { add("account", VaultJson.accountJson(it)) }
        addProperty("seq", seq)
        addProperty("lastSyncMs", lastSyncMs)
        add("vaults", JsonArray().also { a -> vaults.forEach { a.add(VaultJson.vaultJson(it)) } })
        add("items", JsonArray().also { a -> items.values.forEach { a.add(VaultJson.itemJson(it)) } })
        add("outbox", JsonArray().also { a -> outbox.forEach { a.add(VaultJson.saveItemJson(it)) } })
    }

    companion object {
        fun fromJson(o: JsonObject, owner: String): VaultState? {
            if (o.int("v") != 1 || o.str("owner") != owner) return null
            return VaultState(
                account = o.obj("account")?.let { VaultJson.account(it) },
                known = o.bool("known") == true,
                seq = o.long("seq") ?: 0,
                vaults = o.arr("vaults")?.mapNotNull { VaultJson.vault(it.objOrNull()) }.orEmpty(),
                items = o.arr("items")?.mapNotNull { VaultJson.item(it.objOrNull()) }?.associateBy { it.id }.orEmpty(),
                outbox = o.arr("outbox")?.mapNotNull { VaultJson.saveItem(it.objOrNull()) }.orEmpty(),
                lastSyncMs = o.long("lastSyncMs") ?: 0,
            )
        }
    }
}

/**
 * Persists [VaultState] per server and token (another login never sees this one's copy) in the app's private files.
 * Everything in it is either end-to-end encrypted or metadata the server holds anyway (ids, revisions, member names).
 */
@Singleton
class VaultStore @Inject constructor(
    @ApplicationContext context: Context,
    private val setupRepository: SetupRepository,
) {
    private val dir = File(context.filesDir, "vault")

    fun owner(): String = PluginDirectory.ownerKey(setupRepository.getServerUrl(), setupRepository.getApiToken())

    private fun file(owner: String) = File(dir, "state-$owner.json")

    @Synchronized
    fun load(): VaultState {
        val owner = owner()
        if (owner.isEmpty()) return VaultState()
        val f = file(owner)
        if (!f.exists()) return VaultState()
        return runCatching { VaultState.fromJson(JsonParser.parseString(f.readText()).asJsonObject, owner) }
            .onFailure { Timber.w("Vault: local copy unreadable (%s)", it.javaClass.simpleName) }
            .getOrNull() ?: VaultState()
    }

    @Synchronized
    fun save(state: VaultState) {
        val owner = owner()
        if (owner.isEmpty()) return
        dir.mkdirs()
        val tmp = File(dir, "state-$owner.json.tmp")
        tmp.writeText(state.toJson(owner).toString())
        if (!tmp.renameTo(file(owner))) {
            file(owner).delete()
            tmp.renameTo(file(owner))
        }
    }

    /** Forget this login's copy (account deleted, logged out). */
    @Synchronized
    fun clear() {
        val owner = owner()
        if (owner.isNotEmpty()) file(owner).delete()
    }
}
