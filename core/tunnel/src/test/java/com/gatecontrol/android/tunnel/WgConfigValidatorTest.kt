package com.gatecontrol.android.tunnel

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.security.MessageDigest

/**
 * Conformance tests for [WgConfigValidator], the Kotlin 1:1 port of the
 * canonical JS validator. Both are exercised against the SAME golden fixtures,
 * vendored into test resources at `wg-config-fixtures/`.
 *
 * Includes an integrity-hash assertion that detects local corruption of the
 * vendored fixture copy.
 */
class WgConfigValidatorTest {

    private companion object {
        const val FIXTURES_DIR = "wg-config-fixtures"

        /** A parsed fixture per SPEC §7. */
        data class Fixture(
            val name: String,
            val config: String,
            val expect: String,
            val errorContains: String?,
        )

        /**
         * Resolve the vendored fixtures directory on disk. We need the real
         * directory (not just classpath streams) so we can enumerate the
         * *.json files and read their RAW bytes for the integrity hash exactly
         * the way the sync script does.
         */
        fun fixturesDir(): File {
            val classLoader = checkNotNull(WgConfigValidatorTest::class.java.classLoader) {
                "No classloader available to locate test resources"
            }
            val url = checkNotNull(classLoader.getResource(FIXTURES_DIR)) {
                "Fixtures resource directory '$FIXTURES_DIR' not found on test classpath"
            }
            return File(url.toURI())
        }

        /** All *.json fixture files, sorted ascending by filename. */
        fun fixtureJsonFiles(): List<File> =
            fixturesDir()
                .listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?.sortedBy { it.name }
                ?: error("No fixture files found in $FIXTURES_DIR")

        fun parseFixture(file: File): Fixture {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            return Fixture(
                name = json.getString("name"),
                config = json.getString("config"),
                expect = json.getString("expect"),
                errorContains = if (json.has("errorContains")) json.getString("errorContains") else null,
            )
        }

        fun loadFixtures(): List<Fixture> = fixtureJsonFiles().map { parseFixture(it) }
    }

    @TestFactory
    fun `fixtures conform to the validator contract`(): List<DynamicTest> {
        val fixtures = loadFixtures()
        // Sanity: at least one fixture must load. The .fixtures-hash integrity
        // test guards sync completeness; a magic count here breaks confusingly
        // whenever a fixture is added.
        assertTrue(fixtures.isNotEmpty(), "No fixtures loaded")
        return fixtures.map { fx ->
            DynamicTest.dynamicTest(fx.name) {
                val result = WgConfigValidator.validate(fx.config)
                val expectValid = fx.expect == "valid"
                assertEquals(
                    expectValid,
                    result.ok,
                    "Fixture '${fx.name}': expected ok=$expectValid but was ${result.ok} " +
                        "(errors=${result.errors}, warnings=${result.warnings})",
                )
                if (!expectValid && fx.errorContains != null) {
                    assertTrue(
                        result.errors.contains(fx.errorContains),
                        "Fixture '${fx.name}': expected errors to contain " +
                            "'${fx.errorContains}' but errors=${result.errors}",
                    )
                }
            }
        }
    }

    @Test
    fun `unknown key produces warning and stays ok`() {
        val config = buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = CyeI87ssPVm18g0yRG9AZV0vdIe9qtkKvFKsOlTCTHI=")
            appendLine("Address = 10.8.0.2/32")
            appendLine("SomeMadeUpKey = whatever")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = 86R0I45ZRx/P7WQdj+GkW+q0+MU0cS4Zccy+CVTTvY4=")
            appendLine("Endpoint = gate.example.com:51820")
            appendLine("AllowedIPs = 0.0.0.0/0")
        }
        val result = WgConfigValidator.validate(config)
        assertTrue(result.ok, "Unknown key must not affect ok; errors=${result.errors}")
        assertTrue(
            result.warnings.any { it.startsWith("unknown_key:") },
            "Expected an unknown_key warning; warnings=${result.warnings}",
        )
        assertTrue(result.warnings.contains("unknown_key:SomeMadeUpKey"))
    }

    @Test
    fun `unknown section produces warning and stays ok`() {
        val config = buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = CyeI87ssPVm18g0yRG9AZV0vdIe9qtkKvFKsOlTCTHI=")
            appendLine("Address = 10.8.0.2/32")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = 86R0I45ZRx/P7WQdj+GkW+q0+MU0cS4Zccy+CVTTvY4=")
            appendLine("Endpoint = gate.example.com:51820")
            appendLine("AllowedIPs = 0.0.0.0/0")
            appendLine()
            appendLine("[CustomSection]")
            appendLine("Foo = bar")
        }
        val result = WgConfigValidator.validate(config)
        assertTrue(result.ok, "Unknown section must not affect ok; errors=${result.errors}")
        // SPEC §4: bare section name, no brackets.
        assertTrue(result.warnings.contains("unknown_key:CustomSection"))
    }

    @Test
    fun `null input is not ok and does not throw`() {
        val result = WgConfigValidator.validate(null)
        assertFalse(result.ok)
        // Empty config: no interface and no peer.
        assertTrue(result.errors.contains("interface_count"))
        assertTrue(result.errors.contains("no_peer"))
    }

    @Test
    fun `blank input is not ok and does not throw`() {
        val result = WgConfigValidator.validate("   \n  \r\n \t ")
        assertFalse(result.ok)
        assertTrue(result.errors.contains("interface_count"))
        assertTrue(result.errors.contains("no_peer"))
    }

    @Test
    fun `iface_int appears exactly once when both ListenPort and MTU are bad`() {
        val fx = loadFixtures().first { it.name == "invalid_iface_int" }
        val result = WgConfigValidator.validate(fx.config)
        assertFalse(result.ok)
        assertEquals(
            1,
            result.errors.count { it == "iface_int" },
            "iface_int must be deduplicated to a single occurrence; errors=${result.errors}",
        )
    }

    @Test
    fun `vendored fixtures integrity hash matches`() {
        // Recompute the hash exactly the way scripts/sync-wg-fixtures.sh does:
        //   cat $(ls -1 *.json | sort) | sha256sum
        // i.e. concatenate the RAW BYTES of the *.json files (ONLY .json,
        // NOT .fixtures-hash / VENDORED.md) in ascending filename order, then
        // SHA-256, hex-encoded.
        val jsonFiles = fixtureJsonFiles()
        val digest = MessageDigest.getInstance("SHA-256")
        for (f in jsonFiles) {
            digest.update(f.readBytes())
        }
        val computed = digest.digest().joinToString("") { "%02x".format(it) }

        val hashFile = File(fixturesDir(), ".fixtures-hash")
        assertTrue(hashFile.isFile, ".fixtures-hash must exist in vendored fixtures")
        val expected = hashFile.readText(Charsets.UTF_8).trim()

        assertNotNull(expected)
        assertEquals(
            expected,
            computed,
            "Vendored fixture integrity hash mismatch — the vendored copy was " +
                "locally corrupted (or .fixtures-hash is stale).",
        )
    }
}
