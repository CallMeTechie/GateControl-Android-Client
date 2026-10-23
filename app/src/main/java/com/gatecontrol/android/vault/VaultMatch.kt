package com.gatecontrol.android.vault

/**
 * Which entries fit a login form (autofill, mockup A7): by the web domain the browser reports (same site: the
 * registrable domain, "login.sparkasse.de" fits "sparkasse.de") or by the app's package name (`apps`). Pure,
 * unit-tested.
 */
object VaultMatch {
    private val SECOND_LEVEL = setOf("co", "com", "org", "net", "gov", "ac", "edu", "or", "ne", "go")

    /** "https://www.Login.Example.co.uk:8443/x" → "login.example.co.uk"; null when it is no host. */
    fun host(url: String): String? {
        var s = url.trim().lowercase()
        if (s.isEmpty()) return null
        s = s.substringAfter("://", s).substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfterLast('@').substringBefore(':').trimEnd('.')
        if (s.startsWith("www.")) s = s.removePrefix("www.")
        if (s.isEmpty() || s.any { !(it.isLetterOrDigit() || it == '.' || it == '-') }) return null
        return s
    }

    /** The registrable part of a host: "login.example.co.uk" → "example.co.uk"; an IP stays as it is. */
    fun site(host: String): String {
        if (host.all { it.isDigit() || it == '.' }) return host
        val parts = host.split('.').filter { it.isNotEmpty() }
        if (parts.size <= 2) return parts.joinToString(".")
        val tld = parts.last()
        val sld = parts[parts.size - 2]
        val n = if (tld.length == 2 && sld in SECOND_LEVEL) 3 else 2
        return parts.takeLast(n).joinToString(".")
    }

    fun fitsDomain(entry: EntryData, webDomain: String): Boolean {
        val h = host(webDomain) ?: return false
        val want = site(h)
        return entry.urls.any { u -> host(u)?.let { site(it) == want } == true }
    }

    fun fitsApp(entry: EntryData, packageName: String): Boolean = entry.apps.any { it.equals(packageName, ignoreCase = true) }

    /**
     * Entries for a form: a browser page by its domain only (never by the browser's own package); an app by its
     * package, or by a domain the app's form declares. Logins only, not trashed; favourites first.
     */
    fun matches(entries: List<Entry>, packageName: String?, webDomain: String?): List<Entry> {
        val live = entries.filter { !it.trashed && (it.data.type == "login" || it.data.username.isNotEmpty() || it.data.password.isNotEmpty()) }
        val hits = when {
            !webDomain.isNullOrBlank() -> live.filter { fitsDomain(it.data, webDomain) }
            !packageName.isNullOrBlank() -> live.filter { fitsApp(it.data, packageName) }
            else -> emptyList()
        }
        return hits.sortedWith(compareBy({ !it.data.fav }, { it.data.title.lowercase() }))
    }
}
