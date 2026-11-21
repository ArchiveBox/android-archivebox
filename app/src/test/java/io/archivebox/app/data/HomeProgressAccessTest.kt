package io.archivebox.app.data

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class HomeProgressAccessTest {
    @Test fun reachableForbiddenSessionIsOnlySpecialCasedForHomeProgress() = runBlocking {
        val posts = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/auth/browser_session") { exchange ->
            exchange.use {
                if (exchange.requestMethod == "POST" && exchange.requestHeaders.getFirst("Authorization") == "Bearer fixture") {
                    posts.incrementAndGet()
                }
                exchange.requestBody.use { it.readBytes() }
                val response = """{"detail":"Valid credentials but User does not have permission (make sure user.is_superuser=True)"}""".toByteArray()
                exchange.sendResponseHeaders(403, response.size.toLong())
                exchange.responseBody.write(response)
            }
        }
        server.start()
        val connection = ServerConfiguration(name = "Fixture", server = "http://127.0.0.1:${server.address.port}/", token = "fixture")
        val api = ArchiveApi(timeoutSeconds = 2)
        try {
            assertTrue(runCatching { api.homeBrowserSession(connection) }.exceptionOrNull() is HomeProgressScopeDenied)
            assertEquals("Access denied. Check your administrator API key.",
                runCatching { api.browserSession(connection) }.exceptionOrNull()?.message)
            assertEquals(2, posts.get())
        } finally {
            server.stop(0)
        }
        val offline = runCatching { api.homeBrowserSession(connection) }.exceptionOrNull()
        assertTrue(offline is IOException && offline !is HomeProgressScopeDenied)
    }

    @Test fun cookieDenialCanRecoverWithoutFollowingRedirects() = runBlocking {
        val codes = AtomicInteger(401)
        val redirects = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/progress.json") { exchange ->
            exchange.use {
                assertEquals("sessionid=fixture", exchange.requestHeaders.getFirst("Cookie"))
                assertNull(exchange.requestHeaders.getFirst("Authorization"))
                if (codes.get() == 302) exchange.responseHeaders.set("Location", "/login/")
                exchange.sendResponseHeaders(codes.get(), -1)
            }
        }
        server.createContext("/login/") { exchange ->
            exchange.use {
                redirects.incrementAndGet()
                exchange.sendResponseHeaders(200, -1)
            }
        }
        server.start()
        val session = BrowserSession("http://127.0.0.1:${server.address.port}/admin/",
            BrowserSession.Cookie("sessionid", "fixture", 2_000_000_000.0, false))
        val api = ArchiveApi(timeoutSeconds = 2)
        try {
            for (code in listOf(401, 403)) {
                codes.set(code)
                assertTrue(runCatching { api.homeProgress(session) }.exceptionOrNull() is HomeProgressSessionRejected)
            }
            for (code in listOf(302, 404)) {
                codes.set(code)
                assertTrue(runCatching { api.homeProgress(session) }.exceptionOrNull() is HomeProgressUnavailable)
            }
            assertEquals(0, redirects.get())
        } finally {
            server.stop(0)
        }
    }

}
