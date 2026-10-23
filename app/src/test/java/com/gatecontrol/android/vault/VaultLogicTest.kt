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

    private fun login(title: String, urls: List<String> = emptyList(), apps: List<String> = emptyList(), fav: Boolean = false, trashed: Boolean = false) =
        Entry(title, "v", 1, trashed, null, null, null, null, EntryData("login", title, fav = fav, urls = urls, apps = apps, fields = mapOf("username" to "u")))

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

    // ─── import ─────────────────────────────────────────────────────

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
