package io.archivebox.app

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real system configuration changes, restored even when an assertion fails. */
@RunWith(AndroidJUnit4::class)
class VisualProfilesTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val context get() = instrumentation.targetContext
    private fun shell(command: String) = device.executeShellCommand(command)
    private fun awaitHome() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("tab.Archive").fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodesWithTag("share.sheet").fetchSemanticsNodes().isNotEmpty()) device.pressBack()
        compose.onNodeWithTag("tab.Archive").performClick()
        compose.onNodeWithTag("home").assertIsDisplayed()
    }
    private fun capture(id: String, dark: Boolean = false) {
        compose.waitForIdle()
        device.waitForIdle()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            if (dark) assertTrue("The rendered app background must actually be dark", Color.luminance(bitmap.getPixel(bitmap.width - 8, bitmap.height / 2)) < 0.2f)
            val output = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
            File(output, "$id.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    @Test fun darkThemeAndTabletNavigation() {
        val size = Regex("Override size: ([0-9]+x[0-9]+)").find(shell("wm size"))?.groupValues?.get(1)
        val density = Regex("Override density: ([0-9]+)").find(shell("wm density"))?.groupValues?.get(1)
        val night = requireNotNull(Regex("Night mode: (auto|yes|no)\\b").find(shell("cmd uimode night"))) { "The dedicated test device must use auto, yes, or no night mode" }.groupValues[1]
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage("io.archivebox.app")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            awaitHome()
            shell("cmd uimode night yes")
            compose.waitUntil(10_000) { context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES }
            awaitHome()
            capture("dark-mode", dark = true)

            shell("cmd uimode night no")
            shell("wm size 1280x800")
            shell("wm density 160")
            compose.waitUntil(10_000) { context.resources.configuration.screenWidthDp >= 700 }
            awaitHome()
            compose.onNodeWithTag("navigation.rail").assertIsDisplayed()
            compose.onNodeWithTag("tab.Search").assertIsDisplayed()
            capture("tablet")
        } finally {
            shell("wm density ${density ?: "reset"}")
            shell("wm size ${size ?: "reset"}")
            shell("cmd uimode night $night")
        }
    }
}
