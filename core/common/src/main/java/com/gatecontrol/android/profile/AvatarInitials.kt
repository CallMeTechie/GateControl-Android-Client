package com.gatecontrol.android.profile

/** Initials as GateControl and the Familie plugin make them. */
object AvatarInitials {
    private val SPLIT = Regex("[\\s._-]+")

    private val WORDS = Regex("\\s+")

    /**
     * Two words → their first letters, one word → its first two letters, no letter at all (nothing, only emoji) → "?"
     * ([InitialLetters]: whole characters, letters and digits only — „Schatzimausi 💕“ → „S“).
     */
    fun of(name: String?): String {
        val words = name.orEmpty().trim().split(SPLIT).filter { it.isNotEmpty() }
        val chars = if (words.size > 1) {
            listOf(InitialLetters.first(words[0]), InitialLetters.first(words[1]))
        } else {
            InitialLetters.of(words.firstOrNull().orEmpty()).take(2)
        }
        return chars.joinToString("").uppercase().ifEmpty { "?" }
    }

    /**
     * Initials of a chat's name (the Messenger's rule, as its portal and the Windows client make them): the first letters
     * of the first and the last word („van der Berg“ → „VB“), one word → its first two letters, no letter → "?".
     */
    fun ofChat(name: String?): String {
        val words = name.orEmpty().trim().split(WORDS).filter { it.isNotEmpty() }
        val chars = if (words.size > 1) {
            listOf(InitialLetters.first(words.first()), InitialLetters.first(words.last()))
        } else {
            InitialLetters.of(words.firstOrNull().orEmpty()).take(2)
        }
        return chars.joinToString("").uppercase().ifEmpty { "?" }
    }

    /** Initials a server sent, letters and digits only and at most 3 of them (whole characters); null when none is left. */
    fun clean(server: String?): String? =
        server?.let { InitialLetters.of(it).take(3).joinToString("") }?.takeIf { it.isNotEmpty() }

    /** Initials from the server when usable (1–3 letters or digits), else made from [name]. */
    fun pick(server: String?, name: String?): String {
        val letters = InitialLetters.of(server.orEmpty())
        return if (letters.isNotEmpty() && letters.size <= 3) letters.joinToString("") else of(name)
    }
}
