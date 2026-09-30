package com.gatecontrol.android.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EncryptedStorageTest {

    private class MapBackend : SecretBackend {
        val data = mutableMapOf<String, String>()
        override fun get(key: String) = data[key]
        override fun put(entries: Map<String, String>, sync: Boolean) { data.putAll(entries) }
        override fun remove(key: String) { data.remove(key) }
        override fun clear() = data.clear()
    }

    /** Reversible stand-in for the Keystore cipher; fails on anything it did not produce. */
    private class FakeCipher : SecretCipher {
        override fun encrypt(plain: String) = "enc(" + plain.reversed() + ")"
        override fun decrypt(encoded: String): String {
            require(encoded.startsWith("enc(") && encoded.endsWith(")")) { "bad ciphertext" }
            return encoded.removePrefix("enc(").removeSuffix(")").reversed()
        }
    }

    private fun storage(backend: MapBackend = MapBackend(), migration: (EncryptedStorage) -> Unit = {}) =
        EncryptedStorage({ backend to FakeCipher() }, migration)

    @Test
    fun `values round-trip with their types`() {
        val s = storage()
        s.putString("token", "gc_secret")
        s.putInt("peer", 42)
        s.commitBatch("url" to "https://gate.example", "n" to 7, "flag" to true)
        assertEquals("gc_secret", s.getString("token", ""))
        assertEquals(42, s.getInt("peer", -1))
        assertEquals("https://gate.example", s.getString("url", ""))
        assertEquals(7, s.getInt("n", -1))
    }

    @Test
    fun `nothing is written to the backend in plaintext`() {
        val backend = MapBackend()
        val s = storage(backend)
        s.putString("wg_config", "PrivateKey = SECRETKEY")
        assertTrue(backend.data.values.none { it.contains("SECRETKEY") })
        assertTrue(s.isPersistent)
    }

    @Test
    fun `defaults for missing keys and type mismatch`() {
        val s = storage()
        assertEquals("d", s.getString("missing", "d"))
        s.putInt("x", 1)
        assertEquals("d", s.getString("x", "d"))
        s.putString("y", "abc")
        assertEquals(5, s.getInt("y", 5))
    }

    @Test
    fun `undecryptable values are dropped instead of crashing`() {
        val backend = MapBackend()
        backend.data["token"] = "garbage"
        val s = storage(backend)
        assertEquals("", s.getString("token", ""))
        assertFalse(backend.data.containsKey("token"))
    }

    @Test
    fun `without keystore secrets stay in memory only`() {
        val s = EncryptedStorage({ throw IllegalStateException("no keystore") }, {})
        assertFalse(s.isPersistent)
        s.putString("token", "gc_secret")
        assertEquals("gc_secret", s.getString("token", ""))
        s.remove("token")
        assertEquals("", s.getString("token", ""))
    }

    @Test
    fun `legacy values are imported with their types`() {
        val backend = MapBackend()
        val s = storage(backend) { st ->
            st.importLegacy("token", "old")
            st.importLegacy("peer", 9)
            st.importLegacy("big", 5L)
        }
        assertEquals("old", s.getString("token", ""))
        assertEquals(9, s.getInt("peer", -1))
        assertEquals(5, s.getInt("big", -1))
        assertTrue(backend.data.values.none { it.contains("old") && !it.startsWith("enc(") })
    }

    @Test
    fun `clear removes everything`() {
        val backend = MapBackend()
        val s = storage(backend)
        s.putString("a", "1")
        s.clear()
        assertTrue(backend.data.isEmpty())
        assertEquals("", s.getString("a", ""))
    }
}
