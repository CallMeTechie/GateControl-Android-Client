package com.gatecontrol.android.vault

/**
 * The Wi-Fi of an entry as its QR code and a share (plugin gatecontrol-vault 1.4.0, README "WLAN-Freigaben") need it.
 * The same rules as the portal tab (VaultLogic.wifiOf) and the Windows apps (client-core utils/vault.js wifiOf):
 * no password, or security none / offen / open / keine → `nopass`; WEP → `WEP`; WPA3 / SAE without WPA2 → `SAE`;
 * else `WPA`. `hidden` when the field `hidden` is true / 1 / ja / yes. Pure: unit-tested.
 */
object VaultWifiQr {
    data class Wifi(val ssid: String, val security: String, val password: String, val hidden: Boolean)

    private val OPEN = Regex("^(NONE|OFFEN|OPEN|KEINE)$")
    private val SAE = Regex("SAE|WPA3")
    private val HIDDEN = Regex("^(true|1|ja|yes)$", RegexOption.IGNORE_CASE)
    private val ESCAPE = Regex("""([\\;,:"])""")

    /** Is the field `hidden` („Verstecktes Netz“) on: true / 1 / ja / yes. */
    fun hiddenOn(v: String): Boolean = HIDDEN.matches(v.trim())

    /** The editor's „Verstecktes Netz“: "true" when on, "" (the field goes) when off. */
    fun hiddenValue(on: Boolean): String = if (on) "true" else ""

    /** The Wi-Fi of [data], or null without a network name. */
    fun of(data: EntryData): Wifi? {
        val ssid = data.field("ssid")
        if (ssid.isEmpty()) return null
        val pw = data.field("password")
        val sec = data.field("security").uppercase()
        val type = when {
            pw.isEmpty() || OPEN.matches(sec) -> "nopass"
            "WEP" in sec -> "WEP"
            SAE.containsMatchIn(sec) && "WPA2" !in sec -> "SAE"
            else -> "WPA"
        }
        return Wifi(ssid, type, if (type == "nopass") "" else pw, hiddenOn(data.field("hidden")))
    }

    private fun esc(s: String) = ESCAPE.replace(s) { "\\" + it.value }

    /** `WIFI:T:<type>;S:<ssid>;P:<password>;H:true;;` — `\ ; , : "` escaped with `\`, no P for nopass. */
    fun text(w: Wifi): String = buildString {
        append("WIFI:T:").append(w.security).append(";S:").append(esc(w.ssid)).append(';')
        if (w.security != "nopass") append("P:").append(esc(w.password)).append(';')
        if (w.hidden) append("H:true;")
        append(';')
    }

    /** The QR code's text of an entry, or null without a network name. */
    fun text(data: EntryData): String? = of(data)?.let { text(it) }

    /** Does a running share still hold what the entry holds? */
    fun same(data: EntryData, share: VWifiShare): Boolean {
        val w = of(data) ?: return false
        return w.ssid == share.ssid && w.security == share.security && w.password == share.password && w.hidden == share.hidden
    }

    const val TARGET_WALL = "wall"
    const val TARGET_PORTAL = "portal"
    val TARGETS = listOf(TARGET_WALL, TARGET_PORTAL)
    val DURATIONS = listOf("5m", "1h", "24h", "forever")

    /** The targets a writer may still start: one share per target, the wall only with the Familie plugin. */
    fun starts(shares: VWifiShares, writable: Boolean): List<String> =
        if (!writable) emptyList() else TARGETS.filter { t -> shares.shares.none { it.target == t } && (t != TARGET_WALL || shares.wall) }
}
