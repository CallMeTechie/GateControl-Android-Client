package com.gatecontrol.android.notizen

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The plugin's answers (shapes of gatecontrol-notizen 1.0.0, README "API") → the app's models, and back. */
class NotizenJsonTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    @Test
    fun `me with features, prefs and imports`() {
        val me = NotizenJson.me(json("""{"ok":true,"user":{"id":12,"name":"Jenny"},"lang":"de","today":"2026-11-08","time":"10:05","timezone":"Europe/Berlin",
            "features":["tasks","notes","vault","mstodo","dsnote"],"prefs":{"area":"work","evening":"18:30","sources":{"gatecontrol-familie":{"on":true,"kids":"mine"},"bogus":{"on":false}},
            "groups":{"gatecontrol-mail|account:3":"work","x":"nope"},"times":{"morning":"07:00"}},"sharing":"groups","vaults":true,
            "imports":{"mstodo":true,"mstodoSync":false,"dsnote":true},"attachMB":25,"readOnly":false}"""))!!
        assertEquals(12L, me.userId)
        assertEquals("2026-11-08", me.today)
        assertTrue(me.has("vault"))
        assertEquals(NArea.WORK, me.prefs.area)
        assertEquals("18:30", me.prefs.evening)
        assertEquals(setOf("gatecontrol-familie"), me.prefs.sources.keys)
        assertEquals("mine", me.prefs.sources["gatecontrol-familie"]?.kids)
        assertEquals(mapOf("gatecontrol-mail|account:3" to "work"), me.prefs.groups)
        assertEquals(mapOf("morning" to "07:00"), me.prefs.times)
        assertEquals(NImports(mstodo = true, mstodoSync = false, dsnote = true), me.imports)
        assertNull(NotizenJson.me(json("""{"user":{"id":1},"today":"heute"}""")))
    }

    private val myDay = """{"ok":true,"view":"myday","title":"Mein Tag","sections":[
        {"id":"events","title":"Termine heute","count":1,"items":[{"uid":"s:9","type":"source","id":9,"kind":"event","title":"Gelbe Tonne rausstellen","subtitle":"Abholung morgen früh",
          "source":{"plugin":"gatecontrol-alltag","label":"Alltag","open":{"plugin":"gatecontrol-alltag","route":"waste/2026-11-09"}},"due":{"date":"2026-11-09","time":null},
          "show":{"date":"2026-11-08","time":"18:00"},"allDay":true,"lead":"evening","overdue":false,"area":"private","important":false,"done":false,"doneAt":null,"canComplete":true,
          "assignee":null,"progress":null,"group":null,"meta":{"stars":2,"amount":"84,20 €","flagged":true},"updatedAt":1762600000000}]},
        {"id":"today","title":"Heute","count":1,"items":[{"uid":"t:5","type":"task","id":5,"listId":2,"parentId":null,"title":"Zahnarzt anrufen","hasNote":false,"important":true,"done":false,
          "doneAt":null,"due":{"date":"2026-11-08","time":"09:00"},"overdue":false,"remindAt":"2026-11-08T09:00:00+01:00","repeat":{"type":"weekly","interval":1,"days":[1]},
          "repeatText":"Jeden Montag","assignee":{"id":12,"name":"Jenny"},"myDay":true,"progress":{"done":1,"total":3},"attachments":2,"source":null,"noteId":null,"position":3,
          "canComplete":true,"updatedAt":1762600000001},{"type":"weird"}]},
        {"id":"done","title":"Erledigt heute","count":0,"items":[],"collapsed":true}]}"""

    @Test
    fun `a view with tasks and items of other plugins`() {
        val v = NotizenJson.view(json(myDay))!!
        assertEquals(listOf("events", "today", "done"), v.sections.map { it.id })
        val item = v.sections[0].items.single() as NItem
        assertEquals("gatecontrol-alltag", item.plugin)
        assertEquals(NOpen("gatecontrol-alltag", "waste/2026-11-09"), item.open)
        assertEquals(NDue("2026-11-08", "18:00"), item.show)
        assertEquals(mapOf("stars" to "2", "amount" to "84,20 €", "flagged" to "true"), item.meta)
        assertEquals("s:9", item.uid)
        // the unknown row is dropped, the task stays
        val task = v.sections[1].items.single() as NTask
        assertEquals("t:5", task.uid)
        assertEquals(NDue("2026-11-08", "09:00"), task.due)
        assertEquals(NRepeat.Rule("weekly", 1, listOf(1)), task.repeat)
        assertEquals(NProgress(1, 3), task.progress)
        assertEquals(2, task.attachments)
        assertTrue(v.sections[2].collapsed)
    }

    @Test
    fun `task detail with sub tasks, the list, attachments and the source item`() {
        val d = NotizenJson.taskDetail(json("""{"ok":true,"task":{"type":"task","id":7,"listId":2,"title":"Angebot beantworten","note":"Rabatt ab 50 Stück","important":false,"done":false,
            "subtasks":[{"id":8,"title":"Preise vergleichen","done":true},{"id":9,"title":"Lieferzeit","done":false}],"list":{"id":2,"name":"Aufgaben","area":"private"},
            "attachmentList":[{"id":3,"kind":"task","refId":7,"name":"Angebot.pdf","mime":"application/pdf","bytes":12000,"encrypted":false,"hasText":false,"url":"/plugin-media/abc?sig=1"},
              {"id":4,"kind":"task","refId":7,"name":"x","url":"https://evil.example/x"}],
            "source":{"plugin":"gatecontrol-mail","id":"msg:4711"},
            "sourceItem":{"type":"source","id":44,"kind":"task","title":"Angebot beantworten","source":{"plugin":"gatecontrol-mail","label":"Mail","open":{"plugin":"gatecontrol-mail","route":"message/4711"}},"canComplete":true}}}"""))!!
        assertEquals(listOf(NSubtask(8, "Preise vergleichen", true), NSubtask(9, "Lieferzeit", false)), d.subtasks)
        assertEquals("Aufgaben", d.listName)
        assertEquals("Rabatt ab 50 Stück", d.note)
        assertEquals("/plugin-media/abc?sig=1", d.attachmentList[0].url)
        // a link to another host is never kept
        assertNull(d.attachmentList[1].url)
        assertEquals(NSourceRef("gatecontrol-mail", "msg:4711"), d.task.source)
        assertEquals(NOpen("gatecontrol-mail", "message/4711"), d.sourceItem?.open)
    }

    @Test
    fun `nav, notebooks with a Tresor and a bound one, notes`() {
        val n = NotizenJson.nav(json("""{"area":"private","smart":{"myday":8,"important":3,"planned":14,"assigned":2},
            "sources":[{"plugin":"gatecontrol-familie","label":"Familie","hint":"Aufgaben, Packlisten, Einkauf","open":6}],
            "lists":[{"id":1,"name":"Aufgaben","area":"private","isDefault":true,"shared":false,"members":[],"open":5},{"id":2,"name":"Haus & Garten","area":"private","shared":true,"members":[{"id":15,"name":"Jenny"}],"open":7,"color":"green"}],
            "groups":[{"id":1,"name":"Haus","area":"private"}],
            "notes":{"total":40,"notebooks":[{"id":4,"name":null,"area":"private","vault":{"id":"Vt9bJm0n2QwErTyUiOpAsD","nameCipher":null,"keyVersion":2,"needsRotation":true},"count":3},
              {"id":5,"name":"Familie (Pinnwand)","area":"private","bound":{"plugin":"gatecontrol-familie","key":"pinnwand","kind":"notes","label":"Familie"},"count":9}],
              "tags":[{"tag":"auto","count":4}]}}"""))!!
        assertEquals(mapOf("myday" to 8, "important" to 3, "planned" to 14, "assigned" to 2), n.smart)
        assertEquals("Haus & Garten", n.lists[1].name)
        assertEquals("green", n.lists[1].color)
        assertTrue(n.lists[1].shared)
        val vault = n.notebooks[0].vault!!
        assertEquals(2, vault.keyVersion)
        assertTrue(vault.needsRotation)
        assertEquals("pinnwand", n.notebooks[1].bound?.key)
        assertEquals(listOf(NTag("auto", 4)), n.tags)

        val note = NotizenJson.note(json("""{"note":{"id":9,"notebookId":5,"title":"Werkstatt","snippet":"…","pinned":true,"color":"yellow","check":{"done":1,"total":3},
            "tags":["auto"],"rev":4,"updatedAt":5,"createdAt":1,"body":"# x","notebook":{"id":5,"name":"Haushalt","vault":false},
            "attachments":[{"id":3,"kind":"note","refId":9,"name":"IMG.jpg","mime":"image/jpeg","url":"/plugin-media/t"}],"tasks":[{"id":5,"title":"Ölfilter","due":{"date":"2026-11-11"},"done":false}]}}"""))!!
        assertEquals(4L, note.summary.rev)
        assertTrue(note.summary.pinned)
        assertEquals("Haushalt", note.notebookName)
        assertEquals(NNoteTask(5, "Ölfilter", NDue("2026-11-11"), false), note.tasks.single())
        assertFalse(note.summary.encrypted)
    }

    @Test
    fun `moving over and the Tresor answers`() {
        val c = NotizenJson.msConsent(json("""{"deviceId":"d1","userCode":"ABCD-EFGH","verificationUri":"https://microsoft.com/devicelogin","expiresIn":900,"interval":5}"""))
        assertEquals("ABCD-EFGH", c.userCode)
        assertEquals("https://microsoft.com/devicelogin", c.verificationUri)
        assertNull(NotizenJson.msConsent(json("""{"verificationUri":"http://phish.example"}""")).verificationUri)
        val pv = NotizenJson.msPreview(json("""{"lists":[{"id":"AQMk","name":"Büro","open":4,"done":1,"attachments":0,"area":"work","include":true},{"id":"x","name":"Markierte E-Mails","include":false}],"totals":{"lists":1,"open":4,"attachments":0}}"""))!!
        assertEquals(listOf("work", "private"), pv.lists.map { it.area })
        assertEquals(4, pv.totalOpen)
        val job = NotizenJson.job(json("""{"job":{"id":3,"kind":"mstodo","state":"running","stats":{"tasks":12,"lists":2}}}"""))!!
        assertEquals(12, job.stats["tasks"])
        val v = NotizenJson.vaultAnswer(json("""{"vault":{"id":"Vt9bJm0n2QwErTyUiOpAsD","notebookId":4,"area":"private","keyVersion":1,"recoveryPub":"4so6glZ3nmYcfJtX3_3im8vkoWstjdPCJevEXWoO3W4","needsRotation":false,
            "myDevices":[{"deviceId":"TEPe3ybUpp6uwJHEBg_vDQ","state":"approved"}],"pending":1}}"""))!!
        assertEquals(4L, v.notebookId)
        assertEquals("approved", v.myDevices.single().state)
    }

    @Test
    fun `bodies keep the nulls that clear a field`() {
        val p = NotizenJson.patch("due" to null, "important" to true, "repeat" to NRepeat.toJson(NRepeat.Rule("after", 3)))
        assertTrue(p.has("due") && p.get("due").isJsonNull)
        assertEquals("""{"type":"after","interval":3}""", p.get("repeat").toString())
        val q = NotizenJson.quickTaskBody("morgen Zahnarzt #neu", NArea.PRIVATE, null, myDay = true, createList = true)
        assertEquals(true, q.get("createList").asBoolean)
        val prefs = NotizenJson.prefsBody(sources = mapOf("gatecontrol-mail" to NSourcePrefs(flagged = false)), groups = mapOf("gatecontrol-mail|account:3" to null))
        assertEquals("""{"sources":{"gatecontrol-mail":{"flagged":false}},"groups":{"gatecontrol-mail|account:3":null}}""", com.google.gson.GsonBuilder().serializeNulls().create().toJson(prefs))
    }

    @Test
    fun `opening the original of an item`() {
        val all = NotizenOpen.Have(mail = true, drive = true, familie = true, schule = true)
        assertEquals(NotizenOpen.MailMessage(4711), NotizenOpen.target(NOpen("gatecontrol-mail", "message/4711"), all))
        assertEquals(NotizenOpen.Tab("gatecontrol-mail"), NotizenOpen.target(NOpen("gatecontrol-mail", "folder/3"), all))
        assertEquals(NotizenOpen.DriveFile(88), NotizenOpen.target(NOpen("gatecontrol-drive", "node/88"), all))
        assertEquals(NotizenOpen.Familie("shopping/ab12"), NotizenOpen.target(NOpen("gatecontrol-familie", "shopping/ab12"), all))
        assertEquals(NotizenOpen.Familie("tasks"), NotizenOpen.target(NOpen("gatecontrol-familie", "task/5"), all))
        assertEquals(NotizenOpen.Familie("school"), NotizenOpen.target(NOpen("gatecontrol-schule", "homework/3"), all))
        assertEquals(NotizenOpen.Familie("dates"), NotizenOpen.target(NOpen("gatecontrol-alltag", "waste/2026-11-09"), all))
        assertEquals(NotizenOpen.Note(812), NotizenOpen.target(NOpen(NOTIZEN_PLUGIN, "note/812"), all))
        // without the plugin's screen in this app: nothing to open (the details stay)
        assertNull(NotizenOpen.target(NOpen("gatecontrol-mail", "message/4711"), all.copy(mail = false)))
        assertNull(NotizenOpen.target(NOpen("gatecontrol-unknown", "x/1"), all))
        val link = NotizenOpen.link(NOpen("gatecontrol-mail", "message/4711"))
        assertEquals("gatecontrol://plugin/gatecontrol-mail/message/4711", link)
        assertEquals(NOpen("gatecontrol-mail", "message/4711"), NotizenOpen.parse(link))
        assertNotNull(NotizenOpen.parse("gatecontrol://plugin/gatecontrol-drive"))
        assertNull(NotizenOpen.parse("gatecontrol://plugin/../x"))
    }
}
