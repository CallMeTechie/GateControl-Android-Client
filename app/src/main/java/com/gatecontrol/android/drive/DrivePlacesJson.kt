package com.gatecontrol.android.drive

import com.gatecontrol.android.drive.DriveJson.arr
import com.gatecontrol.android.drive.DriveJson.bool
import com.gatecontrol.android.drive.DriveJson.int
import com.gatecontrol.android.drive.DriveJson.long
import com.gatecontrol.android.drive.DriveJson.obj
import com.gatecontrol.android.drive.DriveJson.str
import com.gatecontrol.android.drive.work.GeoPoint
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.util.Locale

/**
 * Parsers and bodies of places and people in the photos (Drive 1.16, features `photoPlaces` and `photoPeople`): pure,
 * null for an answer without the shape needed, names and lists bounded like [DriveJson].
 */
internal object DrivePlacesJson {

    private val PLACE_KEY = Regex("^[A-Za-z0-9:._-]{1,80}$")
    private val MODEL = Regex("^[a-z0-9._-]{1,40}$")
    const val NAME_MAX = 80
    private const val LIST_MAX = 500

    fun placeKey(v: String?): String? = v?.trim()?.takeIf { PLACE_KEY.matches(it) }

    fun model(v: String?): String? = v?.takeIf { MODEL.matches(it) }

    private fun JsonObject.dbl(key: String): Double? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asDouble }.getOrNull() }?.takeIf { it.isFinite() }

    private fun name(v: String?): String? = v?.trim()?.take(NAME_MAX)?.ifEmpty { null }

    private fun JsonArray.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }.take(LIST_MAX)

    fun lat(v: Double?): Double? = v?.takeIf { it in -90.0..90.0 }

    fun lon(v: Double?): Double? = v?.takeIf { it in -180.0..180.0 }

    // ─── places ─────────────────────────────────────────────────────

    fun place(o: JsonObject?): DPlace? {
        if (o == null) return null
        return DPlace(
            key = placeKey(o.str("key")) ?: return null,
            name = name(o.str("name")) ?: return null,
            district = name(o.str("district")),
            region = name(o.str("region")),
            country = o.str("country")?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.length == 2 },
        )
    }

    /** `geo` of an item: `{ lat, lon, place }`; null without valid coordinates. */
    fun geo(o: JsonObject?): DGeo? {
        if (o == null) return null
        return DGeo(lat(o.dbl("lat")) ?: return null, lon(o.dbl("lon")) ?: return null, place(o.obj("place")))
    }

    /** `GET photos/places` → `{ places: [...] }`. */
    fun places(o: JsonObject): List<DPlaceSummary>? = o.arr("places")?.objects()?.mapNotNull { p ->
        DPlaceSummary(
            key = placeKey(p.str("key")) ?: return@mapNotNull null,
            name = name(p.str("name")) ?: return@mapNotNull null,
            region = name(p.str("region")),
            country = p.str("country")?.uppercase(Locale.ROOT)?.takeIf { it.length == 2 },
            count = p.int("count")?.coerceAtLeast(0) ?: 0,
            lat = lat(p.dbl("lat")) ?: return@mapNotNull null,
            lon = lon(p.dbl("lon")) ?: return@mapNotNull null,
            from = p.str("from")?.take(40),
            to = p.str("to")?.take(40),
            cover = DriveJson.node(p.obj("cover")),
        )
    }

    /** `GET photos/map` → `{ clusters: [...] }` (at most 200 are taken). */
    fun clusters(o: JsonObject): List<DCluster>? = o.arr("clusters")?.objects()?.take(200)?.mapNotNull { c ->
        DCluster(
            lat = lat(c.dbl("lat")) ?: return@mapNotNull null,
            lon = lon(c.dbl("lon")) ?: return@mapNotNull null,
            count = c.int("count")?.takeIf { it > 0 } ?: return@mapNotNull null,
            key = placeKey(c.str("key")),
            cover = DriveJson.node(c.obj("cover")),
        )
    }

    fun districts(o: JsonObject): List<DDistrict> = o.arr("districts")?.objects()?.mapNotNull { d ->
        DDistrict(name(d.str("name")) ?: return@mapNotNull null, d.int("count")?.coerceAtLeast(0) ?: 0)
    }.orEmpty()

    /** `search` of `GET photos?q=`: the people and places recognised in the words. */
    fun marks(o: JsonObject?): DSearchMarks {
        if (o == null) return DSearchMarks()
        return DSearchMarks(
            people = o.arr("people")?.objects()?.mapNotNull { p -> (p.long("id") ?: return@mapNotNull null) to (name(p.str("name")) ?: return@mapNotNull null) }.orEmpty(),
            places = o.arr("places")?.objects()?.mapNotNull { p -> (placeKey(p.str("key")) ?: return@mapNotNull null) to (name(p.str("name")) ?: return@mapNotNull null) }.orEmpty(),
        )
    }

    /** `{ lat, lon }` of the commit and of `PUT nodes/<id>/geo`; null without a valid point. */
    fun geoBody(at: GeoPoint?): JsonObject? {
        if (at == null) return null
        val lat = lat(at.lat) ?: return null
        val lon = lon(at.lon) ?: return null
        if (lat == 0.0 && lon == 0.0) return null
        return JsonObject().apply {
            addProperty("lat", lat)
            addProperty("lon", lon)
        }
    }

    /** `PUT nodes/<id>/geo { none: true }`: looked at, there is no position. */
    fun noGeoBody() = JsonObject().apply { addProperty("none", true) }

    /** A box as Drive takes it: `w,s,e,n` with at most 6 decimals. */
    fun bbox(b: DBbox): String? {
        val w = lon(b.west) ?: return null
        val e = lon(b.east) ?: return null
        val s = lat(b.south) ?: return null
        val n = lat(b.north) ?: return null
        if (s > n) return null
        return listOf(w, s, e, n).joinToString(",") { String.format(Locale.ROOT, "%.6f", it).trimEnd('0').trimEnd('.') }
    }

    /** The query of [DPhotoFilter]; null when a value is invalid. */
    fun filterQuery(f: DPhotoFilter): Map<String, String>? = buildMap {
        if (f.place != null) put("place", placeKey(f.place) ?: return null)
        if (f.bbox != null) put("bbox", bbox(f.bbox) ?: return null)
        if (f.people.isNotEmpty()) put("person", f.people.distinct().take(10).joinToString(","))
        if (f.folder != null) put("folder", f.folder.toString())
        if (f.districts && f.place != null) put("districts", "1")
        if (f.withPeople) put("with", "people")
        if (f.unlocated) put("unlocated", "1")
        if (f.unfacedModel != null) {
            put("unfaced", "1")
            put("model", model(f.unfacedModel) ?: return null)
        }
    }

    // ─── people ─────────────────────────────────────────────────────

    /** A box `[x, y, w, h]` (0…1); null when it is not one. */
    fun box(a: JsonArray?): List<Double>? {
        if (a == null || a.size() != 4) return null
        val v = a.mapNotNull { e -> runCatching { e.asDouble }.getOrNull()?.takeIf { it.isFinite() } }
        if (v.size != 4 || v[2] <= 0 || v[3] <= 0) return null
        return v.map { it.coerceIn(0.0, 1.0) }
    }

    fun faceRef(o: JsonObject?): DFaceRef? {
        if (o == null) return null
        return DFaceRef(
            faceId = o.long("faceId"),
            nodeId = o.long("nodeId"),
            box = box(o.arr("box")) ?: return null,
            item = DriveJson.node(o.obj("item")),
            score = o.dbl("score"),
        )
    }

    private fun contact(o: JsonObject?): DPersonContact? {
        if (o == null) return null
        val kind = o.str("kind")?.takeIf { it == CONTACT_FAMILY || it == CONTACT_MAIL } ?: return null
        val id = o.get("id")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }?.take(80) ?: return null
        return DPersonContact(kind, id, name(o.str("name")))
    }

    fun person(o: JsonObject?): DPerson? {
        if (o == null) return null
        return DPerson(
            id = o.long("id") ?: return null,
            name = name(o.str("name")),
            count = o.int("count")?.coerceAtLeast(0) ?: 0,
            hidden = o.bool("hidden") == true,
            cover = faceRef(o.obj("cover")),
            contact = contact(o.obj("contact")),
        )
    }

    /** `GET people` → `{ people, progress: { done, total } }`. */
    fun people(o: JsonObject): DPeople? {
        val list = o.arr("people") ?: return null
        val progress = o.obj("progress")
        return DPeople(
            people = list.objects().mapNotNull(::person),
            done = progress?.int("done")?.coerceAtLeast(0),
            total = progress?.int("total")?.coerceAtLeast(0),
        )
    }

    private fun counts(a: JsonArray?): List<DPersonCount> = a?.objects()?.mapNotNull { p ->
        DPersonCount(p.long("id") ?: return@mapNotNull null, name(p.str("name")), p.int("count")?.coerceAtLeast(0) ?: 0)
    }.orEmpty()

    /** `GET people/<id>` → the person (at the top or as `person`) with `together`, `places`, `uncertain`, `years`. */
    fun personDetail(o: JsonObject): DPersonDetail? {
        val p = person(o.obj("person")) ?: person(o) ?: return null
        val src = if (o.obj("person") != null && !o.has("together")) o.obj("person")!! else o
        val years = src.obj("years")
        return DPersonDetail(
            person = p,
            together = counts(src.arr("together")),
            places = src.arr("places")?.objects()?.mapNotNull { pl ->
                DPlaceCount(placeKey(pl.str("key")) ?: return@mapNotNull null, name(pl.str("name")) ?: return@mapNotNull null, pl.int("count")?.coerceAtLeast(0) ?: 0)
            }.orEmpty(),
            uncertain = src.int("uncertain")?.coerceAtLeast(0) ?: 0,
            yearFrom = years?.int("from"),
            yearTo = years?.int("to"),
        )
    }

    /** `PATCH people/<id>` → `{ person, mergedInto }`. */
    fun patched(o: JsonObject): DPersonPatched? {
        if (!o.has("person") && !o.has("mergedInto")) return null
        return DPersonPatched(person(o.obj("person")), o.long("mergedInto"))
    }

    fun personAnswer(o: JsonObject): DPerson? = person(o.obj("person"))

    fun similar(o: JsonObject): List<DSimilar>? = o.arr("people")?.objects()?.mapNotNull { p ->
        DSimilar(p.long("id") ?: return@mapNotNull null, name(p.str("name")), p.int("count")?.coerceAtLeast(0) ?: 0, p.dbl("score") ?: 0.0)
    }

    fun uncertain(o: JsonObject): List<DFaceRef>? = o.arr("faces")?.objects()?.mapNotNull(::faceRef)

    /** `GET photos/suggest` → people, places and labels (each at most 5). */
    fun suggest(o: JsonObject): DSuggest? {
        if (!o.has("people") && !o.has("places") && !o.has("labels")) return null
        return DSuggest(
            people = counts(o.arr("people")).filter { !it.name.isNullOrBlank() }.take(SUGGEST_MAX),
            places = o.arr("places")?.objects()?.mapNotNull { p ->
                DSuggestPlace(placeKey(p.str("key")) ?: return@mapNotNull null, name(p.str("name")) ?: return@mapNotNull null, name(p.str("sub")), p.int("count")?.coerceAtLeast(0) ?: 0)
            }.orEmpty().take(SUGGEST_MAX),
            labels = o.arr("labels")?.objects()?.mapNotNull { l ->
                val label = name(l.str("label")) ?: return@mapNotNull null
                DSuggestLabel(label, name(l.str("text")) ?: label, l.int("count")?.coerceAtLeast(0) ?: 0)
            }.orEmpty().take(SUGGEST_MAX),
        )
    }

    /** `{ deleted }` of `DELETE photos/geo` and `PUT photos/settings { people: false }`. */
    fun deleted(o: JsonObject): Int = o.int("deleted")?.coerceAtLeast(0) ?: 0

    const val CONTACT_FAMILY = "family"
    const val CONTACT_MAIL = "contact"
    const val SUGGEST_MAX = 5
    /** At most this many faces per photo (`PUT nodes/<id>/faces`). */
    const val FACES_MAX = 50

    /** `PATCH people/<id>`: only what is given; [clearContact] sends `contact: null`, an empty [name] removes it. */
    fun personPatchBody(name: String? = null, hidden: Boolean? = null, contact: DPersonContact? = null, clearContact: Boolean = false) = JsonObject().apply {
        if (name != null) addProperty("name", name.trim().take(NAME_MAX))
        if (hidden != null) addProperty("hidden", hidden)
        if (clearContact) {
            add("contact", JsonNull.INSTANCE)
        } else if (contact != null) {
            add(
                "contact",
                JsonObject().apply {
                    addProperty("kind", contact.kind)
                    addProperty("id", contact.id)
                    contact.name?.let { addProperty("name", it.take(NAME_MAX)) }
                },
            )
        }
    }

    fun mergeBody(into: Long) = JsonObject().apply { addProperty("into", into) }

    fun confirmBody(faceIds: List<Long>, yes: Boolean) = JsonObject().apply {
        add("faceIds", JsonArray().apply { faceIds.distinct().take(LIST_MAX).forEach { add(it) } })
        addProperty("yes", yes)
    }

    fun moveBody(person: Long?) = JsonObject().apply {
        if (person == null) add("person", JsonNull.INSTANCE) else addProperty("person", person)
    }

    /** `PUT nodes/<id>/faces { model, faces }` (at most [FACES_MAX]; `[]`: looked at, none). */
    fun facesBody(model: String, faces: List<DFaceUpload>) = JsonObject().apply {
        addProperty("model", model)
        add(
            "faces",
            JsonArray().apply {
                faces.take(FACES_MAX).forEach { f ->
                    add(
                        JsonObject().apply {
                            add("box", numbers(f.box))
                            f.landmarks?.takeIf { it.size == 10 }?.let { l -> add("landmarks", JsonArray().apply { l.chunked(2).forEach { add(numbers(it)) } }) }
                            addProperty("score", round(f.score.toDouble()))
                            addProperty("embedding", f.embedding)
                        },
                    )
                }
            },
        )
    }

    private fun round(v: Double): Double = Math.round(v.coerceIn(0.0, 1.0) * 1_000_000.0) / 1_000_000.0

    private fun numbers(v: List<Float>): JsonElement = JsonArray().apply { v.forEach { add(round(it.toDouble())) } }

    /** `PUT photos/settings`: the switches of places and people (only those given). */
    fun settingsBody(keepGeo: Boolean? = null, stripGeoOnShare: Boolean? = null, people: Boolean? = null, peopleKids: Boolean? = null) = JsonObject().apply {
        if (keepGeo != null) addProperty("keepGeo", keepGeo)
        if (stripGeoOnShare != null) addProperty("stripGeoOnShare", stripGeoOnShare)
        if (people != null) addProperty("people", people)
        if (peopleKids != null) addProperty("peopleKids", peopleKids)
    }
}
