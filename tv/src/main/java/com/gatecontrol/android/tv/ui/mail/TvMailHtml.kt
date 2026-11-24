package com.gatecontrol.android.tv.ui.mail

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.gatecontrol.android.mail.MMessage
import com.gatecontrol.android.mail.MailLogic
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream

/**
 * „HTML-Ansicht“ (☰ on a mail): the mail as it was made, on a light sheet, in a WebView that can do nothing by itself —
 * as on the phone: JavaScript off, no files, forms, storage or windows; the page's requests are answered by the app,
 * only the plugin's pictures (`cid/…`, and `img/…` the server let through for this sender) with the app's sign-in,
 * everything else empty. External pictures therefore stay blocked as on the phone, links open nothing on the TV.
 * ▲ ▼ scroll, Zurück closes.
 */
@Composable
fun TvMailHtmlDialog(message: MMessage, loader: suspend (String) -> Pair<ByteArray, String>?, onClose: () -> Unit) {
    val colors = GateControlTvTheme.colors
    val html = MailLogic.rewriteCid(message.html.orEmpty(), message.id, message.attachments)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(colors.bg).padding(horizontal = 36.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(message.summary.subject.ifBlank { stringResource(R.string.tv_mail_no_subject) }, style = MaterialTheme.typography.headlineSmall, color = colors.text, maxLines = 1)
            Text(stringResource(R.string.tv_mail_html_note), style = MaterialTheme.typography.bodySmall, color = colors.muted)
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(androidx.compose.ui.graphics.Color.White)) {
                TvMailWebView(html, loader, Modifier.fillMaxSize())
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TvMailWebView(html: String, loader: suspend (String) -> Pair<ByteArray, String>?, modifier: Modifier) {
    val load = rememberUpdatedState(loader)
    val page = MailLogic.page(html)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(Color.WHITE)
                isFocusable = true
                isFocusableInTouchMode = true
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
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    // read from the sofa: larger than on the phone
                    textZoom = TEXT_ZOOM
                    safeBrowsingEnabled = true
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        if (request.url.scheme.equals("data", ignoreCase = true)) return null
                        val path = MailLogic.interceptPath(request.url.toString())
                        if (path != null && request.method.equals("GET", ignoreCase = true)) {
                            val got = runCatching { runBlocking { load.value(path) } }.getOrNull()
                            if (got != null) return WebResourceResponse(got.second, null, 200, "OK", NO_STORE, ByteArrayInputStream(got.first))
                        }
                        return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", NO_STORE, ByteArrayInputStream(ByteArray(0)))
                    }

                    // a link opens nothing on the TV (no browser in the living room for a mail's links)
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                }
                // the remote's ▲ ▼ scroll the page (links are not followed anyway)
                setOnKeyListener { v, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    val step = (v.height * SCROLL_PART).toInt().coerceAtLeast(1)
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            (v as WebView).scrollBy(0, step)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            (v as WebView).scrollBy(0, -step)
                            if (v.scrollY < 0) v.scrollTo(v.scrollX, 0)
                            true
                        }
                        else -> false
                    }
                }
                post { requestFocus() }
            }
        },
        onRelease = { web -> web.destroy() },
        update = { web ->
            if (web.tag != page) {
                web.tag = page
                web.loadDataWithBaseURL(MailLogic.BASE_URL, page, "text/html", "utf-8", null)
            }
        },
    )
}

private const val TEXT_ZOOM = 140
private const val SCROLL_PART = 0.6f

/** Answers of the page's requests are never kept by the WebView. */
private val NO_STORE = mapOf("Cache-Control" to "no-store")
