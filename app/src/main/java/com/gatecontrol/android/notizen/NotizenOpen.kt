package com.gatecontrol.android.notizen

/**
 * Where a tap on an item of another plugin leads (pure, unit-tested): its `source.open { plugin, route }` — for the
 * apps `gatecontrol://plugin/<plugin>/<route>` (docs/notes-sources-contract.md § 5) — mapped to the screen this app has
 * for it; a route the app has no screen for opens that plugin's own tab (or, without one, the item's details stay).
 */
object NotizenOpen {

    sealed interface Target
    /** A mail of the Mail tab. */
    data class MailMessage(val id: Long) : Target
    /** A file's page in Drive (a deadline's document). */
    data class DriveFile(val id: Long) : Target
    /** A screen of the Familie tab ([com.gatecontrol.android.familie.FamilieDest] key). */
    data class Familie(val dest: String) : Target
    data class Note(val id: Long) : Target
    data class Task(val id: Long) : Target
    /** The plugin's start screen (its tab). */
    data class Tab(val plugin: String) : Target

    private const val MAIL = "gatecontrol-mail"
    private const val DRIVE = "gatecontrol-drive"
    private const val FAMILIE = "gatecontrol-familie"
    private const val SCHULE = "gatecontrol-schule"
    private const val ALLTAG = "gatecontrol-alltag"
    private val UUID = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")

    /** What the app can open here. */
    data class Have(val mail: Boolean, val drive: Boolean, val familie: Boolean, val schule: Boolean)

    /** `gatecontrol://plugin/<plugin>/<route>` — the link the contract names (also for sharing outside the app). */
    fun link(open: NOpen): String = "gatecontrol://plugin/${open.plugin}/${open.route}"

    /** The link's parts again; null when it is not one. */
    fun parse(link: String): NOpen? {
        val m = Regex("^gatecontrol://plugin/(gatecontrol-[a-z]{1,40})/?([A-Za-z0-9/._:=-]{0,200})$").matchEntire(link) ?: return null
        return NOpen(m.groupValues[1], m.groupValues[2])
    }

    fun target(open: NOpen?, have: Have): Target? {
        if (open == null) return null
        val parts = open.route.split('/')
        val id = parts.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 && parts.size == 2 }
        return when (open.plugin) {
            NOTIZEN_PLUGIN -> when {
                parts[0] == "note" && id != null -> Note(id)
                parts[0] == "task" && id != null -> Task(id)
                else -> Tab(NOTIZEN_PLUGIN)
            }
            MAIL -> when {
                !have.mail -> null
                parts[0] == "message" && id != null -> MailMessage(id)
                else -> Tab(MAIL)
            }
            DRIVE -> when {
                !have.drive -> null
                parts[0] == "node" && id != null -> DriveFile(id)
                else -> Tab(DRIVE)
            }
            FAMILIE -> when {
                !have.familie -> null
                parts[0] == "task" -> Familie("tasks")
                parts[0] == "shopping" && parts.size == 2 && UUID.matches(parts[1]) -> Familie("shopping/${parts[1]}")
                parts[0] == "shopping" -> Familie("shopping")
                // packing lists and the pinboard live in this hub's notebooks now
                else -> Familie("home")
            }
            // Schule has no tab of its own: its homework, exams and parent evenings are the Familie tab's „Stundenplan“
            SCHULE -> if (have.familie && have.schule) Familie("school") else null
            // Alltag's waste collection is among the appointments of the Familie tab
            ALLTAG -> if (have.familie) Familie("dates") else null
            else -> null
        }
    }
}
