package com.gatecontrol.android.common

import org.json.JSONArray
import org.json.JSONObject

/**
 * Client policy defined by the administrator on the server
 * (GET /api/v1/client/policy, "Client-Richtlinien").
 *
 * Semantics on the client:
 *  - never fetched → [UNRESTRICTED] (no restriction at all),
 *  - fetched once → persisted, applies offline and while the server is
 *    unreachable (last known policy),
 *  - unknown / broken field values fall back to the unrestricted value of
 *    that field (a damaged answer must not lock the user out).
 *
 * Android limits: an app cannot switch on the system "Always-on VPN" or
 * "Block connections without VPN" itself. When the policy requires a kill
 * switch or always-on, the app shows a hint with a button to the system VPN
 * settings ([needsSystemVpnSettings]); what the app can enforce it does
 * (auto-connect on boot/app start, no in-app disconnect, split-tunnel and
 * settings lock, hidden server change).
 *
 * This is a management convenience, not a security boundary against the
 * device owner.
 */
data class ClientPolicy(
    val killSwitch: KillSwitch = KillSwitch.USER,
    val autoConnect: AutoConnect = AutoConnect.USER,
    val autostart: Autostart = Autostart.USER,
    val splitTunnelModes: Set<SplitTunnelMode> = SplitTunnelMode.entries.toSet(),
    val splitTunnelLocked: Boolean = false,
    val lockSettings: Boolean = false,
    val lockServer: Boolean = false,
) {
    enum class KillSwitch(val wire: String) { USER("user"), REQUIRED("required") }
    enum class AutoConnect(val wire: String) { USER("user"), REQUIRED("required"), ALWAYS_ON("always_on") }
    enum class Autostart(val wire: String) { USER("user"), REQUIRED("required"), FORBIDDEN("forbidden") }

    /** Anything restricted compared to [UNRESTRICTED]. */
    val managed: Boolean get() = this != UNRESTRICTED

    /** The user may disconnect the tunnel in the app / tile. */
    val canDisconnect: Boolean get() = autoConnect != AutoConnect.ALWAYS_ON

    /**
     * Forced value of the "connect automatically" (boot) setting, null when
     * the user decides. Auto-connect required wins over autostart forbidden.
     */
    val forcedAutoConnect: Boolean?
        get() = when {
            autoConnect != AutoConnect.USER -> true
            autostart == Autostart.REQUIRED -> true
            autostart == Autostart.FORBIDDEN -> false
            else -> null
        }

    /** The auto-connect switch is disabled in the settings. */
    val autoConnectLocked: Boolean get() = forcedAutoConnect != null || lockSettings

    /** Split-tunnel mode can not be changed by the user at all. */
    val splitTunnelFrozen: Boolean get() = lockSettings || splitTunnelLocked

    /** Kill switch / always-on can only be set by the user in the system settings. */
    val needsSystemVpnSettings: Boolean
        get() = killSwitch == KillSwitch.REQUIRED || autoConnect == AutoConnect.ALWAYS_ON

    fun isModeAllowed(mode: SplitTunnelMode): Boolean = mode in splitTunnelModes

    /**
     * [mode] if allowed, otherwise full tunnel when allowed, otherwise the
     * first allowed mode (off → exclude → include).
     */
    fun clampMode(mode: SplitTunnelMode): SplitTunnelMode = when {
        isModeAllowed(mode) -> mode
        isModeAllowed(SplitTunnelMode.OFF) -> SplitTunnelMode.OFF
        else -> SplitTunnelMode.entries.first { isModeAllowed(it) }
    }

    fun toJson(): String = JSONObject()
        .put("killSwitch", killSwitch.wire)
        .put("autoConnect", autoConnect.wire)
        .put("autostart", autostart.wire)
        .put("splitTunnelModes", JSONArray(SplitTunnelMode.entries.filter { it in splitTunnelModes }.map { it.wire }))
        .put("splitTunnelLocked", splitTunnelLocked)
        .put("lockSettings", lockSettings)
        .put("lockServer", lockServer)
        .toString()

    companion object {
        val UNRESTRICTED = ClientPolicy()

        /** Builds a policy from raw server values; invalid values fall back to unrestricted. */
        fun from(
            killSwitch: String?,
            autoConnect: String?,
            autostart: String?,
            splitTunnelModes: List<String>?,
            splitTunnelLocked: Boolean?,
            lockSettings: Boolean?,
            lockServer: Boolean?,
        ): ClientPolicy {
            val modes = splitTunnelModes.orEmpty().mapNotNull { raw ->
                SplitTunnelMode.entries.firstOrNull { it.wire == raw?.trim()?.lowercase() }
            }.toSet()
            return ClientPolicy(
                killSwitch = KillSwitch.entries.firstOrNull { it.wire == killSwitch } ?: KillSwitch.USER,
                autoConnect = AutoConnect.entries.firstOrNull { it.wire == autoConnect } ?: AutoConnect.USER,
                autostart = Autostart.entries.firstOrNull { it.wire == autostart } ?: Autostart.USER,
                splitTunnelModes = modes.ifEmpty { SplitTunnelMode.entries.toSet() },
                splitTunnelLocked = splitTunnelLocked == true,
                lockSettings = lockSettings == true,
                lockServer = lockServer == true,
            )
        }

        /** Parses [toJson] output; null when the text is not a policy. */
        fun fromJson(json: String?): ClientPolicy? {
            if (json.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(json)
                val modesArr = obj.optJSONArray("splitTunnelModes")
                val modes = modesArr?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
                from(
                    killSwitch = obj.optString("killSwitch", ""),
                    autoConnect = obj.optString("autoConnect", ""),
                    autostart = obj.optString("autostart", ""),
                    splitTunnelModes = modes,
                    splitTunnelLocked = obj.optBoolean("splitTunnelLocked", false),
                    lockSettings = obj.optBoolean("lockSettings", false),
                    lockServer = obj.optBoolean("lockServer", false),
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
