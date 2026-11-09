package com.gatecontrol.android.notizen

import com.gatecontrol.android.drive.vault.DriveVaultCrypto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The Tresor notebooks' format (README "Tresor-Notizbücher – Kryptografie"): Drive's vault format byte for byte with
 * the label prefix `gcnotes-v1` — so this is [DriveVaultCrypto] with [PREFIX], nothing of its own. A note's Markdown is
 * a content in one chunk (UTF-8, base64url in `cipher`), the notebook's name, a note's title and an attachment's
 * name are name ciphers. Plaintext stays in memory only. Pure (unit-tested against the plugin's vectors).
 */
object NotesVault {
    const val PREFIX = DriveVaultCrypto.PREFIX_NOTES
    /** The server takes at most this many characters of `cipher`. */
    const val CIPHER_MAX = 1_000_000

    fun encryptName(tk: ByteArray, vaultId: String, keyVersion: Int, name: String): String =
        DriveVaultCrypto.encryptName(tk, vaultId, keyVersion, name, prefix = PREFIX)

    fun decryptName(keys: (Int) -> ByteArray?, vaultId: String, cipher: String): String =
        DriveVaultCrypto.decryptName(keys, vaultId, cipher, PREFIX)

    /** A note's Markdown → `cipher` (base64url). [salt] only for the vectors. */
    fun encryptText(tk: ByteArray, vaultId: String, keyVersion: Int, text: String, salt: ByteArray = DriveVaultCrypto.randomBytes(16)): String {
        val plain = text.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(plain.size + DriveVaultCrypto.HEADER + DriveVaultCrypto.TAG * 2)
        DriveVaultCrypto.encryptContent(tk, vaultId, keyVersion, ByteArrayInputStream(plain), plain.size.toLong(), out, salt = salt, prefix = PREFIX)
        plain.fill(0)
        return DriveVaultCrypto.b64u(out.toByteArray())
    }

    fun decryptText(keys: (Int) -> ByteArray?, vaultId: String, cipher: String): String {
        val out = ByteArrayOutputStream()
        DriveVaultCrypto.decryptContent(keys, vaultId, ByteArrayInputStream(DriveVaultCrypto.unb64u(cipher)), out, PREFIX)
        return out.toByteArray().let { b -> String(b, Charsets.UTF_8).also { b.fill(0) } }
    }

    /** An attachment's bytes → its ciphertext (uploaded as `application/octet-stream`). */
    fun encryptBytes(tk: ByteArray, vaultId: String, keyVersion: Int, bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size + 64)
        DriveVaultCrypto.encryptContent(tk, vaultId, keyVersion, ByteArrayInputStream(bytes), bytes.size.toLong(), out, prefix = PREFIX)
        return out.toByteArray()
    }

    fun decryptBytes(keys: (Int) -> ByteArray?, vaultId: String, cipher: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(cipher.size)
        DriveVaultCrypto.decryptContent(keys, vaultId, ByteArrayInputStream(cipher), out, PREFIX)
        return out.toByteArray()
    }

    /** The key version of a content cipher (`cipher` of a note), null when it is not one. */
    fun textVersion(cipher: String): Int? = runCatching { DriveVaultCrypto.contentVersion(DriveVaultCrypto.unb64u(cipher).copyOf(DriveVaultCrypto.HEADER)) }.getOrNull()
}
