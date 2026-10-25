package io.archivebox.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.archivebox.app.data.ArchiveApi
import io.archivebox.app.data.ServerConfiguration
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

/** Uses the real server and Android sockets across an idle HTTP connection boundary. */
@RunWith(AndroidJUnit4::class)
class BrowserSessionIntegrationTest {
    @Test fun browserSessionsAfterIdle(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val connection = ServerConfiguration(
            name = "Browser session integration",
            server = requireNotNull(arguments.getString("serverUrl")),
            token = requireNotNull(arguments.getString("apiToken")),
        )
        val api = ArchiveApi()
        val firstStarted = System.nanoTime()
        val first = api.browserSession(connection)
        Log.i("BrowserSessionIntegrationTest", "First session: ${(System.nanoTime() - firstStarted) / 1_000_000}ms")
        // Exercise actual server keep-alive expiry, rather than retrying a failed request.
        delay(6_000)
        val idleStarted = System.nanoTime()
        val second = api.browserSession(connection)
        Log.i("BrowserSessionIntegrationTest", "Session after idle: ${(System.nanoTime() - idleStarted) / 1_000_000}ms")
        assertNotEquals(first.cookie.value, second.cookie.value)
        val warmMillis = measureTimeMillis {
            val third = api.browserSession(connection)
            assertNotEquals(second.cookie.value, third.cookie.value)
        }
        Log.i("BrowserSessionIntegrationTest", "Immediate session: ${warmMillis}ms")
    }
}
