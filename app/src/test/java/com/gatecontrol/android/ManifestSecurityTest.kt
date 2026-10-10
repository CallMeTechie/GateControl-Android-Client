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
    fun `file provider only exposes the export directory`() {
        val xml = moduleFile("src/main/res/xml/file_paths.xml").readText()
        assertFalse(xml.contains("path=\".\""), "whole cache dir must not be shared")
        assertTrue(xml.contains("path=\"export/\""))
    }
}
