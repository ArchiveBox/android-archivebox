package io.archivebox.app

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.espresso.web.assertion.WebViewAssertions.webMatches
import androidx.test.espresso.web.model.Atoms
import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.archivebox.app.data.ArchiveRepository
import org.hamcrest.Matchers.equalTo
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Uses real connection forms, encrypted preferences, discovery and authenticated server pages. */
@RunWith(AndroidJUnit4::class)
class ClientParityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun node(tag: String) = compose.onNodeWithTag(tag)
    private fun await(tag: String) = compose.waitUntil(30_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun click(tag: String, scroll: Boolean = false) { await(tag); if (scroll) node(tag).performScrollTo(); node(tag).performClick() }
    private fun fill(tag: String, value: String) { await(tag); node(tag).performScrollTo().performTextReplacement(value); androidx.test.espresso.Espresso.closeSoftKeyboard() }
    private fun shot(name: String) {
        compose.waitForIdle()
        UiDevice.getInstance(instrumentation).waitForIdle()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val output = File(context.getExternalFilesDir(null), "parity").apply { mkdirs() }
        File(output, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
    @Test fun setupRememberedSwitchingAndEmbeddedSearch() {
        val args = InstrumentationRegistry.getArguments()
        val first = requireNotNull(args.getString("serverUrl"))
        val second = requireNotNull(args.getString("secondServerUrl"))
        val token = requireNotNull(args.getString("apiToken"))
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("setup.skip").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithTag("tab.Archive").fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodesWithTag("setup.skip").fetchSemanticsNodes().isEmpty()) { click("tab.Archive"); click("setup.reopen") }
        shot("setup")
        click("setup.choose")
        for (route in listOf("mac", "hosting", "docker", "python")) {
            click("setup.$route", scroll = true); await("guide.$route"); shot("setup-$route"); click("guide.back", scroll = true)
        }
        click("setup.skip")
        fill("connection.hints", first)
        click("connection.discover", scroll = true)
        val device = UiDevice.getInstance(instrumentation)
        device.wait(Until.findObject(By.res("com.android.permissioncontroller", "permission_allow_button")), 5_000)?.click()
        await("discovery.server")
        val discovered = hasTestTag("discovery.server") and hasAnyDescendant(hasText(first))
        compose.waitUntil(30_000) { compose.onAllNodes(discovered).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(discovered).performScrollTo().assertIsDisplayed()
        shot("discovery")
        val secondToken = requireNotNull(args.getString("secondApiToken"))
        for ((server, key) in listOf(first to token, second to secondToken)) {
            fill("connection.url", server); fill("connection.token", key); click("connection.save", scroll = true)
            compose.waitUntil(30_000) { ArchiveRepository(context).registry.value.active_server?.server == server }
        }
        val registry = ArchiveRepository(context).registry.value
        val firstSaved = registry.servers.single { it.server == first }
        val secondSaved = registry.servers.single { it.server == second }
        node("history.server.${firstSaved.id}").performScrollTo().assertExists()
        shot("remembered")
        click("tab.Archive")
        node("home").performScrollToNode(hasTestTag("sidebar.serverSwitcher"))
        click("sidebar.serverSwitcher"); click("sidebar.switchServer.${firstSaved.id}")
        compose.waitUntil(10_000) { ArchiveRepository(context).registry.value.active_server_id == firstSaved.id }
        assertEquals(registry.default_server_ids, ArchiveRepository(context).registry.value.default_server_ids)
        node("home").performScrollToNode(hasTestTag("sidebar.searchField"))
        fill("sidebar.searchField", "a & b/日本語")
        click("sidebar.searchMode"); compose.onNodeWithText("Metadata").performClick()
        shot("menu-search")
        click("sidebar.searchSubmit"); await("browser.ready")
        val expression = Atoms.script("return new URL(location.href).searchParams.get('q') + '|' + new URL(location.href).searchParams.get('search_mode');", Atoms.castOrDie(String::class.java))
        onWebView().check(webMatches(expression, equalTo("a & b/日本語|meta")))
        shot("embedded-search")
        click("tab.Archive")
        node("home").performScrollToNode(hasTestTag("sidebar.serverSwitcher"))
        click("sidebar.serverSwitcher"); click("sidebar.switchServer.${secondSaved.id}")
        node("home").performScrollToNode(hasTestTag("sidebar.searchField"))
        assertEquals("", node("sidebar.searchField").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        click("tab.Search"); await("browser.ready")
        onWebView().check(webMatches(Atoms.script("return location.origin;", Atoms.castOrDie(String::class.java)), equalTo(second.trimEnd('/'))))
        click("tab.Settings")
        click("history.server.${firstSaved.id}", scroll = true)
        compose.waitUntil(10_000) { ArchiveRepository(context).registry.value.active_server_id == firstSaved.id }
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Connected to ${firstSaved.name}.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("Remembered selection must restore its own API key", token == ArchiveRepository(context).registry.value.active_server?.token)
        shot("remembered-selected")
        // Forget through the UI, preserving any profiles that predated this acceptance run.
        for (saved in listOf(firstSaved, secondSaved)) {
            click("history.forget.${saved.id}", scroll = true); compose.onNodeWithText("Forget server", useUnmergedTree = true).performClick()
            compose.waitUntil(10_000) { ArchiveRepository(context).registry.value.servers.none { it.id == saved.id } }
        }
    }
}
