package io.archivebox.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Connection(val server: String, val token: String, val persona: String = "Default")
data class Snapshot(val id: String, val title: String, val url: String, val tags: List<String>)
data class Receipt(val crawlId: String, val urls: List<String>)
data class Persona(val id: String, val name: String)
data class BrowserSession(val adminUrl: String, val cookieName: String, val cookieValue: String, val secure: Boolean)
data class DiscoveredServer(val url: String, val label: String)

fun normalizeServer(input: String): String {
    val text = input.trim()
    require(text.isNotEmpty()) { "Enter your server address." }
    val explicit = "://" in text
    val url = (if (explicit) text else "http://$text").toHttpUrl()
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
        "Enter a server address without credentials, a query, or a fragment."
    }
    val builder = url.newBuilder()
    // A bare hostname is the common companion-server address; explicit URLs retain their ports.
    if (!explicit && url.port == 80 && !text.substringBefore('/').contains(':')) builder.port(5759)
    builder.encodedPath(url.encodedPath.trimEnd('/') + "/")
    return builder.build().toString()
}

fun extractUrls(text: String): List<String> = Regex("https?://[^\\s<>\\\"]+", RegexOption.IGNORE_CASE)
    .findAll(text).map { match ->
        var url = match.value.trimEnd('.', ',', ';')
        for ((open, close) in listOf('(' to ')', '[' to ']', '{' to '}')) {
            while (url.endsWith(close) && url.count { it == close } > url.count { it == open }) url = url.dropLast(1)
        }
        url
    }
    .filter { value -> runCatching { value.toHttpUrl().let { it.username.isEmpty() && it.password.isEmpty() } }.getOrDefault(false) }
    .distinct().toList()

fun normalizeTags(tags: List<String>): List<String> = tags.flatMap { it.split(',', '\n') }
    .map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase() }

fun normalizeTags(text: String): List<String> = normalizeTags(listOf(text))

fun sameOrigin(a: String, b: String): Boolean = runCatching {
    val left = a.toHttpUrl(); val right = b.toHttpUrl()
    left.scheme == right.scheme && left.host == right.host && left.port == right.port
}.getOrDefault(false)

class ArchiveApi(timeoutSeconds: Long = 20) {
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(minOf(timeoutSeconds, 5), TimeUnit.SECONDS)
        .callTimeout(timeoutSeconds, TimeUnit.SECONDS).build()

    private suspend fun request(
        server: String, path: String, token: String? = null,
        body: JSONObject? = null, method: String = if (body == null) "GET" else "POST",
        query: Map<String, String> = emptyMap(),
    ): JSONObject = withContext(Dispatchers.IO) {
        val url = normalizeServer(server).toHttpUrl().newBuilder().addPathSegments(path)
        query.forEach { (key, value) -> url.addQueryParameter(key, value) }
        val builder = Request.Builder().url(url.build()).header("Accept", "application/json")
        if (token != null) builder.header("Authorization", "Bearer $token")
        builder.method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
        val call = client.newCall(builder.build())
        val data = suspendCancellableCoroutine<String> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("Cannot reach the server. Check its address and your Wi-Fi or VPN.", e))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            check(response.isSuccessful) {
                                when (response.code) {
                                    in 300..399 -> "Server redirected the API request. Use its direct API address."
                                    401, 403 -> "Access denied. Check your administrator API key."
                                    404 -> "ArchiveBox API not found at this address."
                                    else -> "Server returned HTTP ${response.code}."
                                }
                            }
                            val source = response.body?.source() ?: error("Server returned no response.")
                            // Do not load an unbounded response supplied by a discovered host.
                            require(!source.request(8L * 1024 * 1024 + 1)) { "Server response exceeded 8 MB." }
                            val value = source.readUtf8()
                            if (continuation.isActive) continuation.resume(value)
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }
                }
            })
        }
        JSONObject(data)
    }

    suspend fun discover(input: String): String {
        val server = normalizeServer(input)
        val schema = request(server, "api/v1/openapi.json")
        val paths = schema.getJSONObject("paths").keys().asSequence().toList()
        require(schema.getJSONObject("info").getString("title").contains("archivebox", true) &&
            paths.any { it.endsWith("/cli/add") } && paths.any { it.endsWith("/auth/check_api_token") }) {
            "This address does not expose the ArchiveBox API."
        }
        return server
    }

    suspend fun testToken(connection: Connection) {
        require(connection.token.isNotBlank()) { "Enter your API key." }
        val result = request(connection.server, "api/v1/auth/check_api_token", body = JSONObject().put("token", connection.token))
        require(result.optBoolean("success") && !result.isNull("user_id")) { "This API key is invalid or expired." }
    }

    suspend fun snapshots(connection: Connection, query: String = "", offset: Int = 0): List<Snapshot> {
        require(offset >= 0)
        val result = request(connection.server, "api/v1/core/snapshots", connection.token,
            query = mapOf("search" to query, "search_mode" to "meta", "limit" to "50", "offset" to offset.toString()))
        return result.getJSONArray("items").objects().map {
            Snapshot(it.getString("id"), it.optString("title").takeUnless { value -> value == "null" }.orEmpty(), it.getString("url"), it.optJSONArray("tags")?.strings().orEmpty())
        }
    }

    suspend fun tagSuggestions(connection: Connection, query: String): List<String> {
        val result = request(connection.server, "api/v1/core/tags/autocomplete/", connection.token, query = mapOf("q" to query))
        return normalizeTags(result.getJSONArray("tags").objects().map { it.getString("name") })
    }

    suspend fun personas(connection: Connection): List<Persona> {
        val result = mutableListOf<Persona>()
        while (true) {
            val page = request(connection.server, "api/v1/personas/personas", connection.token, query = mapOf("offset" to result.size.toString()))
            val items = page.getJSONArray("items").objects()
            result += items.map { Persona(it.getString("id"), it.getString("name")) }
            if (result.size >= page.getInt("total_items")) return result
            check(items.isNotEmpty()) { "Server returned an incomplete persona list." }
        }
    }

    suspend fun submit(connection: Connection, urls: List<String>): Receipt {
        require(urls.isNotEmpty() && urls.all { extractUrls(it) == listOf(it) }) { "Add at least one HTTP or HTTPS URL." }
        require(personas(connection).any { it.name == connection.persona }) { "The selected persona is unavailable. Choose one in Add URLs." }
        val result = request(connection.server, "api/v1/cli/add", connection.token,
            JSONObject().put("urls", JSONArray(urls)).put("persona", connection.persona).put("depth", 0))
        require(result.optBoolean("success") && (result.optJSONArray("errors")?.length() ?: 0) == 0) { "Server did not confirm submission. Check your archive before trying again." }
        val receipt = result.getJSONObject("result")
        val id = receipt.getString("crawl_id")
        val queued = receipt.getJSONArray("queued_urls").strings()
        require(validId(id) && queued.containsAll(urls)) { "Server did not confirm the queued URLs." }
        return Receipt(id, queued)
    }

    suspend fun updateTags(connection: Connection, receipt: Receipt, tags: List<String>) {
        require(validId(receipt.crawlId))
        val normalized = normalizeTags(tags)
        val result = request(connection.server, "api/v1/crawls/crawl/${receipt.crawlId}", connection.token,
            JSONObject().put("tags", JSONArray(normalized)), "PATCH")
        require(result.getString("id") == receipt.crawlId &&
            normalizeTags(listOf(result.getString("tags_str"))).map(String::lowercase).toSet() == normalized.map(String::lowercase).toSet()) { "Server did not confirm the tag update." }
    }

    suspend fun removeSubmission(connection: Connection, receipt: Receipt) {
        require(validId(receipt.crawlId))
        val result = request(connection.server, "api/v1/crawls/crawl/${receipt.crawlId}", connection.token, method = "DELETE")
        require(result.optBoolean("success") && result.getString("crawl_id") == receipt.crawlId) { "Server did not confirm removal." }
    }

    suspend fun browserSession(connection: Connection): BrowserSession {
        val result = request(connection.server, "api/v1/auth/browser_session", connection.token, JSONObject())
        val admin = result.getString("admin_url")
        val source = connection.server.toHttpUrl()
        val target = admin.toHttpUrl()
        require(target.username.isEmpty() && target.password.isEmpty() && target.query == null && target.fragment == null &&
            target.encodedPath.endsWith("/admin/")) { "Server returned an invalid administrator address." }
        // ArchiveBox may separate web and admin subdomains, but cannot send session credentials to an arbitrary host.
        val baseHost = source.host.replace(Regex("^(web|admin|api)\\."), "")
        require(target.scheme == source.scheme && target.port == source.port &&
            target.host in setOf(source.host, "admin.$baseHost")) { "Server returned a browser session for an unrelated address." }
        val cookie = result.getJSONObject("cookie")
        require(cookie.getDouble("expires") > System.currentTimeMillis() / 1000) { "Server returned an expired browser session." }
        val name = cookie.getString("name"); val value = cookie.getString("value")
        require(name.matches(Regex("[A-Za-z0-9_-]+")) && value.matches(Regex("[A-Za-z0-9._~+/=-]+"))) { "Invalid browser session cookie." }
        val secure = cookie.getBoolean("secure")
        require(!secure || target.isHttps) { "This browser session requires HTTPS." }
        return BrowserSession(admin, name, value, secure)
    }

    companion object { fun validId(value: String) = value.matches(Regex("[a-fA-F0-9]{32}|[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")) }
}

private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
private fun JSONArray.strings() = (0 until length()).map(::getString)
