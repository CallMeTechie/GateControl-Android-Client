package com.gatecontrol.android.layout

import com.gatecontrol.android.network.ClientLayoutDto

/**
 * The order of the start and Familie tabs: entry ids in display order and
 * the ids hidden (`GET/PUT /api/v1/client/layout`, core 1.181+). The app
 * owns the ids ([StartLayout], [FamilieLayout]); the server only checks
 * their shape. Kept on the device as well, so the tabs show the order
 * without asking the server first (and on older servers only there).
 */
data class TabLayout(
    val start: List<String> = emptyList(),
    val startHidden: List<String> = emptyList(),
    val familie: List<String> = emptyList(),
    val familieHidden: List<String> = emptyList(),
) {
    /** Every list in the shape the server accepts (see [clean]). */
    fun sanitized(): TabLayout = TabLayout(clean(start), clean(startHidden), clean(familie), clean(familieHidden))

    fun toDto(): ClientLayoutDto = ClientLayoutDto(start, startHidden, familie, familieHidden)

    companion object {
        /** Entries per list the server keeps. */
        const val MAX_ENTRIES = 100
        private val ENTRY = Regex("^[A-Za-z0-9:_./@-]{1,120}$")

        fun isValidEntry(id: String): Boolean = ENTRY.matches(id)

        /** Valid entries only, first occurrence of each, at most [MAX_ENTRIES]. */
        @Suppress("SENSELESS_COMPARISON")
        fun clean(list: List<String>?): List<String> =
            // Gson bypasses Kotlin null-safety: a stored or served list may hold nulls.
            list.orEmpty().filter { it != null && isValidEntry(it) }.distinct().take(MAX_ENTRIES)

        fun from(dto: ClientLayoutDto?): TabLayout? = dto?.let {
            TabLayout(clean(it.start), clean(it.startHidden), clean(it.familie), clean(it.familieHidden))
        }

        /**
         * [local] plus the entries of [remote] this device does not know
         * (a widget pinned only on another phone), each placed after the
         * entry it followed there — so saving here does not drop another
         * device's place for it. [identity] compares entries (the start
         * tab's width prefix does not make a widget another one). Foreign
         * entries are the first to go when the list would grow past
         * [MAX_ENTRIES].
         */
        fun keepForeign(local: List<String>, remote: List<String>, identity: (String) -> String = { it }): List<String> {
            val known = local.map(identity).toSet()
            val result = local.toMutableList()
            var room = MAX_ENTRIES - local.size
            var anchor: String? = null
            for (entry in remote) {
                val id = identity(entry)
                if (id in known) {
                    anchor = id
                    continue
                }
                if (room <= 0) break
                if (result.any { identity(it) == id }) continue
                val at = anchor?.let { a -> result.indexOfFirst { identity(it) == a } + 1 } ?: 0
                result.add(at, entry)
                anchor = id
                room--
            }
            return result
        }

        /** [list] with the item at [index] moved by [delta] places (accessibility "move up/down"); unchanged at the ends. */
        fun <T> moveBy(list: List<T>, index: Int, delta: Int): List<T> {
            val to = index + delta
            if (index !in list.indices || to !in list.indices) return list
            return list.toMutableList().apply { add(to, removeAt(index)) }
        }
    }
}

/** Where the server keeps the order (admin setting `client_layout_scope`). */
enum class LayoutScope(val wire: String) {
    DEVICE("device"),
    USER("user"),
    GLOBAL("global");

    companion object {
        fun parse(raw: String?): LayoutScope? = entries.firstOrNull { it.wire == raw }
    }
}

/**
 * What the server says about the order: [scope], whether this person may
 * change it ([editable]) and whether the server knows layouts at all
 * ([server] false: older GateControl, the device keeps it).
 */
data class LayoutAccess(
    val scope: LayoutScope = LayoutScope.DEVICE,
    val editable: Boolean = true,
    val server: Boolean = false,
) {
    /** The order comes from the account (same on every device). */
    val shared: Boolean get() = server && scope != LayoutScope.DEVICE

    /** One order for everybody that only an admin may change: arranging is off. */
    val locked: Boolean get() = shared && !editable
}
