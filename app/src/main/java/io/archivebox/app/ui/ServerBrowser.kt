package io.archivebox.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.archivebox.app.data.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@SuppressLint("SetJavaScriptEnabled")
@Composable internal fun ServerBrowser(repository: ArchiveRepository, connection: Connection, path: String) {
    val context = LocalContext.current
    var browser by remember { mutableStateOf<WebView?>(null) }
    var session by remember(connection) { mutableStateOf<BrowserSession?>(null) }
    var error by remember(connection, path) { mutableStateOf<String?>(null) }
    var ready by remember(connection, path) { mutableStateOf(false) }
    var canBack by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(connection, reload) {
        session = null; ready = false; error = null
        try {
            val value = repository.api.browserSession(connection)
            val cookies = CookieManager.getInstance()
            // No bearer token is ever exposed to HTML, JavaScript, browser storage, or a URL.
            suspendCancellableCoroutine<Unit> { continuation -> cookies.removeAllCookies { if (continuation.isActive) continuation.resume(Unit) } }
            cookies.setAcceptCookie(true)
            val cookie = "${value.cookieName}=${value.cookieValue}; Path=/; HttpOnly; SameSite=Lax" + if (value.secure) "; Secure" else ""
            suspendCancellableCoroutine<Unit> { continuation -> cookies.setCookie(value.adminUrl, cookie) { if (continuation.isActive) continuation.resume(Unit) } }
            session = value
        } catch (e: Exception) { error = e.message ?: "Couldn't open your server's browser session." }
    }
    BackHandler(canBack) { browser?.goBack() }
    DisposableEffect(connection) {
        onDispose { browser?.stopLoading(); browser?.destroy(); browser = null; CookieManager.getInstance().removeAllCookies(null); WebStorage.getInstance().deleteAllData() }
    }
    Column(Modifier.fillMaxSize()) {
        if (!ready && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { message -> Column(Modifier.padding(20.dp)) { ErrorCard(message); TextButton(onClick = { reload++ }) { Text("Try again") } } }
        session?.let { authenticated ->
            val target = if (path.startsWith("admin/")) {
                authenticated.adminUrl.substringBefore("/admin") .trimEnd('/') + "/" + path
            } else connection.server.trimEnd('/') + "/" + path
            // Session cookies may be host-only on a separate admin host; archived pages use that host too.
            val destination = if (!path.startsWith("admin/") && !sameOrigin(connection.server, authenticated.adminUrl)) authenticated.adminUrl.substringBefore("/admin").trimEnd('/') + "/" + path else target
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth().testTag(if (ready && error == null) "browser.ready" else "browser.loading"),
                factory = { ctx ->
                    WebView(ctx).apply {
                        browser = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.setSupportMultipleWindows(false)
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.safeBrowsingEnabled = true
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url.toString()
                                if (sameOrigin(url, authenticated.adminUrl) || sameOrigin(url, connection.server)) return false
                                if (request.isForMainFrame && request.url.scheme in listOf("https", "http")) {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                                }
                                return true
                            }
                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { ready = false; error = null; canBack = view.canGoBack() }
                            override fun onPageFinished(view: WebView, url: String) { ready = true; canBack = view.canGoBack() }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) { if (request.isForMainFrame) error = "Couldn't load this page. ${failure.description}" }
                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) { if (request.isForMainFrame) error = "Server returned HTTP ${response.statusCode}." }
                            // The default SSL handler cancels certificate errors; never bypass certificate validation.
                        }
                        tag = destination
                        loadUrl(destination)
                    }
                },
                update = { view -> if (view.tag != destination) { view.tag = destination; view.loadUrl(destination) } },
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { browser?.reload() }) { Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Reload") }
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(browser?.url ?: destination))) }) { Icon(Icons.Outlined.OpenInNew, null); Spacer(Modifier.width(6.dp)); Text("Open in browser") }
            }
        }
    }
}
