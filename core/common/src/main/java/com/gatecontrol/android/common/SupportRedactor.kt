package com.gatecontrol.android.common

/**
 * Redaction for support bundles ("Support-Paket senden").
 *
 * Every string that goes into a bundle passes through [redactText] before it
 * leaves the device; object keys that name a secret lose their value
 * ([isSecretKey]). Same rule set as gatecontrol-client-core
 * `src/support/redact.js` and the server pass (`src/utils/supportRedact.js`):
 *
 *  - WireGuard `PrivateKey = …` / `PresharedKey = …` lines
 *  - Authorization / Proxy-Authorization / Cookie / Set-Cookie /
 *    X-API-Token / X-API-Key header values
 *  - `key=value` / `"key": "value"` pairs with a secret-like key name
 *  - GateControl API tokens (gc_…), JWTs, PEM private keys, WireGuard-style
 *    base64 keys (44 chars, "=" padded), hex strings of 32+ chars, setup
 *    codes XXXX-XXXX-XXXX-XXXX
 *
 * All patterns are linear-time (no nested or unbounded lazy quantifiers).
 */
object SupportRedactor {

    const val MASK = "[REDACTED]"

    private val SECRET_KEY = Regex(
        "(pass(word|wd|phrase)?|pwd|secret|token|api[-_]?key|apikey|private[-_]?key|preshared[-_]?key|psk|cookie|" +
            "authori[sz]ation|credential|enrol(l)?ment[-_]?code|setup[-_]?code|machine[-_]?key|session[-_]?id)",
        RegexOption.IGNORE_CASE,
    )

    /** Keys never copied into a bundle (prototype pollution on JS consumers). */
    val UNSAFE_KEYS = setOf("__proto__", "constructor", "prototype")

    private const val M = "\\[REDACTED\\]"

    // Order matters: specific structures first, generic patterns last.
    private val RULES: List<Pair<Regex, String>> = listOf(
        // PEM private keys: base64 after the BEGIN line, long base64 lines
        Regex("(-----BEGIN [A-Z0-9 ]{0,40}PRIVATE KEY-----)[A-Za-z0-9+/=\\s]*") to "$1$M\n",
        Regex("^[A-Za-z0-9+/]{60,}={0,2}$", RegexOption.MULTILINE) to M,
        // WireGuard config secrets
        Regex("^(\\s*(?:PrivateKey|PresharedKey)\\s*=\\s*).*$", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)) to "$1$M",
        // HTTP auth / cookie headers
        Regex("((?:proxy-)?authorization[\"']?\\s*[:=]\\s*[\"']?)(?:(?:bearer|basic|digest|token)\\s+)?[^\\s\"',;]+", RegexOption.IGNORE_CASE) to "$1$M",
        Regex("((?:set-)?cookie[\"']?\\s*[:=]\\s*[\"']?)[^\\r\\n\"']+", RegexOption.IGNORE_CASE) to "$1$M",
        Regex("(x-api-(?:token|key)[\"']?\\s*[:=]\\s*[\"']?)[^\\s\"',;]+", RegexOption.IGNORE_CASE) to "$1$M",
        // key=value and "key": "value" with a secret-like key name
        Regex(
            "\\b([A-Za-z0-9_-]{0,40}?(?:password|passwd|pwd|secret|token|api[-_]?key|apikey|private[-_]?key|preshared[-_]?key|psk|" +
                "enrol(?:l)?ment[-_]?code|setup[-_]?code|credential)s?[\"']?\\s*[:=]\\s*[\"']?)(?!\\[REDACTED\\])[^\\s\"'&,;}]+",
            RegexOption.IGNORE_CASE,
        ) to "$1$M",
        // Free-standing secrets
        Regex("\\bgc_[A-Za-z0-9_-]{6,}") to "gc_$M",
        Regex("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}") to M,
        Regex("(^|[^A-Za-z0-9+/])[A-Za-z0-9+/]{42}[AEIMQUYcgkosw048]=(?![A-Za-z0-9+/=])") to "$1$M",
        Regex("\\b[A-Fa-f0-9]{32,}\\b") to M,
        Regex("\\b[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}\\b") to M,
    )

    /** Masks every secret in [input]; ordinary diagnostics stay untouched. */
    fun redactText(input: String): String {
        if (input.isEmpty()) return input
        var out = input
        for ((re, replacement) in RULES) out = re.replace(out, replacement)
        return out
    }

    /** True when an object key names a secret (its value is masked). */
    fun isSecretKey(key: String): Boolean = SECRET_KEY.containsMatchIn(key)

    /**
     * Redacts a simple value tree (String / Number / Boolean / null / List / Map)
     * as built for a bundle: secret-named keys lose non-empty values, unsafe
     * keys are dropped, all strings pass [redactText].
     */
    fun redactValue(value: Any?, depth: Int = 0): Any? = when {
        depth > 32 -> MASK
        value is String -> redactText(value)
        value is List<*> -> value.map { redactValue(it, depth + 1) }
        value is Map<*, *> -> buildMap {
            for ((k, v) in value) {
                val key = k?.toString() ?: continue
                if (key in UNSAFE_KEYS) continue
                put(
                    key,
                    if (isSecretKey(key) && v != null && v != "" && v !is Boolean) MASK else redactValue(v, depth + 1),
                )
            }
        }
        else -> value
    }
}
