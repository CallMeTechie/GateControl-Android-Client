package com.gatecontrol.android.gatedrop

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

class GateDropJsonTest {

    private fun obj(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    private val keyB64: String = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })

    @Test
    fun `targets with all sections`() {
        val t = GateDropJson.targets(
            obj(
                """{"ok":true,"me":{"canLink":true,"canNas":1},
                "people":[{"id":"family","name":"Familie","kind":"family"},{"id":7,"name":"Anna Muster","avatar":"/avatars/7.webp","kind":"user","extra":1}],
                "devices":[{"id":"tok-12","name":"Büro-PC","platform":"windows","online":true,"direct":true},{"id":13,"name":"Laptop","platform":"windows","online":false}],
                "nas":[{"id":"f1","label":"Fotos","path":"/Familie/Fotos"}],
                "links":{"enabled":true},"future":{"x":1}}""",
            ),
        )!!
        assertTrue(t.me.canLink)
        assertTrue(t.me.canNas)
        assertEquals(2, t.people.size)
        assertTrue(t.people[0].isFamily)
        assertEquals("7", t.people[1].id)
        assertEquals("/avatars/7.webp", t.people[1].avatar)
        assertEquals(listOf("tok-12", "13"), t.devices.map { it.id })
        assertTrue(t.devices[0].direct && t.devices[0].online)
        assertFalse(t.devices[1].online)
        assertEquals("/Familie/Fotos", t.nas.single().path)
        assertTrue(t.nasOffered)
        assertTrue(t.linkOffered)
    }

    @Test
    fun `targets tolerate missing and broken fields`() {
        val t = GateDropJson.targets(obj("""{"people":[{"name":"no id"},{"id":5}],"devices":"x","links":{}}"""))!!
        assertEquals(GdMe(), t.me)
        assertTrue(t.people.isEmpty())
        assertTrue(t.devices.isEmpty())
        assertTrue(t.nas.isEmpty())
        assertFalse(t.linksEnabled)
        assertFalse(t.linkOffered)
        assertFalse(t.nasOffered)
        // A foreign avatar URL is dropped, the person stays.
        val p = GateDropJson.person(obj("""{"id":3,"name":"Max","avatar":"https://evil.example/a.png"}"""))!!
        assertNull(p.avatar)
        // Links switched on but not allowed for the caller: no link.
        val t2 = GateDropJson.targets(obj("""{"me":{"canLink":false},"links":{"enabled":true}}"""))!!
        assertFalse(t2.linkOffered)
    }

    @Test
    fun `send result with direct transfers`() {
        val r = GateDropJson.sendResult(
            obj(
                """{"id":42,"deliveries":[{"kind":"device","target":"tok-12","status":"pending"},{"kind":"link","target":null,"status":"delivered","via":"server"}],
                "link":{"url":"https://gc.example/s/abcdefghijklmnopqrstuv","pin":"4819"},
                "direct":[{"deviceId":"tok-12","transferId":"tr_abcdef12","mode":"push","candidates":["192.168.1.20:47731","8.8.8.8:47731","10.0.0.5:99999","172.20.1.2:47731"],"key":"$keyB64","ticket":"tick-123456"},
                          {"deviceId":"x","transferId":"tr_2","mode":"fly","key":"$keyB64","ticket":"tick-123456"},
                          {"deviceId":"y","transferId":"tr_33333333","mode":"pull","key":"c2hvcnQ=","ticket":"tick-123456"}]}""",
            ),
        )!!
        assertEquals("42", r.id)
        assertEquals(2, r.deliveries.size)
        assertEquals("server", r.deliveries[1].via)
        assertEquals("https://gc.example/s/abcdefghijklmnopqrstuv", r.link!!.url)
        assertEquals("4819", r.link!!.pin)
        // Unknown mode and a short key are dropped; public and broken candidates too.
        val d = r.direct.single()
        assertEquals(GdMode.PUSH, d.mode)
        assertEquals(listOf(GdCandidate("192.168.1.20", 47731), GdCandidate("172.20.1.2", 47731)), d.candidates)
        assertArrayEquals(ByteArray(32) { (it + 1).toByte() }, d.key)
        assertEquals("tick-123456", d.ticket)
    }

    @Test
    fun `send result in a wrapper and without link pin`() {
        val r = GateDropJson.sendResult(obj("""{"send":{"id":"s-9"},"link":{"url":"javascript:alert(1)"}}"""))!!
        assertEquals("s-9", r.id)
        assertNull(r.link)
        val r2 = GateDropJson.sendResult(obj("""{"id":1,"link":{"url":"https://x.example/s/aaaaaaaaaaaaaaaaaaaaaa","pin":null}}"""))!!
        assertNull(r2.link!!.pin)
        assertNull(GateDropJson.sendResult(obj("""{"deliveries":[]}""")))
    }

    @Test
    fun `private IPv4 only`() {
        assertTrue(GateDropJson.isPrivateIpv4("10.1.2.3"))
        assertTrue(GateDropJson.isPrivateIpv4("172.16.0.1"))
        assertTrue(GateDropJson.isPrivateIpv4("172.31.255.254"))
        assertTrue(GateDropJson.isPrivateIpv4("192.168.178.20"))
        assertTrue(GateDropJson.isPrivateIpv4("169.254.10.1"))
        assertFalse(GateDropJson.isPrivateIpv4("172.32.0.1"))
        assertFalse(GateDropJson.isPrivateIpv4("127.0.0.1"))
        assertFalse(GateDropJson.isPrivateIpv4("8.8.8.8"))
        assertFalse(GateDropJson.isPrivateIpv4("192.168.1.256"))
        assertFalse(GateDropJson.isPrivateIpv4("192.168.01.2"))
        assertFalse(GateDropJson.isPrivateIpv4("fe80::1"))
        assertFalse(GateDropJson.isPrivateIpv4("gc.example"))
        assertEquals(GdCandidate("10.0.0.2", 47731), GateDropJson.candidate(obj("""{"c":{"ip":"10.0.0.2","port":47731}}""").get("c")))
    }

    @Test
    fun `silent transfer push`() {
        val data = """{"gatedrop":{"transfer":"tr_abcdef12","mode":"pull","from":"Büro-PC","send":17,
            "items":[{"name":"../../angebot.pdf","bytes":1200000,"mime":"application/pdf"},{"name":"b.jpg","size":5,"mime":"image/jpeg; q=1"}],
            "candidates":["192.168.1.20:47731"],"key":"$keyB64","ticket":"tick-123456"}}"""
        val o = GateDropJson.pushData(data)!!
        val offer = GateDropJson.offer(o)!!
        assertEquals("tr_abcdef12", offer.transferId)
        assertEquals(GdMode.PULL, offer.mode)
        assertEquals("Büro-PC", offer.from)
        assertEquals("17", offer.sendId)
        assertEquals("angebot.pdf", offer.items[0].name)
        assertEquals(1_200_000L, offer.items[0].bytes)
        assertEquals("image/jpeg", offer.items[1].mime)
        assertNull(GateDropJson.noticeSendId(o))
    }

    @Test
    fun `a transfer push without key, ticket or items is refused`() {
        assertNull(GateDropJson.offer(obj("""{"transfer":"tr_abcdef12","mode":"pull","items":[{"name":"a","bytes":1}],"ticket":"tick-123456"}""")))
        assertNull(GateDropJson.offer(obj("""{"transfer":"tr_abcdef12","mode":"pull","items":[{"name":"a","bytes":1}],"key":"$keyB64"}""")))
        // Without items (too long for the push): an offer that asks GET transfers/<id>.
        assertTrue(GateDropJson.offer(obj("""{"transfer":"tr_abcdef12","mode":"pull","items":null,"itemCount":20,"key":"$keyB64","ticket":"tick-123456"}"""))!!.needsDetails)
        // Indexes must be 0…n-1.
        assertNull(GateDropJson.offer(obj("""{"transfer":"tr_abcdef12","mode":"pull","items":[{"index":1,"name":"a","bytes":1}],"key":"$keyB64","ticket":"tick-123456"}""")))
        // A transfer id that could leave the URL path.
        assertNull(GateDropJson.offer(obj("""{"transfer":"../admin","mode":"pull","items":[{"name":"a","bytes":1}],"key":"$keyB64","ticket":"tick-123456"}""")))
    }

    @Test
    fun `final plugin shapes - transfer push, GET transfers, result, items, link push`() {
        val push = GateDropJson.pushData(
            """{"route":"plg-gatecontrol-gatedrop","gatedrop":{"transfer":"0123456789abcdef01234567","send":"abcdefabcdefabcdefabcdef","mode":"pull",
            "from":{"userId":2,"name":"Anna","deviceId":"42","deviceName":"Pixel"},
            "items":[{"index":1,"name":"b.jpg","bytes":5,"mime":"image/jpeg"},{"index":0,"name":"a.pdf","bytes":12,"mime":"application/pdf"}],
            "candidates":["192.168.1.20:47731"],"key":"$keyB64","ticket":"AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcd","expiresAt":"2026-10-21T10:00:00Z"}}""",
        )
        val offer = GateDropJson.offer(push)!!
        assertEquals("Pixel", offer.from)
        assertEquals("abcdefabcdefabcdefabcdef", offer.sendId)
        assertEquals(listOf("a.pdf", "b.jpg"), offer.items.map { it.name })
        assertFalse(offer.needsDetails)
        // A transfer push also names its send: no delivery.
        assertNull(GateDropJson.noticeSendId(push))

        val detail = GateDropJson.offer(
            obj(
                """{"ok":true,"transfer":{"id":"0123456789abcdef01234567","sendId":"abcdefabcdefabcdefabcdef","side":"receiver","mode":"push","status":"open",
                "from":{"userId":2,"deviceId":"42","deviceName":"Pixel"},"candidates":[],"key":"$keyB64","ticket":"tick-123456",
                "items":[{"index":0,"name":"a.pdf","bytes":12,"mime":"application/pdf"}]}}""",
            ),
        )!!
        assertEquals("0123456789abcdef01234567", detail.transferId)
        assertEquals("abcdefabcdefabcdefabcdef", detail.sendId)
        assertEquals(GdMode.PUSH, detail.mode)
        assertEquals(1, detail.items.size)

        val answer = GateDropJson.transferAnswer(obj("""{"ok":true,"transfer":{"id":"x","status":"failed"},"fallback":"upload","missing":[2,0,0,99]}"""))
        assertEquals("upload", answer.fallback)
        assertEquals(listOf(0, 2), answer.missing)
        assertNull(GateDropJson.transferAnswer(obj("""{"ok":true,"fallback":null}""")).fallback)

        val items = GateDropJson.addItemsBody(mapOf(1 to "0123456789abcdef", 0 to "fedcba9876543210")).getAsJsonArray("items")
        assertEquals(0, items[0].asJsonObject.get("index").asInt)
        assertEquals("fedcba9876543210", items[0].asJsonObject.get("mediaId").asString)

        assertEquals(
            "0123456789abcdef0123456789abcdef",
            GateDropJson.itemMediaId(obj("""{"ok":true,"item":{"mediaId":"x"},"url":"/api/v1/client/plugins/gatecontrol-gatedrop/media/0123456789abcdef0123456789abcdef"}""")),
        )
        assertEquals("0123456789abcdef", GateDropJson.itemMediaId(obj("""{"item":{"mediaId":"0123456789abcdef"},"url":"https://evil.example/x"}""")))

        assertNull(GateDropJson.noticeSendId(GateDropJson.pushData("""{"gatedrop":{"link":"abcdefghijklmnopqrstuv","send":"abcdefabcdefabcdefabcdef"}}""")))
        val links = GateDropJson.links(obj("""{"kind":"links","items":[{"token":"abcdefghijklmnopqrstuv","state":"used","active":false},{"token":"bcdefghijklmnopqrstuvw","state":"active","active":true}],"hasMore":false}"""))!!
        assertEquals(listOf(GdLink.STATE_EXPIRED, GdLink.STATE_ACTIVE), links.map { it.state })
        val received = GateDropJson.entries(obj("""{"kind":"received","items":[{"id":"abcdefabcdefabcdefabcdef","role":"received","createdAt":"2026-10-21T08:00:00Z","from":{"userId":2,"name":"Anna","deviceName":"Pixel"},"items":[{"index":0,"mediaId":null,"name":"a.pdf","mime":"application/pdf","bytes":12,"local":true,"available":false}]}],"hasMore":false}"""))!!
        assertEquals("Pixel", received.single().from)
        assertNull(received.single().items.single().mediaId)
        val person = GateDropJson.person(obj("""{"id":3,"name":"Marc","avatar":null,"initials":"MA","colorHex":"#84CC16","kind":"user"}"""))!!
        assertEquals("MA", person.initials)
        assertEquals("#84CC16", person.colorHex)
    }

    @Test
    fun `server delivery push`() {
        assertEquals("88", GateDropJson.noticeSendId(GateDropJson.pushData("""{"gatedrop":{"send":88}}""")))
        assertEquals("s-1", GateDropJson.noticeSendId(GateDropJson.pushData("""{"gatedrop":{"send":{"id":"s-1"}}}""")))
        assertNull(GateDropJson.pushData("""{"familie":{}}"""))
        assertNull(GateDropJson.pushData("not json"))
        assertNull(GateDropJson.pushData(null))
    }

    @Test
    fun `inbox entries and links`() {
        val list = GateDropJson.entries(
            obj(
                """{"sends":[{"id":5,"createdAt":"2026-10-20T10:12:00Z","from":{"name":"Anna","device":"Laptop"},"text":"WLAN\npw","items":[{"mediaId":"0123456789abcdef","name":"a.pdf","mime":"application/pdf","bytes":12}],"deliveries":[{"kind":"device","target":"t1","targetName":"Büro-PC","status":"delivered","via":"direct"}]},
                {"id":6,"at":1792500000000,"from":"Büro-PC","link":"https://bahn.example/ticket"},{"noid":1}]}""",
            ),
        )!!
        assertEquals(2, list.size)
        assertEquals("Laptop", list[0].from)
        assertEquals("WLAN\npw", list[0].text)
        assertEquals("0123456789abcdef", list[0].items.single().mediaId)
        assertEquals("Büro-PC", list[0].deliveries.single().targetName)
        assertEquals(1792500000000L, list[1].at)
        assertEquals("https://bahn.example/ticket", list[1].link)
        assertNotNull(list[0].at)

        val links = GateDropJson.links(
            obj(
                """{"links":[{"token":"abcdefghijklmnopqrstuv","url":"https://gc.example/s/abcdefghijklmnopqrstuv","title":"Fotos","expiresAt":1792600000000,"downloads":3,"pin":true},
                {"token":"zyxwvutsrqponmlkjihgfe","locked":true},{"token":"bad token!"}]}""",
            ),
        )!!
        assertEquals(2, links.size)
        assertTrue(links[0].active)
        assertEquals(3, links[0].downloads)
        assertTrue(links[0].pin)
        assertEquals(GdLink.STATE_LOCKED, links[1].state)
        assertNull(GateDropJson.entries(obj("""{"ok":true}""")))
    }

    @Test
    fun `send body keeps id types and options`() {
        val body = GateDropJson.sendBody(
            items = listOf(
                GdSendItem("a.pdf", "application/pdf", 12, mediaId = "0123456789abcdef"),
                GdSendItem("b.jpg", "image/jpeg", 34, local = true),
            ),
            text = "Hallo",
            link = "ftp://nope",
            note = "  ",
            to = GdRecipients(people = listOf("7", "family"), devices = listOf("tok-12"), nas = listOf(GdNasRef("f1", "/Fotos")), link = GdLinkRequest(GdExpiry.ONCE, pin = false)),
        )
        val items = body.getAsJsonArray("items")
        assertEquals("0123456789abcdef", items[0].asJsonObject.get("mediaId").asString)
        assertFalse(items[0].asJsonObject.has("local"))
        assertTrue(items[1].asJsonObject.get("local").asBoolean)
        assertEquals(34, items[1].asJsonObject.get("bytes").asLong)
        assertEquals("Hallo", body.get("text").asString)
        assertFalse(body.has("link"))
        assertFalse(body.has("note"))
        val to = body.getAsJsonObject("to")
        assertTrue(to.getAsJsonArray("people")[0].asJsonPrimitive.isNumber)
        assertEquals("family", to.getAsJsonArray("people")[1].asString)
        assertEquals("tok-12", to.getAsJsonArray("devices")[0].asString)
        assertEquals("/Fotos", to.getAsJsonArray("nas")[0].asJsonObject.get("path").asString)
        assertEquals("once", to.getAsJsonObject("link").get("expires").asString)
        assertFalse(to.getAsJsonObject("link").get("pin").asBoolean)
    }

    @Test
    fun `presence and result bodies`() {
        val p = GateDropJson.presenceBody(listOf("192.168.1.5" to 24, "192.168.1.5" to 24), 47731, false)
        assertEquals(1, p.getAsJsonArray("lan").size())
        assertEquals(24, p.getAsJsonArray("lan")[0].asJsonObject.get("prefix").asInt)
        assertEquals(47731, p.get("port").asInt)
        assertFalse(p.get("listening").asBoolean)
        val r = GateDropJson.resultBody(false, error = "push_failed")
        assertFalse(r.get("ok").asBoolean)
        assertEquals("push_failed", r.get("error").asString)
        assertEquals("upload", GateDropJson.fallbackOf(obj("""{"ok":true,"fallback":"upload"}""")))
        assertNull(GateDropJson.fallbackOf(obj("""{"ok":true}""")))
    }

    @Test
    fun `resumable upload answers`() {
        val part = GateDropJson.uploadPart(obj("""{"ok":true,"complete":false,"uploadId":"abcdefgh12","offset":8388608,"total":60000000}"""))
        assertEquals(false, part.complete)
        assertEquals(8_388_608L, part.offset)
        val done = GateDropJson.uploadPart(obj("""{"ok":true,"complete":true,"mediaId":"0123456789abcdef","mime":"video/mp4","bytes":60000000}"""))
        assertEquals(true, done.complete)
        assertEquals("0123456789abcdef", done.mediaId)
        val conflict = GateDropJson.uploadPart(obj("""{"ok":false,"code":"offset_mismatch","offset":4194304}"""))
        assertEquals("offset_mismatch", conflict.code)
        assertEquals(4_194_304L, conflict.offset)
        // An older core: a plain upload answer without `complete`.
        assertNull(GateDropJson.uploadPart(obj("""{"ok":true,"mediaId":"0123456789abcdef"}""")).complete)
        assertEquals(0L, GateDropApi.PART_BYTES % (64 * 1024))
    }

    @Test
    fun `names, urls, times and keys`() {
        assertEquals("passwd", GateDropJson.safeName("../../etc/passwd"))
        assertEquals("a_b.txt", GateDropJson.safeName("a:b.txt"))
        assertEquals("file", GateDropJson.safeName("..."))
        assertEquals("x", GateDropJson.safeName(null, "x"))
        val long = "n".repeat(300) + ".jpeg"
        assertTrue(GateDropJson.safeName(long).length <= GateDropJson.NAME_MAX)
        assertTrue(GateDropJson.safeName(long).endsWith(".jpeg"))
        assertEquals("https://a.example/x?y=1", GateDropJson.webUrl(" https://a.example/x?y=1 "))
        assertNull(GateDropJson.webUrl("intent://x"))
        assertNull(GateDropJson.webUrl("https://a.example/x y"))
        assertEquals(1_000_000_000_000L, GateDropJson.time(JsonParser.parseString("1000000000")))
        assertEquals(1_792_500_000_000L, GateDropJson.time(JsonParser.parseString("1792500000000")))
        assertNotNull(GateDropJson.time(JsonParser.parseString("\"2026-10-20T10:12:00.123Z\"")))
        assertNotNull(GateDropJson.time(JsonParser.parseString("\"2026-10-20 10:12:00\"")))
        assertNotNull(GateDropJson.key(Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 9 })))
        assertNull(GateDropJson.key(Base64.getEncoder().encodeToString(ByteArray(16))))
        assertEquals("application/octet-stream", GateDropJson.mime("<script>"))
        assertEquals("4819", GateDropJson.pin(JsonParser.parseString("4819")))
        assertNull(GateDropJson.pin(JsonParser.parseString("\"12\"")))
    }

    @Test
    fun `pending lists the waiting sends oldest first`() {
        val list = GateDropJson.pending(
            obj(
                """{"ok":true,"items":[
                {"id":"s1","role":"sent","createdAt":"2026-10-20T08:00:00Z","expiresAt":"2026-10-27T08:00:00Z","expired":false,
                 "from":{"userId":3,"name":"Max","avatarUrl":"/a/3.webp","deviceId":"d-pc","deviceName":"Büro-PC"},
                 "text":null,"link":null,"note":"Für dich",
                 "items":[{"index":0,"mediaId":"m1234567890","name":"a.pdf","mime":"application/pdf","bytes":12,"local":false,"available":true},
                          {"index":1,"mediaId":null,"name":"b.jpg","mime":"image/jpeg","bytes":34,"local":true,"available":false}],
                 "deliveries":[{"kind":"device","target":"d-phone","targetName":"Handy","status":"waiting","via":"server"}]},
                {"id":"s2","role":"sent","createdAt":"2026-10-21T08:00:00Z","expired":false,"from":{"name":"Max","deviceName":"Laptop"},
                 "text":"Hallo","items":[],"deliveries":[]},
                {"id":"s1","text":"dup"},
                {"nope":1}
                ]}""",
            ),
        )!!
        assertEquals(listOf("s1", "s2"), list.map { it.id })
        val first = list[0]
        assertEquals("Büro-PC", first.from)
        assertEquals("Für dich", first.note)
        assertFalse(first.expired)
        assertEquals(2, first.items.size)
        assertEquals("m1234567890", first.items[0].mediaId)
        assertTrue(first.items[0].available)
        assertNull(first.items[1].mediaId)
        assertFalse(first.items[1].available)
        assertEquals(GdDelivery.WAITING, first.deliveries.single().status)
        assertEquals("Handy", first.deliveries.single().targetName)
        assertEquals("Hallo", list[1].text)
        assertTrue(list[1].items.isEmpty())
        // An expired send, an empty list, no list.
        assertTrue(GateDropJson.pending(obj("""{"ok":true,"items":[{"id":"x","expired":true}]}"""))!!.single().expired)
        assertTrue(GateDropJson.pending(obj("""{"ok":true,"items":[]}"""))!!.isEmpty())
        assertNull(GateDropJson.pending(obj("""{"ok":true}""")))
        assertNull(GateDropJson.pending(null))
        // Older views without `available`: the files count as available.
        assertTrue(GateDropJson.entry(obj("""{"id":"s","items":[{"mediaId":"m1234567890","name":"a"}]}"""))!!.items.single().available)
    }
}
