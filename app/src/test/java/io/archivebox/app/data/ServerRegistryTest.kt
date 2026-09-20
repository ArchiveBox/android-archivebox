package io.archivebox.app.data

import org.junit.Assert.*
import org.junit.Test

class ServerRegistryTest {
    @Test fun browsingSelectionDoesNotChangeSubmissionDefaults() {
        val home = ServerConfiguration(name = "Home", server = "https://home.example", token = "home-key", persona = "Personal")
        val work = ServerConfiguration(name = "Work", server = "https://work.example", token = "work-key", persona = "Work")
        val registry = ServerRegistry().upsert(home).upsert(work).copy(active_server_id = work.id)
        registry.validate()
        assertEquals(work, registry.active_server)
        assertEquals(listOf(home), registry.default_servers)
        assertEquals(home, registry.remove(work.id).active_server)
        assertEquals(emptyList<ServerConfiguration>(), registry.remove(home.id).default_servers)
    }

    @Test fun updatingCredentialsPreservesIdentityAndOtherProfiles() {
        val home = ServerConfiguration(name = "Home", server = "https://home.example", token = "old", persona = "Personal")
        val work = ServerConfiguration(name = "Work", server = "https://work.example", token = "work", persona = "Work")
        val updated = home.copy(name = "Home", token = "new", persona = "Other")
        val registry = ServerRegistry().upsert(home).upsert(work).upsert(updated)
        assertEquals(2, registry.servers.size)
        assertEquals(listOf(updated), registry.default_servers)
        assertEquals(work, registry.servers.find { it.id == work.id })
        assertThrows(IllegalArgumentException::class.java) { registry.copy(default_server_ids = listOf("missing")).validate() }
        assertThrows(IllegalArgumentException::class.java) { registry.copy(schema_version = 2).validate() }
    }
}
