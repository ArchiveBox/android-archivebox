package io.archivebox.app.data

import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TokenRecoveryTest {
    @Test fun onlyExplicitServerRejectionStartsRecovery() {
        try {
            validateTokenResponse(JSONObject("""{"success":false,"user_id":null,"username":null}"""))
            fail("Expected explicit rejection")
        } catch (_: ApiKeyRejectedException) { }
    }

    @Test fun malformedAndAmbiguousResponsesAreNotKeyRejection() {
        for (json in listOf("{}", """{"success":false}""", """{"success":"false","user_id":null,"username":null}""",
            """{"success":false,"user_id":"123","username":null}""", """{"success":true,"user_id":null}""")) {
            try {
                validateTokenResponse(JSONObject(json))
                fail("Expected invalid response: $json")
            } catch (e: IllegalArgumentException) {
                assertFalse(e is ApiKeyRejectedException)
            }
        }
    }

    @Test fun validKeyDoesNotRecover() {
        validateTokenResponse(JSONObject("""{"success":true,"user_id":"123","username":"tester"}"""))
    }

    @Test fun recoveryUsesSavedOriginAndNoCredential() {
        assertEquals("https://saved.example/admin/api/apitoken/", keyRecoveryUrl("https://saved.example/"))
        assertEquals("http://localhost:5797/base/admin/api/apitoken/", keyRecoveryUrl("http://localhost:5797/base/"))
        for (url in listOf("https://user:secret@example.com", "https://example.com/?token=secret", "https://example.com/#token")) {
            assertThrows(IllegalArgumentException::class.java) { keyRecoveryUrl(url) }
        }
    }
    @Test fun passwordSignInRequiresTlsOrLiteralLoopback() {
        for (server in listOf("https://archive.example/", "http://localhost:5797/", "http://127.0.0.1:5797/", "http://[::1]:5797/")) {
            assertTrue(server, supportsPasswordSignIn(server))
        }
        for (server in listOf("http://10.0.0.2/", "http://192.168.1.2/", "http://172.16.0.2/", "http://archive.example/",
            "http://127.0.0.2/", "http://localhost.example/", "http://localhost./", "http://[::ffff:127.0.0.1]/",
            "https://user:secret@example.com/", "http://localhost/?next=https://example.com", "http://localhost/#secret")) {
            assertFalse(server, supportsPasswordSignIn(server))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { ArchiveApi().signIn(server, "fixture", "fixture") } }
        }
    }

}
