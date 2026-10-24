package com.gatecontrol.android.vault

import com.gatecontrol.android.vault.VaultTotpQr.Result
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Base64

class VaultTotpQrTest {

    @Test
    fun `an otpauth totp code is taken as is with issuer and account`() {
        val uri = "otpauth://totp/GitHub:anna%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=GitHub&digits=8"
        val r = VaultTotpQr.parse(" $uri\n") as Result.Accounts
        assertEquals(listOf(VaultTotpQr.Account(uri, "GitHub", "anna@example.com")), r.accounts)
        assertEquals(8, VaultTotp.parse(r.accounts[0].uri)!!.digits)
        // issuer only in the label
        assertEquals("Example", VaultTotpQr.account("otpauth://totp/Example:bob?secret=JBSWY3DPEHPK3PXP").issuer)
        // neither: just the account
        assertEquals(VaultTotpQr.Account("otpauth://totp/bob?secret=JBSWY3DPEHPK3PXP", "", "bob"), VaultTotpQr.account("otpauth://totp/bob?secret=JBSWY3DPEHPK3PXP"))
    }

    @Test
    fun `other codes are refused with the reason`() {
        assertEquals(Result.Hotp, VaultTotpQr.parse("otpauth://hotp/x?secret=JBSWY3DPEHPK3PXP&counter=1"))
        assertEquals(Result.NotTotp, VaultTotpQr.parse("https://example.com"))
        assertEquals(Result.NotTotp, VaultTotpQr.parse("WIFI:T:WPA;S:x;P:y;;"))
        // a totp URI without a valid secret
        assertEquals(Result.NotTotp, VaultTotpQr.parse("otpauth://totp/x?secret=!!"))
        assertEquals(Result.NotTotp, VaultTotpQr.parse("otpauth-migration://offline?data=%%%"))
    }

    @Test
    fun `base32 matches the decoder`() {
        val bytes = "Hello!Þ­¾ï".toByteArray(Charsets.ISO_8859_1)
        assertTrue(bytes.contentEquals(VaultTotp.base32(VaultTotpQr.base32(bytes))))
        assertEquals("JBSWY3DPEHPK3PXP", VaultTotpQr.base32("Hello!Þ­¾ï".toByteArray(Charsets.ISO_8859_1)))
    }

    // ── Google Authenticator export ─────────────────────────────────────

    private fun ByteArrayOutputStream.varint(v: Long) {
        var x = v
        while (x >= 0x80) {
            write(((x and 0x7f) or 0x80).toInt())
            x = x ushr 7
        }
        write(x.toInt())
    }

    private fun ByteArrayOutputStream.bytes(n: Int, b: ByteArray) {
        varint((n shl 3 or 2).toLong())
        varint(b.size.toLong())
        write(b)
    }

    private fun ByteArrayOutputStream.num(n: Int, v: Long) {
        varint((n shl 3).toLong())
        varint(v)
    }

    private fun otp(secret: ByteArray, name: String, issuer: String, alg: Long, digits: Long, type: Long) = ByteArrayOutputStream().apply {
        bytes(1, secret)
        bytes(2, name.toByteArray())
        bytes(3, issuer.toByteArray())
        num(4, alg)
        num(5, digits)
        num(6, type)
    }.toByteArray()

    private fun migration(vararg params: ByteArray): String {
        val payload = ByteArrayOutputStream().apply {
            params.forEach { bytes(1, it) }
            num(2, 1) // version
            num(3, 1) // batch size
        }.toByteArray()
        return "otpauth-migration://offline?data=" + URLEncoder.encode(Base64.getEncoder().encodeToString(payload), "UTF-8")
    }

    @Test
    fun `a Google Authenticator export becomes one totp URI per account, hotp left out`() {
        val s1 = "Hello!Þ­¾ï".toByteArray(Charsets.ISO_8859_1)
        val s2 = ByteArray(20) { it.toByte() }
        val uri = migration(
            otp(s1, "anna@example.com", "GitHub", alg = 1, digits = 1, type = 2),
            otp(s2, "Bank:bob", "Bank", alg = 2, digits = 2, type = 2),
            otp(s2, "counter", "Old", alg = 1, digits = 1, type = 1),
        )
        val r = VaultTotpQr.parse(uri) as Result.Accounts
        assertEquals(2, r.accounts.size)
        val (a, b) = r.accounts
        assertEquals("GitHub", a.issuer)
        assertEquals("anna@example.com", a.name)
        assertEquals("otpauth://totp/GitHub:anna%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=GitHub", a.uri)
        val pa = assertNotNull(VaultTotp.parse(a.uri)).let { VaultTotp.parse(a.uri)!! }
        assertTrue(s1.contentEquals(pa.secret))
        assertEquals("Bank", b.issuer)
        assertEquals("bob", b.name)
        val pb = VaultTotp.parse(b.uri)!!
        assertEquals("SHA256", pb.algorithm)
        assertEquals(8, pb.digits)
        assertTrue(s2.contentEquals(pb.secret))
        // the URIs read back to the same accounts
        assertEquals("Bank", VaultTotpQr.account(b.uri).issuer)
        assertEquals("bob", VaultTotpQr.account(b.uri).name)
    }

    @Test
    fun `an export with only hotp accounts says so`() {
        assertEquals(Result.Hotp, VaultTotpQr.parse(migration(otp(ByteArray(10), "c", "Old", 1, 1, 1))))
    }
}
