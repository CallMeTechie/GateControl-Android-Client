package com.gatecontrol.android.familie

import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.familie.FamiliePluginCalls.Companion.path
import com.gatecontrol.android.familie.shopping.ShoppingOp
import com.gatecontrol.android.familie.shopping.ShoppingOpsResult
import com.gatecontrol.android.familie.shopping.ShoppingSnapshot
import com.gatecontrol.android.network.ApiClientProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The part of the Familie and Alltag app API (contracts B3, B5) the phone app
 * and GateControl TV share: the shopping lists (`GET shopping[?since]`,
 * `POST shopping/ops`), the dates of the Familie plugin and the Alltag
 * calendar with its waste collection (`app/events`). Never throws.
 */
@Singleton
class FamilieHomeApi @Inject constructor(
    setupRepository: SetupRepository,
    apiClientProvider: ApiClientProvider,
) {
    private val calls = FamiliePluginCalls(setupRepository, apiClientProvider)

    private val unit: (com.google.gson.JsonObject) -> Unit = { }

    // ─── shopping ────────────────────────────────────────────────────

    suspend fun shopping(since: String?): FamilieResult<ShoppingSnapshot> =
        calls.get(path("shopping"), if (since != null) mapOf("since" to since) else emptyMap(), parse = FamilieHomeJson::shopping)

    suspend fun shoppingOps(ops: List<ShoppingOp>): FamilieResult<ShoppingOpsResult> =
        calls.post(path("shopping", "ops"), FamilieHomeJson.opsBody(ops), FamilieHomeJson::opsResult)

    // ─── dates ───────────────────────────────────────────────────────

    suspend fun dates(from: String, to: String): FamilieResult<List<FamilieEvent>> =
        calls.get(path("dates"), mapOf("from" to from, "to" to to), parse = FamilieHomeJson::events)

    suspend fun createDate(draft: EventDraft): FamilieResult<Unit> = calls.post(path("dates"), FamilieHomeJson.eventBody(draft), unit)

    suspend fun updateDate(id: String, draft: EventDraft): FamilieResult<Unit> =
        calls.patch(path("dates", id), FamilieHomeJson.eventBody(draft), parse = unit)

    suspend fun deleteDate(id: String): FamilieResult<Unit> = calls.delete(path("dates", id))

    /** Alltag appointments and waste dates (B5, ≤ 62 days). */
    suspend fun alltagEvents(from: String, to: String): FamilieResult<AlltagPayload> =
        calls.get(path("app", "events"), mapOf("from" to from, "to" to to), plugin = ALLTAG_PLUGIN, parse = FamilieHomeJson::alltag)
}
