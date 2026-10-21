package com.gatecontrol.android.logbook

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

/** Every answer shape of the logbook API of the vehicles plugin (1.6). */
class LogbookJsonTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private val month = json(
        """
        {"ok":true,"month":"2026-10","currentMonth":"2026-10",
         "vehicles":[{"id":3,"name":"Enyaq","model":"Enyaq iV 80","vin":"TMB…"},{"id":"x"},{"id":4,"name":"Octavia"}],
         "vehicleId":3,"canEdit":true,
         "summary":{"total":425,"private":41,"business":128,"commute":252,"open":4,"openCount":1,"count":25},
         "moving":false,"detection":{"anything":true},
         "trips":[
           {"id":41,"date":"2026-10-20","dateLabel":"Mo., 20.10.2026","startTime":"09:05","endTime":"10:02",
            "startedAt":"2026-10-20T07:05:00.000Z","endedAt":"2026-10-20T08:02:00.000Z",
            "startKm":23418,"endKm":23482,"distanceKm":64,
            "startAddress":"Lindenweg 4, 12345 Ort","endAddress":"Hafenstraße 21, 23456 Stadt",
            "start":{"lat":51.2,"lon":6.7},"end":{"lat":51.1,"lon":6.8},
            "purpose":"business","note":"Weber GmbH","source":"auto","edited":false,
            "driver":{"id":7,"username":"marc","displayName":"Marc"},"driverUserId":7,
            "route":{"kind":"road","km":61.8,"points":[[51.2,6.7],[51.15,6.75],[51.1,6.8]]}},
           {"id":42,"date":"2026-10-21","startTime":"18:20","endTime":"18:31","distanceKm":4,
            "start":{"lat":51.2,"lon":6.7},"end":null,"purpose":null,"note":null,"driver":null,"route":null},
           {"id":43,"date":"2026-10-21","purpose":"holiday","route":{"kind":"teleport","km":null,"points":[[51.2,6.7],[91,6.8],[51.3,"x"],[51.25,6.75]]}},
           {"id":44,"date":"2026-10-21","route":{"kind":"road","points":[[51.2,6.7]]}},
           {"id":-1,"date":"2026-10-21"},
           {"id":45,"date":"21.10.2026"},
           {"id":41,"date":"2026-10-20"}
         ]}
        """,
    )

    @Test
    fun `a month is parsed with vehicles, sums and trips`() {
        val m = LogbookJson.month(month)!!
        assertEquals("2026-10", m.month)
        assertEquals("2026-10", m.currentMonth)
        assertEquals(listOf(3L, 4L), m.vehicles.map { it.id })
        assertEquals("Enyaq iV 80", m.vehicles[0].model)
        assertEquals(3L, m.vehicleId)
        assertEquals("Enyaq", m.vehicle?.name)
        assertTrue(m.canEdit)
        assertFalse(m.moving)
        assertEquals(LogbookSummary(425.0, 41.0, 128.0, 252.0, 4.0, 1, 25), m.summary)
        // Invalid ids and dates are dropped, a repeated id counts once.
        assertEquals(listOf(41L, 42L, 43L, 44L), m.trips.map { it.id })
    }

    @Test
    fun `a trip keeps every field`() {
        val t = LogbookJson.month(month)!!.trips.first()
        assertEquals("2026-10-20", t.date)
        assertEquals("Mo., 20.10.2026", t.dateLabel)
        assertEquals("09:05", t.startTime)
        assertEquals("10:02", t.endTime)
        assertEquals("2026-10-20T07:05:00.000Z", t.startedAt)
        assertEquals(23418.0, t.startKm)
        assertEquals(23482.0, t.endKm)
        assertEquals(64.0, t.distanceKm)
        assertEquals("Lindenweg 4, 12345 Ort", t.startAddress)
        assertEquals(LogbookPoint(51.2, 6.7), t.start)
        assertEquals(LogbookPoint(51.1, 6.8), t.end)
        assertEquals(LogbookPurpose.BUSINESS, t.purpose)
        assertEquals("Weber GmbH", t.note)
        assertEquals("auto", t.source)
        assertEquals(LogbookDriver(7L, "Marc"), t.driver)
    }

    @Test
    fun `the route is read with kind, km and points`() {
        val route = LogbookJson.month(month)!!.trips.first().route!!
        assertTrue(route.isRoad)
        assertEquals(61.8, route.km)
        assertEquals(listOf(LogbookPoint(51.2, 6.7), LogbookPoint(51.15, 6.75), LogbookPoint(51.1, 6.8)), route.points)
    }

    @Test
    fun `bad points are dropped, an unknown kind is a straight line, too few points no route`() {
        val trips = LogbookJson.month(month)!!.trips
        val odd = trips.first { it.id == 43L }
        assertNull(odd.purpose)
        assertEquals(LogbookRoute.KIND_LINE, odd.route!!.kind)
        assertEquals(listOf(LogbookPoint(51.2, 6.7), LogbookPoint(51.25, 6.75)), odd.route!!.points)
        assertNull(trips.first { it.id == 44L }.route)
        val open = trips.first { it.id == 42L }
        assertNull(open.route)
        assertNull(open.end)
        assertNull(open.driver)
    }

    @Test
    fun `a driver without display name shows the user name`() {
        assertEquals(LogbookDriver(2L, "anna"), LogbookJson.driver(json("""{"id":2,"username":"anna"}""")))
        assertNull(LogbookJson.driver(json("""{"id":2}""")))
    }

    @Test
    fun `routes are capped`() {
        val points = (0 until 1000).joinToString(",") { "[51.${it % 10},6.5]" }
        val route = LogbookJson.route(json("""{"kind":"road","points":[$points]}"""))!!
        assertEquals(LogbookJson.MAX_ROUTE_POINTS, route.points.size)
    }

    @Test
    fun `an answer without month or with a vehicle id not listed`() {
        assertNull(LogbookJson.month(json("""{"ok":true,"trips":[]}""")))
        val m = LogbookJson.month(json("""{"month":"2026-09","currentMonth":"2026-10","vehicles":[],"vehicleId":9,"trips":[]}"""))!!
        assertNull(m.vehicleId)
        assertEquals(LogbookSummary.EMPTY, m.summary)
        assertFalse(m.canEdit)
        // The current month is never before the shown one.
        assertEquals("2026-10", m.currentMonth)
        assertEquals("2026-10", LogbookJson.month(json("""{"month":"2026-10","currentMonth":"2025-01"}"""))!!.currentMonth)
    }

    @Test
    fun `the saved trip of a PUT`() {
        val t = LogbookJson.savedTrip(json("""{"ok":true,"trip":{"id":41,"date":"2026-10-20","purpose":"private","note":"Arzt"}}"""))!!
        assertEquals(LogbookPurpose.PRIVATE, t.purpose)
        assertEquals("Arzt", t.note)
        assertNull(LogbookJson.savedTrip(json("""{"ok":true}""")))
    }

    @Test
    fun `the PUT body has only what changed, open is an empty purpose`() {
        assertEquals("""{"purpose":""}""", LogbookJson.changeBody(LogbookTripChange(purpose = "")).toString())
        assertEquals("""{"purpose":""}""", LogbookJson.changeBody(LogbookTripChange(purpose = "nonsense")).toString())
        assertEquals("""{"purpose":"commute"}""", LogbookJson.changeBody(LogbookTripChange(purpose = "commute")).toString())
        assertEquals(
            """{"note":"Weber GmbH","startAddress":"A","endAddress":"B"}""",
            LogbookJson.changeBody(LogbookTripChange(note = " Weber GmbH\n", startAddress = "A", endAddress = "B")).toString(),
        )
        assertEquals(0, LogbookJson.changeBody(LogbookTripChange()).size())
    }

    @Test
    fun `an export is decoded`() {
        val bytes = "%PDF-1.4 test".toByteArray()
        val data = Base64.getEncoder().encodeToString(bytes)
        val f = LogbookJson.export(json("""{"ok":true,"file":{"name":"Fahrtenbuch Enyaq 2026-10.pdf","type":"application/pdf","encoding":"base64","data":"$data"}}"""))!!
        assertEquals("Fahrtenbuch_Enyaq_2026-10.pdf", f.name)
        assertEquals("application/pdf", f.type)
        assertArrayEquals(bytes, f.bytes)
        val csv = LogbookJson.export(json("""{"file":{"name":"../x","type":"text/csv; charset=utf-8","data":"$data"}}"""))!!
        assertEquals("x.csv", csv.name)
        assertEquals("text/csv", csv.type)
    }

    @Test
    fun `an export of another type, encoding or no data is refused`() {
        assertNull(LogbookJson.export(json("""{"file":{"name":"a","type":"text/html","data":"QQ=="}}""")))
        assertNull(LogbookJson.export(json("""{"file":{"name":"a","type":"text/csv","encoding":"hex","data":"41"}}""")))
        assertNull(LogbookJson.export(json("""{"file":{"name":"a","type":"text/csv","data":"!!!"}}""")))
        assertNull(LogbookJson.export(json("""{"file":{"name":"a","type":"text/csv","data":""}}""")))
        assertNull(LogbookJson.export(json("""{"ok":true}""")))
    }

    @Test
    fun `file names are made safe`() {
        assertEquals("fahrtenbuch.pdf", LogbookJson.fileName(null, "pdf"))
        assertEquals("fahrtenbuch.csv", LogbookJson.fileName("...", "csv"))
        assertEquals("a_b.pdf", LogbookJson.fileName("a b", "pdf"))
        assertEquals("report.PDF", LogbookJson.fileName("report.PDF", "pdf"))
    }

    @Test
    fun `error codes and texts`() {
        assertEquals("SKODA_NOT_OWNER", LogbookJson.errorCode(json("""{"ok":false,"code":"SKODA_NOT_OWNER"}""")))
        assertEquals("not_found", LogbookJson.errorCode(json("""{"ok":false,"error":"not_found"}""")))
        assertNull(LogbookJson.errorCode(json("""{"ok":false,"error":"Fahrzeug fehlt"}""")))
        assertEquals("Fahrzeug fehlt", LogbookJson.errorText(json("""{"ok":false,"error":"Fahrzeug fehlt"}""")))
        assertNotNull(LogbookJson.errorText(json("""{"message":"Nicht erlaubt"}""")))
    }
}
