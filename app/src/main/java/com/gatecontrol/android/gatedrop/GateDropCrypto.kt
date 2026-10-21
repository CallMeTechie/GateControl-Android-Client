package com.gatecontrol.android.gatedrop

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Block format of the direct LAN transfer (protocol C v1): AES-256-GCM
 * with the transfer's 32-byte key, the file in blocks of 1 MiB, each
 * block sent as ciphertext‖tag(16). The 12-byte nonce of a block is
 * `salt(4) ‖ counter(8, big-endian)` with salt = the first 4 bytes of
 * SHA-256(transferId).
 *
 * Counter: the items of one transfer share the key, so the counter of a
 * block is `itemIndex << 32 | blockIndex` — no nonce is ever used twice
 * under one key, and item 0 counts 0, 1, 2, … exactly as the contract's
 * plain block counter. A file of n bytes has ⌈n / 1 MiB⌉ blocks (an empty
 * file is an empty body, as the Windows client sends it). The receiver checks every tag,
 * the exact number of blocks and the total size: a changed, reordered,
 * cut or extended stream fails. Pure JVM, unit-tested.
 */
object GateDropCrypto {
    const val BLOCK = 1 shl 20
    const val TAG = 16
    const val NONCE = 12
    private const val TAG_BITS = TAG * 8
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** First 4 bytes of SHA-256(transferId as UTF-8). */
    fun salt(transferId: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(transferId.toByteArray(Charsets.UTF_8)).copyOf(4)

    /** Block counter of block [block] of item [itemIndex]. */
    fun counter(itemIndex: Int, block: Long): Long {
        require(itemIndex >= 0 && block in 0..0xFFFF_FFFFL) { "counter out of range" }
        return (itemIndex.toLong() shl 32) or block
    }

    /** `salt ‖ counter` (big-endian), 12 bytes. */
    fun nonce(salt: ByteArray, counter: Long): ByteArray {
        require(salt.size == 4) { "salt must be 4 bytes" }
        val n = ByteArray(NONCE)
        salt.copyInto(n, 0)
        for (i in 0 until 8) n[4 + i] = (counter ushr (56 - 8 * i)).toByte()
        return n
    }

    /** Number of blocks of a file of [size] bytes. */
    fun blocks(size: Long): Long {
        require(size >= 0) { "negative size" }
        return (size + BLOCK - 1) / BLOCK
    }

    /** Length on the wire of a file of [size] bytes. */
    fun encryptedLength(size: Long): Long = size + TAG * blocks(size)

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray): Cipher {
        require(key.size == 32) { "key must be 32 bytes" }
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        }
    }

    /** One block (≤ 1 MiB) → ciphertext‖tag. */
    fun encryptBlock(key: ByteArray, salt: ByteArray, itemIndex: Int, block: Long, plain: ByteArray, len: Int = plain.size): ByteArray {
        require(len in 0..BLOCK) { "block too large" }
        return cipher(Cipher.ENCRYPT_MODE, key, nonce(salt, counter(itemIndex, block))).doFinal(plain, 0, len)
    }

    /** ciphertext‖tag → the block; throws [AEADBadTagException] when it was changed. */
    fun decryptBlock(key: ByteArray, salt: ByteArray, itemIndex: Int, block: Long, sealed: ByteArray, len: Int = sealed.size): ByteArray {
        require(len in TAG..BLOCK + TAG) { "bad block length" }
        return cipher(Cipher.DECRYPT_MODE, key, nonce(salt, counter(itemIndex, block))).doFinal(sealed, 0, len)
    }

    /**
     * Streams [source] (exactly [size] bytes) as encrypted blocks: what is
     * read from the returned stream is the request body of a push / the
     * answer of a pull. Never holds more than one block in memory.
     */
    fun encryptingStream(source: InputStream, key: ByteArray, transferId: String, itemIndex: Int, size: Long): InputStream =
        EncryptingInputStream(source, key, salt(transferId), itemIndex, size)

    /**
     * Reads the encrypted stream of a file of [size] bytes from [input],
     * checks every block and writes the plain file to [output].
     * [onProgress] gets the plain bytes written so far. Throws
     * [AEADBadTagException] for a changed block and [IOException] for a
     * stream that is too short or too long.
     */
    fun decryptStream(
        input: InputStream,
        output: OutputStream,
        key: ByteArray,
        transferId: String,
        itemIndex: Int,
        size: Long,
        onProgress: (Long) -> Unit = {},
    ): Long {
        val salt = salt(transferId)
        val total = blocks(size)
        val buf = ByteArray(BLOCK + TAG)
        var remaining = size
        var written = 0L
        for (b in 0 until total) {
            val plainLen = minOf(remaining, BLOCK.toLong()).toInt()
            val sealedLen = plainLen + TAG
            readFully(input, buf, sealedLen)
            val plain = decryptBlock(key, salt, itemIndex, b, buf, sealedLen)
            output.write(plain)
            remaining -= plainLen
            written += plainLen
            onProgress(written)
        }
        if (input.read() != -1) throw IOException("stream longer than announced")
        return written
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw EOFException("stream ended early")
            off += n
        }
    }

    private class EncryptingInputStream(
        private val source: InputStream,
        private val key: ByteArray,
        private val salt: ByteArray,
        private val itemIndex: Int,
        private val size: Long,
    ) : InputStream() {
        private val total = blocks(size)
        private var block = 0L
        private var remaining = size
        private var current: ByteArray = ByteArray(0)
        private var pos = 0
        private val plain = ByteArray(BLOCK)

        private fun fill(): Boolean {
            if (pos < current.size) return true
            if (block >= total) return false
            val len = minOf(remaining, BLOCK.toLong()).toInt()
            readFully(source, plain, len)
            current = encryptBlock(key, salt, itemIndex, block, plain, len)
            pos = 0
            remaining -= len
            block++
            return true
        }

        override fun read(): Int {
            if (!fill()) return -1
            return current[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, current.size - pos)
            current.copyInto(b, off, pos, pos + n)
            pos += n
            return n
        }

        override fun available(): Int = current.size - pos

        override fun close() = source.close()
    }
}
