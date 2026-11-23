package io.archivebox.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
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
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun allowedArchiveOrigin(url: String, server: String, adminUrl: String): Boolean {
    val target = url.toHttpUrlOrNull() ?: return false
    if (target.username.isNotEmpty() || target.password.isNotEmpty()) return false
    if (sameOrigin(url, server) || sameOrigin(url, adminUrl)) return true
    val source = server.toHttpUrlOrNull() ?: return false
    val base = source.host.replace(Regex("^(admin|web|api)\\."), "")
    return target.scheme == source.scheme && target.port == source.port &&
        (target.host == "web.$base" ||
            Regex("snap-[0-9a-f]{12}\\.${Regex.escape(base)}").matches(target.host))
}

internal const val SNAPSHOT_GRID_PATH = "admin/core/snapshot/grid/"

internal enum class SnapshotTransferMenu(val menuID: String) {
    Import("library-import-menu"),
    Export("snapshot-export-menu");

    // Use the existing server control. No href, status, data attribute, or key
    // is returned to native code, and opening this menu itself submits no job.
    fun openScript(destination: String): String = """
        (() => {
            if (location.href !== ${JSONObject.quote(destination)}) return false;
            for (const id of ['$menuID-sidebar', '$menuID-list']) {
                const menu = document.getElementById(id);
                const button = document.querySelector('button[type="button"][popovertarget="' + id + '"]');
                if (!menu || !menu.hasAttribute('popover') || menu.getAttribute('role') !== 'menu' ||
                    typeof menu.showPopover !== 'function' || !button ||
                    button.disabled || !button.getClientRects().length) continue;
                if (!menu.matches(':popover-open')) button.click();
                return menu.matches(':popover-open');
            }
            return false;
        })();
    """.trimIndent()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable internal fun ServerBrowser(
    repository: ArchiveRepository,
    connection: ServerConfiguration,
    path: String,
    transferMenu: SnapshotTransferMenu? = null,
    onSearchPage: ((String, String?) -> Unit)? = null,
) {
    val context = LocalContext.current
    val searchPageCallback by rememberUpdatedState(onSearchPage)
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
            key(connection.id, connection.server, connection.token, transferMenu) { AndroidView(
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
                            private var fallbackPage: String? = null
                            private var transferOpened = false
                            private var pageGeneration = 0
                            private var transferStartedOn = -1
                            private fun openTransferMenu(view: WebView, url: String) {
                                val menu = transferMenu ?: return
                                if (!ready || transferOpened || transferStartedOn == pageGeneration || path != SNAPSHOT_GRID_PATH ||
                                    url != destination || url != view.url || !sameOrigin(url, authenticated.admin_url)) return
                                val generation = pageGeneration
                                transferStartedOn = generation
                                val deadline = SystemClock.uptimeMillis() + 4_000
                                val script = menu.openScript(destination)
                                fun currentPage() = browser === view && pageGeneration == generation
                                fun attempt() {
                                    if (!currentPage() || !ready || view.url != url || SystemClock.uptimeMillis() >= deadline) return
                                    view.evaluateJavascript(script) { result ->
                                        if (!currentPage()) return@evaluateJavascript
                                        if (result == "true") {
                                            transferOpened = true
                                        } else {
                                            view.postDelayed({ attempt() }, 150)
                                        }
                                    }
                                }
                                // onPageFinished can precede progressive DOM initialization.
                                // Stop on navigation, release, successful opening, or deadline.
                                attempt()
                            }
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
                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                pageGeneration++
                                ready = false; error = null; canBack = view.canGoBack()
                            }
                            override fun onPageFinished(view: WebView, url: String) {
                                if (fallbackPage != null && URI(url).path == base.resolve("../$SEARCH_EDITOR_PATH").path) return
                                if (url == fallbackPage) {
                                    // The unavailable editor was the entry page. Back must not reopen its 404.
                                    view.clearHistory()
                                    fallbackPage = null
                                }
                                ready = error == null; canBack = view.canGoBack()
                                openTransferMenu(view, url)
                                if (ready) rememberSearchPage(view, url)
                            }
                            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                // The results stream can replace its query URL without loading another document.
                                if (ready) rememberSearchPage(view, url)
                            }
                            private fun rememberSearchPage(view: WebView, url: String) {
                                val record = searchPageCallback ?: return
                                if (view !== browser || url != view.url || !sameOrigin(url, authenticated.admin_url)) return
                                val page = url.toHttpUrlOrNull() ?: return
                                val results = page.encodedPath == base.resolve("core/snapshot/").path
                                val route = when {
                                    results -> "admin/core/snapshot/"
                                    page.encodedPath == base.resolve("../$SEARCH_EDITOR_PATH").path -> SEARCH_EDITOR_PATH
                                    else -> return
                                }
                                // Remember the URL for rotation. Mark this navigation as already loaded
                                // so Compose's update block does not restart an active result stream.
                                view.tag = url
                                record(route + (page.encodedQuery?.let { "?$it" } ?: ""), if (results) page.queryParameter("q").orEmpty() else null)
                            }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) { if (request.isForMainFrame) { ready = false; error = "Couldn't load this page. ${failure.description}" } }
                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                if (!request.isForMainFrame) return
                                val editor = base.resolve("../$SEARCH_EDITOR_PATH")
                                if (response.statusCode == 404 && request.url.path == editor.path &&
                                    sameOrigin(request.url.toString(), authenticated.admin_url)) {
                                    // Older servers still expose their own supported Snapshot search.
                                    val fallback = archiveSearchPath(request.url.getQueryParameter("q").orEmpty())
                                    val url = base.resolve(fallback.removePrefix("admin/")).toString()
                                    ready = false
                                    fallbackPage = url
                                    view.post { view.loadUrl(url) }
                                    return
                                }
                                ready = false; error = "Server returned HTTP ${response.statusCode}."
                            }
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
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { browser?.reload() }) { Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Reload") }
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(browser?.url ?: destination))) }) { Icon(Icons.Outlined.OpenInNew, null); Spacer(Modifier.width(6.dp)); Text("Open in browser") }
            }
        }
    }
}
