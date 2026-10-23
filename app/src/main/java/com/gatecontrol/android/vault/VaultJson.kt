package com.gatecontrol.android.vault

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * The Vault plugin's answers → models, and request bodies (README "App-API"). Pure: unit-tested. Anything that does
 * not have the documented shape is dropped (a list entry) or makes the whole answer invalid (null).
 */
object VaultJson {
    private val ID = Regex("^[A-Za-z0-9_-]{16,40}$")
    private val B64 = Regex("^[A-Za-z0-9_-]+$")
    private const val MAX_LIST = 5000

    fun errorCode(o: JsonObject?): String? =
        o?.str("code")?.takeIf { it.length <= 60 && it.all { c -> c.isLetterOrDigit() || c == '_' } }

    fun isRefusal(o: JsonObject): Boolean = o.bool("ok") == false

    private fun id(s: String?) = s?.takeIf { ID.matches(it) }
    private fun b64(s: String?) = s?.takeIf { it.length in 1..65536 && B64.matches(it) }

    fun kdf(o: JsonObject?): VaultCrypto.Kdf? {
        o ?: return null
        return VaultCrypto.Kdf(
            alg = o.str("alg") ?: return null,
            m = o.int("m") ?: return null,
            t = o.int("t") ?: return null,
            p = o.int("p") ?: return null,
            salt = b64(o.str("salt")) ?: return null,
        )
    }

    fun account(o: JsonObject?): VaultCrypto.Account? {
        o ?: return null
        return VaultCrypto.Account(
            kdf = kdf(o.obj("kdf")) ?: return null,
            userKeyPw = b64(o.str("userKeyPw")) ?: return null,
            userKeyRk = b64(o.str("userKeyRk")) ?: return null,
            publicKey = b64(o.str("publicKey")) ?: return null,
            privateKeyEnc = b64(o.str("privateKeyEnc")) ?: return null,
            rev = o.int("rev") ?: return null,
        )
    }

    /** GET account → the account, or null inside Ok when Vault is not set up yet. */
    fun accountAnswer(o: JsonObject): Pair<Boolean, VaultCrypto.Account?>? {
        if (!o.has("account")) return null
        if (o.get("account").isJsonNull) return true to null
        val a = account(o.obj("account")) ?: return null
        return true to a
    }

    fun vault(o: JsonObject?): VVault? {
        o ?: return null
        val pending = o.obj("pending")?.let { p -> VPending(p.int("keyRev") ?: return null, b64(p.str("sealedKey"))) }
        return VVault(
            id = id(o.str("id")) ?: return null,
            kind = o.str("kind")?.takeIf { it == "personal" || it == "shared" } ?: return null,
            ownerId = o.int("ownerId") ?: 0,
            nameEnc = b64(o.str("nameEnc")) ?: return null,
            role = o.str("role")?.takeIf { it in setOf("read", "write", "manage") } ?: return null,
            keyRev = o.int("keyRev")?.takeIf { it > 0 } ?: return null,
            sealedKey = b64(o.str("sealedKey")) ?: return null,
            pending = pending,
            needsRekey = o.bool("needsRekey") == true,
            members = o.arr("members")?.mapNotNull { m ->
                val mo = m.objOrNull() ?: return@mapNotNull null
                VMember(mo.int("userId") ?: return@mapNotNull null, mo.str("name").orEmpty().take(120), mo.str("role") ?: "read")
            }.orEmpty(),
            updatedAt = o.str("updatedAt"),
        )
    }

    fun item(o: JsonObject?): VItem? {
        o ?: return null
        val state = o.str("state")?.takeIf { it == "live" || it == "trash" || it == "purged" } ?: return null
        val blob = b64(o.str("blob"))
        if (state != "purged" && blob == null) return null
        return VItem(
            id = id(o.str("id")) ?: return null,
            vaultId = id(o.str("vaultId")) ?: return null,
            rev = o.int("rev")?.takeIf { it > 0 } ?: return null,
            keyRev = o.int("keyRev")?.takeIf { it > 0 } ?: return null,
            blob = if (state == "purged") null else blob,
            state = state,
            trashedAt = o.str("trashedAt"),
            updatedAt = o.str("updatedAt"),
            updatedBy = o.int("updatedBy"),
            device = o.str("device")?.take(80),
            seq = o.long("seq") ?: 0,
        )
    }

    private fun items(a: JsonArray?) = a?.take(MAX_LIST)?.mapNotNull { item(it.objOrNull()) }.orEmpty()

    fun sync(o: JsonObject): VSync? {
        val vaults = o.arr("vaults") ?: return null
        return VSync(
            seq = o.long("seq") ?: return null,
            account = o.obj("account")?.let { account(it) ?: return null },
            vaults = vaults.mapNotNull { vault(it.objOrNull()) },
            items = items(o.arr("items")),
            more = o.bool("more") == true,
            next = o.long("next") ?: return null,
        )
    }

    fun vaultAnswer(o: JsonObject): VVault? = vault(o.obj("vault"))

    fun people(o: JsonObject): List<VPerson>? = o.arr("people")?.mapNotNull { p ->
        val po = p.objOrNull() ?: return@mapNotNull null
        VPerson(po.int("id") ?: return@mapNotNull null, po.str("name").orEmpty().take(120), b64(po.str("publicKey")))
    }

    fun saveResults(o: JsonObject): List<VSaveResult>? = o.arr("results")?.mapNotNull { r ->
        val ro = r.objOrNull() ?: return@mapNotNull null
        VSaveResult(
            id = ro.str("id") ?: return@mapNotNull null,
            ok = ro.bool("ok") == true,
            rev = ro.int("rev"),
            seq = ro.long("seq"),
            code = errorCode(ro),
            current = item(ro.obj("current")),
        )
    }

    fun itemAnswer(o: JsonObject): VItem? = item(o.obj("item"))

    fun history(o: JsonObject): List<VHistory>? = o.arr("history")?.mapNotNull { h ->
        val ho = h.objOrNull() ?: return@mapNotNull null
        VHistory(
            rev = ho.int("rev") ?: return@mapNotNull null,
            keyRev = ho.int("keyRev") ?: return@mapNotNull null,
            blob = b64(ho.str("blob")) ?: return@mapNotNull null,
            updatedAt = ho.str("updatedAt"),
            updatedBy = ho.int("updatedBy"),
            device = ho.str("device"),
        )
    }

    fun emergency(o: JsonObject?): VEmergency? {
        o ?: return null
        val grantor = o.obj("grantor") ?: return null
        val grantee = o.obj("grantee") ?: return null
        return VEmergency(
            id = o.str("id")?.takeIf { Regex("^[0-9a-f]{24}$").matches(it) } ?: return null,
            grantorId = grantor.int("id") ?: return null,
            grantorName = grantor.str("name").orEmpty(),
            granteeId = grantee.int("id") ?: return null,
            granteeName = grantee.str("name").orEmpty(),
            waitHours = o.int("waitHours") ?: return null,
            status = o.str("status")?.takeIf { it in setOf("ready", "requested", "granted") } ?: return null,
            requestedAt = o.str("requestedAt"),
            releaseAt = o.str("releaseAt"),
            grantedAt = o.str("grantedAt"),
        )
    }

    fun emergencyAnswer(o: JsonObject): VEmergency? = emergency(o.obj("emergency"))

    fun emergencyList(o: JsonObject): VEmergencyList? = VEmergencyList(
        granted = o.arr("granted")?.mapNotNull { emergency(it.objOrNull()) } ?: return null,
        received = o.arr("received")?.mapNotNull { emergency(it.objOrNull()) } ?: return null,
    )

    fun access(o: JsonObject): VAccess? {
        val g = o.obj("grantor") ?: return null
        return VAccess(
            sealedKey = b64(o.str("sealedKey")) ?: return null,
            grantorId = g.int("id") ?: return null,
            grantorName = g.str("name").orEmpty(),
            grantorPublicKey = b64(g.str("publicKey")) ?: return null,
            grantorPrivateKeyEnc = b64(g.str("privateKeyEnc")) ?: return null,
            vaults = o.arr("vaults")?.mapNotNull { vault(it.objOrNull()) } ?: return null,
        )
    }

    fun itemsPage(o: JsonObject): VItemsPage? = VItemsPage(
        seq = o.long("seq") ?: return null,
        items = items(o.arr("items")),
        more = o.bool("more") == true,
        next = o.long("next") ?: return null,
    )

    // ─── bodies ──────────────────────────────────────────────────────

    fun kdfJson(k: VaultCrypto.Kdf) = JsonObject().apply {
        addProperty("alg", k.alg); addProperty("m", k.m); addProperty("t", k.t); addProperty("p", k.p); addProperty("salt", k.salt)
    }

    fun accountBody(b: VaultCrypto.AccountBody) = JsonObject().apply {
        add("kdf", kdfJson(b.kdf))
        addProperty("userKeyPw", b.userKeyPw)
        addProperty("userKeyRk", b.userKeyRk)
        addProperty("publicKey", b.publicKey)
        addProperty("privateKeyEnc", b.privateKeyEnc)
        add("personal", JsonObject().apply {
            addProperty("id", b.personal.id); addProperty("nameEnc", b.personal.nameEnc); addProperty("sealedKey", b.personal.sealedKey)
        })
    }

    fun accountUpdate(u: VaultCrypto.AccountUpdate) = JsonObject().apply {
        addProperty("rev", u.rev)
        add("kdf", kdfJson(u.kdf))
        addProperty("userKeyPw", u.userKeyPw)
        u.userKeyRk?.let { addProperty("userKeyRk", it) }
    }

    /** One entry of POST items. */
    data class SaveItem(val id: String, val vaultId: String, val blob: String, val keyRev: Int, val baseRev: Int)

    fun saveBody(items: List<SaveItem>, device: String?) = JsonObject().apply {
        add("items", JsonArray().also { a ->
            items.forEach { i ->
                a.add(JsonObject().apply {
                    addProperty("id", i.id); addProperty("vaultId", i.vaultId); addProperty("blob", i.blob)
                    addProperty("keyRev", i.keyRev); addProperty("baseRev", i.baseRev)
                })
            }
        })
        device?.let { addProperty("device", it) }
    }

    // ─── the device's own copy (VaultStore): the server's shapes back to JSON ─────

    fun accountJson(a: VaultCrypto.Account) = JsonObject().apply {
        add("kdf", kdfJson(a.kdf))
        addProperty("userKeyPw", a.userKeyPw); addProperty("userKeyRk", a.userKeyRk)
        addProperty("publicKey", a.publicKey); addProperty("privateKeyEnc", a.privateKeyEnc)
        addProperty("rev", a.rev)
    }

    fun vaultJson(v: VVault) = JsonObject().apply {
        addProperty("id", v.id); addProperty("kind", v.kind); addProperty("ownerId", v.ownerId); addProperty("nameEnc", v.nameEnc)
        addProperty("role", v.role); addProperty("keyRev", v.keyRev); addProperty("sealedKey", v.sealedKey)
        v.pending?.let { p -> add("pending", JsonObject().apply { addProperty("keyRev", p.keyRev); p.sealedKey?.let { addProperty("sealedKey", it) } }) }
        addProperty("needsRekey", v.needsRekey)
        add("members", JsonArray().also { a ->
            v.members.forEach { m -> a.add(JsonObject().apply { addProperty("userId", m.userId); addProperty("name", m.name); addProperty("role", m.role) }) }
        })
        v.updatedAt?.let { addProperty("updatedAt", it) }
    }

    fun itemJson(i: VItem) = JsonObject().apply {
        addProperty("id", i.id); addProperty("vaultId", i.vaultId); addProperty("rev", i.rev); addProperty("keyRev", i.keyRev)
        i.blob?.let { addProperty("blob", it) }
        addProperty("state", i.state)
        i.trashedAt?.let { addProperty("trashedAt", it) }
        i.updatedAt?.let { addProperty("updatedAt", it) }
        i.updatedBy?.let { addProperty("updatedBy", it) }
        i.device?.let { addProperty("device", it) }
        addProperty("seq", i.seq)
    }

    fun saveItemJson(i: SaveItem) = JsonObject().apply {
        addProperty("id", i.id); addProperty("vaultId", i.vaultId); addProperty("blob", i.blob)
        addProperty("keyRev", i.keyRev); addProperty("baseRev", i.baseRev)
    }

    fun saveItem(o: JsonObject?): SaveItem? {
        o ?: return null
        return SaveItem(
            id = id(o.str("id")) ?: return null,
            vaultId = id(o.str("vaultId")) ?: return null,
            blob = b64(o.str("blob")) ?: return null,
            keyRev = o.int("keyRev") ?: return null,
            baseRev = o.int("baseRev") ?: return null,
        )
    }

    /** Rough size of one entry inside POST items (an app request is ≤ 64 KB). */
    fun saveItemBytes(i: SaveItem) = i.blob.length + i.id.length + i.vaultId.length + 80
}
