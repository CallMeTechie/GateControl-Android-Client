package com.gatecontrol.android.vault

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 2FA codes (TOTP, RFC 6238) from an entry's `totp` field: an `otpauth://totp/…?secret=…` URI (algorithm SHA1 /
 * SHA256 / SHA512, 6–8 digits, period) or a bare base32 secret (SHA1, 6 digits, 30 s). Pure, unit-tested.
 */
object VaultTotp {
    data class Params(val secret: ByteArray, val algorithm: String = "SHA1", val digits: Int = 6, val period: Int = 30) {
        override fun equals(other: Any?) = other is Params && secret.contentEquals(other.secret) && algorithm == other.algorithm &&
            digits == other.digits && period == other.period
        override fun hashCode() = secret.contentHashCode() * 31 + digits
    }

    data class Code(val code: String, val secondsLeft: Int, val period: Int)

    fun base32(text: String): ByteArray? {
        val s = text.uppercase().replace(Regex("[\\s=-]"), "")
        if (s.isEmpty() || s.any { it !in 'A'..'Z' && it !in '2'..'7' }) return null
        val out = java.io.ByteArrayOutputStream()
        var acc = 0
        var bits = 0
        for (c in s) {
            acc = (acc shl 5) or (if (c in 'A'..'Z') c - 'A' else c - '2' + 26)
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((acc shr bits) and 0xff)
            }
            acc = acc and ((1 shl bits) - 1)
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    /** Pure parse of the query of an otpauth URI (no android.net.Uri, so unit tests run on the JVM). */
    private fun query(uri: String): Map<String, String> {
        val q = uri.substringAfter('?', "").substringBefore('#')
        return q.split('&').mapNotNull { kv ->
            val k = kv.substringBefore('=').lowercase()
            val v = kv.substringAfter('=', "")
            if (k.isEmpty()) null else k to java.net.URLDecoder.decode(v, "UTF-8")
        }.toMap()
    }

    fun parse(field: String): Params? {
        val f = field.trim()
        if (f.isEmpty()) return null
        if (!f.startsWith("otpauth://", ignoreCase = true)) return base32(f)?.let { Params(it) }
        if (!f.substring(10).startsWith("totp", ignoreCase = true)) return null
        val q = query(f)
        val secret = base32(q["secret"] ?: return null) ?: return null
        val alg = (q["algorithm"] ?: "SHA1").uppercase().takeIf { it in setOf("SHA1", "SHA256", "SHA512") } ?: return null
        val digits = q["digits"]?.toIntOrNull()?.takeIf { it in 6..8 } ?: 6
        val period = q["period"]?.toIntOrNull()?.takeIf { it in 10..300 } ?: 30
        return Params(secret, alg, digits, period)
    }

    /** The issuer / account an otpauth URI names (for a new entry's title), else null. */
    fun label(field: String): String? {
        if (!field.startsWith("otpauth://", ignoreCase = true)) return null
        val issuer = query(field)["issuer"]
        val path = runCatching { java.net.URLDecoder.decode(field.substringAfter("://").substringAfter('/').substringBefore('?'), "UTF-8") }.getOrNull()
        return issuer?.takeIf { it.isNotBlank() } ?: path?.substringBefore(':')?.takeIf { it.isNotBlank() }
    }

    fun code(p: Params, nowMs: Long = System.currentTimeMillis()): Code {
        val counter = nowMs / 1000 / p.period
        val mac = Mac.getInstance("Hmac" + p.algorithm)
        mac.init(SecretKeySpec(p.secret, "RAW"))
        val h = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(counter).array())
        val o = h[h.size - 1].toInt() and 0x0f
        val bin = ((h[o].toInt() and 0x7f) shl 24) or ((h[o + 1].toInt() and 0xff) shl 16) or ((h[o + 2].toInt() and 0xff) shl 8) or (h[o + 3].toInt() and 0xff)
        var mod = 1
        repeat(p.digits) { mod *= 10 }
        val code = (bin % mod).toString().padStart(p.digits, '0')
        val left = p.period - ((nowMs / 1000) % p.period).toInt()
        return Code(code, left, p.period)
    }

    /** "482913" → "482 913" (groups of 3, 4 for 8 digits). */
    fun pretty(code: String): String = when (code.length) {
        6 -> code.substring(0, 3) + " " + code.substring(3)
        8 -> code.substring(0, 4) + " " + code.substring(4)
        else -> code
    }
}
