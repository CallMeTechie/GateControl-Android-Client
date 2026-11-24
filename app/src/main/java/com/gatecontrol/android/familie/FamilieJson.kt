package com.gatecontrol.android.familie

import com.gatecontrol.android.familie.emergency.EmergencyCard
import com.gatecontrol.android.familie.emergency.EmergencyContact
import com.gatecontrol.android.familie.emergency.EmergencyInsurance
import com.gatecontrol.android.familie.emergency.EmergencyMember
import com.gatecontrol.android.familie.emergency.EmergencyMemberDraft
import com.gatecontrol.android.familie.rides.DriverChoice
import com.gatecontrol.android.familie.rides.OverviewRides
import com.gatecontrol.android.familie.rides.Ride
import com.gatecontrol.android.familie.rides.RideDraft
import com.gatecontrol.android.familie.rides.RideDriver
import com.gatecontrol.android.familie.rides.RidePerson
import com.gatecontrol.android.familie.rides.RideRule
import com.gatecontrol.android.familie.presence.PresenceState
import com.gatecontrol.android.profile.AvatarInitials
import com.gatecontrol.android.profile.AvatarPalette
import com.gatecontrol.android.profile.ProfileJson
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * Turns the Familie plugin's JSON (contract B3) into [FamilieModels] and
 * builds the request bodies. Lenient where SQLite leaks through (0/1 for
 * booleans, numbers as text), strict where it matters: an entry without
 * its id or required fields is dropped, texts are plain and capped. The
 * readers, the shopping lists, the dates and Alltag come from
 * [FamilieJsonBase] (:core:familie-api, shared with GateControl TV).
 */
object FamilieJson : FamilieJsonBase() {

    /**
     * A member's colour as `#RRGGBB`: `colorHex` (1.3, the favourite
     * colour), else `color` as hex (1.0–1.2, set on the admin page), else
     * `color` as a palette key (1.3 without colorHex).
     */
    internal fun memberColor(o: JsonObject): String? =
        AvatarPalette.validHex(o.str("colorHex")) ?: color(o.str("color"))
            ?: AvatarPalette.hexOf(AvatarPalette.validKey(o.str("color")))

    /** Face fields of a member object (Familie 1.3); null when the plugin sends none (older plugin). */
    internal fun face(o: JsonObject, name: String?): MemberFace? {
        if (FACE_KEYS.none { o.has(it) }) return null
        return MemberFace(
            avatarUrl = ProfileJson.avatarUrl(o.str("avatarUrl")),
            colorHex = memberColor(o),
            initials = AvatarInitials.pick(o.str("initials"), name),
            presence = PresenceState.fromWire(o.str("presence"))?.takeIf { it != PresenceState.NOT_SHARED }?.wire,
        )
    }

    private val FACE_KEYS = listOf("avatarUrl", "colorHex", "initials", "presence")

    // ─── me ──────────────────────────────────────────────────────────

    fun profile(o: JsonObject): FamilieProfile? {
        val me = o.obj("me") ?: return null
        val userId = me.int("userId") ?: return null
        val role = FamilieRole.fromWire(me.str("role")) ?: return null
        val m = o.obj("modules")
        val modules = FamilieModules(
            shopping = m?.bool("shopping") == true,
            tasks = m?.bool("tasks") == true,
            stars = m?.bool("stars") == true,
            dates = m?.bool("dates") == true,
            locate = m?.bool("locate") == true,
            arrivalSos = m?.bool("arrival_sos") == true,
            places = m?.bool("places") == true,
            rides = m?.bool("rides") == true,
            emergency = m?.bool("emergency") == true,
            wall = m?.bool("wall") == true,
            allowance = m?.bool("allowance") == true,
            wishes = m?.bool("wishes") == true,
            expenses = m?.bool("expenses") == true,
            board = m?.bool("board") == true,
            meals = m?.bool("meals") == true,
            presence = m?.bool("presence") == true,
            rules = m?.bool("rules") == true,
        )
        return FamilieProfile(
            me = FamilieMe(
                userId = userId,
                name = clean(me.str("name"), 60).ifBlank { "#$userId" },
                role = role,
                age = me.int("age")?.takeIf { it in 0..150 },
                locateMode = if (role == FamilieRole.CHILD) LocateMode.fromWire(me.str("locateMode")) else null,
                color = memberColor(me),
                face = face(me, me.str("name")),
            ),
            modules = modules,
            members = o.arr("members")?.objects()?.mapNotNull(::member).orEmpty().distinctBy { it.userId },
            serverTime = stamp(o.str("serverTime")),
        )
    }

    private fun member(o: JsonObject): FamilieMember? {
        val id = o.int("userId") ?: return null
        val role = FamilieRole.fromWire(o.str("role")) ?: return null
        return FamilieMember(
            userId = id,
            name = clean(o.str("name"), 60).ifBlank { "#$id" },
            role = role,
            age = o.int("age")?.takeIf { it in 0..150 },
            color = memberColor(o),
            locateMode = LocateMode.fromWire(o.str("locateMode")),
            locatable = o.bool("locatable"),
            face = face(o, o.str("name")),
        )
    }

    // ─── overview ────────────────────────────────────────────────────

    fun overview(o: JsonObject): FamilieOverview {
        val shopping = o.obj("shopping")
        val tasks = o.obj("tasks")
        val stars = o.obj("stars")
        val dates = o.obj("dates")
        val locate = o.obj("locate")
        return FamilieOverview(
            shoppingLists = shopping?.let { s ->
                s.arr("lists")?.objects()?.mapNotNull { l ->
                    val id = l.str("id")?.takeIf { UUID_RE.matches(it) } ?: return@mapNotNull null
                    OverviewList(id, clean(l.str("name"), 40).ifBlank { "–" }, (l.int("open") ?: 0).coerceAtLeast(0))
                }.orEmpty()
            },
            myTasks = tasks?.let { t -> t.arr("mine")?.objects()?.mapNotNull(::task).orEmpty() },
            openTasks = (tasks?.int("open") ?: 0).coerceAtLeast(0),
            stars = stars?.let { OverviewStars((it.int("week") ?: 0).coerceAtLeast(0), (it.int("goal") ?: 0).coerceAtLeast(0)) },
            nextDates = dates?.let { d -> d.arr("next")?.objects()?.mapNotNull(::event).orEmpty() },
            lastLocates = locate?.let { l ->
                l.arr("last")?.objects()?.mapNotNull { r ->
                    val child = r.int("childId") ?: return@mapNotNull null
                    OverviewLocate(child, clean(r.str("status"), 20), stamp(r.str("at")), r.int("requestId"))
                }.orEmpty()
            },
            going = shopping?.arr("going")?.objects()?.mapNotNull { g ->
                val user = g.int("userId") ?: return@mapNotNull null
                OverviewGoing(user, g.str("listId")?.takeIf { UUID_RE.matches(it) }, stamp(g.str("at")))
            }.orEmpty(),
            arrivals = o.arr("arrivals")?.objects()?.mapNotNull { a ->
                val user = a.int("userId") ?: return@mapNotNull null
                OverviewArrival(user, a.str("label")?.let { clean(it, 60) }?.takeIf { it.isNotBlank() }, stamp(a.str("at")))
            },
            rides = o.obj("rides")?.let { r ->
                OverviewRides(
                    next = r.arr("next")?.objects()?.mapNotNull(::ride).orEmpty().take(5),
                    open = (r.int("open") ?: 0).coerceAtLeast(0),
                )
            },
            allowance = FamilieJson12.overviewAllowance(o.obj("allowance")),
            meals = FamilieJson12.overviewMeals(o.obj("meals")),
            board = FamilieJson12.overviewBoard(o.obj("board")),
            presence = FamilieJson12.overviewPresence(o.el("presence")),
            presenceShare = o.obj("presence")?.bool("share"),
            wishes = FamilieJson12.overviewWishes(o.obj("wishes")),
            expenses = FamilieJson12.overviewExpenses(o.obj("expenses")),
            tasksHome = home(tasks?.obj("home")),
        )
    }

    // ─── tasks ───────────────────────────────────────────────────────

    fun task(o: JsonObject): FamilieTask? {
        val id = o.int("id") ?: return null
        val title = clean(o.str("title"), 120)
        if (title.isBlank()) return null
        val last = o.obj("lastDone")
        return FamilieTask(
            id = id,
            title = title,
            mode = o.str("mode")?.takeIf { it in FamilieTask.MODES } ?: FamilieTask.MODE_FIXED,
            assignees = o.ints("assignees").distinct(),
            current = o.int("current"),
            repeat = o.str("repeat")?.takeIf { it in FamilieTask.REPEATS } ?: "none",
            weekdays = o.ints("weekdays").filter { it in 1..7 }.distinct().sorted(),
            dueTime = time(o.str("dueTime")),
            dueDate = date(o.str("dueDate")),
            stars = (o.int("stars") ?: 0).coerceIn(0, 10),
            reminder = o.bool("reminder") == true,
            doneToday = o.bool("doneToday") == true,
            lastDone = last?.let { TaskLastDone(it.int("by"), stamp(it.str("at"))) },
        )
    }

    fun tasks(o: JsonObject): TasksPayload {
        val stars = o.obj("stars")
        return TasksPayload(
            tasks = o.arr("tasks")?.objects()?.mapNotNull(::task).orEmpty(),
            stars = stars?.let { StarsBoard(userMap(it.obj("week")), userMap(it.obj("goal"))) },
            home = home(o.obj("home")),
        )
    }

    /** `home: { home, since }` (1.21 shopping, 1.24 tasks and places): `notizen` | `familie`; anything else: not said. */
    private fun home(o: JsonObject?): String? = o?.str("home")?.takeIf { it == "notizen" || it == "familie" }

    private fun userMap(o: JsonObject?): Map<Int, Int> =
        o?.entrySet()?.mapNotNull { (k, _) ->
            val id = k.toIntOrNull() ?: return@mapNotNull null
            val n = o.int(k) ?: return@mapNotNull null
            id to n.coerceAtLeast(0)
        }?.toMap().orEmpty()

    fun taskBody(d: TaskDraft): JsonObject = JsonObject().apply {
        addProperty("title", d.title.trim().take(120))
        addProperty("mode", d.mode)
        add("assignees", JsonArray().apply { d.assignees.forEach { add(it) } })
        addProperty("repeat", d.repeat)
        add("weekdays", JsonArray().apply { d.weekdays.forEach { add(it) } })
        add("dueTime", d.dueTime?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        add("dueDate", d.dueDate?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        addProperty("stars", d.stars.coerceIn(0, 10))
        addProperty("reminder", d.reminder)
    }

    // ─── locate ──────────────────────────────────────────────────────

    fun locateRequest(o: JsonObject): LocateRequest? {
        val id = o.int("id") ?: return null
        val child = o.int("childId") ?: return null
        val lat = o.dbl("lat")?.takeIf { it in -90.0..90.0 }
        val lon = o.dbl("lon")?.takeIf { it in -180.0..180.0 }
        return LocateRequest(
            id = id,
            childId = child,
            requestedBy = o.int("requestedBy"),
            mode = LocateMode.fromWire(o.str("mode")),
            status = o.str("status")?.takeIf { it.length <= 20 } ?: LocateRequest.PENDING,
            createdAt = stamp(o.str("createdAt")),
            expiresAt = stamp(o.str("expiresAt")),
            lat = if (lon != null) lat else null,
            lon = if (lat != null) lon else null,
            accuracy = o.dbl("accuracy")?.takeIf { it >= 0 },
            at = stamp(o.str("at")),
            battery = o.int("battery")?.takeIf { it in 0..100 },
            code = (o.str("code") ?: o.str("reason"))?.let { clean(it, 40) }?.takeIf { it.isNotBlank() },
            requestedByName = o.str("requestedByName")?.let { clean(it, 60) }?.takeIf { it.isNotBlank() },
            childName = o.str("childName")?.let { clean(it, 60) }?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * The request in an answer of `POST locate/<child>` or `GET
     * locate/requests/<id>`: `request` object, else the top level.
     */
    fun requestIn(o: JsonObject): LocateRequest? = o.obj("request")?.let(::locateRequest) ?: locateRequest(o)

    fun locateRequests(o: JsonObject): List<LocateRequest> =
        o.arr("requests")?.objects()?.mapNotNull(::locateRequest).orEmpty()

    fun answerBody(lat: Double, lon: Double, accuracy: Double, at: String, battery: Int?): JsonObject = JsonObject().apply {
        addProperty("lat", lat)
        addProperty("lon", lon)
        addProperty("accuracy", accuracy)
        addProperty("at", at)
        battery?.takeIf { it in 0..100 }?.let { addProperty("battery", it) }
    }

    fun positionBody(lat: Double?, lon: Double?, accuracy: Double?, extra: Map<String, String> = emptyMap()): JsonObject = JsonObject().apply {
        extra.forEach { (k, v) -> addProperty(k, v) }
        if (lat != null && lon != null) {
            addProperty("lat", lat)
            addProperty("lon", lon)
            accuracy?.let { addProperty("accuracy", it) }
        }
    }

    /** `POST sos` → `sosId` (or `id`). */
    fun sosId(o: JsonObject): Int? = o.int("sosId") ?: o.obj("sos")?.int("id") ?: o.int("id")

    // ─── places ──────────────────────────────────────────────────────

    fun place(o: JsonObject): FamiliePlace? {
        val id = o.int("id") ?: return null
        val lat = o.dbl("lat")?.takeIf { it in -90.0..90.0 } ?: return null
        val lon = o.dbl("lon")?.takeIf { it in -180.0..180.0 } ?: return null
        val owner = o.int("ownerId") ?: return null
        val title = clean(o.str("title"), 80)
        if (title.isBlank()) return null
        val action = o.str("action")?.takeIf { it in listOf(FamiliePlace.ACTION_NOTE, FamiliePlace.ACTION_LIST, FamiliePlace.ACTION_TELL) }
            ?: FamiliePlace.ACTION_NOTE
        return FamiliePlace(
            id = id,
            ownerId = owner,
            targetUserId = o.int("targetUserId") ?: owner,
            title = title,
            note = clean(o.str("note"), 300),
            lat = lat,
            lon = lon,
            radius = (o.int("radius") ?: 150).coerceIn(FamiliePlace.MIN_RADIUS, FamiliePlace.MAX_RADIUS),
            trigger = if (o.str("trigger") == FamiliePlace.TRIGGER_EXIT) FamiliePlace.TRIGGER_EXIT else FamiliePlace.TRIGGER_ENTER,
            action = action,
            listId = o.str("listId")?.takeIf { UUID_RE.matches(it) },
            tellUserIds = o.ints("tellUserIds").distinct(),
            repeat = if (o.str("repeat") == "once") "once" else "always",
            active = o.bool("active") != false,
            consent = o.str("consent")?.takeIf {
                it in listOf(FamiliePlace.CONSENT_ACCEPTED, FamiliePlace.CONSENT_PENDING, FamiliePlace.CONSENT_DECLINED)
            } ?: FamiliePlace.CONSENT_ACCEPTED,
            ownerName = o.str("ownerName")?.let { clean(it, 60) }?.takeIf { it.isNotBlank() },
            targetName = o.str("targetName")?.let { clean(it, 60) }?.takeIf { it.isNotBlank() },
            home = o.str("home")?.takeIf { it == "notizen" || it == "familie" },
            moved = o.bool("moved") == true,
            ref = o.str("ref")?.takeIf { com.gatecontrol.android.places.MyPlaces.idOf(it) != null },
            missing = o.bool("missing") == true,
            refKnown = o.has("ref"),
        )
    }

    fun places(o: JsonObject): List<FamiliePlace> = o.arr("places")?.objects()?.mapNotNull(::place).orEmpty()

    fun placeBody(d: PlaceDraft): JsonObject = JsonObject().apply {
        addProperty("title", d.title.trim().take(80))
        addProperty("note", d.note.trim().take(300))
        addProperty("lat", d.lat)
        addProperty("lon", d.lon)
        addProperty("radius", d.radius.coerceIn(FamiliePlace.MIN_RADIUS, FamiliePlace.MAX_RADIUS))
        addProperty("trigger", d.trigger)
        addProperty("action", d.action)
        add("listId", d.listId?.takeIf { d.action == FamiliePlace.ACTION_LIST }?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        add("tellUserIds", JsonArray().apply { if (d.action == FamiliePlace.ACTION_TELL) d.tellUserIds.forEach { add(it) } })
        addProperty("repeat", d.repeat)
        addProperty("active", d.active)
        d.targetUserId?.let { addProperty("targetUserId", it) }
        // Familie 1.30: the profile place (its point and radius win on the server; the copy above is the fallback)
        when {
            d.ref != null -> addProperty("ref", d.ref)
            d.unlink -> add("ref", JsonNull.INSTANCE)
        }
    }

    /** The same body without the reference (an older plugin refused `ref`): the reminder keeps the copy of the point. */
    fun withoutRef(d: PlaceDraft): PlaceDraft = d.copy(ref = null, unlink = false)

    // ─── rides (1.1) ─────────────────────────────────────────────────

    /** `"12"` or `"<seriesId>:<YYYY-MM-DD>"`. */
    val RIDE_ID_RE = Regex("^[1-9]\\d{0,9}(:\\d{4}-\\d{2}-\\d{2})?$")

    private fun person(o: JsonObject?): RidePerson? {
        val id = o?.int("userId") ?: return null
        return RidePerson(id, clean(o.str("name"), 60).ifBlank { "#$id" })
    }

    private fun driver(o: JsonObject?): RideDriver? {
        if (o == null) return null
        o.int("userId")?.let { return RideDriver.Member(it, clean(o.str("name"), 60).ifBlank { "#$it" }) }
        return clean(o.str("label"), 60).takeIf { it.isNotBlank() }?.let { RideDriver.Label(it) }
    }

    fun ride(o: JsonObject): Ride? {
        val id = o.str("id")?.takeIf { RIDE_ID_RE.matches(it) } ?: return null
        val date = date(o.str("date")) ?: return null
        val time = time(o.str("time")) ?: return null
        val title = clean(o.str("title"), 80)
        if (title.isBlank()) return null
        val lat = o.dbl("lat")?.takeIf { it in -90.0..90.0 }
        val lon = o.dbl("lon")?.takeIf { it in -180.0..180.0 }
        return Ride(
            id = id,
            seriesId = o.int("seriesId")?.takeIf { it > 0 },
            date = date,
            time = time,
            title = title,
            place = clean(o.str("place"), 80),
            lat = if (lon != null) lat else null,
            lon = if (lat != null) lon else null,
            childIds = o.ints("childIds").distinct(),
            status = o.str("status")?.takeIf { it in Ride.STATUSES } ?: Ride.STATUS_OPEN,
            driver = driver(o.obj("driver")),
            requestedBy = person(o.obj("requestedBy")),
            declined = o.arr("declined")?.objects()?.mapNotNull(::person).orEmpty().distinctBy { it.userId },
            askedAt = stamp(o.str("askedAt")),
            remindMinutes = (o.int("remindMinutes") ?: Ride.DEFAULT_REMIND).coerceIn(0, Ride.MAX_REMIND),
            note = clean(o.str("note"), 200),
            rule = o.obj("repeat")?.let { rep ->
                val days = rep.ints("weekdays").filter { it in 1..7 }.distinct().sorted()
                if (days.isEmpty()) null else RideRule(days, date(rep.str("startDate")), date(rep.str("endDate")))
            },
            startAt = stamp(o.str("startAt")),
        )
    }

    fun rides(o: JsonObject): List<Ride> =
        o.arr("rides")?.objects()?.mapNotNull(::ride).orEmpty().sortedWith(compareBy({ it.date }, { it.time }))

    /** The ride in a create / change answer (`ride`, or the top level), else null. */
    fun rideIn(o: JsonObject): Ride? = o.obj("ride")?.let(::ride) ?: ride(o)

    /** `{userId}` | `{label}` | `{clear:true}` (`POST rides/<id>/driver`). */
    fun driverBody(d: DriverChoice): JsonObject = JsonObject().apply {
        when (d) {
            is DriverChoice.Member -> addProperty("userId", d.userId)
            is DriverChoice.Label -> addProperty("label", d.label.trim().take(60))
            DriverChoice.None -> addProperty("clear", true)
        }
    }

    /**
     * `POST rides` / `PATCH rides/<id>`. [create] adds what only a new ride
     * takes (driver, repeat, ask); a change sets the driver through its own
     * call — except for the whole [series] ([withDriver]: `driver` object or
     * null). A change of the whole series keeps the series' dates.
     */
    fun rideBody(d: RideDraft, create: Boolean = true, series: Boolean = false, withDriver: Boolean = false): JsonObject = JsonObject().apply {
        if (!series || create) addProperty("date", d.date)
        addProperty("time", d.time)
        addProperty("title", d.title.trim().take(80))
        addProperty("place", d.place.trim().take(80))
        if (d.lat != null && d.lon != null) {
            addProperty("lat", d.lat)
            addProperty("lon", d.lon)
        } else if (!create) {
            add("lat", JsonNull.INSTANCE)
            add("lon", JsonNull.INSTANCE)
        }
        add("childIds", JsonArray().apply { d.childIds.distinct().forEach { add(it) } })
        addProperty("remindMinutes", d.remindMinutes.coerceIn(0, Ride.MAX_REMIND))
        addProperty("note", d.note.trim().take(200))
        val repeat = d.repeat
        if (repeat != null && repeat.weekdays.isNotEmpty()) {
            add("repeat", JsonObject().apply {
                add("weekdays", JsonArray().apply { repeat.weekdays.filter { it in 1..7 }.distinct().sorted().forEach { add(it) } })
                add("endDate", repeat.endDate?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
            })
        }
        if (create) {
            if (d.driver !is DriverChoice.None) add("driver", driverBody(d.driver))
            d.ask?.let { addProperty("ask", it) }
        } else if (withDriver) {
            add("driver", if (d.driver is DriverChoice.None) JsonNull.INSTANCE else driverBody(d.driver))
        }
    }

    // ─── emergency card (1.1) ────────────────────────────────────────

    private val BLOOD_RE = Regex("^(A|B|AB|0|O)[+-]$")

    private fun bloodType(value: String?): String? {
        val v = value?.trim()?.uppercase()?.replace(" ", "")?.replace("RH", "") ?: return null
        if (!BLOOD_RE.matches(v)) return null
        return if (v.startsWith("O")) "0" + v.substring(1) else v
    }

    private fun contact(o: JsonObject): EmergencyContact? {
        val phone = o.str("phone")?.trim()?.takeIf { EmergencyContact.PHONE_RE.matches(it) } ?: return null
        val label = clean(o.str("label"), EmergencyContact.MAX_LABEL)
        return EmergencyContact(label.ifBlank { phone }, phone, clean(o.str("note"), EmergencyContact.MAX_NOTE))
    }

    private fun contacts(o: JsonObject, max: Int): List<EmergencyContact> =
        o.arr("contacts")?.objects()?.mapNotNull(::contact).orEmpty().take(max)

    private fun emergencyMember(o: JsonObject): EmergencyMember? {
        val id = o.int("userId") ?: return null
        val insurance = o.obj("insurance")?.let { i ->
            val name = clean(i.str("name"), EmergencyInsurance.MAX_NAME)
            val number = clean(i.str("number"), EmergencyInsurance.MAX_NUMBER)
            if (name.isBlank() && number.isBlank()) null else EmergencyInsurance(name, number)
        }
        val updatedBy = o.el("updatedBy")
        return EmergencyMember(
            userId = id,
            name = clean(o.str("name"), 60).ifBlank { "#$id" },
            birthdate = date(o.str("birthdate")),
            age = o.int("age")?.takeIf { it in 0..150 },
            bloodType = bloodType(o.str("bloodType")),
            weightKg = o.dbl("weightKg")?.takeIf { it in EmergencyMember.MIN_WEIGHT..EmergencyMember.MAX_WEIGHT },
            allergies = clean(o.str("allergies"), EmergencyMember.MAX_TEXT),
            medications = clean(o.str("medications"), EmergencyMember.MAX_TEXT),
            conditions = clean(o.str("conditions"), EmergencyMember.MAX_TEXT),
            notes = clean(o.str("notes"), EmergencyMember.MAX_NOTES),
            insurance = insurance,
            contacts = contacts(o, EmergencyMember.MAX_CONTACTS),
            updatedAt = stamp(o.str("updatedAt")),
            // A user id, or { userId, name } — both are accepted.
            updatedBy = if (updatedBy?.isJsonObject == true) updatedBy.asJsonObject.int("userId") else o.int("updatedBy"),
            updatedByName = (if (updatedBy?.isJsonObject == true) updatedBy.asJsonObject.str("name") else o.str("updatedByName"))
                ?.let { clean(it, 60) }?.takeIf { it.isNotBlank() },
        )
    }

    fun emergency(o: JsonObject): EmergencyCard = EmergencyCard(
        members = o.arr("members")?.objects()?.mapNotNull(::emergencyMember).orEmpty().distinctBy { it.userId },
        contacts = contacts(o, EmergencyCard.MAX_FAMILY_CONTACTS),
        updatedAt = stamp(o.str("updatedAt")),
    )

    private fun contactsArray(list: List<EmergencyContact>, max: Int): JsonArray = JsonArray().apply {
        list.mapNotNull { c ->
            val phone = EmergencyContact.cleanPhone(c.phone).takeIf { p -> p.any { it.isDigit() } } ?: return@mapNotNull null
            c.copy(label = c.label.trim().take(EmergencyContact.MAX_LABEL), phone = phone, note = c.note.trim().take(EmergencyContact.MAX_NOTE))
        }.take(max).forEach { c ->
            add(JsonObject().apply {
                addProperty("label", c.label)
                addProperty("phone", c.phone)
                addProperty("note", c.note)
            })
        }
    }

    /** `PUT emergency/members/<userId>` (the name and birthdate come from the member list). */
    fun emergencyMemberBody(d: EmergencyMemberDraft): JsonObject = JsonObject().apply {
        add("bloodType", bloodType(d.bloodType)?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        add("weightKg", EmergencyMember.weight(d.weightKg)?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        addProperty("allergies", d.allergies.trim().take(EmergencyMember.MAX_TEXT))
        addProperty("medications", d.medications.trim().take(EmergencyMember.MAX_TEXT))
        addProperty("conditions", d.conditions.trim().take(EmergencyMember.MAX_TEXT))
        addProperty("notes", d.notes.trim().take(EmergencyMember.MAX_NOTES))
        val ins = d.insurance?.let { EmergencyInsurance(it.name.trim().take(EmergencyInsurance.MAX_NAME), it.number.trim().take(EmergencyInsurance.MAX_NUMBER)) }
            ?.takeIf { it.name.isNotBlank() || it.number.isNotBlank() }
        add(
            "insurance",
            ins?.let {
                JsonObject().apply {
                    addProperty("name", it.name)
                    addProperty("number", it.number)
                }
            } ?: JsonNull.INSTANCE,
        )
        add("contacts", contactsArray(d.contacts, EmergencyMember.MAX_CONTACTS))
    }

    /** `PUT emergency/contacts`. */
    fun emergencyContactsBody(list: List<EmergencyContact>): JsonObject = JsonObject().apply {
        add("contacts", contactsArray(list, EmergencyCard.MAX_FAMILY_CONTACTS))
    }
}