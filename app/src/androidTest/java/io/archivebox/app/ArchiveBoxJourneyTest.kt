package io.archivebox.app

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.espresso.web.assertion.WebViewAssertions.webMatches
import androidx.test.espresso.web.model.Atoms
import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.espresso.web.webdriver.DriverAtoms.findElement
import androidx.test.espresso.web.webdriver.DriverAtoms.getText
import androidx.test.espresso.web.webdriver.DriverAtoms.webClick
import androidx.test.espresso.web.webdriver.Locator
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** End-to-end acceptance against a real, disposable ArchiveBox collection. */
@RunWith(AndroidJUnit4::class)
class ArchiveBoxJourneyTest {
    private companion object {
        const val PROVIDER_SETUP_TEXT = "Set up your preferred model provider first"
        val providerPromptVisible = Atoms.script(
            """function(element) {
                var style = window.getComputedStyle(element);
                var rect = element.getBoundingClientRect();
                var visibleHeight = Math.max(0, Math.min(rect.bottom, window.innerHeight) - Math.max(rect.top, 0));
                return style.display !== 'none' && style.visibility !== 'hidden' &&
                    rect.width > 0 && visibleHeight * window.devicePixelRatio > 30
                    ? 'visible' : 'hidden';
            }""",
            Atoms.castOrDie(String::class.java),
        )
    }
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val server get() = requireNotNull(arguments.getString("serverUrl"))
    private val token get() = requireNotNull(arguments.getString("apiToken"))
    private val output get() = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }

    private fun node(tag: String) = compose.onAllNodesWithTag(tag).onLast()
    private fun await(tag: String) {
        try {
            compose.waitUntil(30_000) {
                val errors = compose.onAllNodesWithTag("error").fetchSemanticsNodes()
                if (errors.isNotEmpty()) {
                    shot("failure", composeIdle = false)
                    throw AssertionError("App rendered an error while awaiting $tag: " + errors.joinToString { it.config.toString() })
                }
                compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: Throwable) {
            shot("failure", composeIdle = false)
            throw failure
        }
    }
    private fun click(tag: String, scroll: Boolean = false) {
        await(tag)
        if (scroll) node(tag).performScrollTo()
        node(tag).performClick()
    }
    private fun fill(tag: String, text: String) {
        await(tag)
        node(tag).performScrollTo().performTextReplacement(text)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
    }
    private fun shot(id: String, composeIdle: Boolean = true) {
        if (composeIdle) compose.waitForIdle()
        device.waitForIdle()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "No device screenshot for $id" }
        File(output, "$id.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
    private fun route(name: String) {
        click("tab.Archive")
        compose.onNodeWithTag("home").performScrollToNode(hasTestTag("route.$name"))
        click("route.$name")
        await("browser.ready")
        compose.onAllNodesWithTag("error").assertCountEquals(0)
        assertFalse("Server routes must authenticate, not render a login form", device.hasObject(By.text("Log in")))
        assertFalse("Server routes must not show a 404 page", device.hasObject(By.text("Not Found")))
    }
    private fun snapshots(search: String): List<JSONObject> {
        val query = URLEncoder.encode(search, "UTF-8")
        val connection = URL("${server.trimEnd('/')}/api/v1/core/snapshots?search=$query").openConnection() as HttpURLConnection
        connection.setRequestProperty("X-ArchiveBox-API-Key", token)
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        return try {
            assertEquals("The real API must authenticate", 200, connection.responseCode)
            val items = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONArray("items")
            List(items.length()) { items.getJSONObject(it) }
        } finally { connection.disconnect() }
    }

    @Test fun realServerJourneyAndEveryMajorScreen() {
        instrumentation.targetContext.startActivity(requireNotNull(instrumentation.targetContext.packageManager
            .getLaunchIntentForPackage("io.archivebox.app")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue("The launcher must open onboarding", device.wait(Until.hasObject(By.text("Connect to existing server")), 5_000))
        await("setup.choose")
        shot("onboarding")
        click("setup.choose")
        click("setup.docker", scroll = true)
        await("guide.docker")
        shot("setup-docker")
        click("guide.back")
        click("setup.skip")
        fill("connection.url", server)
        fill("connection.token", "invalid-android-acceptance-key")
        click("connection.save", scroll = true)
        if (Build.VERSION.SDK_INT >= 37) {
            val allow = device.wait(Until.findObject(By.res("com.android.permissioncontroller", "permission_allow_button")), 5_000)
            requireNotNull(allow) { "Expected the real Android local network permission prompt" }.click()
        }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("This API key is invalid or expired.").assertExists()
        compose.onAllNodesWithTag("connection.status").assertCountEquals(0)
        node("tab.Search").performClick()
        await("connect.open")
        compose.onAllNodesWithTag("search.query").assertCountEquals(0)
        click("connect.open")
        fill("connection.url", server)
        fill("connection.token", token)
        click("connection.save", scroll = true)
        await("connection.status")
        compose.onNodeWithText("Connected.", substring = true).assertExists()
        node("connection.url").performScrollTo()
        shot("connections")

        click("tab.Archive")
        click("tab.Settings")
        node("connection.discover").performScrollTo()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("10.0.2.2", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("10.0.2.2", substring = true).performScrollTo().assertExists()
        shot("discovery")

        click("tab.Archive")
        shot("home")
        route("Snapshots")
        assertTrue("The library must render its real page title", device.wait(Until.hasObject(By.textContains("Snapshots")), 10_000))
        shot("library")
        click("tab.Archive")
        compose.onNodeWithTag("home").performScrollToNode(hasTestTag("sidebar.searchField"))
        fill("sidebar.searchField", "example.com")
        click("sidebar.searchMode")
        compose.onNodeWithText("Metadata").performClick()
        click("sidebar.searchSubmit")
        await("browser.ready")
        val exampleId = snapshots("example.com").first { it.getString("url").trimEnd('/') == "https://example.com" }.getString("id")
        val inputValue = Atoms.script("function(element) { return element.value; }", Atoms.castOrDie(String::class.java))
        onWebView().withElement(findElement(Locator.CSS_SELECTOR, "input[name=q]"))
            .check(webMatches(inputValue, equalTo("example.com")))
        onWebView().withElement(findElement(Locator.CSS_SELECTOR, "select[name=search_mode]"))
            .check(webMatches(inputValue, equalTo("meta")))
        val resultRow = "//input[@name='_selected_action' and @value='$exampleId']/ancestor::tr"
        onWebView().withElement(findElement(Locator.XPATH, resultRow))
            .check(webMatches(getText(), containsString("example.com")))
        onWebView().withElement(findElement(Locator.XPATH, resultRow))
            .check(webMatches(getText(), containsString("Example Domain")))
        shot("search")
        onWebView().withElement(findElement(Locator.XPATH,
            "$resultRow//td[contains(@class, 'field-title_str')]/a[contains(@class, 'snapshot-title-detail-hitbox')]"))
            .perform(webClick())
        await("browser.ready")
        compose.onAllNodesWithTag("error").assertCountEquals(0)
        onWebView().withElement(findElement(Locator.CSS_SELECTOR, ".output-stack-raster")).perform(webClick())
        onWebView().withElement(findElement(Locator.CSS_SELECTOR,
            ".thumb-card[data-plugin-name='screenshot'] a[target='preview']")).perform(webClick())
        // Hide the output cards with the server's own header control so the
        // selected replay has room in the phone viewport.
        onWebView().withElement(findElement(Locator.CSS_SELECTOR, ".header-toggle")).perform(webClick())
        val headerHidden = Atoms.script(
            "function(header) { return header.hidden ? 'hidden' : 'visible'; }",
            Atoms.castOrDie(String::class.java),
        )
        onWebView().withElement(findElement(Locator.CSS_SELECTOR, ".header-bottom"))
            .check(webMatches(headerHidden, equalTo("hidden")))
        val replayVisible = Atoms.script(
            """function(frame) {
                var rect = frame.getBoundingClientRect();
                var visibleHeight = Math.max(0, Math.min(rect.bottom, window.innerHeight) - Math.max(rect.top, 0));
                return frame.src.includes('screenshot') && visibleHeight * window.devicePixelRatio > 300
                    ? 'visible' : 'hidden';
            }""",
            Atoms.castOrDie(String::class.java),
        )
        onWebView().withElement(findElement(Locator.CSS_SELECTOR, "#main-frame"))
            .check(webMatches(replayVisible, equalTo("visible")))
        val archivedImage = device.wait(Until.findObject(By.desc("Screenshot of page")), 10_000)
        assertNotNull("The expanded replay must display its screenshot", archivedImage)
        assertTrue("The full screenshot must be visible, not its thumbnail", archivedImage!!.visibleBounds.height() > 300)
        val screenshot = URL("${server.trimEnd('/')}/snapshot/$exampleId/screenshot/screenshot.png").openConnection() as HttpURLConnection
        screenshot.setRequestProperty("X-ArchiveBox-API-Key", token)
        screenshot.connectTimeout = 10_000
        screenshot.readTimeout = 10_000
        try {
            assertEquals("The replay image must be served by the real archive", 200, screenshot.responseCode)
            assertEquals("image/png", screenshot.contentType)
            assertTrue("The archived PNG must contain image data", screenshot.contentLength > 10_000)
            assertArrayEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10),
                screenshot.inputStream.use { it.readNBytes(8) })
        } finally { screenshot.disconnect() }
        shot("snapshot")

        click("tab.Add")
        fill("add.urls", "https://example.com")
        shot("add")
        fill("add.tags", "ref")
        click("suggestedTag.reference", scroll = true)
        node("tag.reference").assertExists()
        fill("add.tags", "research")
        click("add.tagsConfirm")
        shot("tags")

        val sharedUrl = "https://example.com/?archivebox-android-test=${System.currentTimeMillis()}"
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, sharedUrl)
            setPackage("io.archivebox.app")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        instrumentation.context.startActivity(shareIntent)
        try {
            compose.waitUntil(30_000) { device.hasObject(By.textContains(sharedUrl)) }
        } catch (failure: ComposeTimeoutException) {
            shot("failure", composeIdle = false)
            var action: String? = null
            var data: String? = null
            var textMatches = false
            instrumentation.runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().lastOrNull()
                action = activity?.intent?.action
                data = activity?.intent?.dataString
                textMatches = activity?.intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() == sharedUrl
            }
            fail("Share intent did not display its URL: resumedAction=$action data=$data textMatches=$textMatches")
        }
        node("share.sheet").assertExists()
        node("add.urls").assertTextContains(sharedUrl, substring = true)
        fill("add.tags", "android-share, research")
        click("add.tagsConfirm")
        node("add.save").performScrollTo().assertIsDisplayed()
        shot("share")
        click("add.save", scroll = true)
        await("share.accepted")
        device.setOrientationLeft()
        await("share.accepted")
        node("share.accepted").assertExists()
        device.setOrientationNatural()
        await("share.accepted")
        node("share.accepted").performScrollTo()
        shot("share-saved")
        var saved: JSONObject? = null
        compose.waitUntil(30_000) {
            saved = snapshots(sharedUrl).firstOrNull { it.getString("url") == sharedUrl }
            saved != null && saved!!.getJSONArray("tags").let { tags ->
                (0 until tags.length()).map(tags::getString).containsAll(listOf("android-share", "research"))
            }
        }
        assertNotNull("A share must persist a real snapshot", saved)

        click("tag.research", scroll = true)
        fill("add.tags", "verified")
        click("share.saveTags", scroll = true)
        await("share.tagsSaved")
        val updated = snapshots(sharedUrl).single { it.getString("url") == sharedUrl }
        assertEquals(setOf("android-share", "verified"), updated.getJSONArray("tags").let { tags ->
            (0 until tags.length()).map(tags::getString).toSet()
        })
        click("share.undo", scroll = true)
        compose.onNodeWithText("Remove from server").performClick()
        await("share.undone")
        compose.waitUntil(30_000) { snapshots(sharedUrl).none { it.getString("url") == sharedUrl } }
        click("share.done", scroll = true)
        // A completed external share may finish its Activity. Relaunch through the launcher.
        instrumentation.targetContext.startActivity(requireNotNull(instrumentation.targetContext.packageManager
            .getLaunchIntentForPackage("io.archivebox.app")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await("tab.Activity")
        click("tab.Activity")
        await("browser.ready")
        shot("activity")
        route("Admin")
        shot("server-browser")
        val routes = listOf(
            "Crawls" to "crawls", "Scheduled Crawls" to "scheduled-crawls",
            "Archive Results" to "archive-results", "Tags" to "server-tags", "AI Agent" to "ai-agent",
            "Users" to "users", "Personas" to "personas", "API Keys" to "api-keys",
            "Webhooks" to "webhooks", "Processes" to "processes", "Machines" to "machines",
            "Network Interfaces" to "network-interfaces", "Binaries" to "binaries",
            "Plugins" to "plugins", "Workers" to "workers", "Logs" to "logs",
        )
        for ((name, id) in routes) {
            route(name)
            if (id == "ai-agent") {
                // The provider prompt is HTML inside the authenticated WebView. UiAutomator
                // exposes only the outer WebView node, so By.text() cannot observe this DOM.
                // Espresso-Web evaluates the actual rendered document without adding product
                // test hooks or accepting a screenshot-only assertion.
                val providerPrompt = findElement(
                    Locator.XPATH,
                    "//*[contains(normalize-space(.), '$PROVIDER_SETUP_TEXT') and " +
                        "not(.//*[contains(normalize-space(.), '$PROVIDER_SETUP_TEXT')])]",
                )
                onWebView()
                    .withElement(providerPrompt)
                    .check(webMatches(getText(), containsString(PROVIDER_SETUP_TEXT)))
                    .check(webMatches(providerPromptVisible, equalTo("visible")))
            }
            shot(id)
        }
        click("tab.Settings")
        node("setup.reopen").performScrollTo()
        shot("settings")
        fill("connection.url", "${server.trimEnd('/')}/admin/")
        assertEquals("A same-origin path must retain the key", token, node("connection.token").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.InputText].text)
        fill("connection.url", "https://example.invalid/")
        assertEquals("Changing origin must clear the key", "", node("connection.token").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.InputText].text)
        node("connection.save").assertIsNotEnabled()
        device.setOrientationLeft()
        await("connection.token")
        assertEquals("Rotation must not restore a key for another origin", "", node("connection.token").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.InputText].text)
        device.setOrientationNatural()
        fill("connection.url", server)
        fill("connection.token", token)
        click("connection.save", scroll = true)
        await("connection.status")

        File(output, "device.json").writeText(JSONObject().put("appVersion", BuildConfig.VERSION_NAME)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})").toString())
    }

}
