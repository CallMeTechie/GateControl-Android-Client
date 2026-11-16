package com.gatecontrol.android.profile

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The fields of the own profile (server 1.280+, `profile` in `GET /api/v1/client/me/profile`): names, about, phone
 * numbers, further e-mail addresses, birthday, language and time zone, who sees each, the two consents of the
 * recognition. A server before has no `profile`: the page stays as it was (picture and colour).
 */
data class ProfileDetails(
    val userId: Int,
    val username: String,
    val displayName: String,
    val firstName: String?,
    val lastName: String?,
    val about: String?,
    val birthday: PBirthday?,
    val email: String?,
    val emails: List<PEmail>,
    val language: String?,
    /** IANA name, null = as the server ([serverTimezone]); the same value as the quiet hours. */
    val timezone: String?,
    val serverTimezone: String?,
    /** Field → `self` | `family` | `all` ([ProfileDetailsJson.VIS_FIELDS]). */
    val visibility: Map<String, String>,
    val phones: List<PPhone>,
    val recognition: PRecognition,
    val locked: PLocked,
    /** The person belongs to a GateControl group of the kind Familie: the level „Familie“ is offered. */
    val family: Boolean,
    val features: PFeatures,
    val limits: PLimits,
    val kinds: List<String>,
    /**
     * „Wer sieht dein Bild?“ (`visibility.avatar`, for all of GateControl): `all` | `contacts` | `family` | `self`; null
     * on a server that does not offer it (no `options.avatarVisibility`) — then the row is not shown.
     */
    val avatarVisibility: String? = null,
) {
    fun visibilityOf(field: String): String = visibility[field] ?: ProfileDetailsJson.DEFAULT_VISIBILITY[field] ?: VIS_SELF

    /** The levels a field may get (Familie only with a family, or when it is set already). */
    fun fieldLevels(current: String?): List<String> = listOf(VIS_SELF, VIS_FAMILY, VIS_ALL).filter { it != VIS_FAMILY || family || current == VIS_FAMILY }

    /** The levels a number may get. */
    fun phoneLevels(current: String?): List<String> = listOf(VIS_NONE, VIS_FAMILY, VIS_ALL).filter { it != VIS_FAMILY || family || current == VIS_FAMILY }

    val canAddPhone: Boolean get() = features.phones && phones.size < limits.phones
    val canAddEmail: Boolean get() = emails.size < limits.emails

    companion object {
        const val VIS_NONE = "none"
        const val VIS_SELF = "self"
        const val VIS_FAMILY = "family"
        const val VIS_ALL = "all"
        const val VIS_CONTACTS = "contacts"

        /** The levels of „Wer sieht dein Bild?“, widest first. */
        val AVATAR_LEVELS = listOf(VIS_ALL, VIS_CONTACTS, VIS_FAMILY, VIS_SELF)
    }
}

/** `date` is `YYYY-MM-DD`, or `--MM-DD` without a year. */
data class PBirthday(val month: Int, val day: Int, val year: Int?, val yearHidden: Boolean) {
    val date: String get() = (year?.toString()?.padStart(4, '0') ?: "-") + "-" + month.toString().padStart(2, '0') + "-" + day.toString().padStart(2, '0')
}

/** A further address; [confirmed] after the code from the mail. */
data class PEmail(val id: Long, val address: String, val confirmed: Boolean, val codePending: Boolean)

/**
 * A phone number of the profile, [number] in E.164. Never logged: [toString] leaves the digits out (a crash report or a
 * debug line never carries one).
 */
class PPhone(
    val id: Long,
    val kind: String,
    val number: String,
    val visibility: String,
    val useForRecognition: Boolean,
    /** `whatsapp` | `admin` | `directory` | `unconfirmed`. */
    val state: String,
) {
    val confirmed: Boolean get() = state != STATE_UNCONFIRMED

    override fun toString(): String = "PPhone(id=$id, kind=$kind, state=$state)"
    override fun equals(other: Any?): Boolean = other is PPhone && other.id == id && other.kind == kind && other.number == number &&
        other.visibility == visibility && other.useForRecognition == useForRecognition && other.state == state
    override fun hashCode(): Int = id.hashCode() * 31 + number.hashCode()

    companion object {
        const val STATE_WHATSAPP = "whatsapp"
        const val STATE_ADMIN = "admin"
        const val STATE_DIRECTORY = "directory"
        const val STATE_UNCONFIRMED = "unconfirmed"
    }
}

data class PRecognition(
    val byWhatsapp: Boolean = false,
    val byPhone: Boolean = false,
    val whatsappAnswered: Boolean = false,
    val phoneAnswered: Boolean = false,
    val whatsappLinked: Boolean = false,
    val phonesForRecognition: Int = 0,
)

/** Fields from the directory (AD, Entra): shown, not changed. */
data class PLocked(val displayName: Boolean = false, val email: Boolean = false, val directory: String? = null)

data class PFeatures(val phones: Boolean = true, val emailConfirm: Boolean = true, val recognition: Boolean = true)

data class PLimits(val phones: Int = 5, val emails: Int = 3, val about: Int = 140, val name: Int = 60, val displayName: Int = 100)

/** Parses `profile` strictly where it matters: no user id → none; texts plain and capped; unknown values dropped. */
object ProfileDetailsJson {

    val VIS_FIELDS = listOf("firstName", "lastName", "about", "birthday", "email", "language", "timezone")
    val DEFAULT_VISIBILITY = mapOf(
        "firstName" to "all", "lastName" to "all", "about" to "all", "birthday" to "family",
        "email" to "self", "language" to "self", "timezone" to "self",
    )
    val KINDS = listOf("mobile", "work", "home", "other")
    private val FIELD_LEVELS = setOf("self", "family", "all")
    private val PHONE_LEVELS = setOf("none", "family", "all")
    private val PHONE_STATES = setOf(PPhone.STATE_WHATSAPP, PPhone.STATE_ADMIN, PPhone.STATE_DIRECTORY, PPhone.STATE_UNCONFIRMED)
    private val E164_RE = Regex("^\\+[1-9][0-9]{6,14}$")
    private val TZ_RE = Regex("^[A-Za-z][A-Za-z0-9_+-]*(/[A-Za-z0-9_+-]+){0,2}$")
    private val LANG_RE = Regex("^[a-z]{2,3}(-[A-Za-z]{2,4})?$")
    private val BIRTHDAY_RE = Regex("^(\\d{4}|-)-(\\d{2})-(\\d{2})$")

    /** The cached copy is at most this large (the server's own answer is a few KB). */
    const val MAX_RAW = 64_000

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.arr(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.bool(key: String, default: Boolean = false): Boolean =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: default
    private fun JsonObject.num(key: String): Long? {
        val p = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        return when {
            p.isNumber -> p.asDouble.takeIf { it % 1.0 == 0.0 && it >= 0 && it <= Long.MAX_VALUE }?.toLong()
            p.isString -> p.asString.trim().toLongOrNull()?.takeIf { it >= 0 }
            else -> null
        }
    }

    private fun clean(s: String?, max: Int): String? = s?.filter { !it.isISOControl() }?.trim()?.take(max)?.ifEmpty { null }

    /** `{ ok, user, palette, profile }` → the fields, null when the server has none (older server) or they are unusable. */
    fun fromAnswer(o: JsonObject): ProfileDetails? = o.obj("profile")?.let(::parse)

    fun parse(p: JsonObject): ProfileDetails? {
        val userId = p.num("userId")?.takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: return null
        val limits = p.obj("limits").let { l ->
            PLimits(
                phones = l?.num("phones")?.toInt()?.coerceIn(0, 20) ?: 5,
                emails = l?.num("emails")?.toInt()?.coerceIn(0, 20) ?: 3,
                about = l?.num("about")?.toInt()?.coerceIn(1, 1000) ?: 140,
                name = l?.num("name")?.toInt()?.coerceIn(1, 500) ?: 60,
                displayName = l?.num("displayName")?.toInt()?.coerceIn(1, 500) ?: 100,
            )
        }
        val username = clean(p.str("username"), 100).orEmpty()
        val features = p.obj("features").let { f ->
            PFeatures(phones = f?.bool("phones", true) ?: true, emailConfirm = f?.bool("emailConfirm", true) ?: true, recognition = f?.bool("recognition", true) ?: true)
        }
        val vis = p.obj("visibility")
        return ProfileDetails(
            userId = userId,
            username = username,
            displayName = clean(p.str("displayName"), limits.displayName) ?: username,
            firstName = clean(p.str("firstName"), limits.name),
            lastName = clean(p.str("lastName"), limits.name),
            about = clean(p.str("about"), limits.about),
            birthday = p.obj("birthday")?.let(::birthday),
            email = clean(p.str("email"), 254),
            emails = p.arr("emails")?.mapNotNull { e -> e.takeIf { it.isJsonObject }?.asJsonObject?.let(::email) }.orEmpty().distinctBy { it.id }.take(20),
            language = p.str("language")?.trim()?.takeIf { LANG_RE.matches(it) },
            timezone = p.str("timezone")?.trim()?.takeIf { it.length <= 64 && TZ_RE.matches(it) },
            serverTimezone = p.str("serverTimezone")?.trim()?.takeIf { it.length <= 64 && TZ_RE.matches(it) },
            visibility = VIS_FIELDS.associateWith { f -> vis?.str(f)?.takeIf { it in FIELD_LEVELS } ?: DEFAULT_VISIBILITY.getValue(f) },
            phones = if (!features.phones) emptyList() else p.arr("phones")?.mapNotNull { e -> e.takeIf { it.isJsonObject }?.asJsonObject?.let(::phone) }.orEmpty().distinctBy { it.id }.take(20),
            recognition = p.obj("recognition").let { r ->
                if (r == null) PRecognition() else PRecognition(
                    byWhatsapp = r.bool("byWhatsapp"),
                    byPhone = r.bool("byPhone"),
                    whatsappAnswered = r.bool("whatsappAnswered"),
                    phoneAnswered = r.bool("phoneAnswered"),
                    whatsappLinked = r.bool("whatsappLinked"),
                    phonesForRecognition = r.num("phonesForRecognition")?.toInt()?.coerceIn(0, 100) ?: 0,
                )
            },
            locked = p.obj("locked").let { l -> PLocked(l?.bool("displayName") ?: false, l?.bool("email") ?: false, clean(l?.str("directory"), 100)) },
            family = p.bool("family"),
            features = features,
            limits = limits,
            kinds = p.obj("options")?.arr("kinds")?.mapNotNull { e: JsonElement -> e.takeIf { it.isJsonPrimitive }?.asString }
                ?.filter { it in KINDS }?.distinct()?.ifEmpty { null } ?: KINDS,
            // „Wer sieht dein Bild?“: only on a server that offers the levels
            avatarVisibility = p.obj("options")?.arr("avatarVisibility")?.takeIf { it.size() > 0 }?.let {
                vis?.str("avatar")?.takeIf { v -> v in ProfileDetails.AVATAR_LEVELS } ?: ProfileDetails.VIS_ALL
            },
        )
    }

    /** `{ date, month, day, year, yearHidden }`; the date decides, the parts are checked against it. */
    private fun birthday(b: JsonObject): PBirthday? {
        val m = BIRTHDAY_RE.matchEntire(b.str("date")?.trim() ?: return null) ?: return null
        val year = m.groupValues[1].takeIf { it != "-" }?.toIntOrNull()?.takeIf { it in 1850..2200 }
        if (m.groupValues[1] != "-" && year == null) return null
        val month = m.groupValues[2].toInt()
        val day = m.groupValues[3].toInt()
        if (month !in 1..12 || day !in 1..31) return null
        return PBirthday(month, day, year, yearHidden = b.bool("yearHidden"))
    }

    private fun email(e: JsonObject): PEmail? {
        val id = e.num("id")?.takeIf { it > 0 } ?: return null
        val address = clean(e.str("address"), 254)?.takeIf { '@' in it } ?: return null
        return PEmail(id, address, confirmed = e.str("state") == "confirmed", codePending = e.bool("codePending"))
    }

    private fun phone(o: JsonObject): PPhone? {
        val id = o.num("id")?.takeIf { it > 0 } ?: return null
        val number = o.str("number")?.trim()?.takeIf { E164_RE.matches(it) } ?: return null
        return PPhone(
            id = id,
            kind = o.str("kind")?.takeIf { it in KINDS } ?: "other",
            number = number,
            visibility = o.str("visibility")?.takeIf { it in PHONE_LEVELS } ?: ProfileDetails.VIS_NONE,
            useForRecognition = o.bool("useForRecognition"),
            state = o.str("state")?.takeIf { it in PHONE_STATES } ?: PPhone.STATE_UNCONFIRMED,
        )
    }

    // ─── Cached copy (the encrypted app storage, as the tokens), bound to the login ─────────

    /** The server's `profile` as it came, with the login it belongs to; null when too large. */
    fun encode(raw: JsonObject, owner: String): String? = JsonObject().apply {
        addProperty("v", 1)
        addProperty("owner", owner)
        add("profile", raw)
    }.toString().takeIf { it.length <= MAX_RAW }

    fun decode(raw: String?, owner: String): Pair<JsonObject, ProfileDetails>? = runCatching {
        if (raw.isNullOrBlank() || owner.isEmpty() || raw.length > MAX_RAW) return null
        val o = JsonParser.parseString(raw).asJsonObject
        if (o.str("owner") != owner) return null
        val p = o.obj("profile") ?: return null
        parse(p)?.let { p to it }
    }.getOrNull()

    // ─── Bodies of the changes ─────────────────────────────────────

    /** `{ "birthday": { "date": …, "yearHidden": … } }` or `{ "birthday": null }`. */
    fun birthdayBody(b: PBirthday?): JsonObject = JsonObject().apply {
        if (b == null) add("birthday", com.google.gson.JsonNull.INSTANCE)
        else add("birthday", JsonObject().apply { addProperty("date", b.date); addProperty("yearHidden", b.yearHidden) })
    }

    /** A text field: an empty one is sent as null (= emptied). */
    fun textBody(vararg fields: Pair<String, String?>): JsonObject = JsonObject().apply {
        fields.forEach { (k, v) ->
            val t = v?.trim()?.ifEmpty { null }
            if (t == null) add(k, com.google.gson.JsonNull.INSTANCE) else addProperty(k, t)
        }
    }

    /** `{ "visibility": { field: level, … } }`. */
    fun visibilityBody(level: String, vararg fields: String): JsonObject = JsonObject().apply {
        add("visibility", JsonObject().apply { fields.forEach { addProperty(it, level) } })
    }

    fun merge(vararg parts: JsonObject): JsonObject = JsonObject().apply { parts.forEach { p -> p.entrySet().forEach { (k, v) -> add(k, v) } } }

    fun phoneBody(kind: String?, number: String?, visibility: String?, useForRecognition: Boolean?): JsonObject = JsonObject().apply {
        kind?.let { addProperty("kind", it) }
        number?.let { addProperty("number", it) }
        visibility?.let { addProperty("visibility", it) }
        useForRecognition?.let { addProperty("useForRecognition", it) }
    }

    fun recognitionBody(byWhatsapp: Boolean? = null, byPhone: Boolean? = null): JsonObject = JsonObject().apply {
        add("recognition", JsonObject().apply {
            byWhatsapp?.let { addProperty("byWhatsapp", it) }
            byPhone?.let { addProperty("byPhone", it) }
        })
    }

    private val FILENAME_RE = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
    private val SAFE_NAME_RE = Regex("^[A-Za-z0-9._-]{1,100}\\.json$")

    /** The file name of „Meine Daten“ from `Content-Disposition`, else `gatecontrol-meine-daten-<today>.json`. */
    fun exportName(disposition: String?, today: java.time.LocalDate): String =
        disposition?.let { FILENAME_RE.find(it)?.groupValues?.get(1)?.trim() }?.takeIf { SAFE_NAME_RE.matches(it) }
            ?: "gatecontrol-meine-daten-$today.json"
}
