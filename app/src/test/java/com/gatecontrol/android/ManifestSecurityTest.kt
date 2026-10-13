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
    fun `file provider only exposes the export directory`() {
        val xml = moduleFile("src/main/res/xml/file_paths.xml").readText()
        assertFalse(xml.contains("path=\".\""), "whole cache dir must not be shared")
        assertTrue(xml.contains("path=\"export/\""))
    }
}
