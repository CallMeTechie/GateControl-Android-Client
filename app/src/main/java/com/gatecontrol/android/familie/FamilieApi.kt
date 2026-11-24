package com.gatecontrol.android.familie

import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.familie.FamilieJson.obj
import com.gatecontrol.android.familie.board.BoardPayload
import com.gatecontrol.android.familie.board.NoteDraft
import com.gatecontrol.android.familie.emergency.EmergencyCard
import com.gatecontrol.android.familie.emergency.EmergencyContact
import com.gatecontrol.android.familie.emergency.EmergencyMemberDraft
import com.gatecontrol.android.familie.internet.InternetChild
import com.gatecontrol.android.familie.internet.InternetJson
import com.gatecontrol.android.familie.internet.InternetPayload
import com.gatecontrol.android.familie.internet.PauseRequest
import com.gatecontrol.android.familie.internet.WifiPresence
import com.gatecontrol.android.familie.meals.Meal
import com.gatecontrol.android.familie.meals.MealWeek
import com.gatecontrol.android.familie.money.AllowancePayload
import com.gatecontrol.android.familie.money.AllowanceRuleDraft
import com.gatecontrol.android.familie.money.ExpenseDraft
import com.gatecontrol.android.familie.money.ExpenseMonth
import com.gatecontrol.android.familie.money.WishDraft
import com.gatecontrol.android.familie.money.WishItem
import com.gatecontrol.android.familie.places.RuleWatch
import com.gatecontrol.android.familie.presence.PresencePayload
import com.gatecontrol.android.familie.rides.DriverChoice
import com.gatecontrol.android.familie.rides.Ride
import com.gatecontrol.android.familie.rides.RideDraft
import com.gatecontrol.android.familie.school.SCHULE_PLUGIN
import com.gatecontrol.android.familie.school.SchoolAbsence
import com.gatecontrol.android.familie.school.SchoolChange
import com.gatecontrol.android.familie.school.SchoolFetch
import com.gatecontrol.android.familie.school.SchoolJson
import com.gatecontrol.android.familie.school.SchoolListFetch
import com.gatecontrol.android.familie.school.SchoolAttachmentFile
import com.gatecontrol.android.familie.school.SchoolMessage
import com.gatecontrol.android.familie.school.SchoolMessageAnswer
import com.gatecontrol.android.familie.rules.Rule
import com.gatecontrol.android.familie.rules.RuleJson
import com.gatecontrol.android.familie.rules.RuleOptionPlace
import com.gatecontrol.android.familie.rules.RuleOptions
import com.gatecontrol.android.familie.rules.RuleTestResult
import com.gatecontrol.android.familie.rules.RuleVacation
import com.gatecontrol.android.familie.rules.RulesPayload
import com.gatecontrol.android.familie.shopping.ShoppingOp
import com.gatecontrol.android.familie.shopping.ShoppingOpsResult
import com.gatecontrol.android.familie.shopping.ShoppingSnapshot
import com.gatecontrol.android.network.ApiClientProvider
import com.google.gson.JsonObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ResponseBody
import retrofit2.Response
import timber.log.Timber

/**
 * The Familie plugin's app API (contract B3) through the core server's
 * client plugin bridge (`/api/v1/client/plugins/gatecontrol-familie/api/…`,
 * contract A3): one method per endpoint, typed answers from [FamilieJson].
 * Also the Alltag plugin's `app/events` (B5) and the Schule plugin's
 * `app/timetable` / `app/changes`. Never throws. The calls the phone app
 * shares with GateControl TV (shopping lists, dates, Alltag) are
 * [FamilieHomeApi]'s in :core:familie-api; they are passed on from here.
 */
@Singleton
class FamilieApi @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
) {
    companion object {
        const val CODE_OFFLINE = FamiliePluginCalls.CODE_OFFLINE
        const val CODE_INVALID = FamiliePluginCalls.CODE_INVALID
        /** A school attachment: the plugin logs in at the school and downloads it first (~30 s). */
        private const val ATTACHMENT_TIMEOUT_MS = 75_000L
        /** Download of a plugin file (≤ 25 MB). */
        private const val MEDIA_TIMEOUT_MS = 120_000L

        /** Path from fixed words and ids; an id that is not a plain token is refused. */
        internal fun path(vararg segments: Any): String? = FamiliePluginCalls.path(*segments)
    }

    private val calls = FamiliePluginCalls(setupRepository, apiClientProvider)

    /** Shopping lists, dates and Alltag: shared with GateControl TV. */
    private val home = FamilieHomeApi(setupRepository, apiClientProvider)

    private suspend fun <T> get(path: String?, query: Map<String, String> = emptyMap(), plugin: String = FAMILIE_PLUGIN, parse: (JsonObject) -> T?): FamilieResult<T> =
        calls.get(path, query, plugin, parse)

    private suspend fun <T> post(path: String?, body: JsonObject = JsonObject(), parse: (JsonObject) -> T?): FamilieResult<T> =
        calls.post(path, body, parse)

    /** A POST without a body to another plugin's app API; [slow] for calls the plugin needs long for. */
    private suspend fun <T> postTo(
        plugin: String,
        path: String?,
        timeoutMs: Long = FamiliePluginCalls.TIMEOUT_MS,
        slow: Boolean = false,
        parse: (JsonObject) -> T?,
    ): FamilieResult<T> = calls.postTo(plugin, path, timeoutMs, slow, parse)

    private suspend fun <T> patch(path: String?, body: JsonObject, query: Map<String, String> = emptyMap(), parse: (JsonObject) -> T?): FamilieResult<T> =
        calls.patch(path, body, query, parse)

    private suspend fun <T> put(path: String?, body: JsonObject, parse: (JsonObject) -> T?): FamilieResult<T> =
        calls.put(path, body, parse)

    private suspend fun delete(path: String?, query: Map<String, String> = emptyMap()): FamilieResult<Unit> = calls.delete(path, query)

    private val unit: (JsonObject) -> Unit = { }

    // ─── me / overview ───────────────────────────────────────────────

    suspend fun me(): FamilieResult<FamilieProfile> = get(path("me"), parse = FamilieJson::profile)

    suspend fun overview(): FamilieResult<FamilieOverview> = get(path("overview"), parse = FamilieJson::overview)

    // ─── shopping ────────────────────────────────────────────────────

    suspend fun shopping(since: String?): FamilieResult<ShoppingSnapshot> = home.shopping(since)

    suspend fun shoppingOps(ops: List<ShoppingOp>): FamilieResult<ShoppingOpsResult> = home.shoppingOps(ops)

    suspend fun shoppingGoing(listId: String): FamilieResult<Unit> = post(path("shopping", "lists", listId, "going"), parse = unit)

    /**
     * B3: asks [userId] (null: nobody) to do the shopping of [listId]; [notify]
     * `direct` (chat with that person) | `family` (family chat) | `push`.
     */
    suspend fun shoppingAssign(listId: String, userId: Int?, dueAt: String?, notify: String): FamilieResult<DeliveryResult> =
        post(
            path("shopping", "lists", listId, "assign"),
            JsonObject().apply {
                if (userId == null) add("userId", com.google.gson.JsonNull.INSTANCE) else addProperty("userId", userId)
                dueAt?.let { addProperty("dueAt", it) }
                addProperty("notify", notify)
            },
        ) { o -> delivery(o.obj("notified")) }

    /**
     * How a card / message of the plugin went out (`notified` of an
     * assignment, `card` of a quick task): [ok] null when nothing was sent.
     */
    data class DeliveryResult(val ok: Boolean?, val code: String?)

    private fun delivery(o: JsonObject?): DeliveryResult =
        DeliveryResult(o?.let { FamilieJson.run { it.bool("ok") } }, o?.let { FamilieJson.errorCode(it) })

    // ─── tasks ───────────────────────────────────────────────────────

    suspend fun tasks(): FamilieResult<TasksPayload> = get(path("tasks"), parse = FamilieJson::tasks)

    suspend fun createTask(draft: TaskDraft): FamilieResult<Unit> = post(path("tasks"), FamilieJson.taskBody(draft), unit)

    suspend fun updateTask(id: Int, draft: TaskDraft): FamilieResult<Unit> = patch(path("tasks", id), FamilieJson.taskBody(draft), parse = unit)

    suspend fun deleteTask(id: Int): FamilieResult<Unit> = delete(path("tasks", id))

    /**
     * B4: a task from the chat (`POST tasks/quick`); with [conversationId] the
     * plugin posts its card ("Erledigt") into that conversation.
     * [repeat] `none` | `weekly:<1..7>`.
     */
    suspend fun quickTask(title: String, assigneeId: Int?, due: String?, repeat: String?, conversationId: Long?): FamilieResult<DeliveryResult> =
        post(
            path("tasks", "quick"),
            JsonObject().apply {
                addProperty("title", title)
                if (assigneeId == null) add("assigneeId", com.google.gson.JsonNull.INSTANCE) else addProperty("assigneeId", assigneeId)
                if (due == null) add("due", com.google.gson.JsonNull.INSTANCE) else addProperty("due", due)
                repeat?.let { addProperty("repeat", it) }
                conversationId?.let { addProperty("conversationId", it) }
            },
        ) { o -> delivery(o.obj("card")) }

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

    suspend fun dates(from: String, to: String): FamilieResult<List<FamilieEvent>> = home.dates(from, to)

    suspend fun createDate(draft: EventDraft): FamilieResult<Unit> = home.createDate(draft)

    suspend fun updateDate(id: String, draft: EventDraft): FamilieResult<Unit> = home.updateDate(id, draft)

    suspend fun deleteDate(id: String): FamilieResult<Unit> = home.deleteDate(id)

    /** Alltag appointments and waste dates (B5, ≤ 62 days). */
    suspend fun alltagEvents(from: String, to: String): FamilieResult<AlltagPayload> = home.alltagEvents(from, to)

    // ─── Schule plugin (WebUntis timetable) ──────────────────────────

    /** Timetable of the students the caller may see, [from]…[to] (`YYYY-MM-DD`, ≤ 14 days), with the answer as it came. */
    suspend fun schoolTimetable(from: String, to: String): FamilieResult<SchoolFetch> =
        get(path("app", "timetable"), mapOf("from" to from, "to" to to), plugin = SCHULE_PLUGIN) { o ->
            SchoolJson.timetable(o)?.let { SchoolFetch(it, o.toString()) }
        }

    /** The change log of the last [days] days (1…30), newest first. */
    suspend fun schoolChanges(days: Int = 7): FamilieResult<List<SchoolChange>> =
        get(path("app", "changes"), mapOf("days" to days.coerceIn(1, 30).toString()), plugin = SCHULE_PLUGIN, parse = SchoolJson::changes)

    /** Messages of the last [days] days (1…90; plugin 1.4+, older plugins answer 404), with the answer as it came. */
    suspend fun schoolMessages(days: Int = 30): FamilieResult<SchoolListFetch<SchoolMessage>> =
        get(path("app", "messages"), mapOf("days" to days.coerceIn(1, 90).toString()), plugin = SCHULE_PLUGIN) { o ->
            SchoolJson.messages(o)?.let { SchoolListFetch(it, o.toString()) }
        }

    /** Marks a message read for this user (plugin 1.5+, idempotent; older plugins answer 404). */
    suspend fun schoolMessageRead(id: String): FamilieResult<SchoolMessageAnswer> =
        postTo(SCHULE_PLUGIN, path("app", "messages", id, "read"), parse = SchoolJson::messageAnswer)

    /** Sends the read confirmation to the school (plugin 1.5+; only on the person's explicit tap). */
    suspend fun schoolMessageConfirm(id: String): FamilieResult<SchoolMessageAnswer> =
        postTo(SCHULE_PLUGIN, path("app", "messages", id, "confirm"), ATTACHMENT_TIMEOUT_MS, slow = true, parse = SchoolJson::messageAnswer)

    /** Lets the plugin fetch an attachment into its media store (plugin 1.5+; up to ~30 s). */
    suspend fun schoolAttachment(messageId: String, attachmentId: String): FamilieResult<SchoolAttachmentFile> =
        postTo(
            SCHULE_PLUGIN,
            path("app", "messages", messageId, "attachments", attachmentId),
            timeoutMs = ATTACHMENT_TIMEOUT_MS,
            slow = true,
            parse = SchoolJson::attachmentFile,
        )

    /**
     * Schule 1.7: an attachment into the person's Drive folder [folderId] (the plugin hands it over on the server; it
     * may first fetch it from WebUntis) → where it went ("Familie › Schule › Lena › Elternbrief.pdf").
     */
    suspend fun schoolAttachmentToDrive(messageId: String, attachmentId: String, folderId: Long): FamilieResult<String> {
        val p = path("app", "messages", messageId, "attachments", attachmentId, "drive") ?: return FamilieResult.Failed(CODE_INVALID, 0)
        val body = JsonObject().apply { addProperty("folderId", folderId) }
        return calls.call(SCHULE_PLUGIN, { pluginPost(SCHULE_PLUGIN, p, body) }, SchoolJson::driveSaved, ATTACHMENT_TIMEOUT_MS, true)
    }

    /**
     * A file of the Schule plugin's media store (`GET …/plugins/gatecontrol-schule/media/<mediaId>`,
     * with the device token like every client call); the caller closes the body.
     * Null when the server cannot be reached.
     */
    suspend fun schoolMedia(mediaId: String): Response<ResponseBody>? {
        if (path(mediaId) == null) return null
        val url = setupRepository.getServerUrl()
        if (url.isBlank() || !setupRepository.isConfigured()) return null
        return try {
            withTimeoutOrNull(MEDIA_TIMEOUT_MS) { apiClientProvider.mediaClient(url).pluginMediaGet(SCHULE_PLUGIN, mediaId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Familie: school file not loaded (%s)", e.javaClass.simpleName)
            null
        }
    }

    /** Absences of the last [days] days (1…180; plugin 1.4+, older plugins answer 404), with the answer as it came. */
    suspend fun schoolAbsences(days: Int = 60): FamilieResult<SchoolListFetch<SchoolAbsence>> =
        get(path("app", "absences"), mapOf("days" to days.coerceIn(1, 180).toString()), plugin = SCHULE_PLUGIN) { o ->
            SchoolJson.absences(o)?.let { SchoolListFetch(it, o.toString()) }
        }

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

    /** [myPlaces] / [watchVersion]: Familie 1.5 (place events for rules), null from older servers. */
    data class PresenceFetch(val payload: PresencePayload, val myConsent: Boolean?, val myPlaces: Boolean? = null, val watchVersion: String? = null)

    suspend fun presence(myUserId: Int): FamilieResult<PresenceFetch> =
        get(path("presence"), parse = {
            PresenceFetch(FamilieJson12.presence(it), FamilieJson12.myConsent(it, myUserId), FamilieJson12.myPlacesConsent(it), FamilieJson12.watchVersion(it))
        })

    /** Only for oneself; each flag only when given (`places`: Familie 1.5). */
    suspend fun presenceConsent(share: Boolean? = null, places: Boolean? = null): FamilieResult<Unit> =
        put(
            path("presence", "consent"),
            JsonObject().apply {
                share?.let { addProperty("share", it) }
                places?.let { addProperty("places", it) }
            },
            unit,
        )

    /** Only with consent; the server takes at most one per minute. The answer's `watchVersion` (1.5), if any. */
    suspend fun reportPresence(home: Boolean, at: String): FamilieResult<String?> =
        post(path("presence"), FamilieJson12.presenceBody(home, at)) { FamilieJson12.watchVersion(it) ?: "" }
            .let { r -> if (r is FamilieResult.Ok) FamilieResult.Ok(r.value?.takeIf { it.isNotEmpty() }) else r }

    // ─── Wi-Fi detection (1.17) ───────────────────────────────────────

    /** My switch "WLAN-Erkennung verwenden" and my own devices in the home Wi-Fi. */
    suspend fun presenceWifi(): FamilieResult<WifiPresence> = get(path("presence", "wifi"), parse = InternetJson::wifi)

    /** Only for oneself (the person's consent). */
    suspend fun setPresenceWifi(enabled: Boolean): FamilieResult<WifiPresence> =
        put(path("presence", "wifi"), InternetJson.wifiBody(enabled), InternetJson::wifi)

    /** Whether one of my devices counts for "zu Hause". */
    suspend fun setPresenceWifiDevice(deviceId: Int, counts: Boolean): FamilieResult<WifiPresence> =
        put(path("presence", "wifi", "devices", deviceId), InternetJson.wifiDeviceBody(counts), InternetJson::wifi)

    // ─── Internet zu Hause (1.18 / 1.20) ──────────────────────────────

    /** Parents: every minor child; a minor child: itself, read only. `available` false without the function. */
    suspend fun internet(): FamilieResult<InternetPayload> = get(path("internet"), parse = InternetJson::payload)

    /** Parents only; the answer is the child as it is now. */
    suspend fun internetPause(childId: Int, request: PauseRequest): FamilieResult<InternetChild> =
        post(path("internet", childId, "pause"), InternetJson.pauseBody(request), InternetJson::childAnswer)

    /** Ends the pause (an extra time stays). */
    suspend fun internetResume(childId: Int): FamilieResult<InternetChild> =
        post(path("internet", childId, "resume"), parse = InternetJson::childAnswer)

    /** "Heute länger"; [minutes] 0 ends it. */
    suspend fun internetExtend(childId: Int, minutes: Int, fromNow: Boolean = false): FamilieResult<InternetChild> =
        post(path("internet", childId, "extend"), InternetJson.extendBody(minutes, fromNow), InternetJson::childAnswer)

    // ─── place events for rules (1.5) ─────────────────────────────────

    /** The rule places this phone watches (empty without my consent). */
    suspend fun presenceWatch(): FamilieResult<RuleWatch> = get(path("presence", "watch"), parse = FamilieJson12::ruleWatch)

    /** I entered / left rule place [id]. */
    suspend fun rulePlaceEvent(id: Int, transition: String, at: String): FamilieResult<Unit> =
        post(
            path("presence", "places", id, "event"),
            JsonObject().apply {
                addProperty("transition", transition)
                addProperty("at", at)
            },
            unit,
        )

    // ─── rules and reminders (B1) ────────────────────────────────────

    suspend fun rules(): FamilieResult<RulesPayload> = get(path("rules"), parse = RuleJson::payload)

    suspend fun ruleOptions(): FamilieResult<RuleOptions> = get(path("rules", "options"), parse = RuleJson::options)

    suspend fun createRule(rule: Rule): FamilieResult<Rule?> =
        post(path("rules"), RuleJson.body(rule)) { o -> java.util.Optional.ofNullable(o.obj("rule")?.let(RuleJson::rule)) }
            .let { r -> if (r is FamilieResult.Ok) FamilieResult.Ok(r.value.orElse(null)) else r.asFailure() }

    /** The scope stays as created (Familie 1.9 refuses to change it). */
    suspend fun updateRule(id: Int, rule: Rule): FamilieResult<Unit> = patch(path("rules", id), RuleJson.body(rule, withScope = false), parse = unit)

    /** A dry run of a rule not saved yet (`POST rules/test`). */
    suspend fun testRuleDraft(rule: Rule): FamilieResult<RuleTestResult> = post(path("rules", "test"), RuleJson.body(rule), RuleJson::testResult)

    /** Pauses / resumes a rule. */
    suspend fun setRuleActive(id: Int, active: Boolean): FamilieResult<Unit> = patch(path("rules", id), RuleJson.activeBody(active), parse = unit)

    suspend fun deleteRule(id: Int): FamilieResult<Unit> = delete(path("rules", id))

    /** A dry run: would the rule fire now, and why (not)? */
    suspend fun testRule(id: Int): FamilieResult<RuleTestResult> = post(path("rules", id, "test"), parse = RuleJson::testResult)

    /** „Erledigt“ for one's own open reminder (Familie 1.11). */
    suspend fun ruleOpenDone(ruleId: Int, key: String): FamilieResult<Unit> = post(path("rules", "open", ruleId, "done"), RuleJson.openBody(key), unit)

    /** „In 1 Std.“ for one's own open reminder (at most three notifications a day: `daily_limit`). */
    suspend fun ruleOpenSnooze(ruleId: Int, key: String): FamilieResult<Unit> = post(path("rules", "open", ruleId, "snooze"), RuleJson.openBody(key), unit)

    /** The holiday mode (parents, Familie 1.11): [until] the last day, null = until switched off. */
    suspend fun setVacation(on: Boolean, until: String?): FamilieResult<RuleVacation> =
        put(path("rules", "vacation"), RuleJson.vacationBody(on, until)) { o -> o.obj("vacation")?.let(RuleJson::vacation) }

    /** A new place for rules (parents, `POST presence/places`); radius in metres. */
    suspend fun createRulePlace(name: String, lat: Double, lon: Double, radius: Int): FamilieResult<RuleOptionPlace> =
        post(path("presence", "places"), RuleJson.placeBody(name, lat, lon, radius)) { o -> o.obj("place")?.let(RuleJson::place) }

    private fun <T> FamilieResult<*>.asFailure(): FamilieResult<T> = when (this) {
        is FamilieResult.Failed -> this
        FamilieResult.Offline -> FamilieResult.Offline
        FamilieResult.NotConfigured -> FamilieResult.NotConfigured
        is FamilieResult.Ok -> FamilieResult.Failed(CODE_INVALID, 0)
    }
}
