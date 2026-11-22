package com.gatecontrol.android.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The own APK download of the GitHub build: addresses, checksum and the mandatory signature check. */
class ApkUpdateLogicTest {
    private val own = "com.gatecontrol.client"
    private val key = setOf("a".repeat(64))
    private val other = setOf("b".repeat(64))

    @Test
    fun `only https addresses without user info`() {
        assertTrue(ApkUpdateLogic.allowedUrl("https://github.com/CallMeTechie/GateControl-Android-Client/releases/download/v1.90.0/app.apk"))
        assertTrue(ApkUpdateLogic.allowedUrl(" https://gc.example.de/api/v1/client/update/download?client=android "))
        assertFalse(ApkUpdateLogic.allowedUrl("http://github.com/app.apk"))
        assertFalse(ApkUpdateLogic.allowedUrl("https://user:pw@github.com/app.apk"))
        assertFalse(ApkUpdateLogic.allowedUrl("file:///sdcard/app.apk"))
        assertFalse(ApkUpdateLogic.allowedUrl(""))
        assertFalse(ApkUpdateLogic.allowedUrl(null))
    }

    @Test
    fun `the own server gets the app's client, GitHub a plain one`() {
        assertTrue(ApkUpdateLogic.sameHost("https://gc.example.de/api/v1/client/update/download?client=android", "https://gc.example.de"))
        assertTrue(ApkUpdateLogic.sameHost("https://GC.example.de/x", "https://gc.example.de/"))
        assertFalse(ApkUpdateLogic.sameHost("https://github.com/x.apk", "https://gc.example.de"))
        assertFalse(ApkUpdateLogic.sameHost("https://gc.example.de:8443/x", "https://gc.example.de"))
        assertFalse(ApkUpdateLogic.sameHost("https://gc.example.de.evil.com/x", "https://gc.example.de"))
    }

    @Test
    fun `checksum - the server's value decides, none means the signature alone`() {
        val sha = "c".repeat(64)
        assertTrue(ApkUpdateLogic.shaMatches(sha, sha))
        assertTrue(ApkUpdateLogic.shaMatches(sha.uppercase(), sha))
        assertFalse(ApkUpdateLogic.shaMatches(sha, "d".repeat(64)))
        assertTrue(ApkUpdateLogic.shaMatches(null, sha))
        // a value that is no SHA-256 is no pass
        assertFalse(ApkUpdateLogic.shaMatches("md5:abc", sha))
    }

    @Test
    fun `signature, package and version are mandatory`() {
        fun v(pkg: String? = own, code: Long? = 19_000, signers: Set<String> = key, history: Set<String> = emptySet(), installed: Set<String> = key) =
            ApkUpdateLogic.verify(pkg, code, signers, history, own, 18_900, installed)
        assertNull(v())
        assertEquals("signature", v(signers = other))
        assertEquals("signature", v(signers = emptySet()))
        assertEquals("signature", v(installed = emptySet()))
        assertEquals("package", v(pkg = "com.evil.app"))
        assertEquals("version", v(code = 18_900))
        assertEquals("version", v(code = 18_000))
        assertEquals("invalid", v(pkg = null))
        assertEquals("invalid", v(code = null))
        // a rotated key whose lineage holds the installed one
        assertNull(v(signers = other, history = key + other))
        assertEquals("signature", v(signers = other, history = other))
    }
}
