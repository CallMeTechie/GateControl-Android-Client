package com.gatecontrol.android.vault

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/**
 * What a scanned QR code means for a 2FA field: an `otpauth://totp/…` URI as is, the accounts of a
 * Google Authenticator export (`otpauth-migration://offline?data=…`, TOTP only), or why it doesn't fit.
 * Pure, unit-tested.
 */
object VaultTotpQr {
    /** One 2FA account: the URI for the `totp` field and its label (issuer / account) for the list. */
    data class Account(val uri: String, val issuer: String, val name: String)

    sealed interface Result {
        data class Accounts(val accounts: List<Account>) : Result
        /** A counter-based code: Vault only does time-based codes. */
        data object Hotp : Result
        /** Some other QR code (a link, a WLAN, …). */
        data object NotTotp : Result
    }

    fun parse(raw: String): Result {
        val s = raw.trim()
        return when {
            s.startsWith("otpauth://totp", ignoreCase = true) ->
                if (VaultTotp.parse(s) != null) Result.Accounts(listOf(account(s))) else Result.NotTotp
            s.startsWith("otpauth://hotp", ignoreCase = true) -> Result.Hotp
            s.startsWith("otpauth-migration://", ignoreCase = true) -> migration(s)
            else -> Result.NotTotp
        }
    }

    /** Issuer and account name of an otpauth URI (issuer parameter first, else the label's prefix). */
    fun account(uri: String): Account {
        val label = runCatching { URLDecoder.decode(uri.substringAfter("://").substringAfter('/').substringBefore('?'), "UTF-8") }.getOrDefault("")
        val issuerParam = uri.substringAfter('?', "").split('&')
            .firstOrNull { it.substringBefore('=').equals("issuer", ignoreCase = true) }
            ?.let { runCatching { URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }.getOrNull() }
            ?.trim().orEmpty()
        val prefix = if (':' in label) label.substringBefore(':').trim() else ""
        val name = if (':' in label) label.substringAfter(':').trim() else label.trim()
        return Account(uri, issuerParam.ifEmpty { prefix }, name)
    }

    // ── Google Authenticator export ─────────────────────────────────────

    private fun migration(uri: String): Result {
        val data = uri.substringAfter('?', "").split('&')
            .firstOrNull { it.substringBefore('=') == "data" }?.substringAfter('=', "")
            ?: return Result.NotTotp
        val bytes = runCatching {
            val b64 = URLDecoder.decode(data, "UTF-8").replace('-', '+').replace('_', '/').replace(" ", "+")
            Base64.getDecoder().decode(b64.padEnd((b64.length + 3) / 4 * 4, '='))
        }.getOrNull() ?: return Result.NotTotp
        val params = runCatching { Proto(bytes).fields().filter { it.first == 1 }.mapNotNull { it.second as? ByteArray } }.getOrNull()
            ?: return Result.NotTotp
        var hotp = false
        val accounts = params.mapNotNull { p ->
            runCatching {
                val f = Proto(p).fields()
                fun bytes(n: Int) = f.firstOrNull { it.first == n }?.second as? ByteArray
                fun num(n: Int) = (f.firstOrNull { it.first == n }?.second as? Long) ?: 0L
                val secret = bytes(1)?.takeIf { it.isNotEmpty() } ?: return@runCatching null
                val name = bytes(2)?.toString(Charsets.UTF_8).orEmpty()
                val issuer = bytes(3)?.toString(Charsets.UTF_8).orEmpty()
                // type: 1 HOTP, 2 TOTP (0 unspecified counts as TOTP)
                if (num(6) == 1L) {
                    hotp = true
                    return@runCatching null
                }
                val alg = when (num(4)) { 2L -> "SHA256"; 3L -> "SHA512"; 4L -> return@runCatching null; else -> "SHA1" }
                val digits = if (num(5) == 2L) 8 else 6
                Account(totpUri(secret, issuer, name, alg, digits), issuer, name.substringAfter(':').trim().ifEmpty { name })
            }.getOrNull()
        }
        return when {
            accounts.isNotEmpty() -> Result.Accounts(accounts)
            hotp -> Result.Hotp
            else -> Result.NotTotp
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun totpUri(secret: ByteArray, issuer: String, name: String, alg: String, digits: Int): String {
        val label = if (issuer.isNotEmpty() && !name.startsWith("$issuer:")) "$issuer:$name" else name
        val sb = StringBuilder("otpauth://totp/").append(enc(label).replace("%3A", ":"))
            .append("?secret=").append(base32(secret))
        if (issuer.isNotEmpty()) sb.append("&issuer=").append(enc(issuer))
        if (alg != "SHA1") sb.append("&algorithm=").append(alg)
        if (digits != 6) sb.append("&digits=").append(digits)
        return sb.toString()
    }

    internal fun base32(b: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out = StringBuilder()
        var acc = 0
        var bits = 0
        for (x in b) {
            acc = (acc shl 8) or (x.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(alphabet[(acc shr bits) and 31])
            }
            acc = acc and ((1 shl bits) - 1)
        }
        if (bits > 0) out.append(alphabet[(acc shl (5 - bits)) and 31])
        return out.toString()
    }

    /** Minimal protobuf reader: (field number, Long for varints / ByteArray for length-delimited). */
    private class Proto(private val b: ByteArray) {
        private var i = 0

        private fun varint(): Long {
            var r = 0L
            var shift = 0
            while (true) {
                require(i < b.size && shift < 64)
                val x = b[i++].toInt() and 0xff
                r = r or ((x and 0x7f).toLong() shl shift)
                if (x and 0x80 == 0) return r
                shift += 7
            }
        }

        fun fields(): List<Pair<Int, Any>> {
            val out = mutableListOf<Pair<Int, Any>>()
            while (i < b.size) {
                val key = varint()
                val n = (key ushr 3).toInt()
                when ((key and 7).toInt()) {
                    0 -> out += n to varint()
                    2 -> {
                        val len = varint().toInt()
                        require(len >= 0 && i + len <= b.size)
                        out += n to b.copyOfRange(i, i + len)
                        i += len
                    }
                    1 -> i += 8
                    5 -> i += 4
                    else -> throw IllegalArgumentException("wire type")
                }
                require(i <= b.size)
            }
            return out
        }
    }
}
