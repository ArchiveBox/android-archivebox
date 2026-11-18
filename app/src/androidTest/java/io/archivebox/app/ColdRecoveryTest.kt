package io.archivebox.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.archivebox.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ColdRecoveryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun await(tag: String) = compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    @Test fun restoredKeyAfterProcessRestart() = runBlocking {
        val repo = ArchiveRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val restored = requireNotNull(repo.registry.value.active_server)
        assertNotEquals("synthetic-rejected-key", restored.token)
        repo.api.testToken(restored)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            repo.api.testToken(restored)
            compose.onNodeWithTag("recovery.login").assertDoesNotExist()
        }
    }
    @Test fun rejectedKeySignInPersistsAcrossLaunches() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val repo = ArchiveRepository(instrumentation.targetContext)
        val saved = ServerConfiguration(name = "Recovery fixture", server = requireNotNull(args.getString("serverUrl")), token = "synthetic-rejected-key")
        repo.saveConnection(saved)
        ActivityScenario.launch(MainActivity::class.java).use {
            await("recovery.login")
            compose.onNodeWithTag("recovery.username").performTextInput("recovery-fixture")
            compose.onNodeWithTag("recovery.password").performTextInput("wrong-fixture-password")
            compose.onNodeWithTag("recovery.submit").performClick()
            await("recovery.error")
            assertEquals(saved, ArchiveRepository(instrumentation.targetContext).registry.value.active_server)
            compose.onNodeWithTag("recovery.cancel").performClick()
            compose.onNodeWithTag("recovery.login").assertDoesNotExist()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            await("recovery.login")
            compose.onNodeWithTag("recovery.username").performTextInput("recovery-fixture")
            compose.onNodeWithTag("recovery.password").performTextInput("synthetic-local-password")
            compose.onNodeWithTag("recovery.submit").performClick()
            compose.waitUntil(20_000) { ArchiveRepository(instrumentation.targetContext).registry.value.active_server?.token != saved.token }
        }
        val replaced = requireNotNull(ArchiveRepository(instrumentation.targetContext).registry.value.active_server)
        assertEquals(saved.id, replaced.id)
        assertEquals(saved.server, replaced.server)
        assertNotEquals(saved.token, replaced.token)
        repo.api.testToken(replaced)
        repeat(2) {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitForIdle()
                repo.api.testToken(requireNotNull(ArchiveRepository(instrumentation.targetContext).registry.value.active_server))
                assertEquals(replaced, ArchiveRepository(instrumentation.targetContext).registry.value.active_server)
                compose.onNodeWithTag("recovery.login").assertDoesNotExist()
            }
        }
        // A late response after another selection/logout must not restore it.
        assertTrue(runCatching { repo.replaceRejectedKey(saved, "stale-response") }.isFailure)
        assertEquals(replaced, ArchiveRepository(instrumentation.targetContext).registry.value.active_server)
    }
}
