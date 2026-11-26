package com.gatecontrol.android.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.AEADBadTagException
import javax.crypto.IllegalBlockSizeException

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

    /** What Keystore2 throws when it pruned an operation (as in the field log). */
    private fun pruned() = IllegalBlockSizeException().apply {
        initCause(KeyStoreException("Invalid operation handle: Call on finalized operation with outcome: Pruned."))
    }

    /** Counts decrypts; [failures] decides per call whether (and how) it fails. */
    private class ScriptedCipher(private val failures: (Int) -> Exception? = { null }) : SecretCipher {
        val decrypts = AtomicInteger()
        private val fake = FakeCipher()
        override fun encrypt(plain: String) = fake.encrypt(plain)
        override fun decrypt(encoded: String): String {
            val n = decrypts.incrementAndGet()
            failures(n)?.let { throw it }
            return fake.decrypt(encoded)
        }
    }

    private fun scripted(backend: MapBackend, cipher: ScriptedCipher, sleeps: MutableList<Long> = mutableListOf()) =
        EncryptedStorage({ backend to cipher }, {}, { sleeps += it })

    private fun stored(vararg entries: Pair<String, String>) = MapBackend().apply {
        entries.forEach { (k, v) -> data[k] = FakeCipher().encrypt("s:$v") }
    }

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

    @Test
    fun `clear keeps the requested keys`() {
        val backend = MapBackend()
        val s = storage(backend)
        s.putString("token", "gc_secret")
        s.putString("device", "abc")
        s.clear(keep = setOf("device", "missing"))
        assertEquals("", s.getString("token", ""))
        assertEquals("abc", s.getString("device", ""))
        assertEquals(1, backend.data.size)
    }

    @Test
    fun `a pruned keystore operation never deletes the value`() {
        val backend = stored("server_url" to "https://gate.example.com")
        val cipher = ScriptedCipher { pruned() }
        val sleeps = mutableListOf<Long>()
        val s = scripted(backend, cipher, sleeps)

        assertEquals("", s.getString("server_url", ""))
        assertTrue(backend.data.containsKey("server_url"))
        assertTrue(s.contains("server_url"))
        assertEquals(EncryptedStorage.MAX_ATTEMPTS, cipher.decrypts.get())
        assertEquals(EncryptedStorage.MAX_ATTEMPTS - 1, sleeps.size)
    }

    @Test
    fun `a transient failure is retried and then read`() {
        val backend = stored("server_url" to "https://gate.example.com")
        val cipher = ScriptedCipher { n -> if (n == 1) pruned() else null }
        val s = scripted(backend, cipher)

        assertEquals("https://gate.example.com", s.getString("server_url", ""))
        assertEquals(2, cipher.decrypts.get())
    }

    @Test
    fun `unknown errors keep the value as well`() {
        val backend = stored("api_token" to "gc_secret")
        val s = scripted(backend, ScriptedCipher { IllegalStateException("something odd") })

        assertEquals("", s.getString("api_token", ""))
        assertTrue(backend.data.containsKey("api_token"))
    }

    @Test
    fun `a wrong key (bad tag) deletes the value`() {
        val backend = stored("server_url" to "https://gate.example.com")
        val cipher = ScriptedCipher { AEADBadTagException("tag mismatch") }
        val s = scripted(backend, cipher)

        assertEquals("", s.getString("server_url", ""))
        assertFalse(backend.data.containsKey("server_url"))
        assertFalse(s.contains("server_url"))
        assertEquals(1, cipher.decrypts.get())
    }

    @Test
    fun `classification of keystore failures`() {
        assertTrue(EncryptedStorage.isTransientKeystoreFailure(pruned()))
        assertTrue(EncryptedStorage.isTransientKeystoreFailure(IllegalBlockSizeException()))
        assertTrue(EncryptedStorage.isTransientKeystoreFailure(UnrecoverableKeyException("x").apply { initCause(KeyStoreException("system error")) }))
        assertTrue(EncryptedStorage.isTransientKeystoreFailure(AEADBadTagException("x").apply { initCause(KeyStoreException("Pruned")) }))
        assertFalse(EncryptedStorage.isTransientKeystoreFailure(AEADBadTagException("x")))
        assertFalse(EncryptedStorage.isTransientKeystoreFailure(UnrecoverableKeyException("key gone")))
        assertFalse(EncryptedStorage.isTransientKeystoreFailure(IllegalArgumentException("ciphertext too short")))
    }

    @Test
    fun `decrypted values are cached and the cache follows writes`() {
        val backend = stored("server_url" to "https://a.example.com")
        val cipher = ScriptedCipher()
        val s = scripted(backend, cipher)

        repeat(50) { assertEquals("https://a.example.com", s.getString("server_url", "")) }
        assertEquals(1, cipher.decrypts.get())

        s.putString("server_url", "https://b.example.com")
        assertEquals("https://b.example.com", s.getString("server_url", ""))
        s.commitBatch("server_url" to "https://c.example.com")
        assertEquals("https://c.example.com", s.getString("server_url", ""))
        assertEquals(1, cipher.decrypts.get())

        s.remove("server_url")
        assertEquals("", s.getString("server_url", ""))
        s.putString("token", "t")
        s.clear()
        assertEquals("", s.getString("token", ""))
        assertTrue(backend.data.isEmpty())
    }

    @Test
    fun `parallel reads reach the keystore one at a time`() {
        val keys = (1..24).map { "k$it" }
        val backend = stored(*keys.map { it to "v-$it" }.toTypedArray())
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        // Like Keystore2: a second concurrent operation gets pruned.
        val cipher = object : SecretCipher {
            private val fake = FakeCipher()
            override fun encrypt(plain: String) = fake.encrypt(plain)
            override fun decrypt(encoded: String): String {
                val now = active.incrementAndGet()
                maxActive.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                try {
                    if (now > 1) throw pruned()
                    Thread.sleep(2)
                    return fake.decrypt(encoded)
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        val s = EncryptedStorage({ backend to cipher }, {})
        val pool = Executors.newFixedThreadPool(16)
        val start = CountDownLatch(1)
        val results = keys.flatMap { key -> List(4) { key } }.map { key ->
            pool.submit<Pair<String, String>> { start.await(); key to s.getString(key, "") }
        }
        start.countDown()
        val values = results.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, maxActive.get())
        values.forEach { (key, value) -> assertEquals("v-$key", value) }
        assertEquals(keys.size, backend.data.size)
    }

    @Test
    fun `a busy keystore at start is retried before falling back to memory`() {
        val backend = MapBackend()
        var calls = 0
        val s = EncryptedStorage({ calls++; if (calls == 1) throw pruned() else backend to FakeCipher() }, {}, {})
        assertTrue(s.isPersistent)
        assertEquals(2, calls)
    }
}
