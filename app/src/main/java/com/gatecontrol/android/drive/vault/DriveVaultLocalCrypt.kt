package com.gatecontrol.android.drive.vault

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * The content of a folder that goes onto this phone only, or back to the server — re-encrypted chunk by chunk, the
 * plaintext only ever one chunk in memory, never on the disk (pure JVM, unit-tested).
 */
object DriveVaultLocalCrypt {

    /** What was read through: the SHA-256 of the plaintext (hex) and its size. */
    data class Plain(val sha256: String, val size: Long)

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /**
     * Decrypts [input] (a vault content of [fromKeys] — every GCM tag checked) and writes it encrypted with [toKey] as
     * key version [toVersion] into [output]: tresor key → the folder's own key LK (version 0) on the way here, LK → the
     * vault's current key on the way back. A changed or cut-off content throws `decrypt`, nothing is half written then
     * (the caller drops [output]).
     */
    fun transcode(
        fromKeys: (Int) -> ByteArray?,
        vaultId: String,
        input: InputStream,
        toKey: ByteArray,
        toVersion: Int,
        output: OutputStream,
        onProgress: (Long) -> Unit = {},
    ): Plain {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val writer = DriveVaultCrypto.ContentWriter.start(toKey, vaultId, toVersion, output)
        try {
            val sink = object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(b: ByteArray, off: Int, len: Int) {
                    digest.update(b, off, len)
                    writer.write(b, off, len)
                    size += len
                    onProgress(size)
                }
            }
            DriveVaultCrypto.decryptContent(fromKeys, vaultId, input, sink)
            writer.finish()
        } finally {
            writer.close()
        }
        return Plain(hex(digest.digest()), size)
    }

    /** The plaintext's SHA-256 of a vault content — the check after writing (every tag read, the bytes dropped). */
    fun digest(keys: (Int) -> ByteArray?, vaultId: String, input: InputStream): Plain {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val sink = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                digest.update(b, off, len)
                size += len
            }
        }
        DriveVaultCrypto.decryptContent(keys, vaultId, input, sink)
        return Plain(hex(digest.digest()), size)
    }

    /** A name of the vault ([fromKeys], its key version in the cipher) → the same name with [toKey] as [toVersion]. */
    fun transcodeName(fromKeys: (Int) -> ByteArray?, vaultId: String, cipher: String, toKey: ByteArray, toVersion: Int): String =
        DriveVaultCrypto.encryptName(toKey, vaultId, toVersion, DriveVaultCrypto.decryptName(fromKeys, vaultId, cipher))

    /** The SHA-256 (hex) of [input]'s bytes: the ciphertext as the server keeps it. */
    fun sha256(input: InputStream): String {
        val d = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(256 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            d.update(buf, 0, n)
        }
        return hex(d.digest())
    }
}
