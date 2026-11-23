package com.gatecontrol.android.drive

import com.gatecontrol.android.drive.work.GeoPoint
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Drive 1.16 (`photoPlaces`, `photoPeople`): the answers parsed and the bodies sent. */
class DrivePlacesJsonTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private val item = """{ "id": 12, "areaId": "mine:7", "name": "IMG_1.jpg", "mime": "image/jpeg", "preview": true }"""

    @Test
    fun `a photo item carries its place, located and its people`() {
        val n = DriveJson.node(
            json(
                """{ "id": 12, "areaId": "mine:7", "name": "IMG_1.jpg", "located": true, "people": [3, 4, 3],
                "geo": { "lat": 53.545, "lon": 9.97, "place": { "key": "geonames:2911298", "name": "Hamburg", "district": "St. Pauli", "region": "Hamburg", "country": "de" } } }""",
            ),
        )!!
        assertEquals(DGeo(53.545, 9.97, DPlace("geonames:2911298", "Hamburg", "St. Pauli", "Hamburg", "DE")), n.geo)
        assertEquals(true, n.located)
        assertEquals(listOf(3L, 4L), n.people)
        val plain = DriveJson.node(json(item))!!
        assertNull(plain.geo)
        assertNull(plain.located)
        assertNull(plain.people)
        // broken coordinates: no place
        assertNull(DriveJson.node(json("""{ "id": 1, "areaId": "mine:7", "name": "a", "geo": { "lat": 95, "lon": 9 } }"""))!!.geo)
    }

    @Test
    fun `places, bundles and districts`() {
        val places = DrivePlacesJson.places(
            json("""{ "places": [ { "key": "geonames:1", "name": "Köln", "region": "NRW", "country": "DE", "count": 1204, "lat": 50.94, "lon": 6.96, "from": "2014-05-01T10:00:00Z", "to": "2026-10-01T10:00:00Z", "cover": $item }, { "name": "kein key" } ] }"""),
        )!!
        assertEquals(1, places.size)
        assertEquals("Köln", places[0].name)
        assertEquals(1204, places[0].count)
        assertEquals(12L, places[0].cover?.id)
        val clusters = DrivePlacesJson.clusters(json("""{ "clusters": [ { "lat": 53.5, "lon": 10, "count": 214, "key": "geonames:2", "cover": $item }, { "lat": 1, "lon": 2, "count": 3, "key": null }, { "lat": 1, "lon": 2, "count": 0 } ] }"""))!!
        assertEquals(listOf("geonames:2", null), clusters.map { it.key })
        assertNull(DrivePlacesJson.clusters(json("{}")))
        val photos = DriveJson.photos(json("""{ "items": [], "next": null, "districts": [ { "name": "Altona", "count": 47 } ], "search": { "people": [ { "id": 7, "name": "Mama" } ], "places": [ { "key": "geonames:2", "name": "Hamburg" } ] } }"""))!!
        assertEquals(listOf(DDistrict("Altona", 47)), photos.districts)
        assertEquals(DSearchMarks(listOf(7L to "Mama"), listOf("geonames:2" to "Hamburg")), photos.marks)
    }

    @Test
    fun `people, a person, similar, unsure faces and suggestions`() {
        val people = DrivePlacesJson.people(
            json(
                """{ "people": [ { "id": 3, "name": "Lea", "count": 812, "hidden": false, "cover": { "faceId": 9, "nodeId": 12, "box": [0.1, 0.2, 0.3, 0.4], "item": $item },
                "contact": { "kind": "family", "id": 17, "name": "Lea" } }, { "id": 4, "name": null, "count": 64 } ], "progress": { "done": 4120, "total": 6212 } }""",
            ),
        )!!
        assertEquals(2, people.people.size)
        assertEquals(DPersonContact("family", "17", "Lea"), people.people[0].contact)
        assertEquals(listOf(0.1, 0.2, 0.3, 0.4), people.people[0].cover?.box)
        assertFalse(people.people[1].named)
        assertEquals(4120, people.done)
        assertEquals(6212, people.total)
        val detail = DrivePlacesJson.personDetail(
            json("""{ "person": { "id": 3, "name": "Lea", "count": 812 }, "together": [ { "id": 5, "name": "Mama", "count": 230 } ], "places": [ { "key": "geonames:1", "name": "Köln", "count": 610 } ], "uncertain": 12, "years": { "from": 2016, "to": 2026 } }"""),
        )!!
        assertEquals("Lea", detail.person.name)
        assertEquals(230, detail.together[0].count)
        assertEquals("Köln", detail.places[0].name)
        assertEquals(12, detail.uncertain)
        assertEquals(2016, detail.yearFrom)
        // the same, with the person's fields on top
        assertEquals(12, DrivePlacesJson.personDetail(json("""{ "id": 3, "name": "Lea", "count": 812, "uncertain": 12 }"""))!!.uncertain)
        assertEquals(DPersonPatched(null, 8), DrivePlacesJson.patched(json("""{ "person": null, "mergedInto": 8 }""")))
        assertEquals(0.51, DrivePlacesJson.similar(json("""{ "people": [ { "id": 5, "name": "Tim", "count": 120, "score": 0.51 } ] }"""))!![0].score)
        val faces = DrivePlacesJson.uncertain(json("""{ "faces": [ { "faceId": 1, "box": [0.1, 0.1, 0.2, 0.2], "item": $item, "score": 0.35 }, { "faceId": 2, "box": [0, 0, 0] } ] }"""))!!
        assertEquals(listOf(1L), faces.map { it.faceId })
        val s = DrivePlacesJson.suggest(json("""{ "people": [ { "id": 7, "name": "Mama", "count": 530 } ], "places": [ { "key": "geonames:2", "name": "Hamburg", "sub": "St. Pauli", "count": 214 } ], "labels": [ { "label": "Beach", "text": "Strand", "count": 340 } ] }"""))!!
        assertEquals("Mama", s.people[0].name)
        assertEquals("St. Pauli", s.places[0].sub)
        assertEquals("Strand", s.labels[0].text)
        assertNull(DrivePlacesJson.suggest(json("{}")))
    }

    @Test
    fun `the photo settings with the switches of places and people`() {
        val s = DriveJson.photoSettings(json("""{ "keepGeo": false, "people": true, "peopleKids": false }"""))!!
        assertEquals(false, s.keepGeo)
        assertEquals(true, s.people)
        assertNull(s.stripGeoOnShare)
        assertEquals(true, DriveJson.photoSettings(json("""{ "workFolder": null, "workSubfolders": true, "stripGeoOnShare": true }"""))!!.stripGeoOnShare)
        val body = DrivePlacesJson.settingsBody(people = false)
        assertEquals("""{"people":false}""", body.toString())
    }

    @Test
    fun `the commit and the geo route send where a photo was taken`() {
        val commit = DriveJson.commitBody("m123456", 5, "a.jpg", null, "rename", geo = GeoPoint(53.5453, 9.9701))
        assertEquals(53.5453, commit.getAsJsonObject("geo").get("lat").asDouble)
        assertFalse(DriveJson.commitBody("m123456", 5, "a.jpg", null, "rename").has("geo"))
        assertNull(DrivePlacesJson.geoBody(GeoPoint(0.0, 0.0)))
        assertNull(DrivePlacesJson.geoBody(GeoPoint(91.0, 0.0)))
        assertEquals("""{"none":true}""", DrivePlacesJson.noGeoBody().toString())
    }

    @Test
    fun `filters go into the query`() {
        val q = DriveJson.photosQuery(q = "mama hamburg", filter = DPhotoFilter(place = "geonames:2", people = listOf(3, 4, 3), districts = true, withPeople = true))!!
        assertEquals("geonames:2", q["place"])
        assertEquals("3,4", q["person"])
        assertEquals("1", q["districts"])
        assertEquals("people", q["with"])
        assertEquals("mama hamburg", q["q"])
        assertEquals("6.5,50,7.25,51.000001", DrivePlacesJson.bbox(DBbox(6.5, 50.0, 7.25, 51.000001)))
        assertNull(DrivePlacesJson.bbox(DBbox(0.0, 10.0, 1.0, 5.0)))
        val unfaced = DriveJson.photosQuery(filter = DPhotoFilter(unfacedModel = "sface-2021dec"))!!
        assertEquals("1", unfaced["unfaced"])
        assertEquals("sface-2021dec", unfaced["model"])
        assertEquals("1", DriveJson.photosQuery(filter = DPhotoFilter(unlocated = true))!!["unlocated"])
        // an invalid place key: no query at all
        assertNull(DriveJson.photosQuery(filter = DPhotoFilter(place = "a b")))
        // districts only with a place
        assertFalse(DriveJson.photosQuery(filter = DPhotoFilter(districts = true))!!.containsKey("districts"))
    }

    @Test
    fun `faces go up with box, points, score and the embedding, at most 50`() {
        val f = DFaceUpload(listOf(0.1f, 0.2f, 0.3f, 0.4f), List(10) { 0.5f }, 0.93f, "AAAA")
        val body = DrivePlacesJson.facesBody("sface-2021dec", List(60) { f })
        assertEquals("sface-2021dec", body.get("model").asString)
        val faces = body.getAsJsonArray("faces")
        assertEquals(50, faces.size())
        val first = faces[0].asJsonObject
        assertEquals(4, first.getAsJsonArray("box").size())
        assertEquals(5, first.getAsJsonArray("landmarks").size())
        assertEquals(2, first.getAsJsonArray("landmarks")[0].asJsonArray.size())
        assertEquals("AAAA", first.get("embedding").asString)
        assertEquals(0.93, first.get("score").asDouble, 1e-6)
        // none found: an empty list (looked at)
        assertEquals("""{"model":"sface-2021dec","faces":[]}""", DrivePlacesJson.facesBody("sface-2021dec", emptyList()).toString())
    }

    @Test
    fun `person changes send only what is given`() {
        assertEquals("""{"name":"Oma Gerda"}""", DrivePlacesJson.personPatchBody(name = " Oma Gerda ").toString())
        assertEquals("""{"hidden":true}""", DrivePlacesJson.personPatchBody(hidden = true).toString())
        assertEquals("""{"contact":null}""", DrivePlacesJson.personPatchBody(clearContact = true).toString())
        val linked = DrivePlacesJson.personPatchBody(contact = DPersonContact("contact", "42", "Klaus"))
        assertEquals("contact", linked.getAsJsonObject("contact").get("kind").asString)
        assertEquals("""{"faceIds":[1,2],"yes":false}""", DrivePlacesJson.confirmBody(listOf(1, 2, 1), false).toString())
        assertEquals("""{"person":null}""", DrivePlacesJson.moveBody(null).toString())
        assertEquals("""{"into":9}""", DrivePlacesJson.mergeBody(9).toString())
        assertNotNull(DrivePlacesJson.placeKey("geonames:2911298"))
        assertTrue(DrivePlacesJson.placeKey("../x y") == null)
    }
}
