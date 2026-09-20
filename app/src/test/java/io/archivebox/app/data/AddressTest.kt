package io.archivebox.app.data

import org.junit.Assert.*
import org.junit.Test
import io.archivebox.app.ui.allowedArchiveOrigin

class AddressTest {
    @Test fun bareDeviceUsesCompanionPortAndExplicitUrlsRetainTheirPorts() {
        assertEquals("http://archivebox.local:5759/", normalizeServer("archivebox.local"))
        assertEquals("http://100.65.2.3:5759/", normalizeServer("100.65.2.3"))
        assertEquals("https://archive.example/", normalizeServer("https://archive.example"))
        assertEquals("http://archive.example:8080/base/", normalizeServer("http://archive.example:8080/base"))
        assertEquals("http://[fd7a:115c:a1e0::1]:5759/", normalizeServer("http://[fd7a:115c:a1e0::1]:5759"))
    }

    @Test fun serverCredentialsQueriesAndNonWebSchemesAreRejected() {
        for (address in listOf("", "file:///etc/passwd", "https://user:password@example.com", "https://example.com/?token=secret", "https://example.com/#fragment")) {
            assertThrows(IllegalArgumentException::class.java) { normalizeServer(address) }
        }
    }

    @Test fun sharingPreservesBalancedUrlPunctuationAndDeduplicates() {
        assertEquals(listOf("https://en.wikipedia.org/wiki/Archive_(disambiguation)", "http://[::1]/?q=a!"),
            extractUrls("Read (https://en.wikipedia.org/wiki/Archive_(disambiguation)). Then http://[::1]/?q=a!"))
        assertEquals(listOf("https://example.com/"), extractUrls("https://example.com/\nhttps://example.com/"))
        assertEquals(emptyList<String>(), extractUrls("file:///private/data https://user:secret@example.com"))
    }

    @Test fun tagsDeduplicateCaseInsensitivelyAndKeepReadableSpelling() {
        assertEquals(listOf("Research", "read later", "⭐"), normalizeTags(listOf(" Research,read later", "research\n⭐", "")))
    }

    @Test fun sameOriginChecksSchemeAndPortNotJustHostname() {
        assertTrue(sameOrigin("https://archive.example/base/", "https://archive.example/admin/"))
        assertFalse(sameOrigin("https://archive.example/", "http://archive.example/"))
        assertFalse(sameOrigin("http://archive.example:5759/", "http://archive.example:8080/"))
        assertFalse(sameOrigin("https://archive.example/", "https://archive.example.attacker.test/"))
    }

    @Test fun receiptIdsCannotBecomePaths() {
        assertTrue(ArchiveApi.validId("00000000-0000-4000-8000-000000000001"))
        assertTrue(ArchiveApi.validId("00000000000040008000000000000001"))
        for (id in listOf("../admin", "a/b", "snapshot", "")) assertFalse(ArchiveApi.validId(id))
    }

    @Test fun replayNavigationKeepsSnapshotSubdomainsInsideTheConfiguredServer() {
        val server = "https://api.archive.example:5759/"
        val admin = "https://admin.archive.example:5759/admin/"
        assertTrue(allowedArchiveOrigin(admin, server, admin))
        assertTrue(allowedArchiveOrigin(server, server, admin))
        assertTrue(allowedArchiveOrigin("https://snap-012345abcdef.archive.example:5759/index.html", server, admin))
        for (url in listOf(
            "http://snap-012345abcdef.archive.example:5759/",
            "https://snap-012345abcdef.archive.example/",
            "https://snap-012345abcdef.archive.example.attacker.test:5759/",
            "https://other.archive.example:5759/",
            "https://snap-012345abcdeg.archive.example:5759/",
            "https://user:password@admin.archive.example:5759/admin/",
            "javascript:alert(1)",
        )) assertFalse(url, allowedArchiveOrigin(url, server, admin))
        assertFalse(allowedArchiveOrigin("https://snap-012345abcdef.example/", "https://web.admin.example/", "https://admin.admin.example/admin/"))
    }
}
