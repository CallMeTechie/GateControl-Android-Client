package com.gatecontrol.android.mail

import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Mails in the dark (as the Outlook app shows them while it is dark): the colours of a mail's HTML — already cleaned
 * by the server — are changed before the WebView shows it, never by a script in the page (it has none). The Windows
 * app (gatecontrol-client-core, utils/mail-dark.js) does the same with the same rule; both have the same test cases.
 *
 * The rule:
 *  1. A colour is taken into OKLab (L the lightness 0…1, a/b its hue and chroma).
 *  2. Backgrounds (background*, bgcolor, borders, outlines, shadows): lighter than the middle between the dark page
 *     and white (P_BG = (1 + L_BG) / 2) their lightness is mirrored, L' = L_BG + (1 − L), a/b stay and get the tint
 *     of the page (white → exactly the page #161B22, light grey → a little lighter, light yellow → dark yellow).
 *     Darker backgrounds stay as they are.
 *  3. Text (color, <font color>, body text/link): darker than the middle (P_TX = 0.5) it turns light,
 *     L' = L_TX − (L_TX − P_TX) · (L / P_TX)³ — black → exactly the app's text #E6EAF0 (a/b get its tint), the usual
 *     dark greys of newsletters (#333) nearly as light, a grey near the middle stays about as it is. Lighter text
 *     stays.
 *  4. A colour outside sRGB loses chroma until it fits (lightness and hue stay).
 *  5. Contrast (WCAG): text keeps at least 4.5 : 1 against its background — the background of the same element or
 *     CSS rule (then at most the contrast it had before), else the page. Missing contrast: the lightness moves in
 *     steps of 0.01 away from the background (hue stays) until it is reached.
 *  6. Alpha stays; transparent, currentColor, inherit and pictures (url(…)) stay; <img> is never touched.
 *  7. What has no colour of its own gets the page's colours by CSS (page #161B22, text #E6EAF0, links #79B8FF).
 */
object MailDark {

    /** The dark page: the app's panel, text and link colours (DarkPanel, DarkText, DarkBlue). */
    const val BG = "#161b22"
    const val TEXT = "#e6eaf0"
    const val LINK = "#79b8ff"
    const val MIN_CONTRAST = 4.5
    private const val STEP = 0.01

    /** A colour: r, g, b 0…255, a 0…1. */
    data class Rgb(val r: Int, val g: Int, val b: Int, val a: Double = 1.0)

    data class Lab(val l: Double, val a: Double, val b: Double)

    // ─── colours ────────────────────────────────────────────────────

    private val NAMED: Map<String, String> = (
        "aliceblue:f0f8ff antiquewhite:faebd7 aqua:00ffff aquamarine:7fffd4 azure:f0ffff beige:f5f5dc bisque:ffe4c4 black:000000 " +
            "blanchedalmond:ffebcd blue:0000ff blueviolet:8a2be2 brown:a52a2a burlywood:deb887 cadetblue:5f9ea0 chartreuse:7fff00 " +
            "chocolate:d2691e coral:ff7f50 cornflowerblue:6495ed cornsilk:fff8dc crimson:dc143c cyan:00ffff darkblue:00008b " +
            "darkcyan:008b8b darkgoldenrod:b8860b darkgray:a9a9a9 darkgreen:006400 darkgrey:a9a9a9 darkkhaki:bdb76b darkmagenta:8b008b " +
            "darkolivegreen:556b2f darkorange:ff8c00 darkorchid:9932cc darkred:8b0000 darksalmon:e9967a darkseagreen:8fbc8f " +
            "darkslateblue:483d8b darkslategray:2f4f4f darkslategrey:2f4f4f darkturquoise:00ced1 darkviolet:9400d3 deeppink:ff1493 " +
            "deepskyblue:00bfff dimgray:696969 dimgrey:696969 dodgerblue:1e90ff firebrick:b22222 floralwhite:fffaf0 forestgreen:228b22 " +
            "fuchsia:ff00ff gainsboro:dcdcdc ghostwhite:f8f8ff gold:ffd700 goldenrod:daa520 gray:808080 green:008000 greenyellow:adff2f " +
            "grey:808080 honeydew:f0fff0 hotpink:ff69b4 indianred:cd5c5c indigo:4b0082 ivory:fffff0 khaki:f0e68c lavender:e6e6fa " +
            "lavenderblush:fff0f5 lawngreen:7cfc00 lemonchiffon:fffacd lightblue:add8e6 lightcoral:f08080 lightcyan:e0ffff " +
            "lightgoldenrodyellow:fafad2 lightgray:d3d3d3 lightgreen:90ee90 lightgrey:d3d3d3 lightpink:ffb6c1 lightsalmon:ffa07a " +
            "lightseagreen:20b2aa lightskyblue:87cefa lightslategray:778899 lightslategrey:778899 lightsteelblue:b0c4de " +
            "lightyellow:ffffe0 lime:00ff00 limegreen:32cd32 linen:faf0e6 magenta:ff00ff maroon:800000 mediumaquamarine:66cdaa " +
            "mediumblue:0000cd mediumorchid:ba55d3 mediumpurple:9370db mediumseagreen:3cb371 mediumslateblue:7b68ee " +
            "mediumspringgreen:00fa9a mediumturquoise:48d1cc mediumvioletred:c71585 midnightblue:191970 mintcream:f5fffa " +
            "mistyrose:ffe4e1 moccasin:ffe4b5 navajowhite:ffdead navy:000080 oldlace:fdf5e6 olive:808000 olivedrab:6b8e23 " +
            "orange:ffa500 orangered:ff4500 orchid:da70d6 palegoldenrod:eee8aa palegreen:98fb98 paleturquoise:afeeee " +
            "palevioletred:db7093 papayawhip:ffefd5 peachpuff:ffdab9 peru:cd853f pink:ffc0cb plum:dda0dd powderblue:b0e0e6 " +
            "purple:800080 rebeccapurple:663399 red:ff0000 rosybrown:bc8f8f royalblue:4169e1 saddlebrown:8b4513 salmon:fa8072 " +
            "sandybrown:f4a460 seagreen:2e8b57 seashell:fff5ee sienna:a0522d silver:c0c0c0 skyblue:87ceeb slateblue:6a5acd " +
            "slategray:708090 slategrey:708090 snow:fffafa springgreen:00ff7f steelblue:4682b4 tan:d2b48c teal:008080 " +
            "thistle:d8bfd8 tomato:ff6347 turquoise:40e0d0 violet:ee82ee wheat:f5deb3 white:ffffff whitesmoke:f5f5f5 yellow:ffff00 " +
            "yellowgreen:9acd32"
        ).split(' ').associate { p -> p.substringBefore(':') to p.substringAfter(':') }

    private val HEX = Regex("^#?([0-9a-f]{3,4}|[0-9a-f]{6}|[0-9a-f]{8})$")
    private val HEX6 = Regex("^[0-9a-f]{6}$")
    private val FUNC = Regex("^(rgba?|hsla?)\\(\\s*([^)]*)\\)$")
    private val FUNC_SPLIT = Regex("\\s*[,/]\\s*|\\s+")
    private val NUM = Regex("^[-+]?(\\d+\\.?\\d*|\\.\\d+)(e[-+]?\\d+)?%?$")
    private val LEADING_NUM = Regex("^[-+]?(\\d+\\.?\\d*|\\.\\d+)(e[-+]?\\d+)?")

    private fun num(x: String): Double = if (NUM.matches(x)) x.removeSuffix("%").toDouble() else Double.NaN

    private fun round(x: Double): Int = Math.round(x).toInt()

    /** A colour of CSS or an HTML attribute, null when it is none. */
    fun parseColor(raw: String?): Rgb? {
        val s = raw.orEmpty().trim().lowercase()
        HEX.matchEntire(s)?.let { m ->
            if (s.startsWith('#') || HEX6.matches(s)) {
                var h = m.groupValues[1]
                if (h.length <= 4) h = h.map { "$it$it" }.joinToString("")
                return Rgb(
                    h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16),
                    if (h.length == 8) h.substring(6, 8).toInt(16) / 255.0 else 1.0,
                )
            }
        }
        NAMED[s]?.let { return parseColor("#$it") }
        val m = FUNC.matchEntire(s) ?: return null
        val parts = m.groupValues[2].split(FUNC_SPLIT).filter { it.isNotEmpty() }
        if (parts.size < 3 || parts.size > 4) return null
        val alpha = if (parts.size == 4) (if (parts[3].endsWith("%")) num(parts[3]) / 100 else num(parts[3])) else 1.0
        if (alpha.isNaN()) return null
        val a = min(1.0, max(0.0, alpha))
        if (m.groupValues[1].startsWith("rgb")) {
            val ch = parts.take(3).map { x -> if (x.endsWith("%")) num(x) * 2.55 else num(x) }
            if (ch.any { it.isNaN() }) return null
            val (r, g, b) = ch.map { round(min(255.0, max(0.0, it))) }
            return Rgb(r, g, b, a)
        }
        val hue = LEADING_NUM.find(parts[0])?.value?.toDouble() ?: Double.NaN
        val sat = num(parts[1]) / 100
        val lig = num(parts[2]) / 100
        if (hue.isNaN() || sat.isNaN() || lig.isNaN() || !parts[1].endsWith("%") || !parts[2].endsWith("%")) return null
        val hh = (((hue % 360) + 360) % 360) / 360
        val sl = min(1.0, max(0.0, sat))
        val ll = min(1.0, max(0.0, lig))
        val q = if (ll < 0.5) ll * (1 + sl) else ll + sl - ll * sl
        val p = 2 * ll - q
        fun f(t0: Double): Double {
            var t = t0
            if (t < 0) t += 1
            if (t > 1) t -= 1
            return when {
                t < 1.0 / 6 -> p + (q - p) * 6 * t
                t < 1.0 / 2 -> q
                t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                else -> p
            }
        }
        return Rgb(round(f(hh + 1.0 / 3) * 255), round(f(hh) * 255), round(f(hh - 1.0 / 3) * 255), a)
    }

    private fun toLinear(c8: Int): Double {
        val c = c8 / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun fromLinear(c: Double): Double = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055

    /** sRGB → OKLab. */
    fun toLab(c: Rgb): Lab {
        val r = toLinear(c.r)
        val g = toLinear(c.g)
        val b = toLinear(c.b)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return Lab(
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        )
    }

    /** OKLab → linear sRGB (may lie outside 0…1). */
    private fun labToLinear(lightness: Double, a: Double, b: Double): DoubleArray {
        val l0 = lightness + 0.3963377774 * a + 0.2158037573 * b
        val m0 = lightness - 0.1055613458 * a - 0.0638541728 * b
        val s0 = lightness - 0.0894841775 * a - 1.2914855480 * b
        val l = l0 * l0 * l0
        val m = m0 * m0 * m0
        val s = s0 * s0 * s0
        return doubleArrayOf(
            4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
            -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
            -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s,
        )
    }

    private fun inGamut(rgb: DoubleArray): Boolean = rgb.all { it >= -1e-7 && it <= 1 + 1e-7 }

    /** OKLab → sRGB: outside sRGB the chroma shrinks until it fits (lightness and hue stay). */
    fun fromLab(lightness: Double, a: Double, b: Double): Rgb {
        val l = min(1.0, max(0.0, lightness))
        var lin = labToLinear(l, a, b)
        if (!inGamut(lin)) {
            var lo = 0.0
            var hi = 1.0
            repeat(20) {
                val mid = (lo + hi) / 2
                if (inGamut(labToLinear(l, a * mid, b * mid))) lo = mid else hi = mid
            }
            lin = labToLinear(l, a * lo, b * lo)
        }
        val (r, g, bl) = lin.map { round(fromLinear(min(1.0, max(0.0, it))) * 255) }
        return Rgb(r, g, bl)
    }

    /** WCAG relative luminance (0…1). */
    fun luminance(c: Rgb): Double = 0.2126 * toLinear(c.r) + 0.7152 * toLinear(c.g) + 0.0722 * toLinear(c.b)

    /** WCAG contrast of two colours (1…21). */
    fun contrast(x: Rgb, y: Rgb): Double {
        val a = luminance(x)
        val b = luminance(y)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    private val PAGE = parseColor(BG)!!
    private val LAB_BG = toLab(PAGE)
    private val LAB_TX = toLab(parseColor(TEXT)!!)
    private val P_BG = (1 + LAB_BG.l) / 2
    private const val P_TX = 0.5

    /** A background in the dark (rule 2); the same object when it stays. */
    fun darkBackground(c: Rgb): Rgb {
        val lab = toLab(c)
        if (lab.l <= P_BG) return c
        return fromLab(LAB_BG.l + (1 - lab.l), lab.a + LAB_BG.a, lab.b + LAB_BG.b).copy(a = c.a)
    }

    /**
     * A text colour in the dark (rules 3 and 5): [bg] the background it stands on in the dark (null: the page),
     * [bgBefore] that background before (the contrast to keep is at most the one it had there).
     */
    fun darkText(c: Rgb, bg: Rgb? = null, bgBefore: Rgb? = null): Rgb {
        val lab = toLab(c)
        var l = lab.l
        var a = lab.a
        var b = lab.b
        if (l < P_TX) {
            val t = l / P_TX
            l = LAB_TX.l - (LAB_TX.l - P_TX) * t * t * t
            a += LAB_TX.a
            b += LAB_TX.b
        }
        val under = bg ?: PAGE
        val need = if (bg != null && bgBefore != null) min(MIN_CONTRAST, contrast(c, bgBefore)) else MIN_CONTRAST
        val same = l == lab.l
        var out = if (same) Rgb(c.r, c.g, c.b) else fromLab(l, a, b)
        if (contrast(out, under) >= need) return if (same) c else out.copy(a = c.a)
        val up = toLab(under).l < 0.5
        for (i in 0 until 100) {
            l = if (up) l + STEP else l - STEP
            if (l > 1 || l < 0) break
            out = fromLab(l, a, b)
            if (contrast(out, under) >= need) break
        }
        if (contrast(out, under) < need) out = if (up) Rgb(255, 255, 255) else Rgb(0, 0, 0)
        return out.copy(a = c.a)
    }

    private fun hex2(n: Int): String = n.toString(16).padStart(2, '0')

    /** A colour as CSS: #rrggbb, or rgba(r,g,b,a) with alpha (no spaces: fits an unquoted attribute too). */
    fun cssColor(c: Rgb): String {
        if (c.a < 1) {
            val alpha = java.math.BigDecimal.valueOf(Math.round(c.a * 1000) / 1000.0).stripTrailingZeros().toPlainString()
            return "rgba(${c.r},${c.g},${c.b},$alpha)"
        }
        return "#${hex2(c.r)}${hex2(c.g)}${hex2(c.b)}"
    }

    const val KIND_BG = "bg"
    const val KIND_TEXT = "text"

    /** One colour as text in the dark: [KIND_BG] or [KIND_TEXT] (a text colour: [bg] / [bgBefore] as in [darkText]). */
    fun darkColor(raw: String, kind: String, bg: Rgb? = null, bgBefore: Rgb? = null): String {
        val c = parseColor(raw)
        if (c == null || c.a == 0.0) return raw
        val d = if (kind == KIND_BG) darkBackground(c) else darkText(c, bg, bgBefore)
        return if (d === c) raw else cssColor(d)
    }

    // ─── CSS values and declarations ────────────────────────────────

    /** Colour tokens in a CSS value (url(…) and quoted strings are skipped by [mapValue]). */
    private val TOKEN = Regex(
        "#[0-9a-fA-F]{3,8}\\b|\\b(?:rgba?|hsla?)\\([^()]*\\)|(?<![\\w-])(?:" +
            NAMED.keys.sortedByDescending { it.length }.joinToString("|") + ")(?![\\w-])",
        RegexOption.IGNORE_CASE,
    )
    private val SKIP = Regex("url\\([^)]*\\)|\"[^\"]*\"|'[^']*'", RegexOption.IGNORE_CASE)

    /** Every colour of a CSS value changed by [fn] (pictures url(…) and strings stay). */
    private fun mapValue(value: String, fn: (String) -> String): String {
        val out = StringBuilder()
        var last = 0
        fun plain(s: String) = TOKEN.replace(s) { fn(it.value) }
        for (m in SKIP.findAll(value)) {
            out.append(plain(value.substring(last, m.range.first))).append(m.value)
            last = m.range.last + 1
        }
        return out.append(plain(value.substring(last))).toString()
    }

    /** The first colour of a CSS value (for the background a text stands on), null without one. */
    private fun firstColor(value: String): Rgb? {
        var found: Rgb? = null
        mapValue(value) { tok -> if (found == null) found = parseColor(tok); tok }
        return found?.takeIf { it.a > 0 }
    }

    private val BG_PROP = Regex("^(background|border|outline|column-rule|box-shadow)(-|$)")
    private val TEXT_PROP = Regex("^(color|text-decoration|text-decoration-color|-webkit-text-fill-color|caret-color|text-emphasis-color)$")

    /** Splits a declaration list on ";" outside brackets and strings (the parts, joined again with ";"). */
    private fun splitDeclarations(css: String): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        var quote: Char? = null
        var start = 0
        for (i in css.indices) {
            val ch = css[i]
            if (quote != null) {
                if (ch == quote) quote = null
                continue
            }
            when {
                ch == '"' || ch == '\'' -> quote = ch
                ch == '(' -> depth++
                ch == ')' -> depth = max(0, depth - 1)
                ch == ';' && depth == 0 -> {
                    parts += css.substring(start, i)
                    start = i + 1
                }
            }
        }
        parts += css.substring(start)
        return parts
    }

    /** A background before and in the dark. */
    private class Under(val before: Rgb, val after: Rgb)

    private fun propOf(p: String): String? = p.indexOf(':').takeIf { it >= 0 }?.let { p.substring(0, it).trim().lowercase() }

    /** The background a declaration list sets (background, background-color), null without one. */
    private fun ownBackground(parts: List<String>): Under? {
        var before: Rgb? = null
        for (p in parts) {
            val n = propOf(p)
            if (n == "background" || n == "background-color") before = firstColor(p.substring(p.indexOf(':') + 1)) ?: before
        }
        return before?.let { Under(it, darkBackground(it)) }
    }

    /**
     * A declaration list (a style attribute or a CSS rule) in the dark. [outer]: the element's background from an
     * attribute (bgcolor), used when the list sets none.
     */
    private fun darkDeclarations(css: String, outer: Under? = null): String {
        val parts = splitDeclarations(css)
        val own = ownBackground(parts) ?: outer
        return parts.joinToString(";") { p ->
            val n = propOf(p) ?: return@joinToString p
            val i = p.indexOf(':')
            val value = p.substring(i + 1)
            when {
                BG_PROP.containsMatchIn(n) -> p.substring(0, i + 1) + mapValue(value) { darkColor(it, KIND_BG) }
                TEXT_PROP.matches(n) -> p.substring(0, i + 1) + mapValue(value) { darkColor(it, KIND_TEXT, own?.after, own?.before) }
                else -> p
            }
        }
    }

    private val RULE_BODY = Regex("\\{([^{}]*)\\}")

    /** A <style> block's rules in the dark (each { … } without inner braces is one declaration list). */
    fun darkStyleSheet(css: String): String = RULE_BODY.replace(css) { m -> "{" + darkDeclarations(m.groupValues[1]) + "}" }

    // ─── HTML ───────────────────────────────────────────────────────

    // possessive: a long style attribute is taken in a few steps (no deep backtracking)
    private val TAG = Regex(
        "<!--[\\s\\S]*?-->|<(style)\\b[^>]*+>[\\s\\S]*?</style\\s*>|<[A-Za-z](?:[^>\"']++|\"[^\"]*+\"|'[^']*+')*+>",
        RegexOption.IGNORE_CASE,
    )
    private val TAG_HEAD = Regex("^<[A-Za-z][A-Za-z0-9-]*")
    private val TAG_CLOSE = Regex("/?>$")
    private val ATTR = Regex("(\\s+)([^\\s\"'=<>/`]+)(?:(\\s*=\\s*)(\"[^\"]*\"|'[^']*'|[^\\s\"'=<>`]+))?")
    private val STYLE_OPEN = Regex("^<style\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val STYLE_CLOSE = Regex("</style\\s*>$", RegexOption.IGNORE_CASE)
    private val HASH_ENTITY = Regex("&#35;|&#x23;|&num;", RegexOption.IGNORE_CASE)
    private val BG_ATTRS = setOf("bgcolor", "bordercolor")
    private val TEXT_ATTRS = setOf("color", "text", "link", "vlink", "alink")

    private class Attr(val ws: String, val name: String, val eq: String, val value: String?)

    private fun unquote(v: String): String = if (v.isNotEmpty() && (v[0] == '"' || v[0] == '\'')) v.substring(1, v.length - 1) else v
    private fun requote(v: String, inner: String): String = if (v.isNotEmpty() && (v[0] == '"' || v[0] == '\'')) "${v[0]}$inner${v[0]}" else inner
    private fun decodeColorAttr(v: String): String = HASH_ENTITY.replace(v, "#")

    /** One start tag in the dark: style, bgcolor / bordercolor, color / text / link (pictures are never touched). */
    private fun darkTag(tag: String): String {
        val head = TAG_HEAD.find(tag)?.value ?: return tag
        if (head.equals("<img", ignoreCase = true)) return tag
        val close = TAG_CLOSE.find(tag)?.value
        val inner = tag.substring(head.length, tag.length - (close?.length ?: 0))
        val attrs = ATTR.findAll(inner).map { a -> Attr(a.groupValues[1], a.groupValues[2], a.groupValues[3], a.groups[4]?.value) }.toList()
        fun named(a: Attr) = a.name.lowercase()
        if (attrs.none { a -> a.value != null && (named(a) == "style" || named(a) in BG_ATTRS || named(a) in TEXT_ATTRS) }) return tag
        val bgBefore = attrs.firstOrNull { named(it) == "bgcolor" && it.value != null }?.let { parseColor(decodeColorAttr(unquote(it.value!!))) }
        val outer = bgBefore?.takeIf { it.a > 0 }?.let { Under(it, darkBackground(it)) }
        // the style's own background wins over bgcolor for the text of this element
        val style = attrs.firstOrNull { named(it) == "style" && it.value != null }
        val own = style?.let { ownBackground(splitDeclarations(unquote(it.value!!).replace("&quot;", "\"").replace("&#39;", "'"))) } ?: outer
        val out = attrs.joinToString("") { x ->
            val raw = x.value ?: return@joinToString x.ws + x.name
            val n = named(x)
            val v = unquote(raw)
            val nv = when {
                n == "style" -> darkDeclarations(v, outer)
                n in BG_ATTRS -> darkColor(decodeColorAttr(v), KIND_BG)
                n in TEXT_ATTRS -> darkColor(decodeColorAttr(v), KIND_TEXT, own?.after, own?.before)
                else -> v
            }
            if (nv == v || (nv == decodeColorAttr(v) && n != "style")) x.ws + x.name + x.eq + raw else x.ws + x.name + x.eq + requote(raw, nv)
        }
        return head + out + (close ?: "")
    }

    /** A mail's (cleaned) HTML in the dark: its <style> blocks, style attributes and colour attributes (rules 1–6). */
    fun html(html: String): String = TAG.replace(html) { m ->
        val tag = m.value
        when {
            tag.startsWith("<!--") -> tag
            m.groups[1] != null -> {
                val open = STYLE_OPEN.find(tag)!!.value
                val end = STYLE_CLOSE.find(tag)!!.value
                open + darkStyleSheet(tag.substring(open.length, tag.length - end.length)) + end
            }
            else -> darkTag(tag)
        }
    }

    /** The page's own CSS in the dark (rule 7): what has no colour of its own. */
    fun pageCss(): String = ":root{color-scheme:dark}html,body{background:$BG;color:$TEXT}a{color:$LINK}"

}
