package com.gatecontrol.android.vault

import android.text.InputType
import com.gatecontrol.android.vault.autofill.FieldDesc
import com.gatecontrol.android.vault.autofill.VaultAutofillRules
import com.gatecontrol.android.vault.autofill.VaultBrowsers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Which fields of a login form Vault fills (autofill, mockup A7). */
class VaultAutofillRulesTest {
    private val text = InputType.TYPE_CLASS_TEXT
    private val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    private val email = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS

    @Test
    fun `native app - input types decide`() {
        val f = listOf(FieldDesc(inputType = text, idEntry = "search"), FieldDesc(inputType = email), FieldDesc(inputType = password))
        assertEquals(1 to listOf(2), VaultAutofillRules.classify(f))
    }

    @Test
    fun `autofill hints win over everything`() {
        val f = listOf(FieldDesc(hints = listOf("username")), FieldDesc(hints = listOf("password")))
        assertEquals(0 to listOf(1), VaultAutofillRules.classify(f))
    }

    @Test
    fun `web page - html type and autocomplete, German field names`() {
        val f = listOf(
            FieldDesc(htmlType = "text", htmlName = "suche"),
            FieldDesc(htmlType = "text", htmlName = "benutzername"),
            FieldDesc(htmlType = "password", htmlAutocomplete = "current-password"),
        )
        assertEquals(1 to listOf(2), VaultAutofillRules.classify(f))
    }

    @Test
    fun `the text field before the password is the user name when nothing else tells`() {
        val f = listOf(FieldDesc(inputType = text, idEntry = "field1"), FieldDesc(inputType = text, idEntry = "field2"), FieldDesc(inputType = password))
        assertEquals(1 to listOf(2), VaultAutofillRules.classify(f))
    }

    @Test
    fun `sign-up form - both password fields, no login form at all - nothing`() {
        val signUp = listOf(FieldDesc(inputType = email), FieldDesc(htmlType = "password", htmlName = "new"), FieldDesc(htmlType = "password", htmlName = "repeat"))
        assertEquals(0 to listOf(1, 2), VaultAutofillRules.classify(signUp))
        val search = listOf(FieldDesc(inputType = text, idEntry = "search_box"))
        assertEquals(null to emptyList<Int>(), VaultAutofillRules.classify(search))
    }

    @Test
    fun `a one-time code field is no password`() {
        val f = listOf(FieldDesc(inputType = text, hint = "SMS-Code"), FieldDesc(inputType = text, idEntry = "otp_code"))
        assertEquals(null to emptyList<Int>(), VaultAutofillRules.classify(f))
    }

    // ─── browsers in Android's compatibility mode (Edge, Samsung Internet, …) ───

    @Test
    fun `the address bar gives the domain only when it is clearly an address`() {
        assertEquals("login.sparkasse.de", VaultBrowsers.domainFromAddressBar("login.sparkasse.de/de/home.html"))
        assertEquals("accounts.example.com", VaultBrowsers.domainFromAddressBar("https://accounts.example.com/signin?x=1"))
        assertEquals("example.com", VaultBrowsers.domainFromAddressBar("  http://www.Example.com:8080/  "))
        assertEquals("192.168.1.1", VaultBrowsers.domainFromAddressBar("192.168.1.1:8443/login"))
        // a search, a page title, the browser's own pages, scripts, files, user info
        assertNull(VaultBrowsers.domainFromAddressBar("sparkasse login"))
        assertNull(VaultBrowsers.domainFromAddressBar("Anmelden – Sparkasse"))
        assertNull(VaultBrowsers.domainFromAddressBar("edge://settings"))
        assertNull(VaultBrowsers.domainFromAddressBar("chrome://newtab"))
        assertNull(VaultBrowsers.domainFromAddressBar("about:blank"))
        assertNull(VaultBrowsers.domainFromAddressBar("javascript:alert(1)"))
        assertNull(VaultBrowsers.domainFromAddressBar("file:///sdcard/a.html"))
        assertNull(VaultBrowsers.domainFromAddressBar("data:text/html,x"))
        assertNull(VaultBrowsers.domainFromAddressBar("https://sparkasse.de@evil.example/"))
        assertNull(VaultBrowsers.domainFromAddressBar("localhost"))
        assertNull(VaultBrowsers.domainFromAddressBar("1.2.3"))
        assertNull(VaultBrowsers.domainFromAddressBar("bad_host.example"))
        assertNull(VaultBrowsers.domainFromAddressBar(""))
        assertNull(VaultBrowsers.domainFromAddressBar(null))
    }

    @Test
    fun `the page's own domain wins, the address bar only for known browsers`() {
        assertEquals("shop.example.de", VaultBrowsers.pageDomain("com.microsoft.emmx", "shop.example.de", "evil.example"))
        assertEquals("bank.example.de", VaultBrowsers.pageDomain("com.microsoft.emmx", null, "bank.example.de/login"))
        assertEquals("bank.example.de", VaultBrowsers.pageDomain("com.sec.android.app.sbrowser", null, "https://bank.example.de"))
        // an app is no browser: a text field of it never names a page
        assertNull(VaultBrowsers.pageDomain("de.some.app", null, "bank.example.de"))
        assertNull(VaultBrowsers.pageDomain("com.microsoft.emmx", null, "bank login"))
        assertNull(VaultBrowsers.pageDomain("com.microsoft.emmx", null, null))
    }

    @Test
    fun `a browser is never remembered as the app of a login`() {
        assertTrue(VaultBrowsers.isBrowser("com.microsoft.emmx"))
        assertTrue(VaultBrowsers.isBrowser("com.android.chrome"))
        assertTrue(VaultBrowsers.isBrowser("org.mozilla.firefox"))
        assertTrue(VaultBrowsers.isBrowser("com.opera.browser"))
        assertFalse(VaultBrowsers.isBrowser("de.stadtwerke.app"))
        assertEquals(listOf("de.stadtwerke.app"), VaultAutofillRules.appsToRemember("de.stadtwerke.app", null, web = false))
        assertTrue(VaultAutofillRules.appsToRemember("com.microsoft.emmx", null, web = false).isEmpty())
        assertTrue(VaultAutofillRules.appsToRemember("de.some.app", null, web = true).isEmpty())
        assertTrue(VaultAutofillRules.appsToRemember("de.some.app", "example.com", web = true).isEmpty())
        assertTrue(VaultAutofillRules.appsToRemember("", null, web = false).isEmpty())
    }

    @Test
    fun `a web page without a known domain fits nothing, not even by the browser's package`() {
        fun login(title: String, urls: List<String> = emptyList(), apps: List<String> = emptyList()) =
            Entry(title, "v", 1, false, null, null, null, null, EntryData("login", title, urls = urls, apps = apps, fields = mapOf("password" to "pw")))
        val list = listOf(login("Bank", urls = listOf("https://bank.example.de")), login("Edge remembered", apps = listOf("com.microsoft.emmx")))
        assertEquals(listOf("Bank"), VaultMatch.matches(list, "com.microsoft.emmx", "login.bank.example.de", web = true).map { it.data.title })
        assertTrue(VaultMatch.matches(list, "com.microsoft.emmx", null, web = true).isEmpty())
        // a browser stays a browser even when the form does not say "web"
        assertTrue(VaultMatch.matches(list, "com.microsoft.emmx", null).isEmpty())
        // a web view of an app without its domain
        assertTrue(VaultMatch.matches(listOf(login("App", apps = listOf("de.app"))), "de.app", null, web = true).isEmpty())
        assertEquals(listOf("App"), VaultMatch.matches(listOf(login("App", apps = listOf("de.app"))), "de.app", null).map { it.data.title })
    }

    @Test
    fun `every browser of the compatibility list has its address bar`() {
        val file = listOf(File("src/main/res/xml/vault_autofill_service.xml"), File("app/src/main/res/xml/vault_autofill_service.xml")).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("compatibility-package")
        val ns = "http://schemas.android.com/apk/res/android"
        val packages = (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(ns, "name") }
        assertTrue(packages.size >= 20)
        assertTrue("com.microsoft.emmx" in packages)
        for (p in packages) {
            assertTrue(VaultBrowsers.urlBarIds(p).isNotEmpty(), "$p has no address bar id")
            val max = (nodes.item(packages.indexOf(p)) as Element).getAttributeNS(ns, "maxLongVersionCode")
            assertTrue(max.toLongOrNull() != null, "$p needs maxLongVersionCode")
        }
        // browsers with their own autofill stay out of the mode (they work without it)
        assertFalse("org.mozilla.firefox" in packages)
        assertFalse("com.opera.browser" in packages)
    }

    // ─── what Chromium reports itself (Chrome 135+ / Edge 147+ with another service) ───

    @Test
    fun `a reported page counts only for http and https`() {
        assertEquals("login.bank.example.de", VaultBrowsers.reportedDomain("login.bank.example.de", "https"))
        assertEquals("bank.example.de", VaultBrowsers.reportedDomain("www.bank.example.de:8443", "HTTPS"))
        assertEquals("intranet.example", VaultBrowsers.reportedDomain("intranet.example", "http"))
        // older browsers report no scheme
        assertEquals("bank.example.de", VaultBrowsers.reportedDomain("bank.example.de", null))
        assertNull(VaultBrowsers.reportedDomain("settings", "chrome"))
        assertNull(VaultBrowsers.reportedDomain("settings", "edge"))
        assertNull(VaultBrowsers.reportedDomain("bank.example.de", "file"))
        assertNull(VaultBrowsers.reportedDomain("bank.example.de", "blob"))
        assertNull(VaultBrowsers.reportedDomain("  ", "https"))
        assertNull(VaultBrowsers.reportedDomain(null, "https"))
    }

    @Test
    fun `fields of another page are never filled`() {
        // user name and password on the same page
        assertEquals(VaultBrowsers.Pick("bank.de", 0, listOf(1)), VaultBrowsers.pickPage(listOf("bank.de", "bank.de"), 0, listOf(1)))
        // the password sits in the login frame of another site: that site decides, the page's own field is left out
        assertEquals(VaultBrowsers.Pick("login.idp.example", null, listOf(1)), VaultBrowsers.pickPage(listOf("shop.example", "login.idp.example"), 0, listOf(1)))
        // a second password field in an ad frame stays out
        assertEquals(VaultBrowsers.Pick("bank.de", 0, listOf(1)), VaultBrowsers.pickPage(listOf("bank.de", "bank.de", "ads.example"), 0, listOf(1, 2)))
        // only a user name (first step of a two-step login)
        assertEquals(VaultBrowsers.Pick("bank.de", 0, emptyList()), VaultBrowsers.pickPage(listOf("bank.de"), 0, emptyList()))
        // compatibility mode: no page anywhere, all fields stay (the address bar names the page)
        assertEquals(VaultBrowsers.Pick(null, 0, listOf(1)), VaultBrowsers.pickPage(listOf(null, null), 0, listOf(1)))
        assertEquals(VaultBrowsers.Pick(null, null, emptyList()), VaultBrowsers.pickPage(emptyList(), null, emptyList()))
    }

    // ─── the hint in Vault's settings ───

    private fun b(pkg: String, code: Long = 1, name: String? = null, state: Int? = null) = VaultBrowsers.Installed(pkg, code, name, state)

    @Test
    fun `the browser's own answer decides whether the hint shows`() {
        assertEquals(VaultBrowsers.Switch.CHROME, VaultBrowsers.needsSwitch(b("com.android.chrome", state = 0)))
        assertNull(VaultBrowsers.needsSwitch(b("com.android.chrome", 800_000_000, state = 1)))
        assertEquals(VaultBrowsers.Switch.EDGE, VaultBrowsers.needsSwitch(b("com.microsoft.emmx", name = "140.0", state = 0)))
        assertNull(VaultBrowsers.needsSwitch(b("com.microsoft.emmx", name = "150.0", state = 1)))
        // not one of the browsers with a switch: never
        assertNull(VaultBrowsers.needsSwitch(b("org.mozilla.firefox", state = 0)))
        assertNull(VaultBrowsers.needsSwitch(b("com.sec.android.app.sbrowser", 999_999_999_999)))
    }

    @Test
    fun `without an answer the version decides`() {
        // Chrome above the compatibility mode's last version needs the switch, older Chrome works as it is
        assertEquals(VaultBrowsers.Switch.CHROME, VaultBrowsers.needsSwitch(b("com.android.chrome", VaultBrowsers.CHROME_COMPAT_MAX + 1)))
        assertNull(VaultBrowsers.needsSwitch(b("com.android.chrome", VaultBrowsers.CHROME_COMPAT_MAX)))
        assertEquals(VaultBrowsers.Switch.BRAVE, VaultBrowsers.needsSwitch(b("com.brave.browser", VaultBrowsers.BRAVE_COMPAT_MAX + 1)))
        assertNull(VaultBrowsers.needsSwitch(b("com.brave.browser_beta", Long.MAX_VALUE)))
        // Edge from 147 (its switch), before that the compatibility mode
        assertEquals(VaultBrowsers.Switch.EDGE, VaultBrowsers.needsSwitch(b("com.microsoft.emmx", name = "147.0.3179.54")))
        assertEquals(VaultBrowsers.Switch.EDGE, VaultBrowsers.needsSwitch(b("com.microsoft.emmx.beta", name = "151.0.1.2")))
        assertNull(VaultBrowsers.needsSwitch(b("com.microsoft.emmx", name = "146.0.3856.62")))
        assertNull(VaultBrowsers.needsSwitch(b("com.microsoft.emmx", name = null)))
        assertNull(VaultBrowsers.needsSwitch(b("com.vivaldi.browser", Long.MAX_VALUE)))
    }

    @Test
    fun `one hint per browser, in a fixed order`() {
        val hints = VaultBrowsers.hints(
            listOf(
                b("com.microsoft.emmx", name = "148.0"),
                b("com.android.chrome", state = 0),
                b("com.chrome.beta", state = 0),
                b("org.mozilla.firefox"),
                b("com.brave.browser", state = 1),
            ),
        )
        assertEquals(listOf(VaultBrowsers.Switch.CHROME to "com.android.chrome", VaultBrowsers.Switch.EDGE to "com.microsoft.emmx"), hints)
        assertTrue(VaultBrowsers.hints(listOf(b("org.mozilla.firefox"), b("com.opera.browser"))).isEmpty())
        assertEquals(147, VaultBrowsers.majorVersion("147.0.3179.54"))
        assertNull(VaultBrowsers.majorVersion("beta"))
        assertNull(VaultBrowsers.majorVersion(""))
        assertEquals("content://com.microsoft.emmx.AutofillThirdPartyModeContentProvider/autofill_third_party_mode", VaultBrowsers.thirdPartyModeUri("com.microsoft.emmx"))
    }

    @Test
    fun `the compatibility caps and the manifest's queries match the hint`() {
        fun xml(path: String) = listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.exists() }
        val ns = "http://schemas.android.com/apk/res/android"
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(xml("res/xml/vault_autofill_service.xml"))
        val nodes = doc.getElementsByTagName("compatibility-package")
        val caps = (0 until nodes.length).associate { (nodes.item(it) as Element).let { e -> e.getAttributeNS(ns, "name") to e.getAttributeNS(ns, "maxLongVersionCode").toLong() } }
        for (p in listOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary")) assertEquals(VaultBrowsers.CHROME_COMPAT_MAX, caps[p], p)
        assertEquals(VaultBrowsers.BRAVE_COMPAT_MAX, caps["com.brave.browser"])
        // Edge stays in the compatibility mode (no cap)
        assertTrue(caps.getValue("com.microsoft.emmx") > 1_000_000_000L)
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(xml("AndroidManifest.xml"))
        val queries = manifest.getElementsByTagName("queries").item(0) as Element
        val pkgs = queries.getElementsByTagName("package").let { l -> (0 until l.length).map { (l.item(it) as Element).getAttributeNS(ns, "name") } }
        for (p in VaultBrowsers.switchPackages) assertTrue(p in pkgs, "$p missing in <queries>")
    }
}
