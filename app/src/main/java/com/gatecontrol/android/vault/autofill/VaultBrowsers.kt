package com.gatecontrol.android.vault.autofill

import com.gatecontrol.android.vault.VaultMatch

/**
 * Browsers for Vault's autofill. Browsers without Android's own autofill support (Edge, Samsung Internet, DuckDuckGo,
 * Chrome before 135 …) run in Android's compatibility mode (`compatibility-package` in `vault_autofill_service.xml`):
 * the form comes from the accessibility tree and often without the page's domain — then it is read from the browser's
 * address bar ([URL_BARS], the view ids of the address field; [domainFromAddressBar] takes it only when it is clearly
 * an address). Pure, unit-tested.
 *
 * Rules (safety first): a page in a browser fits only by its domain — never by the browser's package; without a safely
 * read domain Vault suggests nothing and never remembers a browser as the "app" of a login ([isBrowser]).
 */
object VaultBrowsers {
    private val CHROMIUM = listOf("url_bar")

    /**
     * Address field view ids per browser (as Chromium, Samsung, DuckDuckGo and Opera name them). Only browsers that
     * show the page's address there — not its title (Yandex shows titles: left out, a page could name itself
     * "bank.de"). Every package of the compatibility list is here (the test checks it).
     */
    val URL_BARS: Map<String, List<String>> = mapOf(
        "app.vanadium.browser" to CHROMIUM,
        "com.android.browser" to listOf("url"),
        "com.android.chrome" to CHROMIUM,
        "com.brave.browser" to CHROMIUM,
        "com.brave.browser_beta" to CHROMIUM,
        "com.brave.browser_dev" to CHROMIUM,
        "com.brave.browser_nightly" to CHROMIUM,
        "com.chrome.beta" to CHROMIUM,
        "com.chrome.canary" to CHROMIUM,
        "com.chrome.dev" to CHROMIUM,
        "com.duckduckgo.mobile.android" to listOf("omnibarTextInput"),
        "com.ecosia.android" to CHROMIUM,
        "com.kiwibrowser.browser" to CHROMIUM,
        "com.kiwibrowser.browser.dev" to CHROMIUM,
        "com.microsoft.emmx" to CHROMIUM,
        "com.microsoft.emmx.beta" to CHROMIUM,
        "com.microsoft.emmx.canary" to CHROMIUM,
        "com.microsoft.emmx.dev" to CHROMIUM,
        "com.naver.whale" to CHROMIUM,
        "com.opera.gx" to listOf("addressbarEdit"),
        "com.opera.mini.native" to listOf("url_field"),
        "com.opera.mini.native.beta" to listOf("url_field"),
        "com.opera.touch" to listOf("addressbarEdit"),
        "com.sec.android.app.sbrowser" to listOf("location_bar_edit_text"),
        "com.sec.android.app.sbrowser.beta" to listOf("location_bar_edit_text"),
        "com.vivaldi.browser" to CHROMIUM,
        "com.vivaldi.browser.snapshot" to CHROMIUM,
        "org.adblockplus.browser" to CHROMIUM,
        "org.bromite.bromite" to CHROMIUM,
        "org.chromium.chrome" to CHROMIUM,
        "org.cromite.cromite" to CHROMIUM,
        "org.ungoogled.chromium.stable" to CHROMIUM,
    )

    /** Browsers with their own autofill support (they give the domain themselves) — browsers all the same. */
    private val NATIVE = setOf(
        "com.opera.browser", "com.opera.browser.beta",
        "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix", "org.mozilla.fenix.nightly",
        "org.mozilla.fennec_fdroid", "org.mozilla.focus", "org.mozilla.klar", "org.torproject.torbrowser",
        "io.github.forkmaintainers.iceraven", "org.ironfoxoss.ironfox", "com.yandex.browser",
        "com.google.android.captiveportallogin", "com.android.htmlviewer",
    )

    /** [packageName] is a browser: its pages fit by domain only, it is never remembered as a login's app. */
    fun isBrowser(packageName: String?): Boolean = packageName != null && (packageName in URL_BARS || packageName in NATIVE)

    /** The address field ids of [packageName] (empty: none known). */
    fun urlBarIds(packageName: String?): List<String> = packageName?.let { URL_BARS[it] }.orEmpty()

    /**
     * The page's domain for a form of [packageName]: what the page reports ([reported], the browser's own autofill),
     * else for a browser of [URL_BARS] its address bar's text ([addressBar]); null when neither is safely known.
     */
    fun pageDomain(packageName: String?, reported: String?, addressBar: String?): String? {
        reported?.trim()?.takeIf { it.isNotEmpty() }?.let { return VaultMatch.host(it) }
        if (urlBarIds(packageName).isEmpty()) return null
        return domainFromAddressBar(addressBar)
    }

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val LABEL = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$")

    /**
     * The domain in the address bar's [text] — only when it is clearly an address: one word, `http(s)://` or no
     * scheme at all (Chrome leaves `https://` out), a host of labels with a dot (or an IPv4). A search term, a page
     * title, `about:`, `chrome://`, `file:`, `javascript:` → null.
     */
    fun domainFromAddressBar(text: String?): String? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2048 } ?: return null
        if (t.any { it.isWhitespace() }) return null
        val lower = t.lowercase()
        val rest = when {
            lower.startsWith("https://") || lower.startsWith("http://") -> lower
            SCHEME.containsMatchIn(lower) && !Regex("^[a-z0-9.-]+:[0-9]{1,5}(/|$)").containsMatchIn(lower) -> return null
            else -> lower
        }
        val authority = rest.substringAfter("://", rest).substringBefore('/').substringBefore('?').substringBefore('#')
        if ('@' in authority) return null
        val host = VaultMatch.host(rest) ?: return null
        if (host.all { it.isDigit() || it == '.' }) {
            val parts = host.split('.')
            return host.takeIf { parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.toInt() <= 255 } }
        }
        val labels = host.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() || it.length > 63 || !LABEL.matches(it) }) return null
        if (labels.last().all { it.isDigit() }) return null
        return host
    }

    // ─── what Chromium reports (its own autofill: Chrome 135+ "Autofill using another service", Edge 147+) ───

    /**
     * The page a web node names ([domain] / [scheme] of `ViewNode.getWebDomain()` / `getWebScheme()`): its host for
     * http(s) — or without a scheme, as older browsers report it —, null for anything else (`chrome://`, `edge://`,
     * `file:`, `data:`, `blob:` …) or no host.
     */
    fun reportedDomain(domain: String?, scheme: String?): String? {
        val d = domain?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val sc = scheme?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (sc != null && sc != "https" && sc != "http") return null
        return VaultMatch.host(d)
    }

    /** The fields to fill and the page they are on ([pickPage]). */
    data class Pick(val page: String?, val user: Int?, val passwords: List<Int>)

    /**
     * Fields from more than one page (a login frame of another site, an ad frame with a field): the password's page
     * decides (else the user name's), fields of any other page are left out — Vault never fills into a frame of
     * another site than the one it matched. [pages]: the page of each field (index as in [user] / [passwords]).
     */
    fun pickPage(pages: List<String?>, user: Int?, passwords: List<Int>): Pick {
        val anchor = passwords.firstOrNull() ?: user ?: return Pick(null, user, passwords)
        val page = pages.getOrNull(anchor)
        fun same(i: Int) = pages.getOrNull(i) == page
        return Pick(page, user?.takeIf { same(it) }, passwords.filter { same(it) })
    }

    // ─── the hint in Vault's settings: which installed browser still needs its own switch ───

    /** A browser that fills with another service only once it is switched on there. */
    enum class Switch { CHROME, EDGE, BRAVE, VIVALDI }

    /** Chrome / Chrome Beta and Brave stop using the compatibility mode above these (vault_autofill_service.xml). */
    const val CHROME_COMPAT_MAX = 711_900_039L
    const val BRAVE_COMPAT_MAX = 427_912_623L

    /** Edge has the switch from this version (Microsoft: policy ThirdPartyPasswordManagersAllowed, Android ≥ 147). */
    const val EDGE_SWITCH_FROM = 147

    private val SWITCH_PACKAGES: Map<String, Switch> = mapOf(
        "com.android.chrome" to Switch.CHROME, "com.chrome.beta" to Switch.CHROME,
        "com.chrome.dev" to Switch.CHROME, "com.chrome.canary" to Switch.CHROME,
        "com.microsoft.emmx" to Switch.EDGE, "com.microsoft.emmx.beta" to Switch.EDGE,
        "com.microsoft.emmx.dev" to Switch.EDGE, "com.microsoft.emmx.canary" to Switch.EDGE,
        "com.brave.browser" to Switch.BRAVE, "com.brave.browser_beta" to Switch.BRAVE,
        "com.brave.browser_dev" to Switch.BRAVE, "com.brave.browser_nightly" to Switch.BRAVE,
        "com.vivaldi.browser" to Switch.VIVALDI, "com.vivaldi.browser.snapshot" to Switch.VIVALDI,
    )

    /** The packages the hint looks at (also in the manifest's `<queries>`, the test checks it). */
    val switchPackages: Set<String> get() = SWITCH_PACKAGES.keys

    /**
     * The content provider Chromium browsers answer "Autofill using another service" with (Chrome 135+; `0` off,
     * `1` on; an older version has none). Google's documentation names Chrome's; the other Chromium browsers keep
     * Chromium's name with their own package.
     */
    fun thirdPartyModeUri(packageName: String): String = "content://$packageName.AutofillThirdPartyModeContentProvider/autofill_third_party_mode"
    const val THIRD_PARTY_COLUMN = "autofill_third_party_state"

    /** An installed browser as the package manager names it; [thirdPartyState]: the provider's answer, null: none. */
    data class Installed(val packageName: String, val versionCode: Long, val versionName: String?, val thirdPartyState: Int?)

    /**
     * Whether [b] still needs its own switch before it fills with GateControl: the browser's answer decides (on → no,
     * off → yes); without one, Chrome / Chrome Beta and Brave above the compatibility mode's versions and Edge from
     * [EDGE_SWITCH_FROM] (those use their own autofill and need the switch), everything else works through
     * Android's compatibility mode as it is.
     */
    fun needsSwitch(b: Installed): Switch? {
        val kind = SWITCH_PACKAGES[b.packageName] ?: return null
        when (b.thirdPartyState) {
            1 -> return null
            0 -> return kind
        }
        val need = when (kind) {
            Switch.CHROME -> b.packageName in setOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary") && b.versionCode > CHROME_COMPAT_MAX
            Switch.BRAVE -> b.packageName == "com.brave.browser" && b.versionCode > BRAVE_COMPAT_MAX
            Switch.EDGE -> (majorVersion(b.versionName) ?: 0) >= EDGE_SWITCH_FROM
            Switch.VIVALDI -> false
        }
        return if (need) kind else null
    }

    /** One entry per browser kind (Chrome and Chrome Beta: one hint), with the package to open; in a fixed order. */
    fun hints(installed: List<Installed>): List<Pair<Switch, String>> =
        installed.mapNotNull { b -> needsSwitch(b)?.let { it to b.packageName } }
            .distinctBy { it.first }
            .sortedBy { it.first.ordinal }

    /** "147.0.3179.54" → 147; null when it does not start with a number. */
    fun majorVersion(versionName: String?): Int? = versionName?.trim()?.takeWhile { it.isDigit() }?.takeIf { it.isNotEmpty() && it.length <= 6 }?.toInt()
}
