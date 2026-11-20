package com.gatecontrol.android.vault

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Vault's pure parts: 2FA codes, generator, matching, import, the server JSON, the device's copy. */
class VaultLogicTest {

    // ─── TOTP (RFC 6238 appendix B) ──────────────────────────────────

    private fun b32(ascii: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val bits = ascii.toByteArray().joinToString("") { String.format("%8s", Integer.toBinaryString(it.toInt() and 0xff)).replace(' ', '0') }
        return bits.chunked(5).joinToString("") { alphabet[it.padEnd(5, '0').toInt(2)].toString() }
    }

    @Test
    fun `totp - RFC 6238 vectors for SHA1, SHA256, SHA512`() {
        val s1 = b32("12345678901234567890")
        val s256 = b32("12345678901234567890123456789012")
        val s512 = b32("1234567890123456789012345678901234567890123456789012345678901234")
        val cases = listOf(59L to listOf("94287082", "46119246", "90693936"), 1111111109L to listOf("07081804", "68084774", "25091201"))
        for ((t, want) in cases) {
            assertEquals(want[0], VaultTotp.code(VaultTotp.parse("otpauth://totp/x?secret=$s1&digits=8")!!, t * 1000).code)
            assertEquals(want[1], VaultTotp.code(VaultTotp.parse("otpauth://totp/x?secret=$s256&digits=8&algorithm=SHA256")!!, t * 1000).code)
            assertEquals(want[2], VaultTotp.code(VaultTotp.parse("otpauth://totp/x?secret=$s512&digits=8&algorithm=SHA512")!!, t * 1000).code)
        }
        val plain = VaultTotp.parse(s1.lowercase().chunked(4).joinToString(" "))!!
        assertEquals(6, plain.digits)
        assertEquals("287082", VaultTotp.code(plain, 59_000).code)
        assertEquals(1, VaultTotp.code(plain, 59_000).secondsLeft)
        assertEquals("482 913", VaultTotp.pretty("482913"))
        assertNull(VaultTotp.parse("otpauth://hotp/x?secret=$s1"))
        assertNull(VaultTotp.parse("not base32 !"))
        assertEquals("Sparkasse", VaultTotp.label("otpauth://totp/Sparkasse:marc?secret=$s1&issuer=Sparkasse"))
    }

    // ─── generator ──────────────────────────────────────────────────

    @Test
    fun `generator - length, classes, look-alikes, words`() {
        repeat(50) {
            val p = VaultGenerator.generate(VaultGenerator.Options(length = 20))
            assertEquals(20, p.length)
            assertTrue(p.any { it.isUpperCase() } && p.any { it.isLowerCase() } && p.any { it.isDigit() } && p.any { !it.isLetterOrDigit() }, p)
            val q = VaultGenerator.generate(VaultGenerator.Options(length = 12, symbols = false, avoidAmbiguous = true))
            assertTrue(q.none { it in "l1IO0" } && q.all { it.isLetterOrDigit() }, q)
        }
        val w = VaultGenerator.generate(VaultGenerator.Options(words = true, wordCount = 4))
        assertEquals(4, w.split('-').size)
        assertTrue(w.any { it.isDigit() })
        assertEquals(4, VaultGenerator.wordCount("Sonnenblume-Kanu-Turm-47"))
        assertEquals(VaultGenerator.Strength.WEAK, VaultGenerator.strength("aaaaaaaaaaaaaaaa"))
        assertEquals(VaultGenerator.Strength.WEAK, VaultGenerator.strength("Passw0rd!"))
        assertTrue(VaultGenerator.goodMasterPassword("Sonnenblume-Kanu-Turm-47"))
        assertFalse(VaultGenerator.goodMasterPassword("kurz"))
    }

    // ─── matching ───────────────────────────────────────────────────

    private fun login(title: String, urls: List<String> = emptyList(), apps: List<String> = emptyList(), fav: Boolean = false, trashed: Boolean = false, match: String? = null) =
        Entry(title, "v", 1, trashed, null, null, null, null, EntryData("login", title, fav = fav, urls = urls, apps = apps, fields = mapOf("username" to "u"), match = match))

    @Test
    fun `matching - same site by domain, apps by package, favourites first`() {
        assertEquals("login.example.co.uk", VaultMatch.host("https://www.Login.Example.co.uk:8443/x?y"))
        assertEquals("example.co.uk", VaultMatch.site("login.example.co.uk"))
        assertEquals("sparkasse.de", VaultMatch.site("banking.sparkasse.de"))
        assertEquals("192.168.1.1", VaultMatch.site("192.168.1.1"))
        val list = listOf(
            login("Sparkasse", urls = listOf("sparkasse.de")),
            login("Sparkasse Fav", urls = listOf("https://www.sparkasse.de"), fav = true),
            login("Stadtwerke", apps = listOf("de.stadtwerke.app")),
            login("Alt", urls = listOf("sparkasse.de"), trashed = true),
            login("Evil", urls = listOf("sparkasse.de.evil.com")),
        )
        assertEquals(listOf("Sparkasse Fav", "Sparkasse"), VaultMatch.matches(list, "com.android.chrome", "banking.sparkasse.de").map { it.data.title })
        assertEquals(listOf("Stadtwerke"), VaultMatch.matches(list, "de.stadtwerke.app", null).map { it.data.title })
        assertTrue(VaultMatch.matches(list, null, null).isEmpty())
    }

    @Test
    fun `matching - the exact host keeps case, port and trailing dot out, but not www`() {
        assertEquals("www.login.example.co.uk", VaultMatch.exactHost("https://www.Login.Example.co.uk:8443/x?y"))
        assertEquals("nas.example.test", VaultMatch.exactHost("NAS.example.test.:5001"))
        assertEquals("nas.example.test", VaultMatch.host("https://www.nas.example.test/"))
        assertNull(VaultMatch.exactHost("  "))
        val exact = EntryData("login", "NAS", urls = listOf("https://nas.example.test:5001/login"), match = "exact")
        assertTrue(VaultMatch.fitsDomain(exact, "NAS.Example.Test."))
        assertTrue(VaultMatch.fitsDomain(exact, "https://nas.example.test:8443/"))
        assertFalse(VaultMatch.fitsDomain(exact, "example.test"))
        assertFalse(VaultMatch.fitsDomain(exact, "cloud.example.test"))
        assertFalse(VaultMatch.fitsDomain(exact, "www.nas.example.test"), "www. is not the same host")
        // the default (no match, "domain", any other value): the whole registrable domain
        for (m in listOf(null, "domain", "subdomain")) {
            val d = EntryData("login", "Domain", urls = listOf("https://nas.example.test"), match = m)
            assertTrue(VaultMatch.fitsDomain(d, "cloud.example.test"), "match=$m")
            assertTrue(VaultMatch.fitsDomain(d, "www.nas.example.test"), "match=$m")
        }
    }

    @Test
    fun `matching - several services under one domain - exact host first, exact entries only on their host`() {
        val list = listOf(
            login("Alpha Domain", urls = listOf("https://example.test")),
            login("NAS exact", urls = listOf("https://nas.example.test"), match = "exact"),
            login("Cloud exact", urls = listOf("https://cloud.example.test"), match = "exact"),
            login("NAS Domain", urls = listOf("https://nas.example.test:5001")),
            login("Fav Domain", urls = listOf("https://mail.example.test"), fav = true),
            login("Other", urls = listOf("https://other.test")),
        )
        // exact host (exact entries and domain entries with that host) before the domain; favourites first in each group
        assertEquals(
            listOf("NAS Domain", "NAS exact", "Fav Domain", "Alpha Domain"),
            VaultMatch.matches(list, "com.android.chrome", "nas.example.test").map { it.data.title },
        )
        assertEquals(listOf("Cloud exact", "Fav Domain", "Alpha Domain", "NAS Domain"), VaultMatch.matches(list, "com.android.chrome", "cloud.example.test").map { it.data.title })
        // the bare domain: no exact entry, the domain entry with that host first
        assertEquals(listOf("Alpha Domain", "Fav Domain", "NAS Domain"), VaultMatch.matches(list, "com.android.chrome", "example.test").map { it.data.title })
        // apps are unchanged
        assertEquals(listOf("App"), VaultMatch.matches(listOf(login("App", apps = listOf("de.app"), match = "exact")), "de.app", null).map { it.data.title })
    }

    @Test
    fun `entry - match is read and written, only exact, other values kept, unknown keys kept`() {
        val base = """{"v":1,"type":"login","title":"NAS","urls":["https://nas.example.test"],"fields":{},"later":{"x":1}"""
        val exact = EntryData.fromJson("""$base,"match":"exact"}""")!!
        assertEquals("exact", exact.match)
        assertTrue(exact.exactMatch)
        val out = JsonParser.parseString(exact.toJson()).asJsonObject
        assertEquals("exact", out.get("match").asString)
        assertEquals(1, out.getAsJsonObject("later").get("x").asInt)
        assertFalse(exact.extra.has("match"))
        // back to the domain: the key goes, unknown keys stay
        val back = JsonParser.parseString(exact.copy(match = null).toJson()).asJsonObject
        assertFalse(back.has("match"))
        assertTrue(back.has("later"))
        // missing or "domain": the default, nothing written
        assertNull(EntryData.fromJson("$base}")!!.match)
        val domain = EntryData.fromJson("""$base,"match":"domain"}""")!!
        assertNull(domain.match)
        assertFalse(domain.exactMatch)
        assertFalse(JsonParser.parseString(domain.toJson()).asJsonObject.has("match"))
        assertFalse(JsonParser.parseString(EntryData("login", "Neu", urls = listOf("https://example.test")).toJson()).asJsonObject.has("match"))
        // another client's value: counts as the domain, saved unchanged
        val other = EntryData.fromJson("""$base,"match":"prefix"}""")!!
        assertFalse(other.exactMatch)
        assertEquals("prefix", JsonParser.parseString(other.toJson()).asJsonObject.get("match").asString)
        // not a string: an unknown key, kept as it is
        val odd = EntryData.fromJson("""$base,"match":{"mode":2}}""")!!
        assertNull(odd.match)
        assertEquals(2, JsonParser.parseString(odd.toJson()).asJsonObject.getAsJsonObject("match").get("mode").asInt)
    }

    // ─── import ─────────────────────────────────────────────────────

    @Test
    fun `import - Bitwarden uri match 1 or 3 on all web addresses gives exact, else the default`() {
        fun item(name: String, uris: String) = """{"type":1,"name":"$name","login":{"username":"u","password":"p","uris":[$uris]}}"""
        val json = """{"encrypted":false,"items":[
            ${item("Host", "{\"uri\":\"https://nas.example.test\",\"match\":1}")},
            ${item("Exakt", "{\"uri\":\"https://nas.example.test/login\",\"match\":3},{\"uri\":\"https://cloud.example.test\",\"match\":1},{\"uri\":\"androidapp://test.example.app\"}")},
            ${item("Domain", "{\"uri\":\"https://nas.example.test\",\"match\":0}")},
            ${item("Null", "{\"uri\":\"https://nas.example.test\",\"match\":null}")},
            ${item("Ohne", "{\"uri\":\"https://nas.example.test\"}")},
            ${item("Gemischt", "{\"uri\":\"https://nas.example.test\",\"match\":1},{\"uri\":\"https://example.test\",\"match\":0}")},
            ${item("Nur App", "{\"uri\":\"androidapp://test.example.app\",\"match\":1}")}]}"""
        val r = (VaultImport.parse(json) as VaultImport.Outcome.Ok).result
        assertEquals(
            listOf("Host" to "exact", "Exakt" to "exact", "Domain" to null, "Null" to null, "Ohne" to null, "Gemischt" to null, "Nur App" to null),
            r.entries.map { it.title to it.match },
        )
        assertEquals(listOf("test.example.app"), r.entries[1].apps)
    }

    @Test
    fun `import - Bitwarden JSON`() {
        val json = """{"encrypted":false,"items":[
            {"type":1,"name":"GitHub","favorite":true,"notes":"n","login":{"username":"me","password":"pw","totp":"JBSWY3DPEHPK3PXP","uris":[{"uri":"https://github.com"},{"uri":"androidapp://com.github.android"}]},
             "fields":[{"name":"PIN","value":"1234","type":1}]},
            {"type":2,"name":"Notiz","notes":"Text"},
            {"type":3,"name":"Visa","card":{"cardholderName":"M B","number":"4111","expMonth":"3","expYear":"2029","code":"123"}},
            {"type":5,"name":"SSH"}]}"""
        val r = (VaultImport.parse(json) as VaultImport.Outcome.Ok).result
        assertEquals(3, r.entries.size)
        val gh = r.entries[0]
        assertEquals(listOf("com.github.android"), gh.apps)
        assertEquals("JBSWY3DPEHPK3PXP", gh.totp)
        assertTrue(gh.fav && gh.custom.single().secret)
        assertEquals("03/29", r.entries[2].field("expiry"))
        assertEquals(1, r.withTotp)
        assertEquals(VaultImport.Outcome.Encrypted, VaultImport.parse("""{"encrypted":true,"data":"x"}"""))
    }

    @Test
    fun `import - CSV of Chrome, quoted cells, duplicates`() {
        val csv = "name,url,username,password,note\n" +
            "Bank,https://bank.de,anna,\"p,w\"\"x\",\"zwei\nZeilen\"\n" +
            ",https://shop.example.com/login,bob,secret,\n"
        val r = (VaultImport.parse(csv) as VaultImport.Outcome.Ok).result
        assertEquals("Chrome / Edge", r.source)
        assertEquals(2, r.entries.size)
        assertEquals("p,w\"x", r.entries[0].password)
        assertEquals("zwei\nZeilen", r.entries[0].notes)
        assertEquals("shop.example.com", r.entries[1].title)
        val (keep, dupes) = VaultImport.withoutDuplicates(r.entries, listOf(r.entries[0].copy(fav = true)))
        assertEquals(1, dupes)
        assertEquals(listOf("shop.example.com"), keep.map { it.title })
        assertEquals(VaultImport.Outcome.Unknown, VaultImport.parse("just,some\ncsv,file"))
    }

    // ─── entry JSON ─────────────────────────────────────────────────

    @Test
    fun `entry JSON round trip keeps unknown keys`() {
        val json = """{"v":1,"type":"login","title":"X","fields":{"username":"u","future":"f"},"urls":["a.de"],"later":{"x":1}}"""
        val e = EntryData.fromJson(json)!!
        assertEquals("u", e.username)
        val again = JsonParser.parseString(EntryData.fromJson(e.toJson())!!.toJson()).asJsonObject
        assertEquals(1, again.getAsJsonObject("later").get("x").asInt)
        assertEquals("f", again.getAsJsonObject("fields").get("future").asString)
        assertNull(EntryData.fromJson("""{"v":2,"type":"login"}"""))
        assertNull(EntryData.fromJson("""{"v":1,"type":"spaceship"}"""))
    }

    // ─── server JSON and the device's copy ─────────────────────────

    private val vid = "Vt9bJm0n2QwErTyUiOpAsD"
    private fun vaultJson(id: String = vid) = """{"id":"$id","kind":"shared","ownerId":2,"nameEnc":"AAAA","role":"write","keyRev":1,"sealedKey":"BBBB","pending":null,"needsRekey":false,"members":[{"userId":2,"name":"Anna","role":"manage"}]}"""
    private fun itemJson(id: String, seq: Long, state: String = "live", v: String = vid) =
        """{"id":"$id","vaultId":"$v","rev":2,"keyRev":1,"blob":${if (state == "purged") "null" else "\"CCCC\""},"state":"$state","seq":$seq}"""

    private fun page(items: String, vaults: String = vaultJson(), more: Boolean = false, next: Long = 9) =
        VaultJson.sync(JsonParser.parseString("""{"ok":true,"seq":9,"account":null,"vaults":[$vaults],"items":[$items],"more":$more,"next":$next}""").asJsonObject)!!

    @Test
    fun `sync pages merge, tombstones drop entries and unsent changes, gone vaults go`() {
        val a = "Aa1111111111111111111a"
        val b = "Bb2222222222222222222b"
        var s = VaultState().merge(page("${itemJson(a, 3)},${itemJson(b, 4)}"), 1)
        assertEquals(setOf(a, b), s.items.keys)
        s = s.queue(VaultJson.SaveItem(b, vid, "DDDD", 1, 2))
        s = s.merge(page(itemJson(b, 7, "purged")), 2)
        assertEquals(setOf(a), s.items.keys)
        assertTrue(s.outbox.isEmpty())
        s = s.merge(page("", vaults = vaultJson("Other0000000000000000x")), 3)
        assertTrue(s.items.isEmpty(), "a vault missing from the list is gone with its entries")
    }

    @Test
    fun `outbox keeps the first base revision when an entry changes twice offline`() {
        val id = "Aa1111111111111111111a"
        val s = VaultState().queue(VaultJson.SaveItem(id, vid, "AAAA", 1, 2)).queue(VaultJson.SaveItem(id, vid, "BBBB", 1, 3))
        assertEquals(1, s.outbox.size)
        assertEquals(2, s.outbox.single().baseRev)
        assertEquals("BBBB", s.outbox.single().blob)
    }

    @Test
    fun `the copy round-trips through JSON and belongs to one login`() {
        val s = VaultState(known = true, seq = 9).merge(page(itemJson("Aa1111111111111111111a", 3)), 5).queue(VaultJson.SaveItem("Bb2222222222222222222b", vid, "EEEE", 1, 0))
        val back = VaultState.fromJson(s.toJson("owner1"), "owner1")!!
        assertEquals(s.items, back.items)
        assertEquals(s.vaults, back.vaults)
        assertEquals(s.outbox, back.outbox)
        assertNull(VaultState.fromJson(s.toJson("owner1"), "owner2"))
    }

    @Test
    fun `server JSON - invalid shapes are rejected`() {
        assertNull(VaultJson.vault(JsonParser.parseString("""{"id":"short","kind":"shared"}""").asJsonObject))
        assertNull(VaultJson.item(JsonParser.parseString("""{"id":"Aa1111111111111111111a","vaultId":"$vid","rev":1,"keyRev":1,"state":"live","seq":1}""").asJsonObject), "a live entry needs a blob")
        assertNotNull(VaultJson.item(JsonParser.parseString(itemJson("Aa1111111111111111111a", 1, "purged")).asJsonObject))
        val acc = JsonParser.parseString("""{"ok":true,"account":null}""").asJsonObject
        assertEquals(true to null, VaultJson.accountAnswer(acc))
        assertNull(VaultJson.accountAnswer(JsonParser.parseString("""{"ok":true}""").asJsonObject))
        val results = VaultJson.saveResults(JsonParser.parseString(
            """{"ok":true,"results":[{"id":"x","ok":true,"rev":3,"seq":10},{"id":"y","ok":false,"code":"conflict","current":${itemJson("Aa1111111111111111111a", 4)}}]}""",
        ).asJsonObject)!!
        assertEquals(3, results[0].rev)
        assertEquals("conflict", results[1].code)
        assertEquals(4L, results[1].current!!.seq)
    }
}
