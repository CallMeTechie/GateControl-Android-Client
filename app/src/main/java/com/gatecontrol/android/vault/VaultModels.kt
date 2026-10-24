package com.gatecontrol.android.vault

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/** Plugin id of Vault (gatecontrol-vault): the password manager, end-to-end encrypted. */
const val VAULT_PLUGIN = "gatecontrol-vault"

/** Result of one call to the Vault plugin. */
sealed class VResult<out T> {
    data class Ok<T>(val value: T) : VResult<T>()
    /** The server refused: `code` as the plugin README lists it; `body` carries extras (`current`, `account`, `left`). */
    data class Failed(val code: String, val http: Int, val body: JsonObject? = null) : VResult<Nothing>()
    /** No answer (no network, timeout). */
    data object Offline : VResult<Nothing>()
    data object NotConfigured : VResult<Nothing>()

    fun valueOrNull(): T? = (this as? Ok)?.value
}

/** A result that is not Ok, retyped (an Ok here is a programming error). */
internal fun <T> VResult<*>.notOk(): VResult<T> = when (this) {
    is VResult.Ok -> VResult.Failed("unexpected", 0)
    is VResult.Failed -> this
    VResult.Offline -> VResult.Offline
    VResult.NotConfigured -> VResult.NotConfigured
}

/** Ok(Unit) for an Ok, else the failure. */
internal fun VResult<*>.unit(): VResult<Unit> = if (this is VResult.Ok) VResult.Ok(Unit) else notOk()

// ─── the server's records (README "App-API"), still encrypted ────────────

data class VMember(val userId: Int, val name: String, val role: String)

data class VPending(val keyRev: Int, val sealedKey: String?)

data class VVault(
    val id: String,
    val kind: String,
    val ownerId: Int,
    val nameEnc: String,
    val role: String,
    val keyRev: Int,
    val sealedKey: String,
    val pending: VPending?,
    val needsRekey: Boolean,
    val members: List<VMember>,
    val updatedAt: String?,
) {
    val personal get() = kind == "personal"
    val canWrite get() = role == "write" || role == "manage"
    val canManage get() = role == "manage"
    /** The key generation a save must use now: the new one during a key change. */
    val wantedKeyRev get() = pending?.keyRev ?: keyRev
}

data class VItem(
    val id: String,
    val vaultId: String,
    val rev: Int,
    val keyRev: Int,
    val blob: String?,
    val state: String,
    val trashedAt: String?,
    val updatedAt: String?,
    val updatedBy: Int?,
    val device: String?,
    val seq: Long,
) {
    val purged get() = state == "purged"
    val trashed get() = state == "trash"
}

data class VSync(
    val seq: Long,
    val account: VaultCrypto.Account?,
    val vaults: List<VVault>,
    val items: List<VItem>,
    val more: Boolean,
    val next: Long,
)

data class VPerson(val id: Int, val name: String, val publicKey: String?)

data class VHistory(val rev: Int, val keyRev: Int, val blob: String, val updatedAt: String?, val updatedBy: Int?, val device: String?)

data class VSaveResult(val id: String, val ok: Boolean, val rev: Int?, val seq: Long?, val code: String?, val current: VItem?)

/** A running Wi-Fi share (plugin 1.4.0, GET wifi-shares): the copy the server keeps for the wall tablet / portal. */
data class VWifiShare(
    val id: String,
    val itemId: String,
    val target: String,
    val ssid: String,
    val security: String,
    val password: String,
    val hidden: Boolean,
    /** null: until someone ends it. */
    val expiresAt: String?,
    val createdBy: String,
    val canEnd: Boolean,
)

/** GET wifi-shares: the running shares and whether the wall tablet (Familie plugin) is there. */
data class VWifiShares(val shares: List<VWifiShare>, val wall: Boolean, val portal: Boolean)

data class VEmergency(
    val id: String,
    val grantorId: Int,
    val grantorName: String,
    val granteeId: Int,
    val granteeName: String,
    val waitHours: Int,
    val status: String,
    val requestedAt: String?,
    val releaseAt: String?,
    val grantedAt: String?,
)

data class VEmergencyList(val granted: List<VEmergency>, val received: List<VEmergency>)

data class VAccess(
    val sealedKey: String,
    val grantorId: Int,
    val grantorName: String,
    val grantorPublicKey: String,
    val grantorPrivateKeyEnc: String,
    val vaults: List<VVault>,
)

data class VItemsPage(val seq: Long, val items: List<VItem>, val more: Boolean, val next: Long)

// ─── an entry, decrypted ──────────────────────────────────────────────────

/**
 * The plaintext of an entry (JSON, v 1) — the same on every platform:
 * `{ v: 1, type, title, fav, notes, urls: [], apps: [], fields: { … }, custom: [{ name, value, secret }] }`.
 * Field keys per type: login `username`, `password`, `totp` · totp `totp` · card `holder`, `number`, `expiry`
 * (MM/YY), `cvc`, `pin` · wifi `ssid`, `password`, `security` · identity `name`, `email`, `phone`, `address`,
 * `birthday` · note: only `notes`. `totp` is an `otpauth://` URI or a base32 secret. Unknown keys are kept as they are.
 */
data class EntryData(
    val type: String,
    val title: String,
    val fav: Boolean = false,
    val notes: String = "",
    val urls: List<String> = emptyList(),
    val apps: List<String> = emptyList(),
    val fields: Map<String, String> = emptyMap(),
    val custom: List<CustomField> = emptyList(),
    /** Keys this version does not know, carried over unchanged. */
    val extra: JsonObject = JsonObject(),
) {
    data class CustomField(val name: String, val value: String, val secret: Boolean)

    fun field(k: String): String = fields[k].orEmpty()
    val username get() = field("username").ifEmpty { field("email") }
    val password get() = field("password")
    val totp get() = field("totp")

    fun toJson(): String {
        val o = extra.deepCopy()
        o.addProperty("v", 1)
        o.addProperty("type", type)
        o.addProperty("title", title)
        if (fav) o.addProperty("fav", true) else o.remove("fav")
        if (notes.isNotEmpty()) o.addProperty("notes", notes) else o.remove("notes")
        o.add("urls", JsonArray().also { a -> urls.filter { it.isNotBlank() }.forEach { a.add(it) } })
        o.add("apps", JsonArray().also { a -> apps.filter { it.isNotBlank() }.forEach { a.add(it) } })
        o.add("fields", JsonObject().also { f -> fields.filterValues { it.isNotEmpty() }.toSortedMap().forEach { (k, v) -> f.addProperty(k, v) } })
        o.add(
            "custom",
            JsonArray().also { a ->
                custom.filter { it.name.isNotBlank() || it.value.isNotEmpty() }.forEach { c ->
                    a.add(JsonObject().apply { addProperty("name", c.name); addProperty("value", c.value); addProperty("secret", c.secret) })
                }
            },
        )
        return o.toString()
    }

    companion object {
        val TYPES = listOf("login", "totp", "note", "card", "wifi", "identity")
        private val KNOWN = setOf("v", "type", "title", "fav", "notes", "urls", "apps", "fields", "custom")

        fun fromJson(json: String): EntryData? {
            val o = runCatching { com.google.gson.JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null
            if (o.int("v") != 1) return null
            val type = o.str("type")?.takeIf { it in TYPES } ?: return null
            val fields = o.obj("fields")?.entrySet()?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.let { k to it.asString } }?.toMap().orEmpty()
            val custom = o.arr("custom")?.mapNotNull { e ->
                val c = e as? JsonObject ?: return@mapNotNull null
                CustomField(c.str("name").orEmpty(), c.str("value").orEmpty(), c.bool("secret") == true)
            }.orEmpty()
            val extra = JsonObject().also { x -> o.entrySet().filter { it.key !in KNOWN }.forEach { (k, v) -> x.add(k, v) } }
            return EntryData(
                type = type,
                title = o.str("title").orEmpty(),
                fav = o.bool("fav") == true,
                notes = o.str("notes").orEmpty(),
                urls = o.arr("urls")?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.asString }.orEmpty(),
                apps = o.arr("apps")?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.asString }.orEmpty(),
                fields = fields,
                custom = custom,
                extra = extra,
            )
        }
    }
}

/** A decrypted entry as the screens show it. */
data class Entry(
    val id: String,
    val vaultId: String,
    val rev: Int,
    val trashed: Boolean,
    val trashedAt: String?,
    val updatedAt: String?,
    val updatedBy: Int?,
    val device: String?,
    val data: EntryData,
    /** Saved on this device, not yet on the server. */
    val pending: Boolean = false,
)

/** A vault with its decrypted name. */
data class VaultView(val vault: VVault, val name: String) {
    val id get() = vault.id
}

// ─── small JSON helpers ─────────────────────────────────────────────────

internal fun JsonObject.str(k: String): String? = (get(k) as? JsonPrimitive)?.takeIf { it.isString }?.asString
internal fun JsonObject.int(k: String): Int? = (get(k) as? JsonPrimitive)?.takeIf { it.isNumber }?.asNumber?.toDouble()?.takeIf { it % 1.0 == 0.0 && it in -2e9..2e9 }?.toInt()
internal fun JsonObject.long(k: String): Long? = (get(k) as? JsonPrimitive)?.takeIf { it.isNumber }?.asNumber?.toDouble()?.takeIf { it % 1.0 == 0.0 && it in -9e15..9e15 }?.toLong()
internal fun JsonObject.bool(k: String): Boolean? = (get(k) as? JsonPrimitive)?.takeIf { it.isBoolean }?.asBoolean
internal fun JsonObject.obj(k: String): JsonObject? = get(k) as? JsonObject
internal fun JsonObject.arr(k: String): JsonArray? = get(k) as? JsonArray
internal fun JsonElement?.objOrNull(): JsonObject? = this as? JsonObject
