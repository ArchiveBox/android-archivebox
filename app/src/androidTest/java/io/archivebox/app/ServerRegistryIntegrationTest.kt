package io.archivebox.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.archivebox.app.data.ArchiveRepository
import io.archivebox.app.data.ServerConfiguration
import io.archivebox.app.data.ServerRegistry
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Real Android encrypted persistence and API ownership, using disposable server credentials. */
@RunWith(AndroidJUnit4::class)
class ServerRegistryIntegrationTest {
    @Test fun profilesPersistAndReceiptsRemainServerScoped() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val repository = ArchiveRepository(instrumentation.targetContext)
        val fixture = JSONObject(instrumentation.context.assets.open("server_registry.example.json").bufferedReader().use { it.readText() })
        val canonical = ServerRegistry.fromJson(fixture)
        assertEquals("Work", canonical.active_server?.name)
        assertEquals(listOf("Home"), canonical.default_servers.map { it.name })
        assertEquals(fixture.toString(), canonical.toJson().toString())
        val before = repository.registry.value
        val first = ServerConfiguration(
            name = "First",
            server = requireNotNull(arguments.getString("server")),
            token = requireNotNull(arguments.getString("token")), persona = "Personal",
        )
        val second = ServerConfiguration(
            name = "Second",
            server = requireNotNull(arguments.getString("second_server")),
            token = requireNotNull(arguments.getString("second_token")), persona = "Work",
        )
        repository.saveConnection(first)
        repository.saveConnection(second)
        // A different Activity's older repository must not overwrite the newly saved profile.
        val other = ArchiveRepository(instrumentation.targetContext)
        other.saveConnection(second.copy(name = "Second renamed"), select = false)
        repository.saveConnection(first, select = false)
        val reloaded = ArchiveRepository(instrumentation.targetContext)
        assertEquals(before.servers.size + 2, reloaded.registry.value.servers.size)
        assertEquals(first.id, reloaded.registry.value.servers.first { it.id == first.id }.id)
        assertEquals("Personal", reloaded.registry.value.servers.first { it.id == first.id }.persona)
        assertEquals(second.id, reloaded.registry.value.active_server_id)
        assertEquals(listOf(second.id), reloaded.registry.value.default_server_ids)
        reloaded.saveConnection(first.copy(name = "Renamed", token = "temporarily-invalid"), select = false)
        assertEquals(second.id, reloaded.registry.value.active_server_id)
        assertEquals(listOf(second.id), reloaded.registry.value.default_server_ids)
        reloaded.saveConnection(first, select = false)
        reloaded.rememberTags(first.id, listOf("first-only"))
        assertTrue(reloaded.recentTags(second.id).isEmpty())

        val url = "https://example.com/?android-multiserver=${UUID.randomUUID()}"
        val first_receipt = reloaded.api.submit(first, listOf(url))
        val second_receipt = reloaded.api.submit(second, listOf(url))
        assertEquals(first.id, first_receipt.server_id)
        assertEquals(second.id, second_receipt.server_id)
        assertNotEquals(first_receipt.crawl_id, second_receipt.crawl_id)
        assertEquals(listOf(url), first_receipt.queued_urls)
        var rejected = false
        try { reloaded.api.removeSubmission(second, first_receipt) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue("A receipt must not authorize deletion from another server", rejected)
        reloaded.api.updateTags(first, first_receipt, listOf("first-only"))
        reloaded.api.removeSubmission(second, second_receipt)
        val check = URL(first.server.trimEnd('/') + "/api/v1/crawls/crawl/" + first_receipt.crawl_id).openConnection() as HttpURLConnection
        check.setRequestProperty("Authorization", "Bearer " + first.token)
        try { assertEquals(200, check.responseCode) } finally { check.disconnect() }
        reloaded.api.removeSubmission(first, first_receipt)
        // Remove only profiles created by this test; preserve any other test configuration.
        reloaded.clearConnection()
        reloaded.saveConnection(first)
        reloaded.clearConnection()
        assertEquals(before.servers, reloaded.registry.value.servers)
    }
}
