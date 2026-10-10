package com.gatecontrol.android.tunnel

/**
 * Syntactic validator for WireGuard / wg-quick configuration text.
 *
 * This is the Kotlin port of the canonical specification in
 * `gatecontrol-config-hash/spec/wg-config/SPEC.md` and a 1:1 behavioral
 * mirror of the JavaScript/TypeScript implementation
 * (`src/wg-config-validator.ts`). Both implementations are exercised against
 * the same golden fixtures, so they MUST behave identically: same
 * normalization order, same error codes, same regexes/semantics.
 *
 * Pure string/regex parsing only: NO Android APIs, NO I/O, NO network. The
 * validator is fail-closed and never throws on malformed input — every
 * structural problem is reported through `errors`.
 *
 * See SPEC.md for the normative rules. Error codes are a stable contract.
 */

/**
 * Public result type returned by [WgConfigValidator.validate].
 *
 * Result of validating a WireGuard config (see SPEC §1).
 */
data class WgValidationResult(
    /** true iff errors.isEmpty(). Nothing else may set this. */
    val ok: Boolean,
    /** Error codes (see SPEC §4); presence makes ok == false. */
    val errors: List<String>,
    /** Warning codes (see SPEC §4); NEVER affect ok. */
    val warnings: List<String>,
)

object WgConfigValidator {

    /** WG-base64 key material: exactly 43 base64 chars + one '=' pad. */
    private val WG_BASE64 = Regex("^[A-Za-z0-9+/]{43}=$")

    /** Optionally-signed base-10 integer string. */
    private val INT_RE = Regex("^[+-]?\\d+$")

    /** IPv4 octet shape: 1..3 decimal digits. */
    private val IPV4_OCTET = Regex("^\\d{1,3}$")

    /** IPv6 group shape: 1..4 hex digits. */
    private val IPV6_GROUP = Regex("^[0-9A-Fa-f]{1,4}$")

    /** Known [Interface] keys (canonical WireGuard spelling, case-sensitive). */
    private val INTERFACE_KEYS = setOf(
        "PrivateKey",
        "Address",
        "DNS",
        "ListenPort",
        "MTU",
    )

    /** Known [Peer] keys (canonical WireGuard spelling, case-sensitive). */
    private val PEER_KEYS = setOf(
        "PublicKey",
        "PresharedKey",
        "Endpoint",
        "AllowedIPs",
        "PersistentKeepalive",
    )

    /** A parsed config section. */
    private data class Section(
        /** Verbatim header name without brackets, e.g. "Interface". */
        val name: String,
        /** Parsed key/value pairs in order of appearance. */
        val pairs: MutableList<Pair<String, String>> = mutableListOf(),
    )

    private fun isWgBase64(s: String): Boolean = WG_BASE64.matches(s)

    /** Optionally-signed base-10 integer, optional inclusive range. */
    private fun isInt(s: String, min: Long? = null, max: Long? = null): Boolean {
        if (!INT_RE.matches(s)) return false
        if (min == null && max == null) return true
        // toLongOrNull is safe and finite; mirrors JS Number()/Number.isFinite()
        // for the integer strings INT_RE admits (range checks only use small bounds).
        val n = s.toLongOrNull() ?: return false
        if (min != null && n < min) return false
        if (max != null && n > max) return false
        return true
    }

    /** IPv4 dotted-quad, each octet 0..255. */
    private fun isIpv4(s: String): Boolean {
        val parts = s.split(".")
        if (parts.size != 4) return false
        for (p in parts) {
            if (!IPV4_OCTET.matches(p)) return false
            val n = p.toInt()
            if (n < 0 || n > 255) return false
        }
        return true
    }

    /** IPv6 literal. Supports `::` compression and embedded IPv4 tails. */
    private fun isIpv6(s: String): Boolean {
        if (s.isEmpty()) return false
        // At most one '::' compression marker.
        val doubleColonCount = Regex("::").findAll(s).count()
        if (doubleColonCount > 1) return false

        // An embedded IPv4 tail (e.g. ::ffff:1.2.3.4) counts as two groups.
        var head = s
        var tailGroups = 0
        val lastColon = head.lastIndexOf(':')
        if (lastColon != -1 && head.substring(lastColon + 1).contains('.')) {
            val v4 = head.substring(lastColon + 1)
            if (!isIpv4(v4)) return false
            tailGroups = 2
            head = head.substring(0, lastColon + 1) // keep trailing ':' for split logic
        }

        if (doubleColonCount == 1) {
            // JS String.split('::') yields exactly [left, right] for a single occurrence.
            val sepIdx = head.indexOf("::")
            val left = head.substring(0, sepIdx)
            val right = head.substring(sepIdx + 2)
            val leftGroups = if (left == "") emptyList() else left.split(":")
            val rightGroups = if (right == "") emptyList() else right.split(":")
            for (g in leftGroups + rightGroups) {
                if (!IPV6_GROUP.matches(g)) return false
            }
            val total = leftGroups.size + rightGroups.size + tailGroups
            // '::' must compress at least one group, so total groups < 8.
            return total <= 7
        }

        // No compression: must be exactly 8 groups total.
        val trimmed = if (head.endsWith(":")) head.substring(0, head.length - 1) else head
        val groups = if (trimmed == "") emptyList() else trimmed.split(":")
        for (g in groups) {
            if (!IPV6_GROUP.matches(g)) return false
        }
        return groups.size + tailGroups == 8
    }

    /** A bare IP literal (v4 or v6), with NO prefix. */
    private fun isIp(s: String): Boolean = isIpv4(s) || isIpv6(s)

    /** CIDR: `ip/prefix` (v4 0..32, v6 0..128). A bare IP is NOT a CIDR. */
    private fun isCidr(s: String): Boolean {
        val slash = s.lastIndexOf('/')
        if (slash == -1) return false
        val ip = s.substring(0, slash)
        val prefix = s.substring(slash + 1)
        if (!INT_RE.matches(prefix)) return false
        val p = prefix.toLongOrNull() ?: return false
        if (isIpv4(ip)) return p in 0..32
        if (isIpv6(ip)) return p in 0..128
        return false
    }

    /** Comma-separated list; each trimmed entry must satisfy [pred]. Non-empty. */
    private fun isList(value: String, pred: (String) -> Boolean): Boolean {
        if (value.isBlank()) return false
        return value.split(",").map { it.trim() }.all { it.isNotEmpty() && pred(it) }
    }

    /** host:port — split on the LAST ':'; host non-empty, port int 1..65535. */
    private fun isHostPort(value: String): Boolean {
        val idx = value.lastIndexOf(':')
        if (idx == -1) return false
        val host = value.substring(0, idx)
        val port = value.substring(idx + 1)
        if (host.isEmpty()) return false
        return isInt(port, 1, 65535)
    }

    /**
     * Normalize raw input per SPEC §2 (BOM strip, line-ending normalize, split,
     * comment strip, trim, drop blank lines), then parse into ordered sections.
     */
    private fun parseSections(text: String?): List<Section> {
        // 1. Strip BOM. Coerce null to empty so we never throw (SPEC §1).
        var s = text ?: ""
        if (s.isNotEmpty() && s[0].code == 0xFEFF) {
            s = s.substring(1)
        }
        // 2. Normalize line endings (\r\n and lone \r → \n).
        s = s.replace("\r\n", "\n").replace("\r", "\n")
        // 3. Split into lines.
        val rawLines = s.split("\n")

        val sections = mutableListOf<Section>()
        var current: Section? = null

        for (raw in rawLines) {
            // 4. Strip comment: first '#' or ';' to end of line.
            var line = raw
            val hashIdx = line.indexOf('#')
            val semiIdx = line.indexOf(';')
            val cut = when {
                hashIdx != -1 && semiIdx != -1 -> minOf(hashIdx, semiIdx)
                hashIdx != -1 -> hashIdx
                semiIdx != -1 -> semiIdx
                else -> -1
            }
            if (cut != -1) line = line.substring(0, cut)

            // 5. Trim.
            line = line.trim()

            // 6. Ignore blank lines.
            if (line.isEmpty()) continue

            // Section header?
            if (line.startsWith("[") && line.endsWith("]")) {
                val name = line.substring(1, line.length - 1).trim()
                current = Section(name)
                sections.add(current)
                continue
            }

            // Key = Value (split on first '=').
            val eq = line.indexOf('=')
            if (eq == -1) {
                // Lines before the first header are ignored; the server emits none.
                // A non-pair line inside a section is not specified — skip it.
                continue
            }
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            // Pairs before the first header are ignored.
            current?.pairs?.add(key to value)
        }

        return sections
    }

    private fun validateInterface(
        section: Section,
        errors: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        val values = LinkedHashMap<String, String>()
        for ((key, value) in section.pairs) {
            if (key in INTERFACE_KEYS) {
                values[key] = value
            } else {
                warnings.add("unknown_key:$key")
            }
        }

        // PrivateKey (required, WG-base64).
        val privateKey = values["PrivateKey"]
        if (privateKey == null) {
            errors.add("iface_privatekey_missing")
        } else if (!isWgBase64(privateKey)) {
            errors.add("iface_privatekey_format")
        }

        // Address (required, CIDR list).
        val address = values["Address"]
        if (address == null) {
            errors.add("iface_address_missing")
        } else if (!isList(address, ::isCidr)) {
            errors.add("iface_address_cidr")
        }

        // DNS (optional, IP list).
        val dns = values["DNS"]
        if (dns != null && !isList(dns, ::isIp)) {
            errors.add("iface_dns_ip")
        }

        // ListenPort / MTU (optional, integer).
        val listenPort = values["ListenPort"]
        if (listenPort != null && !isInt(listenPort)) {
            errors.add("iface_int")
        }
        val mtu = values["MTU"]
        if (mtu != null && !isInt(mtu)) {
            errors.add("iface_int")
        }
    }

    private fun validatePeer(
        section: Section,
        errors: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        val values = LinkedHashMap<String, String>()
        for ((key, value) in section.pairs) {
            if (key in PEER_KEYS) {
                values[key] = value
            } else {
                warnings.add("unknown_key:$key")
            }
        }

        // PublicKey (required, WG-base64).
        val publicKey = values["PublicKey"]
        if (publicKey == null) {
            errors.add("peer_publickey_missing")
        } else if (!isWgBase64(publicKey)) {
            errors.add("peer_publickey_format")
        }

        // PresharedKey (optional, WG-base64).
        val psk = values["PresharedKey"]
        if (psk != null && !isWgBase64(psk)) {
            errors.add("peer_psk_format")
        }

        // Endpoint (required, host:port).
        val endpoint = values["Endpoint"]
        if (endpoint == null) {
            errors.add("peer_endpoint_missing")
        } else if (!isHostPort(endpoint)) {
            errors.add("peer_endpoint_format")
        }

        // AllowedIPs (required, CIDR list).
        val allowedIps = values["AllowedIPs"]
        if (allowedIps == null) {
            errors.add("peer_allowedips_missing")
        } else if (!isList(allowedIps, ::isCidr)) {
            errors.add("peer_allowedips_cidr")
        }

        // PersistentKeepalive (optional, integer 0..65535).
        val keepalive = values["PersistentKeepalive"]
        if (keepalive != null && !isInt(keepalive, 0, 65535)) {
            errors.add("peer_keepalive")
        }
    }

    /**
     * Validate WireGuard / wg-quick config text for syntactic well-formedness.
     *
     * Fail-closed: never throws; all problems reported via `errors`. Warnings
     * never affect `ok`. There is intentionally NO `trusted` flag and no options.
     * Accepts null (treated as empty config) per SPEC §1.
     */
    fun validate(text: String?): WgValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val sections = parseSections(text)

        var interfaceCount = 0
        var peerCount = 0

        for (section in sections) {
            when (section.name) {
                "Interface" -> {
                    interfaceCount += 1
                    validateInterface(section, errors, warnings)
                }
                "Peer" -> {
                    peerCount += 1
                    validatePeer(section, errors, warnings)
                }
                else -> {
                    // SPEC §3: unknown sections use the same 'unknown_key:' prefix as unknown field keys
                    warnings.add("unknown_key:${section.name}")
                }
            }
        }

        // Exactly one [Interface].
        if (interfaceCount != 1) {
            errors.add("interface_count")
        }
        // At least one [Peer].
        if (peerCount == 0) {
            errors.add("no_peer")
        }

        // Deduplicate (order-preserving) so a code appears at most once; this keeps
        // the JS and Kotlin ports deterministic and identical (SPEC §1).
        val uniqueErrors = errors.distinct()
        val uniqueWarnings = warnings.distinct()

        return WgValidationResult(
            ok = uniqueErrors.isEmpty(),
            errors = uniqueErrors,
            warnings = uniqueWarnings,
        )
    }
}
