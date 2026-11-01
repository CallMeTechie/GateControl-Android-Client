package com.gatecontrol.android.drive

// ─── Drive 1.16: places and people in the photos (features `photoPlaces`, `photoPeople`) ──

/** A place as Drive names it offline (GeoNames): [key] stays the same for a town, [country] is ISO-2. */
data class DPlace(
    val key: String,
    val name: String,
    val district: String? = null,
    val region: String? = null,
    val country: String? = null,
)

/** Where a photo was taken (`geo` of a photo item or node); [place] is null while Drive has not named it. */
data class DGeo(val lat: Double, val lon: Double, val place: DPlace? = null)

/** `GET photos/places`: a place with how many photos, where, from when to when, and a cover photo. */
data class DPlaceSummary(
    val key: String,
    val name: String,
    val region: String?,
    val country: String?,
    val count: Int,
    val lat: Double,
    val lon: Double,
    /** ISO 8601 of the first and the last photo (null: not said). */
    val from: String?,
    val to: String?,
    val cover: DNode?,
)

/** `GET photos/map`: a bundle of photos on the map; [key] when it is exactly one place. */
data class DCluster(val lat: Double, val lon: Double, val count: Int, val key: String?, val cover: DNode?)

/** `districts` of `GET photos?place=&districts=1`: the district names of a place with their counts. */
data class DDistrict(val name: String, val count: Int)

/** A face on a photo: its box (0…1 of the upright picture: x, y, w, h) and the photo item to cut it from. */
data class DFaceRef(val faceId: Long?, val nodeId: Long?, val box: List<Double>, val item: DNode?, val score: Double? = null)

/** What a person is linked to (a family member or a mail contact). */
data class DPersonContact(val kind: String, val id: String, val name: String?)

/** A person of `GET people` (only the viewer's own). */
data class DPerson(
    val id: Long,
    val name: String?,
    val count: Int,
    val hidden: Boolean,
    val cover: DFaceRef?,
    val contact: DPersonContact?,
) {
    val named: Boolean get() = !name.isNullOrBlank()
}

/** `GET people`: the people and how far the phones have looked ([done] of [total] pictures). */
data class DPeople(val people: List<DPerson>, val done: Int?, val total: Int?)

/** Someone on photos together with a person, a person's place. */
data class DPersonCount(val id: Long, val name: String?, val count: Int)

data class DPlaceCount(val key: String, val name: String, val count: Int)

/** `GET people/<id>`: the person, who is on photos with them, where, unsure faces, the years. */
data class DPersonDetail(
    val person: DPerson,
    val together: List<DPersonCount>,
    val places: List<DPlaceCount>,
    val uncertain: Int,
    val yearFrom: Int?,
    val yearTo: Int?,
)

/** `PATCH people/<id>`: the person now and, when the name was taken, the one it went into. */
data class DPersonPatched(val person: DPerson?, val mergedInto: Long?)

/** `GET people/<id>/similar`: a person that may be the same, with how near. */
data class DSimilar(val id: Long, val name: String?, val count: Int, val score: Double)

/** `GET photos/suggest`: what the typed words may mean (people, places, what is in the photos). */
data class DSuggest(
    val people: List<DPersonCount> = emptyList(),
    val places: List<DSuggestPlace> = emptyList(),
    val labels: List<DSuggestLabel> = emptyList(),
) {
    val empty: Boolean get() = people.isEmpty() && places.isEmpty() && labels.isEmpty()
}

data class DSuggestPlace(val key: String, val name: String, val sub: String?, val count: Int)

data class DSuggestLabel(val label: String, val text: String, val count: Int)

/** `search` of `GET photos?q=`: the people and places the words were taken as (the marks). */
data class DSearchMarks(val people: List<Pair<Long, String>> = emptyList(), val places: List<Pair<String, String>> = emptyList())

/** A face found on this phone for `PUT nodes/<id>/faces`: [box] and [landmarks] 0…1 of the upright picture. */
data class DFaceUpload(val box: List<Float>, val landmarks: List<Float>?, val score: Float, val embedding: String)

/** A box on the map: west, south, east, north (WGS84). */
data class DBbox(val west: Double, val south: Double, val east: Double, val north: Double)

/**
 * The new filters of `GET photos`, `photos/months`, `photos/places` and `photos/map` (Drive 1.16): a [place], a [bbox],
 * photos with all of [people], a [folder]; [districts] adds a place's districts, [withPeople] the people on each item;
 * [unlocated] / [unfacedModel] list what the phones still have to look at.
 */
data class DPhotoFilter(
    val place: String? = null,
    val bbox: DBbox? = null,
    val people: List<Long> = emptyList(),
    val folder: Long? = null,
    val districts: Boolean = false,
    val withPeople: Boolean = false,
    val unlocated: Boolean = false,
    val unfacedModel: String? = null,
)
