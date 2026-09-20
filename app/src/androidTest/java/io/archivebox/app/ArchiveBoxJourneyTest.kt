package io.archivebox.app

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** End-to-end acceptance against a real, disposable ArchiveBox collection. */
@RunWith(AndroidJUnit4::class)
class ArchiveBoxJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
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
    private fun captureWidget() {
        device.pressHome()
        val launcher = device.launcherPackageName
        val widgetSearch = By.res("io.archivebox.app", "widget_search")
        if (!device.hasObject(widgetSearch)) {
            requireNotNull(device.wait(Until.findObject(By.res(launcher, "workspace")), 5_000)).longClick()
            requireNotNull(device.wait(Until.findObject(By.text("Widgets")), 5_000)).click()
            requireNotNull(device.wait(Until.findObject(By.desc("Browse widgets")), 5_000)).click()
            requireNotNull(device.wait(Until.findObject(By.text("ArchiveBox")), 5_000)).click()
            requireNotNull(device.wait(Until.findObject(By.res("com.android.launcher3.widgetpicker", "widget_preview")), 5_000)).click()
            requireNotNull(device.wait(Until.findObject(By.desc("Add ArchiveBox widget")), 5_000)).click()
        }
        requireNotNull(device.wait(Until.findObject(widgetSearch), 5_000))
        shot("widget", composeIdle = false)
        device.findObject(widgetSearch).click()
        await("search.query")
        device.pressHome()
        requireNotNull(device.wait(Until.findObject(By.res("io.archivebox.app", "widget_add")), 5_000)).click()
        await("share.sheet")
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
        await("setup.connect")
        shot("onboarding")
        click("setup.docker", scroll = true)
        await("guide.docker")
        shot("setup-docker")
        click("guide.back")
        click("setup.connect", scroll = true)
        fill("connection.url", server)
        fill("connection.token", token)
        click("connection.save", scroll = true)
        if (Build.VERSION.SDK_INT >= 37) {
            val allow = device.wait(Until.findObject(By.res("com.android.permissioncontroller", "permission_allow_button")), 5_000)
            requireNotNull(allow) { "Expected the real Android local network permission prompt" }.click()
        }
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
        click("tab.Search")
        fill("search.query", "example.com")
        click("search.submit")
        val exampleId = snapshots("example.com").first { it.getString("url").trimEnd('/') == "https://example.com" }.getString("id")
        await("search.result.$exampleId")
        shot("search")
        compose.onNode(hasText("Open saved page") and hasAnyAncestor(hasTestTag("search.result.$exampleId"))).performClick()
        await("browser.ready")
        compose.onAllNodesWithTag("error").assertCountEquals(0)
        assertTrue("The snapshot must render actual archived content", device.wait(Until.hasObject(By.textContains("Example Domain")), 10_000))
        shot("snapshot")

        click("tab.Add")
        fill("add.urls", "https://example.com")
        shot("add")
        fill("add.tags", "research")
        click("add.tagsConfirm")
        shot("tags")

        val sharedUrl = "https://example.com/?archivebox-android-test=${System.currentTimeMillis()}"
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, sharedUrl)
            setClass(instrumentation.targetContext, MainActivity::class.java)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        instrumentation.targetContext.startActivity(shareIntent)
        await("share.sheet")
        node("add.urls").assertTextContains(sharedUrl, substring = true)
        fill("add.tags", "android-share, research")
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
        for ((name, id) in routes) { route(name); shot(id) }
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
        captureWidget()

        File(output, "device.json").writeText(JSONObject().put("appVersion", BuildConfig.VERSION_NAME)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})").toString())
    }

}
