package io.archivebox.app.data

import io.archivebox.app.ui.canReadServerKey
import io.archivebox.app.ui.isServerLoginPage
import io.archivebox.app.ui.serverLoginUrl
import io.archivebox.app.ui.trustedLoginPage
import org.junit.Assert.*
import org.junit.Test

class LoginPolicyTest {
    @Test fun loginIsAnExactAdminRouteWithoutCredentials() {
        val server = "https://api.archive.example:5797/"
        assertEquals("https://api.archive.example:5797/admin/login/?next=%2Fadmin%2F", serverLoginUrl(server))
        for (url in listOf("https://admin.archive.example:5797/admin/login/", "https://api.archive.example:5797/admin/login?next=%2Fadmin%2F")) {
            assertTrue(isServerLoginPage(url, server))
            assertFalse(canReadServerKey(url, server))
        }
        assertTrue(canReadServerKey("https://admin.archive.example:5797/admin/", server))
        assertTrue(canReadServerKey("https://admin.archive.example:5797/admin/core/snapshot/", server))
        assertFalse(isServerLoginPage("https://admin.archive.example:5797/admin/login-fake/", server))
    }

    @Test fun recoveryNeverReadsTokensFromReplayExternalOrDowngradedPages() {
        val server = "https://api.archive.example:5797/"
        for (url in listOf(
            "https://web.archive.example:5797/admin/",
            "https://snap-012345abcdef.archive.example:5797/admin/",
            "https://other.archive.example:5797/admin/",
            "https://admin.archive.example.attacker.test:5797/admin/",
            "http://admin.archive.example:5797/admin/",
            "https://admin.archive.example/admin/",
            "https://user:password@admin.archive.example:5797/admin/",
            "https://api.archive.example:5797/web/123/",
            "https://api.archive.example:5797/administer/",
            "https://api.archive.example:5797/admin/../web/",
            "https://api.archive.example:5797/%61dmin/",
            "javascript:window.ARCHIVEBOX_API_KEY",
        )) {
            assertFalse(url, trustedLoginPage(url, server))
            assertFalse(url, canReadServerKey(url, server))
        }
        assertFalse(trustedLoginPage("https://admin.example:5797/admin/", "https://api.admin.example:5797/"))
    }

    @Test fun reverseProxyPrefixAndSingleDomainServerRemainSupported() {
        val server = "http://127.0.0.1:5797/collection/"
        assertEquals("http://127.0.0.1:5797/collection/admin/login/?next=%2Fcollection%2Fadmin%2F", serverLoginUrl(server))
        assertTrue(isServerLoginPage(server + "admin/login/", server))
        assertTrue(canReadServerKey(server + "admin/", server))
        assertFalse(canReadServerKey("http://127.0.0.1:5797/admin/", server))
    }
}
