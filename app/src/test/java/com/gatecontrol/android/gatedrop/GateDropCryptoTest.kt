package com.gatecontrol.android.gatedrop

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import kotlin.random.Random

class GateDropCryptoTest {

    private val key = ByteArray(32) { it.toByte() }
    private val transferId = "T3stTransfer01"
    private val block = GateDropCrypto.BLOCK

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun encrypt(plain: ByteArray, item: Int = 0, tid: String = transferId): ByteArray =
        GateDropCrypto.encryptingStream(ByteArrayInputStream(plain), key, tid, item, plain.size.toLong()).readBytes()

    private fun decrypt(sealed: ByteArray, size: Long, item: Int = 0, tid: String = transferId, k: ByteArray = key): ByteArray {
        val out = ByteArrayOutputStream()
        GateDropCrypto.decryptStream(ByteArrayInputStream(sealed), out, k, tid, item, size)
        return out.toByteArray()
    }

    @Test
    fun `salt is the first 4 bytes of SHA-256 of the transfer id`() {
        val expected = MessageDigest.getInstance("SHA-256").digest(transferId.toByteArray()).copyOf(4)
        assertArrayEquals(expected, GateDropCrypto.salt(transferId))
        assertEquals("fc348f52", hex(GateDropCrypto.salt(transferId)))
    }

    @Test
    fun `nonce is salt and a big-endian 8-byte counter`() {
        val salt = GateDropCrypto.salt(transferId)
        assertEquals("fc348f52" + "0000000000000000", hex(GateDropCrypto.nonce(salt, GateDropCrypto.counter(0, 0))))
        assertEquals("fc348f52" + "0000000000000005", hex(GateDropCrypto.nonce(salt, GateDropCrypto.counter(0, 5))))
        // Item index in the upper 32 bits: items under the same key never share a nonce.
        assertEquals("fc348f52" + "0000000100000002", hex(GateDropCrypto.nonce(salt, GateDropCrypto.counter(1, 2))))
        assertEquals(12, GateDropCrypto.nonce(salt, 0).size)
    }

    @Test
    fun `known answers (cross-checked with another AES-GCM implementation)`() {
        val salt = GateDropCrypto.salt(transferId)
        val c00 = GateDropCrypto.encryptBlock(key, salt, 0, 0, "GateDrop".toByteArray())
        assertEquals("11d2c9192328f5ba945bef007f002dfa3ac63f279577361e", hex(c00))
        val c12 = GateDropCrypto.encryptBlock(key, salt, 1, 2, "x".toByteArray())
        assertEquals("944f716db395f0dbe412468fad79cedf70", hex(c12))
        assertArrayEquals("x".toByteArray(), GateDropCrypto.decryptBlock(key, salt, 1, 2, c12))
    }

    @Test
    fun `block count and wire length`() {
        assertEquals(0, GateDropCrypto.blocks(0))
        assertEquals(1, GateDropCrypto.blocks(1))
        assertEquals(1, GateDropCrypto.blocks(block.toLong()))
        assertEquals(2, GateDropCrypto.blocks(block + 1L))
        assertEquals(0L, GateDropCrypto.encryptedLength(0))
        assertEquals(17L, GateDropCrypto.encryptedLength(1))
        assertEquals(block + 16L, GateDropCrypto.encryptedLength(block.toLong()))
        assertEquals(2L * block + 1 + 3 * 16, GateDropCrypto.encryptedLength(2L * block + 1))
        val twoGb = 2L * 1024 * 1024 * 1024
        assertEquals(twoGb + 2048 * 16, GateDropCrypto.encryptedLength(twoGb))
    }

    @Test
    fun `round trip for sizes around the block boundary`() {
        val rnd = Random(42)
        for (size in listOf(0, 1, 15, 16, 17, block - 1, block, block + 1, 2 * block + block / 2)) {
            val plain = rnd.nextBytes(size)
            val sealed = encrypt(plain, item = 3)
            assertEquals(GateDropCrypto.encryptedLength(size.toLong()), sealed.size.toLong(), "length for $size")
            assertArrayEquals(plain, decrypt(sealed, size.toLong(), item = 3), "round trip for $size")
        }
    }

    @Test
    fun `stream blocks equal single block encryption`() {
        val plain = Random(7).nextBytes(block + 10)
        val sealed = encrypt(plain, item = 2)
        val salt = GateDropCrypto.salt(transferId)
        val first = GateDropCrypto.encryptBlock(key, salt, 2, 0, plain.copyOfRange(0, block))
        val second = GateDropCrypto.encryptBlock(key, salt, 2, 1, plain.copyOfRange(block, block + 10))
        assertArrayEquals(first + second, sealed)
    }

    @Test
    fun `a changed byte is detected`() {
        val plain = Random(1).nextBytes(block + 100)
        val sealed = encrypt(plain)
        for (pos in listOf(0, block - 1, block + 15, sealed.size - 1)) {
            val bad = sealed.copyOf().also { it[pos] = (it[pos].toInt() xor 0x01).toByte() }
            assertThrows(AEADBadTagException::class.java) { decrypt(bad, plain.size.toLong()) }
        }
    }

    @Test
    fun `reordered blocks, wrong item, wrong transfer and wrong key fail`() {
        val plain = Random(2).nextBytes(2 * block)
        val sealed = encrypt(plain)
        val one = block + 16
        val swapped = sealed.copyOfRange(one, 2 * one) + sealed.copyOfRange(0, one)
        assertThrows(AEADBadTagException::class.java) { decrypt(swapped, plain.size.toLong()) }
        assertThrows(AEADBadTagException::class.java) { decrypt(sealed, plain.size.toLong(), item = 1) }
        assertThrows(AEADBadTagException::class.java) { decrypt(sealed, plain.size.toLong(), tid = "OtherTransfer1") }
        assertThrows(AEADBadTagException::class.java) { decrypt(sealed, plain.size.toLong(), k = ByteArray(32) { 7 }) }
    }

    @Test
    fun `cut and extended streams fail`() {
        val plain = Random(3).nextBytes(block + 5)
        val sealed = encrypt(plain)
        // The whole last block missing.
        assertThrows(EOFException::class.java) { decrypt(sealed.copyOf(block + 16), plain.size.toLong()) }
        // Cut inside a block.
        assertThrows(EOFException::class.java) { decrypt(sealed.copyOf(sealed.size - 3), plain.size.toLong()) }
        // Extra bytes after the last block.
        assertThrows(IOException::class.java) { decrypt(sealed + byteArrayOf(0), plain.size.toLong()) }
        // Announced size larger than sent.
        assertThrows(EOFException::class.java) { decrypt(sealed, plain.size + 1L) }
    }

    @Test
    fun `empty file is an empty body (as the Windows client sends it)`() {
        val sealed = encrypt(ByteArray(0))
        assertEquals(0, sealed.size)
        assertArrayEquals(ByteArray(0), decrypt(sealed, 0))
        assertThrows(IOException::class.java) { decrypt(ByteArray(16), 0) }
    }
}
