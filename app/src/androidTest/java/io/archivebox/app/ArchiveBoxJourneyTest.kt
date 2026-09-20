package io.archivebox.app

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
        compose.waitUntil(30_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun click(tag: String, scroll: Boolean = false) {
        await(tag)
        if (scroll) node(tag).performScrollTo()
        node(tag).performClick()
    }
    private fun fill(tag: String, text: String) {
        await(tag)
        node(tag).performScrollTo().performTextReplacement(text)
    }
    private fun shot(id: String) {
        compose.waitForIdle()
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

        fill("connection.hints", "127.0.0.1")
        click("connection.discover", scroll = true)
        await("discovery.server")
        node("discovery.server").performScrollTo().assertTextContains("127.0.0.1", substring = true)
        shot("discovery")

        route("Snapshots")
        shot("library")
        click("tab.Search")
        fill("search.query", "example.com")
        click("search.submit")
        val exampleId = snapshots("example.com").first { it.getString("url").trimEnd('/') == "https://example.com" }.getString("id")
        await("search.result.$exampleId")
        shot("search")
        node("search.result.$exampleId").performClick()
        await("browser.ready")
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
        click("tab.Settings")
        node("setup.reopen").performScrollTo()
        shot("settings")

        File(output, "device.json").writeText(JSONObject().put("appVersion", BuildConfig.VERSION_NAME)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})").toString())
    }

}
