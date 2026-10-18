package com.gatecontrol.android.chat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Messenger 1.4: status, stories, polls, events, arrival, actions, cards, preferences and tools. */
class ChatMessenger14Test {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private fun msg(kind: String, extra: String) = ChatJson.message(
        json("""{"id":50,"conversationId":12,"senderId":7,"kind":"$kind","mine":false,"createdAt":"2026-10-18T10:00:00.000Z",$extra}"""),
    )!!

    /** Through the Room cache: what was parsed comes back the same. */
    private fun roundTrip(m: ChatMessage) = assertEquals(m, ChatJson.message(ChatSerializer.message(m)))

    // ─── features ───────────────────────────────────────────────────

    @Test
    fun `the 1_4 features are read from the features object`() {
        val f = ChatJson.features(json("""{"direct":true,"status":true,"polls":true,"events":false,"msg_tools":true,"arrival":1}"""))
        assertTrue(f.status)
        assertTrue(f.polls)
        assertFalse(f.events, "false keys are off")
        assertTrue(f.msgTools)
        assertFalse(f.arrival, "only booleans count")
        assertFalse(f.stories)
        assertEquals(emptySet<String>(), ChatFeatureHint.unknown(f), "all 1.4 keys are known to this app")
    }

    // ─── polls ──────────────────────────────────────────────────────

    @Test
    fun `a poll is parsed, capped and kept through the cache`() {
        val m = msg(
            "poll",
            """"poll":{"question":"Was essen wir am Sonntag?","multi":false,"anonymous":false,"allowAdd":true,"closesAt":"2026-10-25T16:00:00.000Z",
               "closed":false,"voterCount":3,"memberCount":4,"myVotes":["o2","nope"],
               "options":[{"id":"o1","text":"Lasagne","count":1,"voters":[9]},{"id":"o2","text":"Grillen","count":2,"voters":[7,8]},
                          {"id":3,"text":"Pizza bestellen","count":0,"voters":[],"addedBy":9},{"text":"no id"}]}""",
        )
        val p = m.poll!!
        assertEquals("Was essen wir am Sonntag?", p.question)
        assertEquals(listOf("o1", "o2", "3"), p.options.map { it.id }, "numeric ids become keys, options without id are dropped")
        assertEquals(listOf("o2"), p.myVotes, "unknown option ids are ignored")
        assertEquals(3, p.totalVotes)
        assertEquals(9L, p.options[2].addedBy)
        assertFalse(m.isUnknownKind)
        roundTrip(m)
        // Numeric ids go back as numbers.
        assertTrue(ChatJson14.voteBody(listOf("3", "o2")).getAsJsonArray("optionIds")[0].asJsonPrimitive.isNumber)
    }

    @Test
    fun `an anonymous poll shows no voters`() {
        val p = msg("poll", """"poll":{"question":"Q","anonymous":true,"options":[{"id":"a","text":"A","count":2,"voters":[1,2]}]}""").poll!!
        assertNull(p.options[0].voters)
        assertEquals(2, p.options[0].count)
    }

    @Test
    fun `a poll send body keeps 2 to 12 trimmed options`() {
        val body = ChatJson14.pollSendBody("  Frage  ", listOf(" A ", "", "B") + (1..20).map { "o$it" }, multi = true, anonymous = false, allowAdd = true, closesAt = null)
        assertEquals("Frage", body.get("question").asString)
        assertEquals(12, body.getAsJsonArray("options").size())
        assertEquals("A", body.getAsJsonArray("options")[0].asString)
        assertTrue(body.get("closesAt").isJsonNull)
    }

    // ─── events ─────────────────────────────────────────────────────

    @Test
    fun `an event with place, checklist and answers`() {
        val m = msg(
            "event",
            """"event":{"title":"Sonntagsessen bei Oma","startsAt":"2026-10-26T11:30:00.000Z","endsAt":"2026-10-26T15:00:00.000Z",
               "place":{"familiePlaceId":"p1","label":"Oma Gisela","lat":52.5,"lon":13.4},"description":"Jeder bringt etwas mit",
               "checklist":[{"id":"c1","text":"Würstchen","doneBy":9},{"id":"c2","text":"Salat"}],
               "rsvp":{"yes":[{"userId":7,"guests":0},{"userId":8,"guests":1}],"maybe":[],"no":[9],"mine":"yes"},
               "guests":true,"reminderMin":120,"familyCalendar":true,"departureHint":true,"cancelled":false}""",
        )
        val e = m.event!!
        assertEquals("Oma Gisela", e.place!!.label)
        assertEquals("p1", e.place!!.familiePlaceId)
        assertEquals(9L, e.checklist[0].doneBy)
        assertEquals(3, e.rsvp!!.yesCount, "yes answers with their guests")
        assertEquals(listOf(9L), e.rsvp!!.no.map { it.userId }, "plain ids are read too")
        assertEquals(ChatRsvp.YES, e.rsvp!!.mine)
        roundTrip(m)
    }

    @Test
    fun `an event without answers or title`() {
        val noRsvp = msg("event", """"event":{"title":"T","startsAt":"2026-10-26T11:30:00.000Z","rsvp":false}""").event!!
        assertNull(noRsvp.rsvp)
        assertNull(msg("event", """"event":{"startsAt":"2026-10-26T11:30:00.000Z"}""").event, "no title, no event")
    }

    @Test
    fun `the next event of a conversation`() {
        val c = ChatJson.conversation(
            json(
                """{"id":12,"kind":"group","title":"Familie","archived":true,"pinned":[40,41],
                   "nextEvent":{"messageId":50,"title":"Sonntagsessen","startsAt":"2026-10-26T11:30:00.000Z","yesCount":3}}""",
            ),
        )!!
        assertTrue(c.archived)
        assertEquals(listOf(40L, 41L), c.pinned)
        assertEquals(3, c.nextEvent!!.yesCount)
        assertEquals(c, ChatJson.conversation(ChatSerializer.conversation(c)))
    }

    @Test
    fun `an event send body`() {
        val body = ChatJson14.eventSendBody(
            "Essen", "2026-10-26T11:30:00.000Z", null, false, ChatEventPlace("p1", "Oma", 52.0, 13.0), " ",
            listOf("Salat", " ", "Nachtisch"), rsvp = false, guests = true, reminderMin = 60, familyCalendar = true, departureHint = false,
        )
        assertEquals(2, body.getAsJsonArray("checklist").size())
        assertFalse(body.has("description"), "blank description left out")
        assertFalse(body.get("guests").asBoolean, "guests only with answers")
        assertEquals("p1", body.getAsJsonObject("place").get("familiePlaceId").asString)
    }

    // ─── arrival ────────────────────────────────────────────────────

    @Test
    fun `a live arrival with position`() {
        val m = msg(
            "arrival",
            """"arrival":{"dest":{"familiePlaceId":"home","label":"Zuhause","lat":52.5,"lon":13.4,"radiusM":150},"state":"live",
               "etaAt":"2026-10-18T15:40:00.000Z","distanceM":3100,"progress":1.7,"lastAt":"2026-10-18T15:31:00.000Z",
               "position":{"lat":52.4,"lon":13.3},"onArrive":{"actionId":"lights/on","label":"Licht an"}}""",
        )
        val a = m.arrival!!
        assertTrue(a.live)
        assertEquals(1f, a.progress, "progress capped at 1")
        assertEquals("lights/on", a.onArriveActionId)
        assertEquals(150, a.dest.radiusM)
        roundTrip(m)
        val stopped = msg("arrival", """"arrival":{"dest":{"label":"Schule"},"state":"bogus"}""").arrival!!
        assertEquals(ChatArrival.STATE_STOPPED, stopped.state)
        assertNull(stopped.lat)
    }

    @Test
    fun `arrival positions go out at most every 20 s and stop when no longer live`() {
        assertTrue(ChatArrivalLogic.shouldPost(null, 1_000))
        assertFalse(ChatArrivalLogic.shouldPost(1_000, 20_999))
        assertTrue(ChatArrivalLogic.shouldPost(1_000, 21_000))
        val share = ChatLiveShare(12, 50, "Zuhause", 0)
        assertFalse(ChatArrivalLogic.expired(share, 3 * 3_600_000L - 1))
        assertTrue(ChatArrivalLogic.expired(share, 3 * 3_600_000L))
        val dest = ChatEventPlace(null, "Zuhause", null, null)
        assertTrue(ChatArrivalLogic.keepGoing(null))
        assertTrue(ChatArrivalLogic.keepGoing(ChatArrival(dest, "live", null, null, null, null, null, null, null)))
        assertFalse(ChatArrivalLogic.keepGoing(ChatArrival(dest, "arrived", null, null, null, null, null, null, null)))
        val decoded = ChatArrivalLogic.decode(ChatArrivalLogic.encode(listOf(share, share.copy(messageId = 51, destLabel = "A|B"))) + "junk")
        assertEquals(setOf(50L, 51L), decoded.map { it.messageId }.toSet())
        assertEquals("A B", decoded.first { it.messageId == 51L }.destLabel)
        val pos = ChatJson14.positionBody(52.5, 13.4, 12.7f, Float.NaN)
        assertEquals(12, pos.get("accuracy").asInt)
        assertFalse(pos.has("speedMps"))
    }

    // ─── actions and cards ──────────────────────────────────────────

    @Test
    fun `action cards of every mode`() {
        val req = msg(
            "action",
            """"action":{"mode":"request","actionId":"gatecontrol-door/garage","label":"Garage öffnen","plugin":"gatecontrol-door",
               "approverIds":[7,8],"state":"open"}""",
        )
        assertEquals(ChatActionCard.MODE_REQUEST, req.action!!.mode)
        assertEquals(listOf(7L, 8L), req.action!!.approverIds)
        roundTrip(req)
        val grant = msg("action", """"action":{"mode":"grant","actionId":"light","label":"Licht","grant":{"toUserId":9,"until":"2026-10-18T20:00:00.000Z"},"state":"done","result":{"message":"ok"},"by":7}""")
        assertEquals(9L, grant.action!!.grantTo)
        assertEquals("ok", grant.action!!.result)
        roundTrip(grant)
        assertNull(msg("action", """"action":{"mode":"explode","actionId":"x"}""").action)
        val send = ChatJson14.actionSendBody(ChatActionCard.MODE_GRANT, "light", 9, "2026-10-18T20:00:00.000Z")
        assertEquals(9, send.getAsJsonObject("grant").get("toUserId").asInt)
        assertFalse(ChatJson14.actionSendBody(ChatActionCard.MODE_RUN, "light", 9, null).has("grant"))
    }

    @Test
    fun `a doorbell card with picture and buttons`() {
        val m = msg(
            "card",
            """"media":{"id":"0123456789abcdef0123456789abcdef","mime":"image/jpeg"},
               "card":{"kind":"doorbell","title":"Klingel · Haustür","text":"Paketbote an der Haustür",
               "actions":["gatecontrol-door/open",{"id":"live","label":"Live-Bild","route":"plg-gatecontrol-door"},{"label":"no target"}]}""",
        )
        val c = m.card!!
        assertEquals("0123456789abcdef0123456789abcdef", c.imageMediaId, "the message's media is the card's picture")
        assertEquals(2, c.buttons.size)
        assertEquals("gatecontrol-door/open", c.buttons[0].actionId)
        assertEquals("plg-gatecontrol-door", c.buttons[1].route)
        roundTrip(m)
    }

    @Test
    fun `unknown kinds are kept and flagged`() {
        val m = msg("sticker", """"text":"🦖"""")
        assertTrue(m.isUnknownKind)
        roundTrip(m)
        assertNull(ChatJson.message(json("""{"id":1,"conversationId":1,"kind":"Bad Kind!","createdAt":"2026-10-18T10:00:00.000Z"}""")))
    }

    @Test
    fun `a story reply and the 1_4 system events`() {
        val m = msg("text", """"text":"Glückwunsch!","replyStory":{"storyId":"s9","ownerId":8,"preview":{"kind":"text","text":"Mathe geschafft"}}""")
        assertEquals("s9", m.replyStory!!.storyId)
        assertEquals("Mathe geschafft", m.replyStory!!.text)
        roundTrip(m)
        val sys = msg("system", """"system":{"event":"poll_closed","messageId":40,"winner":"Grillen"}""")
        assertEquals("Grillen", sys.system!!.label)
        assertEquals(40L, sys.system!!.messageId)
        roundTrip(sys)
        val rsvp = msg("system", """"system":{"event":"rsvp","userId":8,"answer":"yes","guests":1}""")
        assertEquals("yes", rsvp.system!!.answer)
        assertEquals(1, rsvp.system!!.guests)
    }

    @Test
    fun `a tombstone drops the 1_4 objects`() {
        val m = msg("poll", """"poll":{"question":"Q","options":[{"id":"a","text":"A"}]}""")
        val tomb = ChatJson.message(ChatJsonPatch.tombstone(ChatSerializer.message(m), "2026-10-18T11:00:00.000Z"))!!
        assertTrue(tomb.deleted)
        assertNull(tomb.poll)
    }

    // ─── status and stories ─────────────────────────────────────────

    @Test
    fun `the status feed orders unseen first and keeps the own entry`() {
        val feed = ChatJson14.feed(
            json(
                """{"ok":true,"items":[
                  {"user":{"id":8,"name":"Anna"},"status":{"text":"Im Homeoffice","preset":"homeoffice","until":"2026-10-18T15:00:00.000Z","visibility":"all"},"stories":[],"unseen":false},
                  {"user":{"id":9,"name":"Lena"},"status":null,"stories":[
                     {"id":"s1","kind":"text","text":"Mathe-Arbeit geschafft!","bg":"#7C3AED","font":"serif","createdAt":"2026-10-18T09:00:00.000Z","expiresAt":"2026-10-19T09:00:00.000Z","seen":false,"place":"Schule"},
                     {"id":"../x","kind":"text","text":"bad id"},
                     {"id":"s2","kind":"image","mediaId":"0123456789abcdef0123456789abcdef","createdAt":"2026-10-18T09:30:00.000Z","seen":true}]},
                  {"user":{"name":"no id"},"stories":[]}],
                 "me":{"status":{"text":"Unterwegs","preset":"away","eta":"2026-10-18T16:00:00.000Z","destLabel":"Zuhause","visibility":"family"},
                       "stories":[{"id":"m1","kind":"text","text":"Grillen","seen":true,"viewers":3}]}}""",
            ),
        )!!
        assertEquals(listOf(9L, 8L), feed.items.map { it.user.userId }, "unseen first")
        val lena = feed.items[0]
        assertTrue(lena.unseen, "computed from the stories")
        assertEquals(listOf("s1", "s2"), lena.stories.map { it.id }, "path-unsafe ids dropped, oldest first")
        assertEquals("serif", lena.stories[0].font)
        assertEquals(0, ChatStatusLogic.startIndex(lena))
        assertEquals("Zuhause", feed.me!!.status!!.destLabel)
        assertEquals(3, feed.me!!.stories[0].viewerCount)
        val seen = ChatStatusLogic.markSeen(feed, 9, "s1")
        assertFalse(seen.items.first { it.user.userId == 9L }.unseen)
        assertEquals(2, ChatStatusLogic.strip(feed).size)
        // Through the cache (story and status writers).
        val s = lena.stories[0]
        assertEquals(s, ChatJson14.story(ChatJson14.writeStory(s)))
        val st = feed.me!!.status!!
        assertEquals(st, ChatJson14.status(ChatJson14.writeStatus(st)))
    }

    @Test
    fun `status bodies, until choices and whether a status still applies`() {
        val s = ChatStatus("x".repeat(80), ChatStatus.PRESET_AWAY, null, ChatStatus.VIS_CUSTOM, listOf(8, 9), eta = "2026-10-18T16:00:00.000Z", destLabel = "Zuhause")
        val body = ChatJson14.statusBody(s)
        assertEquals(60, body.get("text").asString.length)
        assertEquals(2, body.getAsJsonArray("userIds").size())
        assertEquals("Zuhause", body.get("destLabel").asString)
        assertFalse(ChatJson14.statusBody(s.copy(preset = ChatStatus.PRESET_DND, visibility = ChatStatus.VIS_ALL)).has("userIds"))
        assertNull(ChatJson14.status(json("""{"preset":"none","text":""}""")), "an empty status is none")
        val now = 1_000_000L
        assertEquals(now + 1_800_000, ChatStatusLogic.untilMs(ChatStatusLogic.UNTIL_30M, now, 5_000_000, null))
        assertEquals(5_000_000L, ChatStatusLogic.untilMs(ChatStatusLogic.UNTIL_WORK, now, 5_000_000, now - 1), "work end passed: end of day")
        assertNull(ChatStatusLogic.untilMs(ChatStatusLogic.UNTIL_NEVER, now, 5_000_000, null))
        assertTrue(ChatStatusLogic.active(s.copy(until = null), now))
        assertFalse(ChatStatusLogic.active(s.copy(until = "1970-01-01T00:00:00.000Z"), now))
    }

    @Test
    fun `story bodies and viewers`() {
        val body = ChatJson14.storyBody(ChatStory.KIND_TEXT, "Hallo", "#zzzzzz", "comic", null, null, null, ChatStatus.VIS_FAMILY, listOf(1), 50)
        assertFalse(body.has("bg"), "bad colours are left out")
        assertFalse(body.has("font"))
        assertFalse(body.has("userIds"), "only for custom")
        assertEquals(50, body.get("eventMessageId").asInt)
        assertFalse(body.has("durationH"), "no duration: the server's 24 h")
        // 1.4.1: a chosen duration, only the offered values
        assertEquals(3, ChatJson14.storyBody(ChatStory.KIND_TEXT, "x", null, null, null, null, null, ChatStatus.VIS_ALL, emptyList(), null, 3).get("durationH").asInt)
        assertFalse(ChatJson14.storyBody(ChatStory.KIND_TEXT, "x", null, null, null, null, null, ChatStatus.VIS_ALL, emptyList(), null, 2).has("durationH"))
        val viewers = ChatJson14.viewers(json("""{"viewers":[{"user":{"id":8,"name":"Anna"},"seenAt":"2026-10-18T10:00:00.000Z","reaction":"👏"},{"user":{}}]}"""))!!
        assertEquals(1, viewers.size)
        assertEquals("👏", viewers[0].reaction)
    }

    @Test
    fun `status events carry no conversation`() {
        val e = ChatEvents.parse(MESSENGER_PLUGIN, "story.new", json("""{"userId":8,"storyId":"s1"}"""))
        assertInstanceOf(ChatEvent.StatusChanged::class.java, e)
        assertEquals("s1", (e as ChatEvent.StatusChanged).storyId)
        assertNull(ChatEvents.parse(MESSENGER_PLUGIN, "status.update", json("""{"conversationId":1}""")), "no user, no event")
        val poll = ChatEvents.parse(MESSENGER_PLUGIN, "poll.update", json("""{"conversationId":12,"messageId":50}"""))
        assertEquals(ChatEvent.MessageUpdate(12, 50), poll)
        assertEquals(ChatEvent.MessageUpdate(12, 51), ChatEvents.parse(MESSENGER_PLUGIN, "arrival.update", json("""{"conversationId":12,"messageId":51}""")))
    }

    // ─── preferences ────────────────────────────────────────────────

    @Test
    fun `privacy, notify and chat preferences`() {
        val profile = ChatJson.profile(
            json(
                """{"user":{"id":7,"name":"Marc"},"prefs":{"readReceipts":false,"typing":true,"preview":true,
                   "privacy":{"lastSeen":"family","avatar":"nobody","statusDefault":"custom","placeInStatus":"bogus","groupAdd":"family","defaultDisappearingS":604800},
                   "notify":{"groupsMentionsOnly":true,"reactions":false,"statusDigest":true,"eventReminderMin":15},"chats":{"keepArchived":false}},
                   "limits":{"quotaFull":false,"usage":{"usedBytes":1800000000,"quotaBytes":5000000000}},
                   "status":{"text":"Im Homeoffice","preset":"homeoffice"}}""",
            ),
        )!!
        val p = profile.prefs14
        assertEquals(ChatPrivacy.AUDIENCE_FAMILY, p.privacy.lastSeen)
        assertEquals(ChatPrivacy.AUDIENCE_FAMILY, p.privacy.placeInStatus, "unknown values keep the default")
        assertEquals(604_800, p.privacy.defaultDisappearingS)
        assertTrue(p.notify.groupsMentionsOnly)
        assertEquals(15, p.notify.eventReminderMin)
        assertFalse(p.keepArchived)
        assertEquals(5_000_000_000L, profile.limits.usage!!.quotaBytes)
        assertEquals("Im Homeoffice", profile.status!!.text)
        assertEquals(profile, ChatJson.profile(ChatSerializer.profile(profile)), "the cached profile keeps the 1.4 parts")
        assertEquals(ChatPrefs14(), ChatJson14.prefs14(json("""{"readReceipts":true}""")), "older servers: defaults")
    }

    // ─── tools ──────────────────────────────────────────────────────

    @Test
    fun `lists, reminders, suggestions, places, actions and blocks`() {
        val lists = ChatJson14.lists(json("""{"lists":[{"id":"l1","name":"Arbeit","conversationIds":[12,13]},{"id":"l2"},{"id":3,"name":"Verein","conversationIds":[]}]}"""))!!
        assertEquals(listOf("l1", "3"), lists.map { it.id })
        val body = ChatJson14.listsBody(lists)
        assertEquals(lists, ChatJson14.lists(body))
        val reminders = ChatJson14.reminders(json("""{"reminders":[{"id":"r1","messageId":50,"conversationId":12,"at":"2026-10-19T08:00:00.000Z","preview":"Milch"},{"id":"r2"}]}"""))!!
        assertEquals(1, reminders.size)
        val sug = ChatJson14.suggestion(json("""{"items":["Milch","Brot","Kaffee","Milch"],"dates":[{"startsAt":"2026-10-23T13:00:00.000Z","label":"Do 15:00","title":"Zahnarzt"},{"label":"no time"}]}"""))!!
        assertEquals(listOf("Milch", "Brot", "Kaffee"), sug.items)
        assertEquals("Zahnarzt", sug.dates.single().title)
        assertTrue(ChatSuggestion(emptyList(), emptyList()).isEmpty)
        val places = ChatJson14.places(json("""{"places":[{"id":"home","name":"Zuhause","lat":52.5,"lon":13.4,"radiusM":120},{"id":"x","lat":1}]}"""))!!
        assertEquals(listOf("Zuhause"), places.map { it.name })
        val actions = ChatJson14.homeActions(json("""{"actions":[{"id":"gatecontrol-door/open","plugin":"gatecontrol-door","label":"Haustür öffnen","icon":"M3 3h18v18H3z","needsConfirm":true},{"id":"x"}]}"""))!!
        assertEquals(1, actions.size)
        assertTrue(actions[0].needsConfirm)
        val blocks = ChatJson14.blocks(json("""{"blocks":[{"user":{"id":9,"name":"Tim"}},{"userId":10,"name":"Max"}]}"""))!!
        assertEquals(listOf(9L, 10L), blocks.map { it.userId })
    }

    @Test
    fun `pins, list filters and export names`() {
        assertEquals(listOf(2L, 3L, 4L), ChatToolsLogic.pinAdd(listOf(1, 2, 3), 4), "at most three, the oldest goes")
        assertEquals(listOf(1L, 3L, 2L), ChatToolsLogic.pinAdd(listOf(1, 2, 3), 2), "pinned again: moves to the end")
        val list = ChatList("l1", "Arbeit", listOf(12))
        assertEquals(listOf(12L, 13L), ChatToolsLogic.toggle(list, 13).conversationIds)
        assertEquals(emptyList<Long>(), ChatToolsLogic.toggle(list, 12).conversationIds)
        assertEquals("l5", ChatToolsLogic.newListId(listOf(ChatList("l4", "x", emptyList())), 4))
        assertEquals("Chat-Anna-Lena-2026-10-18.txt", ChatToolsLogic.exportName("Anna & Lena", LocalDate.of(2026, 10, 18)))
        assertEquals("Chat-Chat-2026-10-18.txt", ChatToolsLogic.exportName("!!!", LocalDate.of(2026, 10, 18)))
    }

    @Test
    fun `server shapes of Messenger 1_4`() {
        // An event that asks for no answers still sends the (empty) answers.
        val e = msg("event", """"event":{"title":"T","startsAt":"2026-10-26T11:30:00.000Z","rsvpEnabled":false,"rsvp":{"yes":[],"maybe":[],"no":[],"mine":null,"myGuests":0},"creatorId":7}""")
        assertNull(e.event!!.rsvp)
        assertEquals(7L, e.event!!.creatorId)
        roundTrip(e)
        val withGuests = msg("event", """"event":{"title":"T","startsAt":"2026-10-26T11:30:00.000Z","rsvpEnabled":true,"rsvp":{"yes":[{"userId":7,"guests":2}],"mine":"yes","myGuests":2}}""")
        assertEquals(2, withGuests.event!!.rsvp!!.myGuests)
        roundTrip(withGuests)
        // A card with its picture inline and a link to a screen of the app.
        val card = msg(
            "card",
            """"card":{"kind":"doorbell","title":"Klingel","text":"Paketbote","image":{"mime":"image/jpeg","bytes":4,"data":"/9j/4A=="},
               "link":{"label":"Live-Bild","route":"plg-gatecontrol-door"},
               "actions":[{"plugin":"gatecontrol-door","actionId":"open","label":"Tür öffnen","needsConfirm":true,"canRun":false}],"lastRun":{"by":8,"ok":true}}""",
        ).card!!
        assertEquals("/9j/4A==", card.imageBase64)
        assertEquals("image/jpeg", card.imageMime)
        assertEquals(listOf("open", null), card.buttons.map { it.actionId })
        assertEquals("plg-gatecontrol-door", card.buttons[1].route)
        assertFalse(card.buttons[0].canRun)
        assertTrue(card.buttons[0].needsConfirm)
        assertEquals(8L, card.lastRunBy)
        val action = msg("action", """"action":{"mode":"run","actionId":"open","plugin":"gatecontrol-door","label":"Haustür","state":"open","needsConfirm":true,"result":{"ok":false,"message":"offline"},"can":{"run":true,"approve":false,"deny":false}}""").action!!
        assertTrue(action.canRun)
        assertEquals(false, action.resultOk)
        assertEquals("offline", action.result)
        val send = ChatJson14.actionSendBody(ChatActionCard.MODE_RUN, "123", plugin = "gatecontrol-door")
        assertTrue(send.get("actionId").asJsonPrimitive.isString, "action ids are strings")
        assertEquals("gatecontrol-door", send.get("plugin").asString)
        assertEquals("open", ChatJson14.actionRunBody("open", "gatecontrol-door").get("actionId").asString)
        // Lists keep string ids, even digits.
        assertTrue(ChatJson14.listsBody(listOf(ChatList("7", "Arbeit", listOf(1)))).getAsJsonArray("lists")[0].asJsonObject.get("id").asJsonPrimitive.isString)
        // The export comes in parts.
        val part = ChatJson14.exportPart(json("""{"format":"txt","fileName":"Chat Anna.txt","text":"18.10.26, 10:00 - Anna: Hi\n","count":1,"truncated":true,"before":40}"""))!!
        assertTrue(part.truncated)
        assertEquals(40L, part.before)
        assertTrue(ChatJson14.arrived(json("""{"messageId":50,"accepted":true,"arrived":true}""")))
        assertTrue(ChatArrivalLogic.gone(ChatResult.Failed("closed", 409)))
        assertFalse(ChatArrivalLogic.gone(ChatResult.Offline))
        // poll_closed names the winners and the question; a grant's run names the granter.
        val closed = msg("system", """"system":{"event":"poll_closed","by":null,"messageId":40,"question":"Essen?","winner":null,"winners":["Grillen","Pizza"],"votes":2}""").system!!
        assertEquals("Grillen, Pizza", closed.label)
        assertEquals("Essen?", closed.title)
        val run = msg("system", """"system":{"event":"action_run","userId":9,"messageId":41,"label":"Licht","mode":"grant","asUserId":7}""")
        assertEquals(7L, run.system!!.otherUserId)
        roundTrip(run)
        // The departure hint push names its deep link.
        assertEquals("arrival", ChatNotifyLogic.payload(json("""{"conversationId":12,"messageId":50,"kind":"event_departure","deepLink":"arrival"}"""))!!.open)
        val peer = ChatJson.conversation(json("""{"id":3,"kind":"direct","peer":{"userId":9,"name":"Tim","blocked":true,"online":false,"lastSeenAt":"2026-10-18T09:00:00.000Z"},"can":{"send":false,"pin":false}}"""))!!
        assertTrue(peer.peer!!.blocked)
        assertFalse(peer.can.pin)
        assertEquals(peer, ChatJson.conversation(ChatSerializer.conversation(peer)))
        assertEquals(ChatEvent.AccountChanged("lists.update"), ChatEvents.parse(MESSENGER_PLUGIN, "lists.update", json("{}")))
    }

    @Test
    fun `final README shapes - card picture, run results, Familie integrations`() {
        val card = msg("card", """"card":{"kind":"doorbell","title":"Klingel","text":"Paket","image":{"mime":"image/jpeg","bytes":20480},"link":{"label":"Live-Bild","route":"plg-gatecontrol-door","kind":"live_view"},"actions":[]}""")
        assertTrue(card.card!!.hasImage, "the picture comes from GET messages/:id/card/image")
        assertNull(card.card!!.imageBase64)
        roundTrip(card)
        assertEquals("/9j/", ChatJson14.cardImage(json("""{"mime":"image/jpeg","bytes":3,"data":"/9j/"}""")))
        assertNull(ChatJson14.cardImage(json("""{"data":"not base64!"}""")))
        val run = ChatJson14.runResult(json("""{"ok":true,"message":null,"result":{"ok":false,"message":"Tür offline","state":null,"error":"failed"}}"""))
        assertEquals(false, run.ok)
        assertEquals("Tür offline", run.text)
        val me = ChatJson.profile(json("""{"user":{"id":7},"integrations":{"familie":{"lists":true,"calendar":false,"places":true}}}"""))!!
        assertFalse(me.familie.calendar)
        assertEquals(me, ChatJson.profile(ChatSerializer.profile(me)))
        assertTrue(ChatJson.profile(json("""{"user":{"id":7}}"""))!!.familie.lists, "older servers: assumed")
    }

    // ─── device settings ────────────────────────────────────────────

    @Test
    fun `automatic downloads and send quality`() {
        val c = ChatDeviceConfig(autoWifi = ChatAutoDownload.ALL, autoMobile = ChatAutoDownload.PHOTOS)
        assertTrue(ChatAutoDownload.everything(c, unmetered = true))
        assertFalse(ChatAutoDownload.everything(c, unmetered = false))
        assertTrue(ChatAutoDownload.photos(c, unmetered = false))
        assertFalse(ChatAutoDownload.photos(c.copy(autoMobile = ChatAutoDownload.NONE), unmetered = false))
        assertEquals(2048, ChatQuality.maxSide(ChatQuality.STANDARD, 2048))
        assertEquals(1280, ChatQuality.maxSide(ChatQuality.SAVER, 2048))
        assertEquals(1000, ChatQuality.maxSide(ChatQuality.SAVER, 1000))
        assertNull(ChatQuality.maxSide(ChatQuality.ORIGINAL, 2048))
        assertEquals(1.3f, ChatLook.fontScale(ChatLook.FONT_XLARGE))
    }

    // ─── deep links ─────────────────────────────────────────────────

    @Test
    fun `a push names its message and what to open`() {
        val p = ChatNotifyLogic.payload(json("""{"conversationId":"12","messageId":50,"open":"arrival","conversation":{"id":"12","isGroup":true}}"""))!!
        assertEquals(ChatNotifyLogic.OPEN_ARRIVAL, p.open)
        assertNull(ChatNotifyLogic.payload(json("""{"conversationId":"12","open":"rm -rf"}"""))!!.open)
        val line = ChatNotifLine(1, 1, "8", "Anna", null, "Abstimmung", 0)
        val c = ChatNotifyLogic.add(null, p, line)
        assertEquals(50L, c.messageId)
        val back = ChatNotifyLogic.fromJson(ChatNotifyLogic.toJson(mapOf(12L to c)))[12L]!!
        assertEquals(50L, back.messageId)
        assertEquals("arrival", back.open)
        ChatDeepLink.request(12, 50, null)
        assertNull(ChatDeepLink.take(13), "another conversation does not take it")
        assertEquals(50L, ChatDeepLink.take(12)!!.messageId)
        assertNull(ChatDeepLink.take(12), "once")
    }
}
