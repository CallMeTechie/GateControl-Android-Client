package com.gatecontrol.android.ui.mail

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.toColorInt
import com.gatecontrol.android.mail.MailLogic
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream

/**
 * A mail's HTML (cleaned by the server) in a WebView that can do nothing by itself (contract D): JavaScript off, no
 * file or content access, no forms, storage or windows; the page's requests are answered by the app — the plugin's
 * pictures (`img/<token>`, `cid/<mail>/<attachment>`) loaded with the app's own sign-in through [loader], everything
 * else answered empty, so the mail never reaches the network by itself. A tap on a link leaves the app ([onLink]).
 * Shown on a light sheet as the mail was made, or [dark]: its colours changed before it is shown ([MailLogic.darkMail]
 * — light backgrounds dark, dark text light, the hue kept, pictures never) on the app's dark page, as the Outlook app
 * does while it is dark. No algorithmic darkening of the WebView: not on every phone, and it spoils pictures.
 *
 * Wide mails (a newsletter's table of a fixed width) fit the screen: the page keeps pictures and tables inside it,
 * and what is still wider is measured from outside (the WebView's content width, no script) and laid out again
 * smaller ([MailLogic.fitZoom], at most [MailLogic.FIT_ROUNDS] times). Pinching still zooms in.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun MailHtmlView(
    html: String,
    loader: suspend (String) -> Pair<ByteArray, String>?,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Counts up when the page is to load its pictures again (an embedded one the provider was still sending came). */
    round: Int = 0,
    /** The mail dark ([MailLogic.showDark]). */
    dark: Boolean = false,
) {
    val load = rememberUpdatedState(loader)
    val link = rememberUpdatedState(onLink)
    // the zoom that makes the mail fit (1 until measured), and how often it was laid out again
    val zoom = remember(html) { mutableDoubleStateOf(1.0) }
    val rounds = remember(html) { intArrayOf(0) }
    // the colours in the dark once per mail (the zoom does not change them)
    val body = remember(html, dark) { if (dark) MailLogic.darkMail(html) else html }
    val page = remember(body, dark, zoom.doubleValue) { MailLogic.page(body, dark = dark, zoom = zoom.doubleValue) }
    val sheet = if (dark) DARK_SHEET else Color.WHITE
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(androidx.compose.ui.graphics.Color(sheet))) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { ctx ->
                MailWebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setBackgroundColor(sheet)
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    settings.apply {
                        javaScriptEnabled = false
                        javaScriptCanOpenWindowsAutomatically = false
                        allowFileAccess = false
                        allowContentAccess = false
                        domStorageEnabled = false
                        databaseEnabled = false
                        setSupportMultipleWindows(false)
                        setGeolocationEnabled(false)
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        loadsImagesAutomatically = true
                        builtInZoomControls = true
                        displayZoomControls = false
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        textZoom = 100
                        safeBrowsingEnabled = true
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val url = request.url.toString()
                            // the page itself: newer WebViews hand loadDataWithBaseURL's data: document through here too;
                            // a data: URL never leaves the device, so the WebView loads it (the CSP still applies)
                            if (request.url.scheme.equals("data", ignoreCase = true)) return null
                            // anything else must be one of ours
                            val path = MailLogic.interceptPath(url)
                            if (path != null && request.method.equals("GET", ignoreCase = true)) {
                                val got = runCatching { runBlocking { load.value(path) } }.getOrNull()
                                // never kept by the WebView: a picture refused while blocked comes once "Bilder laden" allowed it
                            if (got != null) return WebResourceResponse(got.second, null, 200, "OK", NO_STORE, ByteArrayInputStream(got.first))
                            }
                            return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", NO_STORE, ByteArrayInputStream(ByteArray(0)))
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            (view as? MailWebView)?.measureSoon()
                        }

                        override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
                            (view as? MailWebView)?.scaleNow = newScale
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val url = request.url.toString()
                            if (request.hasGesture() && MailLogic.externalLink(url)) link.value(url)
                            return true
                        }
                    }
                }
            },
            onRelease = { web -> web.destroy() },
            update = { web ->
                web.onWide = { viewCss, contentCss ->
                    val next = MailLogic.fitZoom(viewCss, contentCss, zoom.doubleValue)
                    if (next != null && rounds[0] < MailLogic.FIT_ROUNDS) {
                        rounds[0]++
                        zoom.doubleValue = next
                    }
                }
                web.setBackgroundColor(sheet)
                val key = page to round
                if (web.tag != key) {
                    web.tag = key
                    web.loadDataWithBaseURL(MailLogic.BASE_URL, page, "text/html", "utf-8", null)
                }
            },
        )
    }
}

/** The dark page behind a mail (the app's panel, [com.gatecontrol.android.mail.MailDark.BG]). */
private val DARK_SHEET = com.gatecontrol.android.mail.MailDark.BG.toColorInt()

/** Answers of the page's requests are never kept by the WebView. */
private val NO_STORE = mapOf("Cache-Control" to "no-store")

/**
 * The mail's WebView, which can tell how wide its content is ([onWide], in CSS px): after the page loaded and whenever
 * its size changes (pictures came).
 */
private class MailWebView(context: Context) : WebView(context) {
    /** The page's scale now (the WebView's pixels per CSS px times the pinch). */
    var scaleNow: Float = context.resources.displayMetrics.density
    var onWide: ((viewCss: Double, contentCss: Double) -> Unit)? = null
    private val measure = Runnable { measureNow() }

    fun measureSoon() {
        removeCallbacks(measure)
        postDelayed(measure, MEASURE_DELAY_MS)
    }

    private fun measureNow() {
        // the page is laid out device-wide (viewport width=device-width); the content's width at the scale now (pinched
        // or not) in CSS px
        val density = resources.displayMetrics.density
        if (width <= 0 || scaleNow <= 0f) return
        onWide?.invoke(width / density.toDouble(), computeHorizontalScrollRange() / scaleNow.toDouble())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        measureSoon()
    }

    private companion object {
        const val MEASURE_DELAY_MS = 150L
    }
}
