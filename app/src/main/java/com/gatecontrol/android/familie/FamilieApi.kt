package com.gatecontrol.android.familie

import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.familie.emergency.EmergencyCard
import com.gatecontrol.android.familie.emergency.EmergencyContact
import com.gatecontrol.android.familie.emergency.EmergencyMemberDraft
import com.gatecontrol.android.familie.board.BoardPayload
import com.gatecontrol.android.familie.board.NoteDraft
import com.gatecontrol.android.familie.meals.Meal
import com.gatecontrol.android.familie.meals.MealWeek
import com.gatecontrol.android.familie.money.AllowancePayload
import com.gatecontrol.android.familie.money.AllowanceRuleDraft
import com.gatecontrol.android.familie.money.ExpenseDraft
import com.gatecontrol.android.familie.money.ExpenseMonth
import com.gatecontrol.android.familie.money.WishDraft
import com.gatecontrol.android.familie.money.WishItem
import com.gatecontrol.android.familie.presence.PresencePayload
import com.gatecontrol.android.familie.rides.DriverChoice
import com.gatecontrol.android.familie.rides.Ride
import com.gatecontrol.android.familie.rides.RideDraft
import com.gatecontrol.android.familie.shopping.ShoppingOp
import com.gatecontrol.android.familie.shopping.ShoppingSnapshot
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.Response
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a call to a plugin's app API. */
sealed interface FamilieResult<out T> {
    data class Ok<T>(val value: T) : FamilieResult<T>

    /**
     * The server answered with an error: [code] from `{ok:false, code}` or
     * `http_<status>`. [retryAfter] (s) with too_soon / daily_limit;
     * [requestId] when a locate request was refused because one is open;
     * [field] names the refused input of an `invalid` answer (e.g.
     * `contacts.0.phone`); [detail] the driver of an `already_taken` ride.
     */
    data class Failed(
        val code: String,
        val http: Int,
        val retryAfter: Int? = null,
        val requestId: Int? = null,
        val field: String? = null,
        val detail: String? = null,
    ) : FamilieResult<Nothing>

    /** No answer (network, VPN, timeout). */
    data object Offline : FamilieResult<Nothing>

    data object NotConfigured : FamilieResult<Nothing>
}

/** What a create call returned, when the answer carries it. */
data class Created<T>(val value: T?)

fun <T> FamilieResult<T>.valueOrNull(): T? = (this as? FamilieResult.Ok)?.value

fun FamilieResult<*>.errorCode(): String? = when (this) {
    is FamilieResult.Failed -> code
    FamilieResult.Offline -> FamilieApi.CODE_OFFLINE
    FamilieResult.NotConfigured -> FamilieApi.CODE_OFFLINE
    is FamilieResult.Ok -> null
}

/**
 * The Familie plugin's app API (contract B3) through the core server's
 * client plugin bridge (`/api/v1/client/plugins/gatecontrol-familie/api/…`,
 * contract A3): one method per endpoint, typed answers from [FamilieJson].
 * Also the Alltag plugin's `app/events` (B5). Never throws.
 */
@Singleton
class FamilieApi @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
) {
    companion object {
        const val CODE_OFFLINE = "offline"
        const val CODE_INVALID = "invalid_answer"
        private const val TIMEOUT_MS = 20_000L
        /** No slashes, no leading dot (`..`): one plain path segment. */
        private val SEGMENT_RE = Regex("^[A-Za-z0-9_:-][A-Za-z0-9_.:-]{0,63}$")

        /** Path from fixed words and ids; an id that is not a plain token is refused. */
        internal fun path(vararg segments: Any): String? {
            val parts = segments.map { it.toString() }
            return if (parts.all { SEGMENT_RE.matches(it) }) parts.joinToString("/") else null
        }
    }

    private suspend fun <T> call(
        plugin: String = FAMILIE_PLUGIN,
        request: suspend ApiClient.() -> Response<JsonObject>,
        parse: (JsonObject) -> T?,
    ): FamilieResult<T> {
        val url = setupRepository.getServerUrl()
        if (url.isBlank() || !setupRepository.isConfigured()) return FamilieResult.NotConfigured
        return try {
            val response = withTimeoutOrNull(TIMEOUT_MS) { apiClientProvider.getClient(url).request() }
                ?: return FamilieResult.Offline
            if (response.isSuccessful) {
                val body = response.body() ?: JsonObject()
                if (body.has("ok") && body.get("ok").let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && !it.asBoolean }) {
                    return failed(body, response.code())
                }
                parse(body)?.let { FamilieResult.Ok(it) } ?: FamilieResult.Failed(CODE_INVALID, response.code())
            } else {
                val err = runCatching {
                    response.errorBody()?.use { b -> b.string().takeIf { it.length < 16_384 } }
                        ?.let { JsonParser.parseString(it).takeIf { e -> e.isJsonObject }?.asJsonObject }
                }.getOrNull()
                Timber.d("Familie: %s answered HTTP %d", plugin, response.code())
                failed(err, response.code())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            FamilieResult.Offline
        } catch (e: Exception) {
            Timber.d("Familie: call failed (%s)", e.javaClass.simpleName)
            FamilieResult.Failed(CODE_INVALID, 0)
        }
    }

    private fun failed(body: JsonObject?, http: Int): FamilieResult.Failed = FamilieResult.Failed(
        code = FamilieJson.errorCode(body) ?: if (body != null && http in 200..299) "failed" else "http_$http",
        http = http,
        retryAfter = FamilieJson.retryAfter(body),
        requestId = FamilieJson.refusedRequestId(body),
        field = FamilieJson.errorField(body),
        detail = FamilieJson.errorDriver(body),
    )

    private suspend fun <T> get(path: String?, query: Map<String, String> = emptyMap(), plugin: String = FAMILIE_PLUGIN, parse: (JsonObject) -> T?): FamilieResult<T> {
        val p = path ?: return FamilieResult.Failed(CODE_INVALID, 0)
        return call(plugin, { pluginGet(plugin, p, query) }, parse)
    }

    private suspend fun <T> post(path: String?, body: JsonObject = JsonObject(), parse: (JsonObject) -> T?): FamilieResult<T> {
        val p = path ?: return FamilieResult.Failed(CODE_INVALID, 0)
        return call(FAMILIE_PLUGIN, { pluginPost(FAMILIE_PLUGIN, p, body) }, parse)
    }

    private suspend fun <T> patch(path: String?, body: JsonObject, query: Map<String, String> = emptyMap(), parse: (JsonObject) -> T?): FamilieResult<T> {
        val p = path ?: return FamilieResult.Failed(CODE_INVALID, 0)
        return call(FAMILIE_PLUGIN, { pluginPatch(FAMILIE_PLUGIN, p, body, query) }, parse)
    }

    private suspend fun <T> put(path: String?, body: JsonObject, parse: (JsonObject) -> T?): FamilieResult<T> {
        val p = path ?: return FamilieResult.Failed(CODE_INVALID, 0)
        return call(FAMILIE_PLUGIN, { pluginPut(FAMILIE_PLUGIN, p, body) }, parse)
    }

    private suspend fun delete(path: String?, query: Map<String, String> = emptyMap()): FamilieResult<Unit> {
        val p = path ?: return FamilieResult.Failed(CODE_INVALID, 0)
        return call(FAMILIE_PLUGIN, { pluginDelete(FAMILIE_PLUGIN, p, query) }) { }
    }

    private val unit: (JsonObject) -> Unit = { }

    // ─── me / overview ───────────────────────────────────────────────

    suspend fun me(): FamilieResult<FamilieProfile> = get(path("me"), parse = FamilieJson::profile)

    suspend fun overview(): FamilieResult<FamilieOverview> = get(path("overview"), parse = FamilieJson::overview)

    // ─── shopping ────────────────────────────────────────────────────

    suspend fun shopping(since: String?): FamilieResult<ShoppingSnapshot> =
        get(path("shopping"), if (since != null) mapOf("since" to since) else emptyMap(), parse = FamilieJson::shopping)

    suspend fun shoppingOps(ops: List<ShoppingOp>): FamilieResult<FamilieJson.OpsResult> =
        post(path("shopping", "ops"), FamilieJson.opsBody(ops), FamilieJson::opsResult)

    suspend fun shoppingGoing(listId: String): FamilieResult<Unit> = post(path("shopping", "lists", listId, "going"), parse = unit)

    // ─── tasks ───────────────────────────────────────────────────────

    suspend fun tasks(): FamilieResult<TasksPayload> = get(path("tasks"), parse = FamilieJson::tasks)

    suspend fun createTask(draft: TaskDraft): FamilieResult<Unit> = post(path("tasks"), FamilieJson.taskBody(draft), unit)

    suspend fun updateTask(id: Int, draft: TaskDraft): FamilieResult<Unit> = patch(path("tasks", id), FamilieJson.taskBody(draft), parse = unit)

    suspend fun deleteTask(id: Int): FamilieResult<Unit> = delete(path("tasks", id))

    /** [occurrence] (`YYYY-MM-DD`) from a reminder: tick exactly that one. */
    suspend fun taskDone(id: Int, occurrence: String? = null): FamilieResult<Unit> =
        post(path("tasks", id, "done"), JsonObject().apply { occurrence?.let { addProperty("occurrence", it) } }, unit)

    suspend fun taskSnooze(id: Int, minutes: Int, occurrence: String? = null): FamilieResult<Unit> =
        post(
            path("tasks", id, "snooze"),
            JsonObject().apply {
                addProperty("minutes", minutes.coerceIn(15, 240))
                occurrence?.let { addProperty("occurrence", it) }
            },
            unit,
        )

    // ─── dates ───────────────────────────────────────────────────────

    suspend fun dates(from: String, to: String): FamilieResult<List<FamilieEvent>> =
        get(path("dates"), mapOf("from" to from, "to" to to), parse = FamilieJson::events)

    suspend fun createDate(draft: EventDraft): FamilieResult<Unit> = post(path("dates"), FamilieJson.eventBody(draft), unit)

    suspend fun updateDate(id: String, draft: EventDraft): FamilieResult<Unit> = patch(path("dates", id), FamilieJson.eventBody(draft), parse = unit)

    suspend fun deleteDate(id: String): FamilieResult<Unit> = delete(path("dates", id))

    /** Alltag appointments and waste dates (B5, ≤ 62 days). */
    suspend fun alltagEvents(from: String, to: String): FamilieResult<AlltagPayload> =
        get(path("app", "events"), mapOf("from" to from, "to" to to), plugin = ALLTAG_PLUGIN, parse = FamilieJson::alltag)

    // ─── locate ──────────────────────────────────────────────────────

    /** The created request; its fields are null when the server's answer does not carry it. */
    suspend fun requestLocation(childId: Int): FamilieResult<Created<LocateRequest>> =
        post(path("locate", childId), parse = { Created(FamilieJson.requestIn(it)) })

    suspend fun answer(requestId: Int, lat: Double, lon: Double, accuracy: Double, at: String, battery: Int?): FamilieResult<Unit> =
        post(path("locate", "requests", requestId, "answer"), FamilieJson.answerBody(lat, lon, accuracy, at, battery), unit)

    suspend fun decline(requestId: Int, reason: String? = "busy"): FamilieResult<Unit> =
        post(path("locate", "requests", requestId, "decline"), JsonObject().apply { reason?.let { addProperty("reason", it) } }, unit)

    suspend fun fail(requestId: Int, code: LocateFailCode): FamilieResult<Unit> =
        post(path("locate", "requests", requestId, "fail"), JsonObject().apply { addProperty("code", code.wire) }, unit)

    suspend fun locateRequests(childId: Int?, limit: Int = 30): FamilieResult<List<LocateRequest>> =
        get(
            path("locate", "requests"),
            buildMap {
                if (childId != null) put("childId", childId.toString())
                put("limit", limit.toString())
            },
            parse = FamilieJson::locateRequests,
        )

    suspend fun locateRequest(id: Int): FamilieResult<LocateRequest> = get(path("locate", "requests", id), parse = FamilieJson::requestIn)

    // ─── arrival & SOS ───────────────────────────────────────────────

    suspend fun arrival(label: String?, lat: Double?, lon: Double?, accuracy: Double?): FamilieResult<Unit> =
        post(
            path("arrival"),
            FamilieJson.positionBody(lat, lon, accuracy, label?.trim()?.take(60)?.takeIf { it.isNotBlank() }?.let { mapOf("label" to it) }.orEmpty()),
            unit,
        )

    suspend fun sos(lat: Double?, lon: Double?, accuracy: Double?, at: String?): FamilieResult<Created<Int>> =
        post(path("sos"), FamilieJson.positionBody(lat, lon, accuracy, at?.let { mapOf("at" to it) }.orEmpty()), parse = { Created(FamilieJson.sosId(it)) })

    suspend fun sosAck(sosId: Int): FamilieResult<Unit> = post(path("sos", sosId, "ack"), parse = unit)

    // ─── places ──────────────────────────────────────────────────────

    suspend fun places(): FamilieResult<List<FamiliePlace>> = get(path("places"), parse = FamilieJson::places)

    suspend fun createPlace(draft: PlaceDraft): FamilieResult<Unit> = post(path("places"), FamilieJson.placeBody(draft), unit)

    suspend fun updatePlace(id: Int, draft: PlaceDraft): FamilieResult<Unit> = patch(path("places", id), FamilieJson.placeBody(draft), parse = unit)

    suspend fun deletePlace(id: Int): FamilieResult<Unit> = delete(path("places", id))

    suspend fun placeConsent(id: Int, accept: Boolean): FamilieResult<Unit> =
        post(path("places", id, "consent"), JsonObject().apply { addProperty("accept", accept) }, unit)

    suspend fun placeTriggered(id: Int, transition: String, at: String): FamilieResult<Unit> =
        post(
            path("places", id, "triggered"),
            JsonObject().apply {
                addProperty("transition", transition)
                addProperty("at", at)
            },
            unit,
        )

    // ─── rides (1.1) ─────────────────────────────────────────────────

    /** A ride id as a path segment (`12` or `3:2026-10-14`); anything else is refused. */
    private fun rideId(id: String): String? = id.takeIf { FamilieJson.RIDE_ID_RE.matches(it) }

    private fun seriesQuery(series: Boolean): Map<String, String> = if (series) mapOf("series" to "1") else emptyMap()

    /** Rides from [from] to [to] (`YYYY-MM-DD`, ≤ 62 days; the server's default: today … +7). */
    suspend fun rides(from: String? = null, to: String? = null): FamilieResult<List<Ride>> =
        get(
            path("rides"),
            buildMap {
                if (from != null) put("from", from)
                if (to != null) put("to", to)
            },
            parse = FamilieJson::rides,
        )

    /** The created ride (its first occurrence), null when the answer does not carry it. */
    suspend fun createRide(draft: RideDraft): FamilieResult<Created<Ride>> =
        post(path("rides"), FamilieJson.rideBody(draft, create = true), parse = { Created(FamilieJson.rideIn(it)) })

    /**
     * Parents; for a series occurrence only that one, with [series] the whole
     * series (then [withDriver] sets the series' driver from the draft).
     */
    suspend fun updateRide(id: String, draft: RideDraft, series: Boolean = false, withDriver: Boolean = false): FamilieResult<Unit> =
        patch(
            rideId(id)?.let { path("rides", it) },
            FamilieJson.rideBody(draft, create = false, series = series, withDriver = series && withDriver),
            seriesQuery(series),
            parse = unit,
        )

    /** Parents: a cancelled occurrence is open again (`PATCH {status:"open"}`). */
    suspend fun reopenRide(id: String): FamilieResult<Unit> =
        patch(rideId(id)?.let { path("rides", it) }, JsonObject().apply { addProperty("status", Ride.STATUS_OPEN) }, parse = unit)

    /**
     * An occurrence (or a one-off ride) is cancelled — parents, or the child
     * who asked for a one-off ride; with [series] the whole series goes (parents).
     */
    suspend fun deleteRide(id: String, series: Boolean = false): FamilieResult<Unit> =
        delete(rideId(id)?.let { path("rides", it) }, seriesQuery(series))

    private suspend fun rideAction(id: String, action: String, body: JsonObject = JsonObject()): FamilieResult<Unit> =
        post(rideId(id)?.let { path("rides", it, action) }, body, unit)

    /** "Ich mache es": the caller drives. */
    suspend fun takeRide(id: String): FamilieResult<Unit> = rideAction(id, "take")

    /** "Rückgängig": the driver gives the ride back. */
    suspend fun releaseRide(id: String): FamilieResult<Unit> = rideAction(id, "release")

    /** "Kann nicht" (parents). */
    suspend fun declineRide(id: String): FamilieResult<Unit> = rideAction(id, "decline")

    suspend fun setRideDriver(id: String, driver: DriverChoice): FamilieResult<Unit> = rideAction(id, "driver", FamilieJson.driverBody(driver))

    /** "Wer kann …?": push to the parents who have not declined (≥ 5 min apart). */
    suspend fun askRide(id: String): FamilieResult<Unit> = rideAction(id, "ask")

    // ─── emergency card (1.1) ────────────────────────────────────────

    /** The card and the answer as it came (for the encrypted offline copy). */
    data class EmergencyFetch(val card: EmergencyCard, val raw: JsonObject)

    suspend fun emergency(): FamilieResult<EmergencyFetch> =
        get(path("emergency"), parse = { EmergencyFetch(FamilieJson.emergency(it), it) })

    suspend fun putEmergencyMember(userId: Int, draft: EmergencyMemberDraft): FamilieResult<Unit> =
        put(path("emergency", "members", userId), FamilieJson.emergencyMemberBody(draft), unit)

    suspend fun putEmergencyContacts(contacts: List<EmergencyContact>): FamilieResult<Unit> =
        put(path("emergency", "contacts"), FamilieJson.emergencyContactsBody(contacts), unit)

    // ─── allowance (1.2) ─────────────────────────────────────────────

    /** Parents: every child's account; a child: its own. */
    suspend fun allowance(): FamilieResult<AllowancePayload> = get(path("allowance"), parse = FamilieJson12::allowance)

    /** Parents: a booking (`manual` signed, `spend` negative). */
    suspend fun allowanceBooking(childId: Int, cents: Int, note: String, kind: String): FamilieResult<Unit> =
        post(path("allowance", childId, "bookings"), FamilieJson12.bookingBody(cents, note, kind), unit)

    /** Parents: removes a manual / spend booking. */
    suspend fun deleteAllowanceBooking(childId: Int, bookingId: Int): FamilieResult<Unit> = delete(path("allowance", childId, "bookings", bookingId))

    suspend fun allowanceRule(childId: Int, draft: AllowanceRuleDraft): FamilieResult<Unit> =
        put(path("allowance", childId, "rule"), FamilieJson12.ruleBody(draft), unit)

    /** A child asks to turn [stars] into money (parents approve). */
    suspend fun allowanceConvert(childId: Int, stars: Int): FamilieResult<Unit> =
        post(path("allowance", childId, "convert"), JsonObject().apply { addProperty("stars", stars) }, unit)

    /** Parents: approve or decline a convert request. */
    suspend fun allowanceDecide(requestId: Int, approve: Boolean): FamilieResult<Unit> =
        post(path("allowance", "requests", requestId, if (approve) "approve" else "decline"), parse = unit)

    // ─── wishes (1.2) ────────────────────────────────────────────────

    /** Everyone's lists; reservations on [myUserId]'s own items never come through. */
    suspend fun wishes(myUserId: Int?): FamilieResult<List<WishItem>> =
        get(path("wishes"), parse = { FamilieJson12.wishes(it, myUserId) })

    suspend fun createWish(draft: WishDraft): FamilieResult<Unit> = post(path("wishes"), FamilieJson12.wishBody(draft), unit)

    suspend fun updateWish(id: Int, draft: WishDraft): FamilieResult<Unit> = patch(path("wishes", id), FamilieJson12.wishBody(draft), parse = unit)

    /** The owner got it (after the occasion): hidden from then on. */
    suspend fun wishGotIt(id: Int, gotIt: Boolean): FamilieResult<Unit> =
        patch(path("wishes", id), JsonObject().apply { addProperty("gotIt", gotIt) }, parse = unit)

    suspend fun deleteWish(id: Int): FamilieResult<Unit> = delete(path("wishes", id))

    /** "Ich besorge das" ([reserve]) / "freigeben". */
    suspend fun reserveWish(id: Int, reserve: Boolean): FamilieResult<Unit> =
        post(path("wishes", id, if (reserve) "reserve" else "unreserve"), parse = unit)

    // ─── expenses (1.2, parents) ─────────────────────────────────────

    /** [month] `YYYY-MM`. */
    suspend fun expenses(month: String): FamilieResult<ExpenseMonth> =
        if (!FamilieJson12.isMonth(month)) FamilieResult.Failed(CODE_INVALID, 0)
        else get(path("expenses"), mapOf("month" to month), parse = { FamilieJson12.expenses(it, month) })

    suspend fun createExpense(draft: ExpenseDraft): FamilieResult<Unit> = post(path("expenses"), FamilieJson12.expenseBody(draft), unit)

    suspend fun updateExpense(id: Int, draft: ExpenseDraft): FamilieResult<Unit> =
        patch(path("expenses", id), FamilieJson12.expenseBody(draft), parse = unit)

    suspend fun deleteExpense(id: Int): FamilieResult<Unit> = delete(path("expenses", id))

    /** "Erledigt": the month counts as settled (a record only, nothing is paid). */
    suspend fun settleExpenses(month: String): FamilieResult<Unit> =
        post(path("expenses", "settle"), JsonObject().apply { addProperty("month", month) }, unit)

    /** Takes "Erledigt" back. */
    suspend fun unsettleExpenses(month: String): FamilieResult<Unit> =
        post(path("expenses", "unsettle"), JsonObject().apply { addProperty("month", month) }, unit)

    // ─── board (1.2) ─────────────────────────────────────────────────

    suspend fun board(): FamilieResult<BoardPayload> = get(path("board"), parse = FamilieJson12::board)

    suspend fun createNote(draft: NoteDraft): FamilieResult<Unit> = post(path("board", "notes"), FamilieJson12.noteBody(draft), unit)

    suspend fun updateNote(id: Int, draft: NoteDraft): FamilieResult<Unit> =
        patch(path("board", "notes", id), FamilieJson12.noteBody(draft), parse = unit)

    suspend fun deleteNote(id: Int): FamilieResult<Unit> = delete(path("board", "notes", id))

    /** A new checklist from [templateId] or with [title]. */
    suspend fun createChecklist(templateId: Int?, title: String?): FamilieResult<Unit> =
        post(path("board", "checklists"), FamilieJson12.checklistBody(templateId, title), unit)

    suspend fun deleteChecklist(id: Int): FamilieResult<Unit> = delete(path("board", "checklists", id))

    /** Every item of the list open again (packing for the next trip). */
    suspend fun resetChecklist(id: Int): FamilieResult<Unit> = post(path("board", "checklists", id, "reset"), parse = unit)

    suspend fun addChecklistItem(checklistId: Int, text: String): FamilieResult<Unit> =
        post(path("board", "checklists", checklistId, "items"), JsonObject().apply { addProperty("text", text.trim().take(80)) }, unit)

    suspend fun checkChecklistItem(checklistId: Int, itemId: Int, done: Boolean): FamilieResult<Unit> =
        patch(path("board", "checklists", checklistId, "items", itemId), JsonObject().apply { addProperty("done", done) }, parse = unit)

    suspend fun deleteChecklistItem(checklistId: Int, itemId: Int): FamilieResult<Unit> =
        delete(path("board", "checklists", checklistId, "items", itemId))

    /** Saves a checklist as a template ("Urlaub", "Schwimmbad"). */
    suspend fun createTemplate(checklistId: Int, title: String): FamilieResult<Unit> =
        post(
            path("board", "templates"),
            JsonObject().apply {
                addProperty("checklistId", checklistId)
                addProperty("title", title.trim().take(60))
            },
            unit,
        )

    suspend fun deleteTemplate(id: Int): FamilieResult<Unit> = delete(path("board", "templates", id))

    // ─── meals (1.2) ─────────────────────────────────────────────────

    /** [week] `YYYY-Www`. */
    suspend fun meals(week: String): FamilieResult<MealWeek> =
        if (!MealWeek.isKey(week)) FamilieResult.Failed(CODE_INVALID, 0)
        else get(path("meals"), mapOf("week" to week), parse = { FamilieJson12.meals(it, week) })

    /** [slot] lunch | dinner. */
    suspend fun putMeal(date: String, slot: String, meal: Meal): FamilieResult<Unit> =
        put(mealPath(date, slot), FamilieJson12.mealBody(meal), unit)

    suspend fun deleteMeal(date: String, slot: String): FamilieResult<Unit> = delete(mealPath(date, slot))

    /** "Zutaten auf die Liste": the ingredients become open items of [listId] (no duplicates). */
    suspend fun mealToShopping(date: String, slot: String, listId: String): FamilieResult<com.gatecontrol.android.familie.money.ShoppingAdded> =
        post(mealPath(date, slot, "to-shopping"), JsonObject().apply { addProperty("listId", listId) }, FamilieJson12::shoppingAdded)

    private fun mealPath(date: String, slot: String, vararg more: String): String? =
        if (FamilieJson.date(date) == null || slot !in MealWeek.SLOTS) null else path("meals", date, slot, *more)

    // ─── presence (1.2) ──────────────────────────────────────────────

    data class PresenceFetch(val payload: PresencePayload, val myConsent: Boolean?)

    suspend fun presence(myUserId: Int): FamilieResult<PresenceFetch> =
        get(path("presence"), parse = { PresenceFetch(FamilieJson12.presence(it), FamilieJson12.myConsent(it, myUserId)) })

    /** Only for oneself. */
    suspend fun presenceConsent(share: Boolean): FamilieResult<Unit> =
        put(path("presence", "consent"), JsonObject().apply { addProperty("share", share) }, unit)

    /** Only with consent; the server takes at most one per minute. */
    suspend fun reportPresence(home: Boolean, at: String): FamilieResult<Unit> =
        post(path("presence"), FamilieJson12.presenceBody(home, at), unit)
}
