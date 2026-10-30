package com.gatecontrol.android.mail

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Contract C2: the plugin's answers read from the tree, the bodies the app sends. */
class MailJsonTest {

    private fun obj(s: String) = JsonParser.parseString(s).asJsonObject

    private val account = """{"id":3,"provider":"gmx","email":"a@gmx.de","name":"Privat · GMX","color":"#F5B544","kind":"personal",
        "state":"ok","error":null,"push":"relevant","ai":true,"attachments":"auto","signature":"Gruß"}"""

    @Test
    fun `me - features, limits, Microsoft, accounts, unread and settings`() {
        val me = MailJson.me(
            obj(
                """{"features":["mail","ai","drive"],"limits":{"accountsMax":5,"quotaBytes":5368709120,"usedBytes":1024,"retentionMonths":12},
                "microsoft":{"clientId":null,"authority":"https://login.microsoftonline.com/common","scopes":["openid","Mail.Send"]},
                "accounts":[$account,{"id":0,"email":"bad"}],"unread":{"all":4,"byAccount":{"3":2,"x":1}},
                "settings":{"threads":false,"swipeLeft":"delete","swipeRight":"bogus"}}""",
            ),
        )!!
        assertTrue(me.has(MMe.FEATURE_AI))
        assertFalse(me.has(MMe.FEATURE_MICROSOFT))
        assertEquals(5368709120L, me.limits.quotaBytes)
        assertNull(me.microsoft.clientId, "no client id: Microsoft is not set up")
        assertEquals(listOf("openid", "Mail.Send"), me.microsoft.scopes)
        assertEquals(1, me.accounts.size, "an account without a valid id is dropped")
        val a = me.accounts.single()
        assertEquals("Privat · GMX", a.label)
        assertEquals("#F5B544", a.color)
        assertEquals("relevant", a.push)
        assertEquals("auto", a.attachments)
        assertEquals(4, me.unreadAll)
        assertEquals(mapOf(3L to 2), me.unreadByAccount)
        assertFalse(me.settings.threads)
        assertEquals("delete", me.settings.swipeLeft)
        assertEquals(MSettings.DEFAULT.swipeRight, me.settings.swipeRight, "an unknown swipe falls back")
    }

    @Test
    fun `me - without features it is no answer, defaults fill the rest`() {
        assertNull(MailJson.me(obj("""{"accounts":[]}""")))
        val me = MailJson.me(obj("""{"features":["mail"]}"""))!!
        assertEquals(MailMicrosoft.DEFAULT_AUTHORITY, me.microsoft.authority)
        assertEquals(MailMicrosoft.DEFAULT_SCOPES, me.microsoft.scopes)
        assertEquals(MSettings.DEFAULT, me.settings)
        assertTrue(me.accounts.isEmpty())
        // an http authority is not taken
        assertEquals(MailMicrosoft.DEFAULT_AUTHORITY, MailJson.me(obj("""{"features":[],"microsoft":{"authority":"http://evil"}}"""))!!.microsoft.authority)
    }

    @Test
    fun `account - a bad color is dropped, unknown values fall back`() {
        val a = MailJson.account(obj("""{"id":"7","email":"x@y.de","color":"red","kind":"weird","push":"loud","attachments":"x","error":{"code":"auth_failed","at":5}}"""))!!
        assertEquals(7L, a.id)
        assertNull(a.color)
        assertEquals(MAccount.KIND_PERSONAL, a.kind)
        assertEquals("all", a.push)
        assertEquals("off", a.attachments)
        assertEquals("auth_failed", a.errorCode)
        assertEquals(5L, a.errorAt)
        assertEquals("x@y.de", a.label)
        assertTrue(MailJson.account(obj("""{"id":1,"provider":"m365","email":"a@b.de"}"""))!!.microsoft)
    }

    @Test
    fun `providers - an array or an object with a list, hints in both languages`() {
        val root = JsonParser.parseString("""[{"id":"gmx","label":"GMX","auth":"password","hint":{"de":"IMAP erlauben","en":"Allow IMAP"},"help":"https://x"},
            {"id":"gmail","label":"Gmail","auth":"app_password","hint":null,"help":"http://insecure"},{"label":"no id"}]""")
        val list = MailJson.providers(root)!!
        assertEquals(listOf("gmx", "gmail"), list.map { it.id })
        assertEquals("Allow IMAP", list[0].hint("en"))
        assertEquals("IMAP erlauben", list[0].hint("de"))
        assertNull(list[1].help, "only https help links")
        assertEquals(1, MailJson.providers(JsonParser.parseString("""{"providers":[{"id":"imap"}]}"""))!!.size)
    }

    @Test
    fun `folders - per account with their role`() {
        val f = MailJson.folders(
            obj("""{"accounts":[{"accountId":3,"folders":[{"id":10,"name":"INBOX","path":"INBOX","role":"inbox","parentId":null,"unread":2,"total":9},
            {"id":11,"name":"Rechnungen","path":"INBOX/Rechnungen","role":"bogus","parentId":10,"unread":0,"total":1}]}]}"""),
        )!!
        val list = f.getValue(3L)
        assertEquals(MFolder.ROLE_INBOX, list[0].role)
        assertNull(list[1].role)
        assertEquals(10L, list[1].parentId)
    }

    private val summary = """{"id":42,"accountId":3,"folderId":10,"threadId":5,"threadCount":2,"from":{"name":"Stadtwerke","email":"info@sw.de"},
        "to":[{"name":"Max","email":"m@gmx.de"},"Erika <e@x.de>"],"subject":"Rechnung","snippet":"Im Anhang","date":1730000000000,"unread":true,
        "flagged":false,"answered":false,"hasAttachments":true,"tab":"other","savedToDrive":true,"ai":{"summary":"84,20 €"}}"""

    @Test
    fun `a page of summaries with its cursor and counts`() {
        val page = MailJson.page(obj("""{"items":[$summary,{"id":1}],"next":"c-1","counts":{"relevant":3,"other":7}}"""))!!
        val s = page.items.single()
        assertEquals(42L, s.id)
        assertEquals("Stadtwerke", s.from.label)
        assertEquals(listOf("m@gmx.de", "e@x.de"), s.to.map { it.email }, "objects and strings")
        assertEquals(MSummary.TAB_OTHER, s.tab)
        assertEquals("84,20 €", s.aiSummary)
        assertEquals("c-1", page.next)
        assertEquals(MCounts(3, 7), page.counts)
        assertEquals("17", MailJson.page(obj("""{"items":[],"next":17}"""))!!.next, "a numeric cursor")
        assertNull(MailJson.page(obj("""{"next":null}""")))
    }

    @Test
    fun `a message - body, pictures, trackers, attachments with Drive`() {
        val m = MailJson.message(
            obj(
                """{${summary.trim().removePrefix("{").removeSuffix("}")},"cc":[],"bcc":[],"replyTo":[{"email":"r@sw.de"}],
                "html":"<p>Hallo<img src=\"img/abc\"></p>","text":"Hallo","imagesBlocked":true,"trackers":2,
                "attachments":[{"id":7,"name":"Rechnung.pdf","mime":"application/pdf","size":212000,"inline":false,"cid":null,
                "savedTo":null,"suggestion":{"folderId":99,"areaId":"mine:1","path":"Finanzen › Strom","where":"Meine Dateien","name":"2026-11-03 Stadtwerke Rechnung.pdf","confidence":0.72,"reason":"learned"}},
                {"id":8,"name":"logo.png","mime":"image/png","size":100,"inline":true,"cid":"<logo@x>","savedTo":{"nodeId":5,"where":"Bilder"},"suggestion":null}],
                "listUnsubscribe":{"url":"javascript:alert(1)","mail":"unsub@sw.de"}}""",
            ),
        )!!
        assertTrue(m.imagesBlocked)
        assertEquals(2, m.trackers)
        assertEquals("r@sw.de", m.replyTo.single().email)
        val pdf = m.attachments[0]
        assertEquals(0.72, pdf.suggestion!!.confidence)
        assertEquals("learned", pdf.suggestion!!.reason)
        assertEquals("logo@x", m.attachments[1].cid, "angle brackets dropped")
        assertEquals(5L, m.attachments[1].savedTo!!.nodeId)
        assertNull(m.unsubscribe!!.url, "only web links")
        assertEquals("unsub@sw.de", m.unsubscribe!!.mail)
    }

    @Test
    fun `AI - done with to-dos and replies, none is empty`() {
        val ai = MailJson.ai(
            obj(
                """{"status":"done","summary":"Kurz","todos":[{"kind":"deadline","text":"Zahlen bis 28.11.","date":"2026-11-28"},
                {"kind":"event","text":"Termin","date":"2026-13-01","time":"25:00"},{"kind":"task"}],
                "replies":[{"label":"Danke","text":"Danke, ist angekommen."},{"text":"Ohne Label"}]}""",
            ),
        )!!
        assertTrue(ai.done)
        assertEquals(2, ai.todos.size, "a to-do without text is dropped")
        assertEquals("2026-11-28", ai.todos[0].date)
        assertNull(ai.todos[1].date, "an impossible date is dropped")
        assertNull(ai.todos[1].time)
        assertEquals("Ohne Label", ai.replies[1].label)
        assertEquals(MAi.NONE, MailJson.ai(obj("""{"status":"none"}""")))
        assertNull(MailJson.ai(obj("""{}""")))
    }

    @Test
    fun `drafts, sending and the outbox`() {
        val d = MailJson.draftAnswer(
            obj(
                """{"draft":{"id":9,"accountId":3,"to":[{"name":"Tim","email":"tim@firma.de"}],"cc":[],"bcc":[],"subject":"Angebot","html":"<p>x</p>",
                "attachments":[{"id":1,"name":"Preisliste.xlsx","size":46000000,"mime":"application/vnd.ms-excel","as":"link","url":"https://gc/l/x"}],
                "warnings":[{"kind":"too_large","text":"Als Link"}]}}""",
            ),
        )!!
        assertEquals(9L, d.id)
        assertTrue(d.attachments.single().asLink)
        assertEquals("too_large", d.warnings.single().kind)
        val send = MailJson.send(obj("""{"outboxId":4,"undoUntil":1000,"sendAt":2000}"""))!!
        assertEquals(MSend(4, 1000, 2000), send)
        assertEquals(9L, MailJson.cancelled(obj("""{"draftId":9}""")))
        val out = MailJson.outbox(obj("""{"items":[{"id":4,"draftId":9,"accountId":3,"subject":"x","sendAt":5,"state":"failed","error":{"code":"rejected"}}]}"""))!!
        assertEquals("rejected", out.single().error)
        assertEquals(listOf("a@b.de"), MailJson.contacts(obj("""{"items":[{"name":"A","email":"a@b.de","source":"person"},{"email":"no-at"}]}"""))!!.map { it.email })
    }

    @Test
    fun `filing, suggestions and saving`() {
        val items = MailJson.filing(
            obj("""{"items":[{"id":1,"messageId":42,"attachmentId":7,"name":"Beitrag.pdf","from":{"name":"Verein","email":"v@x.de"},"date":5,
            "suggestion":{"folderId":99,"path":"Finanzen","confidence":0.64,"reason":"ai"}},{"id":2,"messageId":0,"attachmentId":1}]}"""),
        )!!
        assertEquals(1, items.size)
        assertEquals(0.64, items[0].suggestion!!.confidence)
        val s = MailJson.suggest(obj("""{"suggestion":null,"duplicate":true}"""))
        assertNull(s.suggestion)
        assertTrue(s.duplicate)
        assertEquals(MSavedTo(5, "Finanzen › Strom"), MailJson.savedAnswer(obj("""{"savedTo":{"nodeId":5,"where":"Finanzen › Strom"}}""")))
        val all = MailJson.saveAll(obj("""{"saved":[{"attachmentId":7,"savedTo":{"nodeId":5,"where":"A"}}],"failed":[{"attachmentId":8,"code":"too_large"}]}"""))
        assertEquals(7L, all.saved.single().first)
        assertEquals(8L to "too_large", all.failed.single())
    }

    @Test
    fun `errors - the plugin's code, the core's, else the status`() {
        assertEquals("auth_failed", MailJson.failureCode(409, obj("""{"error":{"code":"auth_failed","hint":{"de":"IMAP erlauben","en":"Allow IMAP"}}}""")))
        assertEquals("not_found", MailJson.failureCode(404, obj("""{"ok":false,"code":"not_found"}""")))
        assertEquals("http_502", MailJson.failureCode(502, null))
        assertEquals("http_400", MailJson.failureCode(400, obj("""{"error":{"code":"Bad Code!"}}""")))
        assertTrue(MailJson.isRefusal(obj("""{"error":{"code":"x"}}""")))
        assertFalse(MailJson.isRefusal(obj("""{"ok":true}""")))
        val err = obj("""{"error":{"code":"auth_failed","hint":{"de":"IMAP erlauben","en":"Allow IMAP"}}}""")
        assertEquals("Allow IMAP", MailJson.errorHint(err, "en"))
        assertEquals("IMAP erlauben", MailJson.errorHint(err.getAsJsonObject("error"), "de"))
        assertEquals("plain", MailJson.errorHint(obj("""{"hint":"plain"}"""), "de"))
    }

    @Test
    fun `the realtime event`() {
        assertEquals(MChanged(3, 10), MailJson.changed(JsonParser.parseString("""{"kind":"changed","accountId":3,"folderId":10}""")))
        assertEquals(MChanged(3, null), MailJson.changed(JsonParser.parseString("""{"mail":{"kind":"changed","accountId":3}}""")))
        assertNull(MailJson.changed(JsonParser.parseString("""{"kind":"other"}""")))
        assertNull(MailJson.changed(null))
    }

    @Test
    fun `the realtime event since 1_1_2 - a body, an attachment, a thread`() {
        assertEquals(
            MChanged(3, null, messageId = 42, attachmentId = 7),
            MailJson.changed(JsonParser.parseString("""{"kind":"changed","accountId":3,"folderId":null,"messageId":42,"attachmentId":7}""")),
        )
        assertEquals(MChanged(3, 10, threadId = 9), MailJson.changed(JsonParser.parseString("""{"mail":{"kind":"changed","accountId":3,"folderId":10,"threadId":9}}""")))
        assertEquals(MChanged(3, null), MailJson.changed(JsonParser.parseString("""{"kind":"changed","accountId":3,"messageId":0,"attachmentId":"x"}""")))
    }

    @Test
    fun `account picture - media id and a raster mime, else none`() {
        val a = MailJson.account(obj("""{"id":5,"provider":"m365","email":"t@firma.de","avatar":{"mediaId":"ab_12","mime":"image/jpeg"}}"""))!!
        assertEquals(MAvatar("ab_12", "image/jpeg"), a.avatar)
        assertNull(MailJson.account(obj("""{"id":5,"provider":"m365","email":"t@firma.de","avatar":null}"""))!!.avatar)
        assertNull(MailJson.account(obj(account))!!.avatar, "older servers send none")
        assertNull(MailJson.account(obj("""{"id":5,"email":"t@firma.de","avatar":{"mediaId":"../x","mime":"image/png"}}"""))!!.avatar)
        assertNull(MailJson.account(obj("""{"id":5,"email":"t@firma.de","avatar":{"mediaId":"m1","mime":"image/svg+xml"}}"""))!!.avatar)
    }

    @Test
    fun `busy - when to ask again and whether it is still loading`() {
        val loading = obj("""{"code":"busy","reason":"loading","retryAfter":5}""")
        assertEquals(5, MailJson.busyRetryAfter("busy", loading))
        assertTrue(MailJson.busyLoading("busy", loading))
        assertEquals(12, MailJson.busyRetryAfter("busy", obj("""{"error":{"code":"busy","retryAfter":12}}""")))
        assertFalse(MailJson.busyLoading("busy", obj("""{"error":{"code":"busy","retryAfter":12}}""")), "throttled, not loading")
        assertEquals(5, MailJson.busyRetryAfter("busy", null))
        assertEquals(60, MailJson.busyRetryAfter("busy", obj("""{"retryAfter":3600}""")))
        assertEquals(1, MailJson.busyRetryAfter("busy", obj("""{"retryAfter":0}""")))
        assertNull(MailJson.busyRetryAfter("not_found", loading))
        assertFalse(MailJson.busyLoading("conflict", loading))
    }

    @Test
    fun `bodies - drafts with every kind of attachment, flags, moves, accounts`() {
        val body = MailJson.draftBody(
            accountId = 3,
            to = listOf(MAddress("Tim", "tim@firma.de")),
            cc = emptyList(),
            bcc = null,
            subject = "Angebot",
            html = "<p>x</p>",
            replyTo = 42,
            mode = "forward",
            attachments = listOf(
                MAtt.Kept(1),
                MAtt.Upload("m1", "a.pdf", "application/pdf"),
                MAtt.Forwarded(42, 7),
                MAtt.Drive(5, asLink = false),
                MAtt.Drive(6, asLink = true, expiresDays = 200, password = "pw"),
            ),
        )
        assertEquals(3L, body.get("accountId").asLong)
        assertEquals("tim@firma.de", body.getAsJsonArray("to")[0].asJsonObject.get("email").asString)
        assertFalse(body.has("bcc"), "null fields are left out (the server fills them in)")
        assertEquals("forward", body.get("mode").asString)
        val atts = body.getAsJsonArray("attachments").map { it.asJsonObject }
        assertEquals(1L, atts[0].get("id").asLong)
        assertEquals("m1", atts[1].get("mediaId").asString)
        assertEquals("a.pdf", atts[1].get("name").asString)
        assertFalse(atts[1].has("mime"), "Att of a device file: { mediaId, name }")
        assertEquals(7L, atts[2].get("attachmentId").asLong)
        assertEquals("copy", atts[3].get("as").asString)
        assertFalse(atts[3].has("expiresDays"))
        assertEquals("link", atts[4].get("as").asString)
        assertEquals(90, atts[4].get("expiresDays").asInt, "at most 90 days")
        assertEquals("pw", atts[4].get("password").asString)
        assertFalse(MailJson.draftBody(null, null, null, null, null, null, mode = "bogus").has("mode"))

        val flags = MailJson.flagsBody(listOf(1, 2), unread = false, flagged = null)
        assertEquals(2, flags.getAsJsonArray("ids").size())
        assertFalse(flags.get("unread").asBoolean)
        assertFalse(flags.has("flagged"))
        assertEquals(9L, MailJson.moveBody(listOf(1), 9).get("folderId").asLong)
        assertEquals("other", MailJson.tabBody(listOf(1), "other").get("tab").asString)
        assertFalse(MailJson.settingsBody(swipeLeft = "explode").has("swipeLeft"))

        val acc = MailJson.accountBody("imap", " a@b.de ", "pw", " ", "Name", "#46E0A0", "business", MServer("imap.b.de", 993, "tls"), null)
        assertEquals("a@b.de", acc.get("email").asString)
        assertFalse(acc.has("username"))
        assertEquals(993, acc.getAsJsonObject("imap").get("port").asInt)
        assertFalse(acc.has("smtp"))
        val patch = MailJson.accountPatch(push = "relevant", attachments = "never")
        assertEquals(setOf("push"), patch.keySet())
        val ms = MailJson.microsoftBody("code", "verifier", "msauth://x/y", null, "bad", null)
        assertEquals(setOf("code", "codeVerifier", "redirectUri"), ms.keySet())
        assertEquals("sender", MailJson.imagesBody(true).get("allow").asString)
        assertEquals("once", MailJson.imagesBody(false).get("allow").asString)
        assertEquals("family_calendar", MailJson.todoBody().get("action").asString)
        assertEquals(setOf("folderId", "remember"), MailJson.driveSaveBody(5, " ", true).keySet())
    }

    @Test
    fun `pictures of the HTML - inline Base64 or a media file, never SVG`() {
        val img = MailJson.picture(obj("""{"mime":"image/png","size":3,"data":"AQID"}"""))!!
        assertEquals("image/png", img.mime)
        assertEquals(listOf<Byte>(1, 2, 3), img.data!!.toList())
        val cid = MailJson.picture(obj("""{"mediaId":"m_1","mime":"image/jpeg","size":10,"url":"/x"}"""))!!
        assertEquals("m_1", cid.mediaId)
        assertNull(cid.data)
        assertNull(MailJson.picture(obj("""{"mime":"image/svg+xml","data":"PHN2Zz4="}""")), "SVG can carry script")
        assertNull(MailJson.picture(obj("""{"mime":"text/html","data":"AQID"}""")))
        assertNull(MailJson.picture(obj("""{"mime":"image/png","mediaId":"../x"}""")))
        assertNull(MailJson.picture(obj("""{"mime":"image/png"}""")))
        assertTrue(MailJson.message(obj("""{"id":1,"accountId":2,"bodyMissing":true}"""))!!.bodyMissing)
    }
}
