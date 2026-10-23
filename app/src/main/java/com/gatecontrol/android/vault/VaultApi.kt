package com.gatecontrol.android.vault

import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.Response
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Vault plugin's app API (README "App-API") through the core's plugin bridge
 * (`/api/v1/client/plugins/gatecontrol-vault/api/…`): one method per route, answers parsed by [VaultJson]. Works
 * over the server's public address — no VPN needed. Never throws (except cancellation). Everything that leaves here
 * is already encrypted.
 */
@Singleton
class VaultApi @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
    private val settingsRepository: SettingsRepository,
) {
    companion object {
        const val CODE_INVALID = "invalid_answer"
        private const val TIMEOUT_MS = 25_000L
        private val SAFE_SEGMENT = Regex("^[A-Za-z0-9_-]{1,40}$")
    }

    private suspend fun lang(): Map<String, String> =
        runCatching { settingsRepository.getLocale().first() }.getOrNull()?.takeIf { it == "de" || it == "en" }
            ?.let { mapOf("lang" to it) } ?: emptyMap()

    private suspend fun <T> call(request: suspend ApiClient.() -> Response<JsonObject>, parse: (JsonObject) -> T?): VResult<T> {
        val url = setupRepository.getServerUrl()
        if (url.isBlank() || !setupRepository.isConfigured()) return VResult.NotConfigured
        return try {
            val response = withTimeoutOrNull(TIMEOUT_MS) { apiClientProvider.getClient(url).request() } ?: return VResult.Offline
            if (response.isSuccessful) {
                val body = response.body() ?: JsonObject()
                if (VaultJson.isRefusal(body)) return VResult.Failed(VaultJson.errorCode(body) ?: "failed", response.code(), body)
                parse(body)?.let { VResult.Ok(it) } ?: VResult.Failed(CODE_INVALID, response.code())
            } else {
                val err = runCatching {
                    response.errorBody()?.use { b -> b.string().takeIf { it.length < 262_144 } }
                        ?.let { JsonParser.parseString(it).takeIf { e -> e.isJsonObject }?.asJsonObject }
                }.getOrNull()
                VResult.Failed(VaultJson.errorCode(err) ?: "http_${response.code()}", response.code(), err)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            VResult.Offline
        } catch (e: Exception) {
            Timber.d("Vault: call failed (%s)", e.javaClass.simpleName)
            VResult.Failed(CODE_INVALID, 0)
        }
    }

    private val unit: (JsonObject) -> Unit = { }

    private suspend fun <T> get(path: String, query: Map<String, String> = emptyMap(), parse: (JsonObject) -> T?) =
        call(request = { pluginGet(VAULT_PLUGIN, path, query + lang()) }, parse = parse)

    private suspend fun <T> post(path: String, body: JsonObject, parse: (JsonObject) -> T?) =
        call(request = { pluginPost(VAULT_PLUGIN, path, body, lang()) }, parse = parse)

    private suspend fun <T> put(path: String, body: JsonObject, parse: (JsonObject) -> T?) =
        call(request = { pluginPut(VAULT_PLUGIN, path, body, lang()) }, parse = parse)

    private suspend fun <T> del(path: String, parse: (JsonObject) -> T?) =
        call(request = { pluginDelete(VAULT_PLUGIN, path, lang()) }, parse = parse)

    private fun seg(v: String): String? = v.takeIf { SAFE_SEGMENT.matches(it) }
    private fun bad(): VResult<Nothing> = VResult.Failed(CODE_INVALID, 0)

    // ─── account ────────────────────────────────────────────────────

    /** A value that may be absent in a successful answer (the parser's null means "invalid answer"). */
    private class Opt<T>(val value: T?)

    private fun <T> VResult<Opt<T>>.flat(): VResult<T?> = when (this) {
        is VResult.Ok -> VResult.Ok(value.value)
        is VResult.Failed -> this
        VResult.Offline -> VResult.Offline
        VResult.NotConfigured -> VResult.NotConfigured
    }

    /** Ok(null): Vault is not set up for this person yet. */
    suspend fun account(): VResult<VaultCrypto.Account?> =
        get("account") { o -> VaultJson.accountAnswer(o)?.let { Opt(it.second) } }.flat()

    suspend fun createAccount(body: VaultCrypto.AccountBody): VResult<VaultCrypto.Account> =
        post("account", VaultJson.accountBody(body)) { VaultJson.account(it.obj("account")) }

    suspend fun updateAccount(update: VaultCrypto.AccountUpdate): VResult<VaultCrypto.Account> =
        put("account", VaultJson.accountUpdate(update)) { VaultJson.account(it.obj("account")) }

    suspend fun deleteAccount(): VResult<Unit> = post("account/delete", JsonObject().apply { addProperty("confirm", "DELETE") }, unit)

    suspend fun people(): VResult<List<VPerson>> = get("people", parse = VaultJson::people)

    // ─── sync ───────────────────────────────────────────────────────

    suspend fun sync(since: Long, vault: String? = null, limit: Int = 500): VResult<VSync> {
        val q = buildMap {
            put("since", since.toString())
            put("limit", limit.toString())
            if (vault != null) put("vault", seg(vault) ?: return bad())
        }
        return get("sync", q, VaultJson::sync)
    }

    // ─── vaults ─────────────────────────────────────────────────────

    suspend fun createVault(id: String, nameEnc: String, sealedKey: String): VResult<VVault> =
        post("vaults", JsonObject().apply { addProperty("id", id); addProperty("nameEnc", nameEnc); addProperty("sealedKey", sealedKey) }, VaultJson::vaultAnswer)

    suspend fun renameVault(id: String, nameEnc: String): VResult<VVault> =
        put("vaults/${seg(id) ?: return bad()}", JsonObject().apply { addProperty("nameEnc", nameEnc) }, VaultJson::vaultAnswer)

    suspend fun deleteVault(id: String): VResult<Unit> = del("vaults/${seg(id) ?: return bad()}", unit)

    suspend fun addMember(vaultId: String, userId: Int, role: String, sealedKey: String): VResult<VVault> =
        post(
            "vaults/${seg(vaultId) ?: return bad()}/members",
            JsonObject().apply { addProperty("userId", userId); addProperty("role", role); addProperty("sealedKey", sealedKey) },
            VaultJson::vaultAnswer,
        )

    suspend fun setRole(vaultId: String, userId: Int, role: String): VResult<VVault> =
        put("vaults/${seg(vaultId) ?: return bad()}/members/$userId", JsonObject().apply { addProperty("role", role) }, VaultJson::vaultAnswer)

    /** Ok(null) after leaving (or when the vault is gone). */
    suspend fun removeMember(vaultId: String, userId: Int): VResult<VVault?> =
        del("vaults/${seg(vaultId) ?: return bad()}/members/$userId") { o -> Opt(VaultJson.vault(o.obj("vault"))) }.flat()

    suspend fun rekeyStart(vaultId: String, keyRev: Int, keys: Map<Int, String>): VResult<VVault> =
        post(
            "vaults/${seg(vaultId) ?: return bad()}/rekey/start",
            JsonObject().apply {
                addProperty("keyRev", keyRev)
                add("keys", com.google.gson.JsonArray().also { a ->
                    keys.forEach { (uid, sealed) -> a.add(JsonObject().apply { addProperty("userId", uid); addProperty("sealedKey", sealed) }) }
                })
            },
            VaultJson::vaultAnswer,
        )

    suspend fun rekeyFinish(vaultId: String, nameEnc: String): VResult<VVault> =
        post("vaults/${seg(vaultId) ?: return bad()}/rekey/finish", JsonObject().apply { addProperty("nameEnc", nameEnc) }, VaultJson::vaultAnswer)

    // ─── entries ────────────────────────────────────────────────────

    suspend fun save(items: List<VaultJson.SaveItem>, device: String?): VResult<List<VSaveResult>> =
        post("items", VaultJson.saveBody(items, device), VaultJson::saveResults)

    suspend fun trash(id: String): VResult<VItem> = post("items/${seg(id) ?: return bad()}/trash", JsonObject(), VaultJson::itemAnswer)

    suspend fun restore(id: String): VResult<VItem> = post("items/${seg(id) ?: return bad()}/restore", JsonObject(), VaultJson::itemAnswer)

    suspend fun purge(id: String): VResult<VItem> = del("items/${seg(id) ?: return bad()}", VaultJson::itemAnswer)

    suspend fun history(id: String): VResult<List<VHistory>> = get("items/${seg(id) ?: return bad()}/history", parse = VaultJson::history)

    // ─── emergency access ──────────────────────────────────────────

    suspend fun emergency(): VResult<VEmergencyList> = get("emergency", parse = VaultJson::emergencyList)

    suspend fun putEmergency(granteeId: Int, waitHours: Int, sealedKey: String): VResult<VEmergency> =
        post(
            "emergency",
            JsonObject().apply { addProperty("granteeId", granteeId); addProperty("waitHours", waitHours); addProperty("sealedKey", sealedKey) },
            VaultJson::emergencyAnswer,
        )

    suspend fun deleteEmergency(id: String): VResult<Unit> = del("emergency/${seg(id) ?: return bad()}", unit)

    /** request (trusted person), deny / approve (grantor). */
    suspend fun emergencyStep(id: String, step: String): VResult<VEmergency> {
        if (step !in setOf("request", "deny", "approve")) return bad()
        return post("emergency/${seg(id) ?: return bad()}/$step", JsonObject(), VaultJson::emergencyAnswer)
    }

    suspend fun emergencyAccess(id: String): VResult<VAccess> = get("emergency/${seg(id) ?: return bad()}/access", parse = VaultJson::access)

    suspend fun emergencyItems(id: String, since: Long): VResult<VItemsPage> =
        get("emergency/${seg(id) ?: return bad()}/items", mapOf("since" to since.toString(), "limit" to "500"), VaultJson::itemsPage)
}
