package com.gatecontrol.android.drive.vault

import com.gatecontrol.android.familie.emergency.AesGcmCipher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.crypto.KeyGenerator

/** The vaults' own names for the menu (Drive 1.11): encrypted on the phone, dropped when unreadable or gone. */
class DriveVaultNamesTest {

    @TempDir lateinit var dir: File

    private val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val file get() = File(dir, "names.bin")

    private fun names(secret: javax.crypto.SecretKey = aes) = DriveVaultNames(file, AesGcmCipher { secret })

    @Test
    fun `names are kept encrypted and read back`() {
        names().put("a", "Steuer-Unterlagen")
        names().put("b", "Familien-Dokumente")
        assertEquals("Steuer-Unterlagen", names().get("a"))
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("Steuer"))
    }

    @Test
    fun `vaults that are gone lose their names, an unreadable file is dropped`() {
        names().put("a", "A")
        names().put("b", "B")
        names().keep(setOf("b"))
        assertNull(names().get("a"))
        assertEquals("B", names().get("b"))
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(names(other).get("b"))
        assertFalse(file.exists())
    }
}
