package com.gatecontrol.android.ui.mail

import android.annotation.SuppressLint
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.gatecontrol.android.mail.MailLogic
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream

/**
 * A mail's HTML (cleaned by the server) in a WebView that can do nothing by itself (contract D): JavaScript off, no
 * file or content access, no forms, storage or windows; the page's requests are answered by the app — the plugin's
 * pictures (`img/<token>`, `cid/<mail>/<attachment>`) loaded with the app's own sign-in through [loader], everything
 * else answered empty, so the mail never reaches the network by itself. A tap on a link leaves the app ([onLink]).
 * Shown on a light sheet: mails are made for one.
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
) {
    val load = rememberUpdatedState(loader)
    val link = rememberUpdatedState(onLink)
    val page = remember(html) { MailLogic.page(html) }
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(androidx.compose.ui.graphics.Color.White)) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setBackgroundColor(Color.WHITE)
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
                                if (got != null) return WebResourceResponse(got.second, null, ByteArrayInputStream(got.first))
                            }
                            return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
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
                val key = page to round
                if (web.tag != key) {
                    web.tag = key
                    web.loadDataWithBaseURL(MailLogic.BASE_URL, page, "text/html", "utf-8", null)
                }
            },
        )
    }
}
