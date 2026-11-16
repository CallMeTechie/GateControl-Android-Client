package com.gatecontrol.android.profile

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The fields of the own profile as server 1.280 sends them (apps contract, all numbers made of zeros). */
class ProfileDetailsJsonTest {

    private fun json(s: String) = JsonParser.parseString(s).asJsonObject

    private val sample = """{
      "userId": 7, "username": "jenny", "displayName": "Jenny Backes",
      "firstName": "Jenny", "lastName": "Backes", "about": "Meist ab 17 Uhr erreichbar",
      "birthday": { "date": "1986-03-14", "month": 3, "day": 14, "year": 1986, "yearHidden": true },
      "email": "jenny@familie-backes.example",
      "emails": [{ "id": 3, "address": "jenny.privat@example.com", "state": "unconfirmed", "codePending": true }],
      "language": "de", "timezone": "Europe/Berlin", "serverTimezone": "Europe/Berlin",
      "visibility": { "firstName": "all", "lastName": "all", "about": "all", "birthday": "family", "email": "self", "language": "self", "timezone": "self" },
      "phones": [{ "id": 11, "kind": "mobile", "number": "+4915100000000", "visibility": "none", "useForRecognition": true, "state": "whatsapp", "confirmed": true, "createdAt": "2026-11-15 19:00:00" }],
      "recognition": { "byWhatsapp": false, "byPhone": true, "whatsappAnswered": false, "phoneAnswered": true, "whatsappLinked": true, "phonesForRecognition": 1 },
      "locked": { "displayName": false, "email": false, "directory": null },
      "family": true,
      "features": { "phones": true, "emailConfirm": true, "recognition": true },
      "limits": { "phones": 5, "emails": 3, "about": 140, "name": 60, "displayName": 100 },
      "options": { "kinds": ["mobile","work","home","other"], "phoneVisibility": ["none","family","all"], "fieldVisibility": ["self","family","all"], "phoneStates": ["unconfirmed","whatsapp","admin","directory"] }
    }"""

    @Test
    fun `the contract's profile is read completely`() {
        val d = ProfileDetailsJson.parse(json(sample))!!
        assertEquals(7, d.userId)
        assertEquals("Jenny Backes", d.displayName)
        assertEquals("Jenny", d.firstName)
        assertEquals("Meist ab 17 Uhr erreichbar", d.about)
        assertEquals(PBirthday(3, 14, 1986, yearHidden = true), d.birthday)
        assertEquals("1986-03-14", d.birthday!!.date)
        assertEquals(listOf(PEmail(3, "jenny.privat@example.com", confirmed = false, codePending = true)), d.emails)
        assertEquals("Europe/Berlin", d.timezone)
        assertEquals("family", d.visibilityOf("birthday"))
        assertEquals(1, d.phones.size)
        val p = d.phones.single()
        assertEquals("+4915100000000", p.number)
        assertEquals("none", p.visibility)
        assertTrue(p.useForRecognition)
        assertTrue(p.confirmed)
        assertEquals(PRecognition(false, true, false, true, true, 1), d.recognition)
        assertTrue(d.family)
        assertTrue(d.canAddPhone)
        assertEquals(listOf("mobile", "work", "home", "other"), d.kinds)
    }

    @Test
    fun `an older server sends no profile - the page stays as before`() {
        assertNull(ProfileDetailsJson.fromAnswer(json("""{"ok":true,"user":{"id":7,"name":"Anna"},"palette":[]}""")))
        assertNotNull(ProfileDetailsJson.fromAnswer(json("""{"ok":true,"user":{"id":7},"profile":$sample}""")))
        // no user id: nothing
        assertNull(ProfileDetailsJson.parse(json("""{"username":"x"}""")))
    }

    @Test
    fun `a number is never in a log line`() {
        val p = ProfileDetailsJson.parse(json(sample))!!.phones.single()
        assertFalse(p.toString().contains("0000"), p.toString())
        assertFalse(ProfileDetailsJson.parse(json(sample)).toString().contains("+49"))
    }

    @Test
    fun `unknown values are dropped, bad numbers too`() {
        val d = ProfileDetailsJson.parse(
            json(
                """{"userId":7,"username":"u","visibility":{"about":"world","birthday":"self"},
                "phones":[{"id":1,"kind":"fax","number":"+4915100000000","visibility":"public","state":"magic"},
                          {"id":2,"kind":"mobile","number":"015100000000"},{"id":3,"number":"+49 151 00000000"}],
                "birthday":{"date":"--03-14","yearHidden":false},"timezone":"../etc","language":"<b>"}""",
            ),
        )!!
        assertEquals("u", d.displayName)
        assertEquals("all", d.visibilityOf("about"))
        assertEquals("self", d.visibilityOf("birthday"))
        assertEquals(1, d.phones.size)
        with(d.phones.single()) {
            assertEquals("other", kind)
            assertEquals("none", visibility)
            assertEquals(PPhone.STATE_UNCONFIRMED, state)
            assertFalse(confirmed)
        }
        assertEquals(PBirthday(3, 14, null, false), d.birthday)
        assertEquals("--03-14", d.birthday!!.date)
        assertNull(d.timezone)
        assertNull(d.language)
        assertFalse(d.family)
    }

    @Test
    fun `phone numbers switched off - none shown, none to add`() {
        val d = ProfileDetailsJson.parse(json(sample.replace("\"features\": { \"phones\": true", "\"features\": { \"phones\": false")))!!
        assertTrue(d.phones.isEmpty())
        assertFalse(d.canAddPhone)
    }

    @Test
    fun `Familie is offered only with a family or when set already`() {
        val d = ProfileDetailsJson.parse(json(sample.replace("\"family\": true", "\"family\": false")))!!
        assertEquals(listOf("none", "all"), d.phoneLevels("none"))
        assertEquals(listOf("self", "all"), d.fieldLevels("all"))
        assertEquals(listOf("self", "family", "all"), d.fieldLevels("family"))
    }

    @Test
    fun `the cached copy belongs to one login`() {
        val raw = json(sample)
        val enc = ProfileDetailsJson.encode(raw, "owner-a")!!
        assertEquals(7, ProfileDetailsJson.decode(enc, "owner-a")!!.second.userId)
        assertNull(ProfileDetailsJson.decode(enc, "owner-b"))
        assertNull(ProfileDetailsJson.decode("nonsense", "owner-a"))
        assertNull(ProfileDetailsJson.decode(enc, ""))
    }

    @Test
    fun `bodies of the changes`() {
        assertEquals("""{"birthday":null}""", ProfileDetailsJson.birthdayBody(null).toString())
        assertEquals("""{"birthday":{"date":"--03-14","yearHidden":true}}""", ProfileDetailsJson.birthdayBody(PBirthday(3, 14, null, true)).toString())
        assertEquals("""{"firstName":"Jenny","lastName":null}""", ProfileDetailsJson.textBody("firstName" to " Jenny ", "lastName" to "").toString())
        assertEquals(
            """{"about":"Hallo","visibility":{"about":"family"}}""",
            ProfileDetailsJson.merge(ProfileDetailsJson.textBody("about" to "Hallo"), ProfileDetailsJson.visibilityBody("family", "about")).toString(),
        )
        assertEquals("""{"visibility":"all"}""", ProfileDetailsJson.phoneBody(null, null, "all", null).toString())
        assertEquals("""{"recognition":{"byPhone":false}}""", ProfileDetailsJson.recognitionBody(byPhone = false).toString())
    }

    @Test
    fun `the file name of my data`() {
        val today = LocalDate.of(2026, 11, 15)
        assertEquals("gatecontrol-meine-daten-2026-11-15.json", ProfileDetailsJson.exportName("""attachment; filename="gatecontrol-meine-daten-2026-11-15.json"""", today))
        assertEquals("gatecontrol-meine-daten-2026-11-15.json", ProfileDetailsJson.exportName(null, today))
        // never a path or another type
        assertEquals("gatecontrol-meine-daten-2026-11-15.json", ProfileDetailsJson.exportName("""attachment; filename="../../x.sh"""", today))
    }

    @Test
    fun `who sees the picture - only on a server that offers the levels`() {
        assertNull(ProfileDetailsJson.parse(json(sample))!!.avatarVisibility, "an older server: no row")
        val withLevels = sample.replace("\"phoneStates\"", "\"avatarVisibility\": [\"all\",\"contacts\",\"family\",\"self\"], \"phoneStates\"")
        assertEquals("all", ProfileDetailsJson.parse(json(withLevels))!!.avatarVisibility, "not chosen yet: all")
        val contacts = withLevels.replace("\"timezone\": \"self\" }", "\"timezone\": \"self\", \"avatar\": \"contacts\" }")
        assertEquals("contacts", ProfileDetailsJson.parse(json(contacts))!!.avatarVisibility)
        val odd = withLevels.replace("\"timezone\": \"self\" }", "\"timezone\": \"self\", \"avatar\": \"nobody\" }")
        assertEquals("all", ProfileDetailsJson.parse(json(odd))!!.avatarVisibility, "an unknown level: all")
        assertEquals(listOf("all", "contacts", "family", "self"), ProfileDetails.AVATAR_LEVELS)
        assertEquals(
            json("""{"visibility":{"avatar":"family"}}"""),
            com.gatecontrol.android.ui.profile.ProfileFieldsViewModel.avatarVisibilityBody("family"),
        )
    }
}
