package io.archivebox.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
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
import io.archivebox.app.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun allowedArchiveOrigin(url: String, server: String, adminUrl: String): Boolean {
    val target = url.toHttpUrlOrNull() ?: return false
    if (target.username.isNotEmpty() || target.password.isNotEmpty()) return false
    if (sameOrigin(url, server) || sameOrigin(url, adminUrl)) return true
    val source = server.toHttpUrlOrNull() ?: return false
    val base = source.host.replace(Regex("^(admin|web|api)\\."), "")
    return target.scheme == source.scheme && target.port == source.port &&
        Regex("snap-[0-9a-f]{12}\\.${Regex.escape(base)}").matches(target.host)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable internal fun ServerBrowser(repository: ArchiveRepository, connection: ServerConfiguration, path: String) {
    val context = LocalContext.current
    var browser by remember { mutableStateOf<WebView?>(null) }
    var session by remember(connection) { mutableStateOf<BrowserSession?>(null) }
    // These cells must outlive route changes: the WebView client retains their references.
    var error by remember(connection) { mutableStateOf<String?>(null) }
    var ready by remember(connection) { mutableStateOf(false) }
    var canBack by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(connection, reload) {
        session = null; ready = false; error = null
        try {
            val value = repository.api.browserSession(connection)
            withContext(Dispatchers.Main.immediate) {
                val cookies = CookieManager.getInstance()
                // No bearer token is ever exposed to HTML, JavaScript, browser storage, or a URL.
                suspendCancellableCoroutine<Unit> { continuation -> cookies.removeAllCookies { if (continuation.isActive) continuation.resume(Unit) } }
                cookies.setAcceptCookie(true)
                val cookie = "${value.cookie.name}=${value.cookie.value}; Path=/; HttpOnly; SameSite=Lax" + if (value.cookie.secure) "; Secure" else ""
                suspendCancellableCoroutine<Unit> { continuation -> cookies.setCookie(value.admin_url, cookie) { if (continuation.isActive) continuation.resume(Unit) } }
            }
            session = value
        } catch (e: Exception) { error = e.message ?: "Couldn't open your server's browser session." }
    }
    BackHandler(canBack) { browser?.goBack() }
    DisposableEffect(connection) {
        onDispose { CookieManager.getInstance().removeAllCookies(null); WebStorage.getInstance().deleteAllData() }
    }
    Column(Modifier.fillMaxSize()) {
        if (!ready && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { message -> Column(Modifier.padding(20.dp)) { ErrorCard(message); TextButton(onClick = { reload++ }) { Text("Try again") } } }
        session?.let { authenticated ->
            // Resolve paths structurally: an admin.* hostname must never be mistaken for /admin/.
            // Keep authenticated pages on the validated cookie host, including split-host deployments.
            val base = URI(authenticated.admin_url.trimEnd('/') + "/")
            val destination = base.resolve(if (path.startsWith("admin/")) path.removePrefix("admin/") else "../$path").toString()
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth().testTag(if (ready && error == null) "browser.ready" else "browser.loading"),
                factory = { ctx ->
                    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
                    WebView(ctx).apply {
                        browser = this
                        // A wrap-content WebView reports a zero CSS percentage-height viewport.
                        // Server replay frames and dialogs need the actual bounded Compose viewport.
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        settings.javaScriptEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
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
                                if (allowedArchiveOrigin(url, connection.server, authenticated.admin_url)) return false
                                // Replay may embed external HTTP(S) content. Let the browser enforce its
                                // same-origin policy; the administrator cookie remains host-only.
                                if (!request.isForMainFrame && request.url.scheme in listOf("https", "http")) return false
                                if (request.isForMainFrame && request.url.scheme in listOf("https", "http")) {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                                }
                                return true
                            }
                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { ready = false; error = null; canBack = view.canGoBack() }
                            override fun onPageFinished(view: WebView, url: String) { ready = error == null; canBack = view.canGoBack() }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) { if (request.isForMainFrame) { ready = false; error = "Couldn't load this page. ${failure.description}" } }
                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) { if (request.isForMainFrame) { ready = false; error = "Server returned HTTP ${response.statusCode}." } }
                            // The default SSL handler cancels certificate errors; never bypass certificate validation.
                        }
                        tag = destination
                        loadUrl(destination)
                    }
                },
                onReset = null,
                onRelease = { view ->
                    view.stopLoading(); view.clearCache(true); view.clearHistory(); view.destroy()
                    if (browser === view) browser = null
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
