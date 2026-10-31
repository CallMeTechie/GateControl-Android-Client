package com.gatecontrol.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards security-relevant manifest/source decisions that no ViewModel test
 * can see. Gradle runs unit tests with the module directory as working dir.
 */
class ManifestSecurityTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun moduleFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull { it.exists() }
            ?: error("$path not found from ${File(".").absolutePath}")

    private val manifest: Element by lazy {
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(moduleFile("src/main/AndroidManifest.xml"))
            .documentElement
    }

    private fun components(tag: String): List<Element> {
        val nodes = manifest.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.androidAttr(name: String): String = getAttributeNS(androidNs, name)

    @Test
    fun `tile action activity is not exported`() {
        val tileActivity = components("activity")
            .firstOrNull { it.androidAttr("name").endsWith("TileActionActivity") }
        assertNotNull(tileActivity, "TileActionActivity must be declared")
        assertEquals("false", tileActivity!!.androidAttr("exported"))
        assertEquals(0, tileActivity.getElementsByTagName("intent-filter").length)
    }

    @Test
    fun `quick action activity is not exported and has no intent filter`() {
        val activity = components("activity")
            .firstOrNull { it.androidAttr("name").endsWith("QuickActionActivity") }
        assertNotNull(activity, "QuickActionActivity must be declared")
        assertEquals("false", activity!!.androidAttr("exported"))
        assertEquals(0, activity.getElementsByTagName("intent-filter").length)
    }

    @Test
    fun `quick action tiles can only be bound by the system`() {
        val tiles = components("service").filter { it.androidAttr("name").contains("QuickActionTile") }
        assertEquals(3, tiles.size)
        tiles.forEach { assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", it.androidAttr("permission")) }
    }

    @Test
    fun `exported MainActivity no longer handles tile extras`() {
        val source = moduleFile("src/main/java/com/gatecontrol/android/MainActivity.kt").readText()
        assertFalse(source.contains("TILE_ACTION"), "MainActivity must not act on tile extras")
        assertFalse(source.contains("getStringExtra"), "MainActivity must not act on intent extras")
        assertFalse(source.contains("tunnelConnector"), "MainActivity must not connect the VPN itself")
    }

    @Test
    fun `company sign-in redirect is only gatecontrol signin and stays out of recents`() {
        val activity = components("activity").firstOrNull { it.androidAttr("name").endsWith("SigninRedirectActivity") }
        assertNotNull(activity, "SigninRedirectActivity must be declared")
        assertEquals("true", activity!!.androidAttr("excludeFromRecents"))
        val data = activity.getElementsByTagName("data")
        assertEquals(1, data.length)
        val d = data.item(0) as Element
        assertEquals("gatecontrol", d.androidAttr("scheme"))
        assertEquals("signin", d.androidAttr("host"))
        val source = moduleFile("src/main/java/com/gatecontrol/android/SigninRedirectActivity.kt").readText()
        assertFalse(source.contains("getStringExtra"), "the redirect must not act on intent extras")
    }

    @Test
    fun `unused install permission is not requested`() {
        val permissions = components("uses-permission").map { it.androidAttr("name") }
        assertFalse("android.permission.REQUEST_INSTALL_PACKAGES" in permissions)
    }

    @Test
    fun `trusted WLANs declare the location permissions Android needs for the SSID`() {
        val permissions = components("uses-permission").map { it.androidAttr("name") }
        // Fine needs coarse alongside on Android 12+; background for SSIDs while the app is not in use.
        assertTrue("android.permission.ACCESS_FINE_LOCATION" in permissions)
        assertTrue("android.permission.ACCESS_COARSE_LOCATION" in permissions)
        assertTrue("android.permission.ACCESS_BACKGROUND_LOCATION" in permissions)
    }

    @Test
    fun `every specialUse foreground service declares its subtype`() {
        val specialUse = components("service")
            .filter { it.androidAttr("foregroundServiceType").contains("specialUse") }
        assertTrue(specialUse.isNotEmpty())
        for (service in specialUse) {
            val props = service.getElementsByTagName("property")
            val subtype = (0 until props.length).map { props.item(it) as Element }
                .firstOrNull { it.androidAttr("name") == "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" }
            assertNotNull(subtype, "${service.androidAttr("name")} lacks PROPERTY_SPECIAL_USE_FGS_SUBTYPE")
            assertTrue(subtype!!.androidAttr("value").isNotBlank())
        }
    }

    @Test
    fun `push service is a non-exported specialUse service with its subtype`() {
        val push = components("service").firstOrNull { it.androidAttr("name").endsWith("push.PushService") }
        assertNotNull(push, "PushService must be declared")
        assertEquals("false", push!!.androidAttr("exported"))
        assertEquals("specialUse", push.androidAttr("foregroundServiceType"))
        val props = push.getElementsByTagName("property")
        val subtype = (0 until props.length).map { props.item(it) as Element }
            .first { it.androidAttr("name") == "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" }
        assertTrue(subtype.androidAttr("value").contains("without Google services"))
    }

    @Test
    fun `push receiver and open activity are not exported`() {
        val receiver = components("receiver").first { it.androidAttr("name").endsWith("PushActionReceiver") }
        assertEquals("false", receiver.androidAttr("exported"))
        assertEquals(0, receiver.getElementsByTagName("intent-filter").length)
        val activity = components("activity").first { it.androidAttr("name").endsWith("PushOpenActivity") }
        assertEquals("false", activity.androidAttr("exported"))
        assertEquals(0, activity.getElementsByTagName("intent-filter").length)
    }

    @Test
    fun `push permissions are declared`() {
        val permissions = components("uses-permission").map { it.androidAttr("name") }
        assertTrue("android.permission.POST_NOTIFICATIONS" in permissions)
        assertTrue("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" in permissions)
        assertTrue("android.permission.FOREGROUND_SERVICE_SPECIAL_USE" in permissions)
    }

    @Test
    fun `familie receivers and activities are not exported`() {
        listOf("FamilieActionReceiver", "GeofenceReceiver").forEach { name ->
            val receiver = components("receiver").first { it.androidAttr("name").endsWith(name) }
            assertEquals("false", receiver.androidAttr("exported"), name)
            assertEquals(0, receiver.getElementsByTagName("intent-filter").length, name)
        }
        listOf("FamilieOpenActivity", "FamilieLocateActivity").forEach { name ->
            val activity = components("activity").first { it.androidAttr("name").endsWith(name) }
            assertEquals("false", activity.androidAttr("exported"), name)
            assertEquals(0, activity.getElementsByTagName("intent-filter").length, name)
        }
    }

    @Test
    fun `file provider only exposes the export directory and opened chat files`() {
        val xml = moduleFile("src/main/res/xml/file_paths.xml").readText()
        assertFalse(xml.contains("path=\".\""), "whole cache dir must not be shared")
        assertTrue(xml.contains("path=\"export/\""))
        assertTrue(xml.contains("path=\"chat-open/\""))
        assertTrue(xml.contains("path=\"camera/\""), "camera captures only in their own directory")
        assertFalse(xml.contains("chat-media"), "the chat media cache stays private")
        assertFalse(xml.contains("files-path"), "nothing of the private files dir")
    }

    @Test
    fun `chat notification receiver is not exported, the share target is off until the server offers chats`() {
        val receiver = components("receiver").first { it.androidAttr("name").endsWith("ChatActionReceiver") }
        assertEquals("false", receiver.androidAttr("exported"))
        assertEquals(0, receiver.getElementsByTagName("intent-filter").length)
        val share = components("activity").first { it.androidAttr("name").endsWith("ChatShareActivity") }
        assertEquals("true", share.androidAttr("exported"))
        assertEquals("false", share.androidAttr("enabled"), "switched on at runtime only with the messenger")
        val actions = share.getElementsByTagName("action").let { n -> (0 until n.length).map { (n.item(it) as Element).androidAttr("name") } }.toSet()
        assertEquals(setOf("android.intent.action.SEND", "android.intent.action.SEND_MULTIPLE"), actions)
        val permissions = components("uses-permission").map { it.androidAttr("name") }
        assertTrue("android.permission.RECORD_AUDIO" in permissions)
    }

    @Test
    fun `favourites may call straight away, telephony stays optional for tablets`() {
        val permissions = components("uses-permission").map { it.androidAttr("name") }
        assertTrue("android.permission.CALL_PHONE" in permissions)
        val telephony = components("uses-feature").firstOrNull { it.androidAttr("name") == "android.hardware.telephony" }
        assertNotNull(telephony, "CALL_PHONE implies telephony: declare it optional")
        assertEquals("false", telephony!!.androidAttr("required"))
    }

    @Test
    fun `chat bubble activity is not exported and can be embedded as a bubble`() {
        val bubble = components("activity").first { it.androidAttr("name").endsWith("ChatBubbleActivity") }
        assertEquals("false", bubble.androidAttr("exported"))
        assertEquals(0, bubble.getElementsByTagName("intent-filter").length)
        assertEquals("true", bubble.androidAttr("allowEmbedded"))
        assertEquals("true", bubble.androidAttr("resizeableActivity"))
        assertEquals("always", bubble.androidAttr("documentLaunchMode"))
    }

    @Test
    fun `sharing shortcuts target the share activity with the chat category`() {
        val main = components("activity").first { it.androidAttr("name").endsWith(".MainActivity") }
        val meta = main.getElementsByTagName("meta-data").let { n -> (0 until n.length).map { n.item(it) as Element } }
        assertTrue(meta.any { it.androidAttr("name") == "android.app.shortcuts" && it.androidAttr("resource") == "@xml/shortcuts" })
        val xml = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(moduleFile("src/main/res/xml/shortcuts.xml")).documentElement
        assertEquals(0, xml.getElementsByTagName("shortcut").length, "no static shortcuts")
        val target = xml.getElementsByTagName("share-target").item(0) as Element
        assertEquals("com.gatecontrol.android.chat.ChatShareActivity", target.androidAttr("targetClass"))
        val category = (target.getElementsByTagName("category").item(0) as Element).androidAttr("name")
        assertEquals(com.gatecontrol.android.chat.ChatShortcutLogic.SHARE_CATEGORY, category)
    }

    @Test
    fun `mail sign-in redirect is msauth of the app only, off until the server offers Mail`() {
        val activity = components("activity").firstOrNull { it.androidAttr("name").endsWith("MailSigninActivity") }
        assertNotNull(activity, "MailSigninActivity must be declared")
        assertEquals("true", activity!!.androidAttr("exported"))
        assertEquals("false", activity.androidAttr("enabled"), "enabled by MailStartup only while the plugin is there")
        assertEquals("true", activity.androidAttr("excludeFromRecents"))
        val data = activity.getElementsByTagName("data")
        assertEquals(1, data.length)
        val d = data.item(0) as org.w3c.dom.Element
        assertEquals("msauth", d.androidAttr("scheme"))
        assertEquals("com.gatecontrol.client", d.androidAttr("host"))
        val xml = moduleFile("src/main/res/xml/file_paths.xml").readText()
        assertTrue(xml.contains("path=\"mail-open/\""), "opened attachments only")
        assertFalse(xml.contains("path=\"mail-up"), "uploads stay private")
    }
}
