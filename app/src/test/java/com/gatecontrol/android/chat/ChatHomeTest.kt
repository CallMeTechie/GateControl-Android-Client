package com.gatecontrol.android.chat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Home features of the messenger: `GET me` limits and defaults (C4), request and grant bodies (C2/C3). */
class ChatHomeTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private fun msg(extra: String) = ChatJson.message(
        json("""{"id":50,"conversationId":12,"senderId":7,"kind":"action","mine":false,"createdAt":"2026-10-18T10:00:00.000Z",$extra}"""),
    )!!

    @Test
    fun `limits and defaults come from me`() {
        val p = ChatJson.profile(
            json(
                """{"user":{"id":7},"features":{"actions":true,"action_requests":true,"grant_off":true},"limits":{"requestTtlMin":30,"grantMaxDays":3},
                   "homeActions":{"available":true,"reason":null,"approversDefault":"parents","childrenMayRequest":false,"familyRoles":true,
                   "canRequest":false,"doorbell":{"lastCardAt":"2026-10-18T15:52:00Z"}}}""",
            ),
        )!!
        val h = p.home
        assertTrue(h.known)
        assertEquals(30, h.requestTtlMin)
        assertEquals(3, h.grantMaxDays)
        assertEquals(ChatApprovers.PARENTS, h.approversDefault)
        assertEquals(false, h.childrenMayRequest)
        assertEquals(true, h.available)
        assertTrue(h.familyRoles)
        assertEquals(false, h.canRequest)
        assertEquals("2026-10-18T15:52:00Z", h.doorbellLastCardAt)
        assertTrue("action_requests" in p.features.tokens && "grant_off" in p.features.tokens)
        assertEquals(p, ChatJson.profile(ChatSerializer.profile(p)), "the cached profile keeps the home defaults")
    }

    @Test
    fun `an older messenger sends none - the usual defaults`() {
        val h = ChatJson.profile(json("""{"user":{"id":7},"limits":{"requestTtlMin":"x"},"homeActions":"bogus"}"""))!!.home
        assertFalse(h.known)
        assertEquals(ChatHomeInfo.DEFAULT_TTL_MIN, h.requestTtlMin)
        assertEquals(ChatHomeInfo.DEFAULT_GRANT_DAYS, h.grantMaxDays)
        assertNull(h.approversDefault)
        assertTrue(h.offAtEndDefault)
    }

    @Test
    fun `out of range values are clamped, unknown approvers dropped`() {
        val h = ChatJson.profile(json("""{"user":{"id":7},"limits":{"requestTtlMin":0,"grantMaxDays":9999},"homeActions":{"approversDefault":"everyone"}}"""))!!.home
        assertEquals(1, h.requestTtlMin)
        assertEquals(365, h.grantMaxDays)
        assertNull(h.approversDefault)
    }

    @Test
    fun `request body - a keyword or certain people, the note trimmed`() {
        val parents = ChatHomeJson.requestBody("open", "gatecontrol-door", ChatApproverChoice(ChatApprovers.PARENTS), "  Schlüssel vergessen ")
        assertEquals("request", parents["mode"].asString)
        assertEquals("parents", parents["approvers"].asString)
        assertEquals("gatecontrol-door", parents["plugin"].asString)
        assertEquals("Schlüssel vergessen", parents["note"].asString)
        assertFalse(parents.has("approverIds"))

        val marc = ChatHomeJson.requestBody("open", null, ChatApproverChoice(null, listOf(3L)), "  ")
        assertEquals(3L, marc["approvers"].asJsonArray[0].asLong)
        assertEquals(3L, marc["approverIds"].asJsonArray[0].asLong, "older messengers understand approverIds")
        assertFalse(marc.has("note"))
        assertFalse(marc.has("plugin"))
    }

    @Test
    fun `grant body carries offAtEnd in the grant and beside it`() {
        val b = ChatHomeJson.grantBody("light", "gatecontrol-smarthome", 9, "2026-10-18T20:00:00Z", offAtEnd = true)
        assertEquals("grant", b["mode"].asString)
        val g = b["grant"].asJsonObject
        assertEquals(9L, g["toUserId"].asLong)
        assertEquals("2026-10-18T20:00:00Z", g["until"].asString)
        assertTrue(g["offAtEnd"].asBoolean)
        assertFalse(b.has("offAtEnd"), "only in grant (Messenger 1.5)")
        assertFalse(ChatHomeJson.grantBody("light", null, 9, "x", offAtEnd = false)["grant"].asJsonObject.has("offAtEnd"))
    }

    @Test
    fun `an action card keeps the request note and offAtEnd`() {
        val req = msg(""""action":{"mode":"request","actionId":"open","label":"Haustür","note":"Hab meinen Schlüssel vergessen","state":"open"}""")
        assertEquals("Hab meinen Schlüssel vergessen", req.action!!.note)
        val grant = msg(""""action":{"mode":"grant","actionId":"light","label":"Licht","grant":{"toUserId":9,"until":"2026-10-18T20:00:00.000Z","offAtEnd":true},"state":"open"}""")
        assertTrue(grant.action!!.offAtEnd)
        assertEquals(grant, ChatJson.message(ChatSerializer.message(grant)))
        assertEquals(req, ChatJson.message(ChatSerializer.message(req)))
    }

    @Test
    fun `the family group - managed by Familie, else titled Familie`() {
        val other = ChatJson.conversation(json("""{"id":1,"kind":"group","title":"Verein"}"""))!!
        val titled = ChatJson.conversation(json("""{"id":2,"kind":"group","title":"Familie"}"""))!!
        val direct = ChatJson.conversation(json("""{"id":3,"kind":"direct","title":"Familie"}"""))!!
        assertEquals(2L, ChatHomeJson.familyGroup(listOf(other, direct, titled))?.id)
        assertNull(ChatHomeJson.familyGroup(listOf(other, direct)))
    }

    @Test
    fun `requestable actions with their approvers`() {
        val r = ChatHomeJson.requestable(
            json(
                """{"actions":[{"id":"open","plugin":"gatecontrol-door","label":"Haustür","icon":null,"kind":"button","needsConfirm":true,"approverIds":[3,4],"self":false},
                   {"id":"light","plugin":"gatecontrol-smarthome","label":"Licht","kind":"toggle","needsConfirm":false,"approverIds":[],"self":true},
                   {"label":"no id"}],"approvers":"parents","approversDefault":"parents","canRequest":true,"requestTtlMin":15}""",
            ),
        )!!
        assertEquals(listOf("open", "light"), r.actions.map { it.action.id })
        assertEquals(listOf(3L, 4L), r.actions[0].approverIds)
        assertTrue(r.actions[1].self)
        assertEquals("toggle", r.actions[1].action.kind)
        assertEquals("parents", r.approvers)
        assertEquals(15, r.requestTtlMin)
        assertEquals("2,3", ChatHomeJson.approversQuery(ChatApproverChoice(null, listOf(2, 3))))
        assertEquals("rights", ChatHomeJson.approversQuery(ChatApproverChoice(ChatApprovers.RIGHTS)))
        assertNull(ChatHomeJson.requestable(json("""{"ok":true}""")))
    }

    @Test
    fun `doorbell of the door plugin`() {
        val b = ChatHomeJson.doorbell(json("""{"doorbell":{"lastRingAt":"2026-10-18T15:52:00Z","source":"Ring · Haustür","chat":{"at":"2026-10-18T15:52:03Z","ok":false,"error":"not_found","fallback":null}}}"""))!!
        assertEquals("Ring · Haustür", b.source)
        assertEquals(false, b.chatOk)
        assertEquals("not_found", b.chatError)
        assertFalse(b.fallback)
        assertTrue(ChatHomeJson.doorbell(json("""{"doorbell":{"lastRingAt":"x","chat":{"ok":true,"fallback":"users"}}}"""))!!.fallback)
        assertNull(ChatHomeJson.doorbell(json("""{"doorbell":null}""")))
    }

    @Test
    fun `grant_off system message and the expiry of a request`() {
        val sys = ChatJson.message(
            json("""{"id":51,"conversationId":12,"senderId":null,"kind":"system","createdAt":"2026-10-18T20:00:00.000Z",
                "system":{"event":"grant_off","messageId":50,"label":"Wohnzimmer","ok":false,"byUserId":3,"toUserId":9}}"""),
        )!!
        assertEquals(false, sys.system!!.ok)
        assertEquals(3L, sys.system!!.by)
        assertEquals(9L, sys.system!!.otherUserId)
        assertEquals(sys, ChatJson.message(ChatSerializer.message(sys)))
        val req = msg(""""action":{"mode":"request","actionId":"open","label":"Haustür","state":"open","expiresAt":"2026-10-18T10:15:00.000Z","approvers":"parents"}""")
        assertEquals("2026-10-18T10:15:00.000Z", req.action!!.expiresAt)
        assertEquals(req, ChatJson.message(ChatSerializer.message(req)))
    }
}
