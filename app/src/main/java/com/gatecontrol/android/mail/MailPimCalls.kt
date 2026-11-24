package com.gatecontrol.android.mail

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/*
 * The phone app's own routes of the Mail plugin on top of [MailApi] (core:mail-api): the Microsoft sign-in of an
 * account, calendars, free/busy, sharing, resources and contacts. GateControl TV has none of them.
 */

/** Keeps `null` members (a PATCH that clears a field); Retrofit's Gson leaves them out. */
private val NULLS_GSON = GsonBuilder().serializeNulls().create()
private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
private val OVERLAY_ID = Regex("^(familie|birthdays|alltag:[0-9]{1,15})$")

/** The invitation the mail carries (the card above the text), read from the server's answer. */
val MMessage.invite: MInvitation? get() = inviteJson?.let(MailPimJson::invitation)

// ─── Microsoft sign-in ──────────────────────────────────────────

suspend fun MailApi.addMicrosoft(body: JsonObject): MResult<MAccount> = post("accounts/microsoft", body, MailApi.ACCOUNT_TIMEOUT_MS, MailJson::accountAnswer)

suspend fun MailApi.reloginMicrosoft(id: Long, body: JsonObject): MResult<MAccount> = post("accounts/$id/microsoft", body, MailApi.ACCOUNT_TIMEOUT_MS, MailJson::accountAnswer)

// ─── calendars (since Mail 1.2.0) ───────────────────────────────

/** A PATCH whose body keeps its `null` members (clearing a field: a rule, the online link, a birthday). */
private suspend fun <T> MailApi.patchNulls(path: String, body: JsonObject, parse: (JsonObject) -> T?) =
    call(request = {
        pluginPatchRaw(MAIL_PLUGIN, path, NULLS_GSON.toJson(body).toRequestBody(JSON_TYPE), langQuery())
    }, parse = parse)

/** `GET calendars` with the raw answer (kept for offline). */
suspend fun MailApi.calendars(): MResult<Pair<MCalendars, JsonObject>> = get("calendars") { o -> MailPimJson.calendars(o)?.let { it to o } }

suspend fun MailApi.createCalendar(name: String, color: String?): MResult<MCalendar> =
    post("calendars", MailPimJson.calendarBody(name, color), parse = MailPimJson::calendarAnswer)

suspend fun MailApi.patchCalendar(id: Long, body: JsonObject): MResult<MCalendar> = patch("calendars/$id", body, parse = MailPimJson::calendarAnswer)

suspend fun MailApi.deleteCalendar(id: Long): MResult<Unit> = del("calendars/$id", unit)

suspend fun MailApi.patchOverlay(id: String, body: JsonObject): MResult<MOverlay> = patch("overlays/" + id.takeIf { OVERLAY_ID.matches(it) }.orEmpty(), body, parse = MailPimJson::overlayAnswer)

suspend fun MailApi.subscribeCalendar(url: String, name: String?): MResult<MCalendar> =
    post("calendars/subscribe", MailPimJson.subscribeBody(url, name), timeoutMs = 45_000L, parse = MailPimJson::calendarAnswer)

suspend fun MailApi.calendarSettings(body: JsonObject): MResult<MCalPrefs> = put("calendar/settings", body, MailPimJson::prefsAnswer)

/** `GET events` in `[from, to)` (≤ 62 days) of [calendars] (ids and overlay ids; null: all visible). */
suspend fun MailApi.events(from: Long, to: Long, calendars: List<String>? = null): MResult<Pair<MEvents, JsonObject>> =
    get("events", buildMap {
        put("from", from.toString())
        put("to", to.toString())
        calendars?.let { put("calendars", it.joinToString(",")) }
    }) { o -> MailPimJson.events(o)?.let { it to o } }

suspend fun MailApi.event(id: Long, occurrence: String?): MResult<Pair<MEvent, JsonObject>> =
    get("events/$id", occurrence?.let { mapOf("occurrence" to it) }.orEmpty()) { o -> MailPimJson.event(o)?.let { it to o } }

suspend fun MailApi.createEvent(body: JsonObject): MResult<MEventSaved> = post("events", body, timeoutMs = 60_000L, parse = MailPimJson::eventSaved)

/** `PATCH events/<id>`: `409 conflict { current }` when [body]'s etag is old ([MailPimJson.conflictEvent]). */
suspend fun MailApi.updateEvent(id: Long, body: JsonObject): MResult<MEventSaved> = patchNulls("events/$id", body, MailPimJson::eventSaved)

suspend fun MailApi.deleteEvent(id: Long, scope: String, occurrence: String?, notify: Boolean, comment: String?): MResult<Unit> =
    call(request = {
        pluginDelete(MAIL_PLUGIN, "events/$id", buildMap {
            put("scope", scope)
            occurrence?.let { put("occurrence", it) }
            if (notify) put("notify", "1")
            comment?.trim()?.takeIf { it.isNotEmpty() }?.let { put("comment", it.take(1000)) }
        } + langQuery())
    }, parse = unit)

suspend fun MailApi.respondEvent(id: Long, body: JsonObject): MResult<MEvent> = post("events/$id/respond", body, timeoutMs = 60_000L, parse = MailPimJson::eventAnswer)

suspend fun MailApi.invitations(): MResult<List<MInvitation>> = get("invitations", mapOf("state" to "open"), MailPimJson::invitations)

suspend fun MailApi.invitation(id: Long): MResult<MInvitation> = get("invitations/$id", parse = MailPimJson::invitation)

suspend fun MailApi.respondInvitation(id: Long, body: JsonObject): MResult<MInviteAnswer> =
    post("invitations/$id/respond", body, timeoutMs = 60_000L, parse = MailPimJson::inviteAnswer)

suspend fun MailApi.applyInvitation(id: Long): MResult<MInviteAnswer> = post("invitations/$id/apply", JsonObject(), timeoutMs = 60_000L, parse = MailPimJson::inviteAnswer)

suspend fun MailApi.dismissInvitation(id: Long, decline: Boolean): MResult<Unit> = post("invitations/$id/dismiss", MailPimJson.dismissBody(decline), timeoutMs = 60_000L, parse = unit)

// ─── free/busy, sharing, resources (since Mail 1.9.0) ───────────

/** `POST freebusy`: the day's free/busy of the person, [attendees] and [resources] (never titles). */
suspend fun MailApi.freeBusy(from: Long, to: Long, attendees: List<String>, resources: List<Long>): MResult<MFreeBusy> =
    post("freebusy", MailPimJson.freeBusyBody(from, to, attendees, resources), parse = MailPimJson::freeBusy)

/** `POST freebusy/suggest`: up to [limit] slots of [duration] minutes from [from] when everybody known is free. */
suspend fun MailApi.suggest(duration: Int, from: Long?, attendees: List<String>, resources: List<Long>, limit: Int = 4): MResult<MSuggestions> =
    post("freebusy/suggest", MailPimJson.suggestBody(duration, from, attendees, resources, limit), parse = MailPimJson::suggestions)

suspend fun MailApi.shares(calendarId: Long): MResult<MShares> = get("calendars/$calendarId/shares", parse = MailPimJson::shares)

suspend fun MailApi.addShare(calendarId: Long, type: String, principalId: Long, level: String): MResult<MShare> =
    post("calendars/$calendarId/shares", MailPimJson.shareBody(type, principalId, level), parse = MailPimJson::shareAnswer)

suspend fun MailApi.patchShare(calendarId: Long, shareId: Long, level: String): MResult<MShare> =
    patch("calendars/$calendarId/shares/$shareId", MailPimJson.levelBody(level), parse = MailPimJson::shareAnswer)

suspend fun MailApi.deleteShare(calendarId: Long, shareId: Long): MResult<Unit> = del("calendars/$calendarId/shares/$shareId", unit)

suspend fun MailApi.shared(): MResult<MSharedList> = get("shared", parse = MailPimJson::sharedList)

suspend fun MailApi.patchShared(calendarId: Long, visible: Boolean? = null, color: String? = null): MResult<MSharedCalendar> =
    patch("shared/$calendarId", MailPimJson.sharedPatch(visible, color), parse = MailPimJson::sharedAnswer)

/** People (who may use Mail) and groups by name, to share with ([q] ≥ 2 characters). */
suspend fun MailApi.principals(q: String): MResult<List<MPrincipal>> = get("principals", mapOf("q" to q.trim().take(60)), MailPimJson::principals)

/** `GET resources` (with [from]/[to]: each one's taken times in it, ≤ 31 days). */
suspend fun MailApi.resources(from: Long? = null, to: Long? = null): MResult<MResources> =
    get("resources", buildMap {
        if (from != null && to != null) {
            put("from", from.toString())
            put("to", to.toString())
        }
    }, MailPimJson::resources)

suspend fun MailApi.book(resourceId: Long, body: JsonObject): MResult<MEventSaved> = post("resources/$resourceId/book", body, timeoutMs = 60_000L, parse = MailPimJson::eventSaved)

/** `GET bookings?state=open|done|mine`. */
suspend fun MailApi.bookings(state: String): MResult<MBookings> = get("bookings", mapOf("state" to state), MailPimJson::bookings)

suspend fun MailApi.decideBooking(id: Long, approve: Boolean, override: Boolean = false, comment: String? = null): MResult<MBooking> =
    post("bookings/$id/decide", MailPimJson.decideBody(approve, override, comment), timeoutMs = 60_000L, parse = MailPimJson::bookingAnswer)

suspend fun MailApi.cancelBooking(id: Long): MResult<Unit> = del("bookings/$id", unit)

// ─── contacts (since Mail 1.2.0, path `cards`) ──────────────────

suspend fun MailApi.addressbooks(): MResult<Pair<MBooks, JsonObject>> = get("addressbooks") { o -> MailPimJson.books(o)?.let { it to o } }

suspend fun MailApi.patchAddressbook(id: Long, visible: Boolean): MResult<MAddressbook> = patch("addressbooks/$id", MailPimJson.bookPatch(visible), parse = MailPimJson::bookAnswer)

/** `GET cards`: a page of the list ([since]: only what changed, for the offline copy). */
suspend fun MailApi.cards(q: String? = null, book: Long? = null, group: Long? = null, cursor: String? = null, since: Long? = null, limit: Int = 200): MResult<Pair<MCardPage, JsonObject>> =
    get("cards", buildMap {
        q?.trim()?.takeIf { it.isNotEmpty() }?.let { put("q", it.take(100)) }
        put("book", book?.toString() ?: "all")
        group?.let { put("group", it.toString()) }
        cursor?.let { put("cursor", it) }
        since?.let { put("since", it.toString()) }
        put("limit", limit.coerceIn(1, 1000).toString())
    }) { o -> MailPimJson.cardPage(o)?.let { it to o } }

suspend fun MailApi.card(id: Long): MResult<Pair<MCard, JsonObject>> = get("cards/$id") { o -> MailPimJson.fullCard(o)?.let { it to o } }

suspend fun MailApi.createCard(body: JsonObject): MResult<MCard> = post("cards", body, parse = MailPimJson::cardAnswer)

/**
 * `PATCH cards/<id>`: `409 conflict { current }` ([MailPimJson.conflictCard]), `409 read_only`; since Mail 1.4.0
 * with `writeBack` (the linked cards the change went back to).
 */
suspend fun MailApi.patchCard(id: Long, body: JsonObject): MResult<MCardSaved> = patchNulls("cards/$id", body, MailPimJson::cardSaved)

/** `DELETE cards/<id>`; [linkedToo] (Mail 1.4.0, `linked=delete`): the linked cards go at their provider too. */
suspend fun MailApi.deleteCard(id: Long, linkedToo: Boolean = false): MResult<Unit> =
    call(request = { pluginDelete(MAIL_PLUGIN, "cards/$id", (if (linkedToo) mapOf("linked" to "delete") else emptyMap()) + langQuery()) }, parse = unit)

// ─── the GC contact as the master (Mail 1.4.0; an older server answers 404) ───

suspend fun MailApi.mergePreview(ids: List<Long>): MResult<MPreview> = post("cards/merge/preview", MailPimJson.idsBody(ids), parse = MailPimJson::previewAnswer)

suspend fun MailApi.merges(limit: Int = 50): MResult<List<MMerge>> = get("cards/merges", mapOf("limit" to limit.coerceIn(1, 200).toString()), MailPimJson::merges)

suspend fun MailApi.undoMerge(id: Long, keepMaster: Boolean): MResult<MUnmerge> =
    post("cards/merges/$id/undo", MailPimJson.unmergeBody(null, keepMaster), timeoutMs = 60_000L, parse = MailPimJson::unmerge)

/** "Verknüpfung lösen": the linked cards [ids] of the GC contact [id] (null: all). */
suspend fun MailApi.unmerge(id: Long, ids: List<Long>?, keepMaster: Boolean = false): MResult<MUnmerge> =
    post("cards/$id/unmerge", MailPimJson.unmergeBody(ids, keepMaster), timeoutMs = 60_000L, parse = MailPimJson::unmerge)

/** "Übernehmen" ([mode] `replace` | `add`, null: the server's default). */
suspend fun MailApi.acceptConflict(id: Long, conflictId: Long, mode: String?): MResult<MCard> =
    post("cards/$id/conflicts/$conflictId/accept", MailPimJson.acceptBody(mode), parse = MailPimJson::cardAnswer)

/** "Verwerfen": the value stays away until it changes at the provider. */
suspend fun MailApi.dismissConflict(id: Long, conflictId: Long): MResult<MCard> =
    post("cards/$id/conflicts/$conflictId/dismiss", JsonObject(), parse = MailPimJson::cardAnswer)

/** Favourite and main number only (GateControl's own, never to the provider). */
suspend fun MailApi.patchOwn(id: Long, body: JsonObject): MResult<MCardSaved> = patchNulls("cards/$id", body, MailPimJson::cardSaved)

suspend fun MailApi.favorites(): MResult<Pair<List<MFavorite>, JsonObject>> = get("cards/favorites") { o -> MailPimJson.favorites(o)?.let { it to o } }

suspend fun MailApi.putFavorites(ids: List<Long>): MResult<List<MFavorite>> = put("cards/favorites", MailPimJson.idsBody(ids), MailPimJson::favorites)

/** The calendar widget on Start: today and the next [days] − 1 days. */
suspend fun MailApi.upcoming(days: Int = 2, limit: Int = 8): MResult<Pair<MUpcoming, JsonObject>> =
    get("events/upcoming", mapOf("days" to days.coerceIn(1, 7).toString(), "limit" to limit.coerceIn(1, 20).toString())) { o -> MailPimJson.upcoming(o)?.let { it to o } }

suspend fun MailApi.cardPhoto(id: Long): MResult<MPhoto> = get("cards/$id/photo", parse = MailPimJson::photo)

suspend fun MailApi.putCardPhoto(id: Long, mime: String, bytes: ByteArray): MResult<MCard> = put("cards/$id/photo", MailPimJson.photoBody(mime, bytes), MailPimJson::cardAnswer)

suspend fun MailApi.deleteCardPhoto(id: Long): MResult<MCard> = del("cards/$id/photo", MailPimJson::cardAnswer)

suspend fun MailApi.lookup(email: String): MResult<MLookup> = get("cards/lookup", mapOf("email" to email.trim().take(320)), MailPimJson::lookup)

suspend fun MailApi.activity(id: Long): MResult<MActivity> = get("cards/$id/activity", parse = MailPimJson::activity)

suspend fun MailApi.cardDraft(messageId: Long?, email: String): MResult<MCardDraft> =
    get("cards/draft", buildMap {
        messageId?.let { put("messageId", it.toString()) }
        put("email", email.trim().take(320))
    }, MailPimJson::cardDraft)

/** `GET cards/duplicates` ([details]: each card in full and the preview, Mail 1.4.0). */
suspend fun MailApi.duplicates(details: Boolean = false): MResult<List<MDuplicate>> =
    get("cards/duplicates", if (details) mapOf("details" to "1") else emptyMap(), MailPimJson::duplicates)

suspend fun MailApi.merge(body: JsonObject): MResult<MCard> = post("cards/merge", body, timeoutMs = 60_000L, parse = MailPimJson::cardAnswer)

suspend fun MailApi.ignoreDuplicates(ids: List<Long>): MResult<Unit> = post("cards/duplicates/ignore", MailJson.idsBody(ids), parse = unit)

suspend fun MailApi.importVcf(vcf: String, addressbookId: Long?): MResult<MImport> =
    post("cards/import", MailPimJson.importBody(vcf, addressbookId), timeoutMs = 120_000L, parse = MailPimJson::importAnswer)

suspend fun MailApi.exportVcf(book: Long? = null, group: Long? = null): MResult<MExport> =
    get("cards/export", buildMap {
        put("book", book?.toString() ?: "all")
        group?.let { put("group", it.toString()) }
    }, MailPimJson::export)

suspend fun MailApi.createGroup(name: String): MResult<MGroup> = post("groups", MailPimJson.groupBody(name), parse = MailPimJson::groupAnswer)

suspend fun MailApi.renameGroup(id: Long, name: String): MResult<MGroup> = patch("groups/$id", MailPimJson.groupBody(name), parse = MailPimJson::groupAnswer)

suspend fun MailApi.deleteGroup(id: Long): MResult<Unit> = del("groups/$id", unit)

suspend fun MailApi.group(id: Long): MResult<MGroupDetail> = get("groups/$id", parse = MailPimJson::groupDetail)

suspend fun MailApi.groupMembers(id: Long, body: JsonObject): MResult<MGroupDetail> = post("groups/$id/members", body, parse = MailPimJson::groupDetail)
