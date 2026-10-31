package com.gatecontrol.android.mail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Mails in the dark ([MailDark]): the colour rule — light backgrounds dark, dark text light, the hue kept, at least
 * 4.5 : 1 for text — and the mail's HTML. The Windows app (gatecontrol-client-core, test/mail-dark.test.js) has the
 * same cases: both follow the same rule.
 */
class MailDarkTest {

    private fun c(s: String) = MailDark.parseColor(s)!!
    private fun dark(s: String, kind: String) = MailDark.darkColor(s, kind)

    private fun hueDiff(x: String, y: String): Double {
        val a = MailDark.toLab(c(x))
        val b = MailDark.toLab(c(y))
        val d = abs(Math.toDegrees(atan2(a.b, a.a)) - Math.toDegrees(atan2(b.b, b.a))) % 360
        return if (d > 180) 360 - d else d
    }

    private fun chroma(x: String) = MailDark.toLab(c(x)).let { hypot(it.a, it.b) }

    @Test
    fun `white to dark - white is the page, light greys a little lighter, darker backgrounds stay`() {
        assertEquals("#161b22", dark("#ffffff", MailDark.KIND_BG))
        assertEquals("#161b22", dark("#FFF", MailDark.KIND_BG))
        assertEquals("#161b22", dark("white", MailDark.KIND_BG))
        assertEquals("#161b22", dark("rgb(255, 255, 255)", MailDark.KIND_BG))
        assertEquals("#1f242b", dark("#f2f2f2", MailDark.KIND_BG))
        assertEquals("#1d2f40", dark("#d9e8f5", MailDark.KIND_BG))
        assertEquals("#000000", dark("#000000", MailDark.KIND_BG))
        assertEquals("rgba(22,27,34,0.5)", dark("rgba(255,255,255,0.5)", MailDark.KIND_BG))
    }

    @Test
    fun `black to light - black is the app text, dark greys nearly as light, light text stays`() {
        assertEquals("#e6eaf0", dark("#000000", MailDark.KIND_TEXT))
        assertEquals("#e6eaf0", dark("black", MailDark.KIND_TEXT))
        assertEquals("#c1c4ca", dark("#333333", MailDark.KIND_TEXT))
        assertEquals("#ffffff", dark("#ffffff", MailDark.KIND_TEXT))
    }

    @Test
    fun `a brand colour stays recognisable - same hue, still coloured`() {
        assertEquals("#e30613", dark("#e30613", MailDark.KIND_BG), "a strong colour stays as it is")
        assertEquals("#343000", dark("#ffe600", MailDark.KIND_BG))
        assertTrue(hueDiff("#ffe600", "#343000") < 10 && chroma("#343000") > 0.05, "yellow stays yellow")
        assertEquals("#1c85e2", dark("#0078d4", MailDark.KIND_TEXT))
        assertTrue(hueDiff("#0078d4", "#1c85e2") < 3 && chroma("#1c85e2") > 0.15, "the blue stays the blue")
        assertEquals("#f9312c", dark("#e30613", MailDark.KIND_TEXT))
        assertTrue(hueDiff("#e30613", "#f9312c") < 3)
    }

    @Test
    fun `minimum contrast - every text colour reaches 4,5 to 1 on the page`() {
        assertEquals("#838383", dark("#777777", MailDark.KIND_TEXT))
        for (x in listOf("#000000", "#333333", "#555555", "#666666", "#777777", "#7f7f7f", "#999999", "#0078d4", "#0563c1", "#e30613", "#00a650", "#ff9900", "#6a3fd6", "navy", "hsl(210, 100%, 30%)")) {
            val out = dark(x, MailDark.KIND_TEXT)
            assertTrue(MailDark.contrast(c(out), c(MailDark.BG)) >= MailDark.MIN_CONTRAST, "$x → $out")
        }
        // on its own background: at least what it had (white on a red button stays white)
        assertEquals("#ffffff", MailDark.cssColor(MailDark.darkText(c("#ffffff"), c("#e30613"), c("#e30613"))))
        val cell = MailDark.darkBackground(c("#ffffff"))
        val grey = MailDark.darkText(c("#999999"), cell, c("#ffffff"))
        assertTrue(MailDark.contrast(grey, cell) >= minOf(4.5, MailDark.contrast(c("#999999"), c("#ffffff"))))
    }

    @Test
    fun `what is no colour stays`() {
        for (x in listOf("transparent", "currentColor", "inherit", "initial", "none", "#12", "rgb(1,2)", "url(x)")) {
            assertEquals(x, dark(x, MailDark.KIND_BG))
            assertEquals(x, dark(x, MailDark.KIND_TEXT))
        }
    }

    @Test
    fun `the HTML - style attributes, bgcolor, color, font color and style blocks, pictures and comments stay`() {
        val html = "<style>.x{background-color:#fff;color:#333}@media (max-width:600px){.y{background:#FFF url(gcmail://img/a) no-repeat;border:1px solid #ddd}}</style>" +
            "<!-- <td bgcolor=\"#fff\"> --><table bgcolor=\"#FFFFFF\" width=600><tr>" +
            "<td style=\"background:#FFE600;color:#000;font-family:&quot;Arial&quot;\"><font color=\"#000000\" face=Arial>Hi</font><img src=\"gcmail://img/x\" style=\"background:white\"></td>" +
            "<td bgcolor=#ffffff style=\"color:#999\">x</td>" +
            "<td style=\"background-color:transparent;color:inherit;border-color:currentColor;background-image:url(&quot;gcmail://img/b&quot;)\">y</td>" +
            "<td bgcolor=\"#e30613\"><font color=white>Btn</font></td></tr></table><p style=color:black>t</p><body text=\"#000000\" link=\"#0563c1\">"
        val out = MailDark.html(html)
        assertTrue(out.contains("<style>.x{background-color:#161b22;color:#c1c4ca}@media (max-width:600px){.y{background:#161b22 url(gcmail://img/a) no-repeat;border:1px solid #2e343c}}</style>"))
        assertTrue(out.contains("<!-- <td bgcolor=\"#fff\"> -->"), "comments are not touched")
        assertTrue(out.contains("<table bgcolor=\"#161b22\" width=600>"))
        assertTrue(out.contains("<td style=\"background:#343000;color:#e6eaf0;font-family:&quot;Arial&quot;\">"))
        assertTrue(out.contains("<font color=\"#e6eaf0\" face=Arial>Hi</font>"))
        assertTrue(out.contains("<img src=\"gcmail://img/x\" style=\"background:white\">"), "pictures are never touched")
        assertTrue(out.contains("<td bgcolor=#161b22 style=\"color:#999\">"))
        assertTrue(out.contains("style=\"background-color:transparent;color:inherit;border-color:currentColor;background-image:url(&quot;gcmail://img/b&quot;)\""), "background pictures stay")
        assertTrue(out.contains("<td bgcolor=\"#e30613\"><font color=white>Btn</font>"))
        assertTrue(out.contains("<p style=color:#e6eaf0>"))
        assertTrue(out.contains("<body text=\"#e6eaf0\" link=\"#3585e6\">"))
        assertEquals("<p>Hallo</p>", MailDark.html("<p>Hallo</p>"))
        // a long style attribute is no problem for the pattern
        val long = "<td style=\"" + "color:#000;".repeat(5000) + "\">x</td>"
        assertTrue(MailDark.html(long).startsWith("<td style=\"color:#e6eaf0;color:#e6eaf0;"))
    }

    @Test
    fun `the page - dark around a dark mail, light around the original, no script, the fit wrapper`() {
        val mail = "<table bgcolor=\"#ffffff\"><tr><td><font color=\"#000\">Hi</font><img src=\"img/tok\"></td></tr></table>"
        val light = MailLogic.page(mail)
        val night = MailLogic.page(MailLogic.darkMail(mail), dark = true)
        assertTrue(light.contains("<table bgcolor=\"#ffffff\">") && light.contains("<meta name=\"color-scheme\" content=\"light\">") && light.contains(":root{color-scheme:light}"))
        assertTrue(night.contains("<table bgcolor=\"#161b22\">") && night.contains("<font color=\"#e6eaf0\">") && night.contains("<img src=\"img/tok\">"))
        assertTrue(night.contains("<meta name=\"color-scheme\" content=\"dark\">") && night.contains(":root{color-scheme:dark}html,body{background:#161b22;color:#e6eaf0}a{color:#79b8ff}"))
        for (p in listOf(light, night)) {
            assertTrue(p.contains("script-src 'none'") && !p.contains("<script", ignoreCase = true))
            assertTrue(p.contains("<div id=\"gc-fit\">"))
        }
        assertTrue(MailLogic.page(MailLogic.darkMail(mail), dark = true, zoom = 0.6).contains("#gc-fit{zoom:0.600}"), "fitting the screen still works")
    }

    @Test
    fun `dark only while the app is dark, the setting is on and the mail was not switched to light`() {
        assertTrue(MailLogic.showDark(appDark = true, setting = true, shownLight = false))
        assertFalse(MailLogic.showDark(appDark = true, setting = true, shownLight = true))
        assertFalse(MailLogic.showDark(appDark = true, setting = false, shownLight = false))
        assertFalse(MailLogic.showDark(appDark = false, setting = true, shownLight = false))
    }
}
