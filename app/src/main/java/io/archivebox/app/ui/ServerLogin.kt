package io.archivebox.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.archivebox.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

internal fun serverLoginUrl(server: String): String = normalizeServer(server).toHttpUrl().newBuilder()
    .addPathSegments("admin/login/").addQueryParameter("next", normalizeServer(server).toHttpUrl().encodedPath + "admin/").build().toString()

/** Login is allowed only on the configured control origin or its exact built-in admin sibling. */
internal fun trustedLoginPage(url: String, server: String): Boolean {
    val target = url.toHttpUrlOrNull() ?: return false
    val source = normalizeServer(server).toHttpUrl()
    val base = source.host.replace(Regex("^(admin|web|api)\\."), "")
    return target.username.isEmpty() && target.password.isEmpty() &&
        target.scheme == source.scheme && target.port == source.port &&
        target.host in setOf(source.host, "admin.$base") &&
        target.encodedPath.startsWith(source.encodedPath + "admin/")
}

internal fun isServerLoginPage(url: String, server: String): Boolean = trustedLoginPage(url, server) &&
    url.toHttpUrl().encodedPath.trimEnd('/') == normalizeServer(server).toHttpUrl().encodedPath + "admin/login"

internal fun canReadServerKey(url: String, server: String): Boolean = trustedLoginPage(url, server) && !isServerLoginPage(url, server)

/** A deliberately separate unauthenticated flow. Protected ServerBrowser still requires browser_session. */
@SuppressLint("SetJavaScriptEnabled")
@Composable internal fun ServerLogin(repository: ArchiveRepository, attempt: ServerLoginAttempt, onComplete: (String) -> Unit, onCancel: () -> Unit) {
    val scope = rememberCoroutineScope()
    val completed by rememberUpdatedState(onComplete)
    var prepared by remember(attempt) { mutableStateOf(false) }
    var error by remember(attempt) { mutableStateOf<String?>(null) }
    var saving by remember(attempt) { mutableStateOf(false) }
    var browser by remember(attempt) { mutableStateOf<WebView?>(null) }
    var verification by remember(attempt) { mutableStateOf<Job?>(null) }
    val destination = serverLoginUrl(attempt.connection.server)
    LaunchedEffect(attempt) {
        val cookies = CookieManager.getInstance()
        suspendCancellableCoroutine<Unit> { continuation -> cookies.removeAllCookies { if (continuation.isActive) continuation.resume(Unit) } }
        WebStorage.getInstance().deleteAllData()
        cookies.setAcceptCookie(true)
        prepared = true
    }
    DisposableEffect(attempt) {
        onDispose {
            attempt.cancel(); verification?.cancel()
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        }
    }
    BackHandler { onCancel() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onCancel, modifier = Modifier.testTag("connection.login.cancel")) { Text("Cancel login") }
            TextButton(onClick = { browser?.reload() }, enabled = !saving) { Text("Reload") }
        }
        Text("Sign in to ${attempt.connection.name}. Your API key will be saved automatically after login.", Modifier.padding(horizontal = 16.dp))
        if (saving || !prepared) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        if (prepared) AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("connection.login"),
            factory = { context -> WebView(context).apply {
                browser = this
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
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
                    private var navigation = 0L
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        !trustedLoginPage(request.url.toString(), attempt.connection.server)

                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        navigation++; verification?.cancel(); saving = false; error = null
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        if (attempt.cancelled || view.url != url || !canReadServerKey(url, attempt.connection.server)) return
                        val page = navigation
                        // Read only the core admin template's own key, in the main frame. No JS bridge,
                        // password scraping, injected bearer header, or token URL is involved.
                        view.evaluateJavascript("location.href === ${JSONObject.quote(url)} ? window.ARCHIVEBOX_API_KEY : null") { result ->
                            if (attempt.cancelled || browser !== view || navigation != page || view.url != url) return@evaluateJavascript
                            val key = runCatching { JSONArray("[$result]").optString(0).takeUnless { it == "null" }.orEmpty() }.getOrDefault("")
                            if (key.isBlank()) return@evaluateJavascript
                            verification?.cancel()
                            verification = scope.launch {
                                saving = true; error = null
                                try {
                                    repository.completeServerLogin(attempt, key)
                                    completed(key)
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { error = e.message ?: "Couldn't save the API key. Try logging in again." }
                                finally { saving = false }
                            }
                        }
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                        if (request.isForMainFrame) error = "Couldn't load server login. Check the address and connection."
                    }
                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        if (request.isForMainFrame) error = "Server returned HTTP ${response.statusCode}."
                    }
                    // Default SSL handling rejects invalid certificates, just like protected browsing.
                }
                loadUrl(destination) // Intentionally no Authorization header, even if the old key was valid.
            } },
            onReset = null,
            onRelease = { view ->
                verification?.cancel(); view.stopLoading(); view.clearCache(true); view.clearHistory(); view.destroy()
                if (browser === view) browser = null
            },
        )
    }
}
