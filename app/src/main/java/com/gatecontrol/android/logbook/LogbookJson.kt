package com.gatecontrol.android.logbook

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.Base64

/**
 * Parses the answers of the vehicles plugin's logbook API (app/logbook…).
 * Everything is untrusted: ids must be positive, enums known, numbers
 * finite, coordinates on the globe, texts plain and capped; a malformed
 * trip or vehicle is dropped, a malformed answer is null. Pure (Gson tree
 * only, no reflection — safe under R8), unit-tested.
 */
object LogbookJson {

    /** An export is a few hundred KB; anything far bigger is refused. */
    const val MAX_EXPORT_BYTES = 8 * 1024 * 1024
    /** The plugin sends ≤ 150 points a route; more are cut. */
    const val MAX_ROUTE_POINTS = 400
    private const val MAX_TRIPS = 2000

    val MONTH_RE = Regex("^[0-9]{4}-(0[1-9]|1[0-2])$")
    private val DATE_RE = Regex("^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$")
    private val HHMM_RE = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
    private val CODE_RE = Regex("^[A-Za-z][A-Za-z0-9_]{0,60}$")
    private val TIME_RE = Regex("^[0-9TZ:+.-]{10,40}$")
    private val CONTROL_RE = Regex("[\\u0000-\\u001F\\u007F]")
    private val FILE_NAME_RE = Regex("[^A-Za-z0-9._-]")
    private val EXPORT_TYPES = mapOf("application/pdf" to "pdf", "text/csv" to "csv")

    // ─── small helpers ──────────────────────────────────────────────

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.prim(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive

    private fun JsonObject.str(key: String): String? = prim(key)?.takeIf { it.isString }?.asString

    private fun JsonObject.arr(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonElement.objOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject

    /** Positive integer id (number or digit string). */
    private fun JsonObject.id(key: String): Long? {
        val p = prim(key) ?: return null
        val n = when {
            p.isNumber -> p.asBigDecimal.takeIf { it.signum() > 0 && it.stripTrailingZeros().scale() <= 0 && it.precision() <= 15 }?.toLong()
            p.isString -> p.asString.takeIf { it.length in 1..15 && it.all(Char::isDigit) }?.toLong()
            else -> null
        }
        return n?.takeIf { it > 0 }
    }

    /** A finite number ≥ 0 (km, counts), also as a numeric string. */
    private fun JsonObject.amount(key: String): Double? {
        val p = prim(key) ?: return null
        val d = when {
            p.isNumber -> p.asDouble
            p.isString -> p.asString.trim().toDoubleOrNull()
            else -> null
        }
        return d?.takeIf { it.isFinite() && it >= 0 && it < 1e9 }
    }

    private fun JsonObject.flag(key: String): Boolean? {
        val p = prim(key) ?: return null
        return when {
            p.isBoolean -> p.asBoolean
            p.isNumber -> p.asDouble != 0.0
            else -> null
        }
    }

    /** Plain single-line text, capped; blank → null. */
    fun text(value: String?, max: Int = 120): String? {
        if (value == null) return null
        return CONTROL_RE.replace(value, " ").trim().take(max).takeIf { it.isNotBlank() }
    }

    private fun time(value: String?): String? = value?.trim()?.takeIf { TIME_RE.matches(it) }

    private fun coordinate(lat: Double, lon: Double): LogbookPoint? =
        if (lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) LogbookPoint(lat, lon) else null

    // ─── errors ─────────────────────────────────────────────────────

    /** `{ok:false, code}` → code (also `error` when it is a code). */
    fun errorCode(body: JsonObject?): String? =
        body?.str("code")?.takeIf { CODE_RE.matches(it) } ?: body?.str("error")?.takeIf { CODE_RE.matches(it) }

    /** Human text of an error (`message`, or `error` when it is not a code). */
    fun errorText(body: JsonObject?): String? =
        text(body?.str("message"), 200) ?: body?.str("error")?.takeIf { !CODE_RE.matches(it) }?.let { text(it, 200) }

    // ─── logbook ────────────────────────────────────────────────────

    /** `{lat, lon}` → point, null when missing or off the globe. */
    fun point(o: JsonObject?): LogbookPoint? {
        if (o == null) return null
        val lat = o.prim("lat")?.takeIf { it.isNumber }?.asDouble ?: return null
        val lon = (o.prim("lon") ?: o.prim("lng"))?.takeIf { it.isNumber }?.asDouble ?: return null
        return coordinate(lat, lon)
    }

    /** `{kind, km, points: [[lat, lon], …]}` → route; null with fewer than 2 usable points. */
    fun route(o: JsonObject?): LogbookRoute? {
        if (o == null) return null
        val points = o.arr("points")?.asSequence()?.mapNotNull { e ->
            val pair = e.takeIf { it.isJsonArray }?.asJsonArray?.takeIf { it.size() >= 2 } ?: return@mapNotNull null
            val lat = pair[0].takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble ?: return@mapNotNull null
            val lon = pair[1].takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble ?: return@mapNotNull null
            coordinate(lat, lon)
        }?.take(MAX_ROUTE_POINTS)?.toList().orEmpty()
        if (points.size < 2) return null
        // An unknown kind is drawn like the uncertain one: dashed.
        val kind = if (o.str("kind") == LogbookRoute.KIND_ROAD) LogbookRoute.KIND_ROAD else LogbookRoute.KIND_LINE
        return LogbookRoute(kind, o.amount("km"), points)
    }

    fun driver(o: JsonObject?): LogbookDriver? {
        if (o == null) return null
        val name = text(o.str("displayName"), 60) ?: text(o.str("username"), 60) ?: return null
        return LogbookDriver(o.id("id"), name)
    }

    fun trip(o: JsonObject): LogbookTrip? {
        val id = o.id("id") ?: return null
        val date = o.str("date")?.takeIf { DATE_RE.matches(it) } ?: return null
        return LogbookTrip(
            id = id,
            date = date,
            dateLabel = text(o.str("dateLabel"), 40),
            startTime = o.str("startTime")?.takeIf { HHMM_RE.matches(it) },
            endTime = o.str("endTime")?.takeIf { HHMM_RE.matches(it) },
            startedAt = time(o.str("startedAt")),
            endedAt = time(o.str("endedAt")),
            startKm = o.amount("startKm"),
            endKm = o.amount("endKm"),
            distanceKm = o.amount("distanceKm"),
            startAddress = text(o.str("startAddress"), 160),
            endAddress = text(o.str("endAddress"), 160),
            start = point(o.obj("start")),
            end = point(o.obj("end")),
            purpose = LogbookPurpose.parse(o.str("purpose")),
            note = text(o.str("note"), 200),
            source = o.str("source")?.takeIf { it == "auto" || it == "manual" },
            edited = o.flag("edited") == true,
            driver = driver(o.obj("driver")),
            route = route(o.obj("route")),
        )
    }

    fun vehicle(o: JsonObject): LogbookVehicle? {
        val id = o.id("id") ?: return null
        return LogbookVehicle(id, text(o.str("name"), 60) ?: "#$id", text(o.str("model"), 60))
    }

    fun summary(o: JsonObject?): LogbookSummary {
        if (o == null) return LogbookSummary.EMPTY
        return LogbookSummary(
            total = o.amount("total") ?: 0.0,
            private = o.amount("private") ?: 0.0,
            business = o.amount("business") ?: 0.0,
            commute = o.amount("commute") ?: 0.0,
            open = o.amount("open") ?: 0.0,
            openCount = o.amount("openCount")?.toInt() ?: 0,
            count = o.amount("count")?.toInt() ?: 0,
        )
    }

    /** `GET app/logbook` → one month; null without a valid `month`. */
    fun month(o: JsonObject): LogbookMonth? {
        val month = o.str("month")?.takeIf { MONTH_RE.matches(it) } ?: return null
        val vehicles = o.arr("vehicles")?.mapNotNull { it.objOrNull()?.let(::vehicle) }?.distinctBy { it.id }.orEmpty()
        val vehicleId = o.id("vehicleId")?.takeIf { id -> vehicles.any { it.id == id } }
        return LogbookMonth(
            month = month,
            currentMonth = o.str("currentMonth")?.takeIf { MONTH_RE.matches(it) }?.let { maxOf(it, month) } ?: month,
            vehicles = vehicles,
            vehicleId = vehicleId,
            canEdit = o.flag("canEdit") == true,
            summary = summary(o.obj("summary")),
            moving = o.flag("moving") == true,
            trips = o.arr("trips")?.asSequence()?.mapNotNull { it.objOrNull()?.let(::trip) }?.take(MAX_TRIPS)?.toList()
                ?.distinctBy { it.id }.orEmpty(),
        )
    }

    /** `PUT app/logbook/trips/<id>` → the trip as saved. */
    fun savedTrip(o: JsonObject): LogbookTrip? = o.obj("trip")?.let(::trip)

    /** Body of the PUT: only the fields given; texts trimmed and capped. */
    fun changeBody(change: LogbookTripChange): JsonObject = JsonObject().apply {
        change.purpose?.let { addProperty("purpose", LogbookPurpose.parse(it) ?: "") }
        change.note?.let { addProperty("note", it.replace(CONTROL_RE, " ").trim().take(200)) }
        change.startAddress?.let { addProperty("startAddress", it.replace(CONTROL_RE, " ").trim().take(160)) }
        change.endAddress?.let { addProperty("endAddress", it.replace(CONTROL_RE, " ").trim().take(160)) }
    }

    // ─── export ─────────────────────────────────────────────────────

    /** `{file: {name, type, encoding: "base64", data}}` → file; null when not a PDF/CSV, too big or not base64. */
    fun export(o: JsonObject): LogbookExportFile? {
        val f = o.obj("file") ?: return null
        val type = f.str("type")?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it in EXPORT_TYPES } ?: return null
        if (f.str("encoding")?.lowercase().let { it != null && it != "base64" }) return null
        val data = f.str("data") ?: return null
        if (data.length > MAX_EXPORT_BYTES * 4 / 3 + 16) return null
        val bytes = runCatching { Base64.getMimeDecoder().decode(data) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_EXPORT_BYTES) return null
        return LogbookExportFile(fileName(f.str("name"), EXPORT_TYPES.getValue(type)), type, bytes)
    }

    /** A safe file name with the type's extension: `Fahrtenbuch Enyaq 2026-10.pdf` → `Fahrtenbuch_Enyaq_2026-10.pdf`. */
    fun fileName(raw: String?, ext: String): String {
        val base = raw?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.replace(FILE_NAME_RE, "_")?.trim('.', '_')?.take(80)
            ?.takeIf { it.isNotEmpty() } ?: "fahrtenbuch"
        return if (base.lowercase().endsWith(".$ext")) base else "$base.$ext"
    }
}
