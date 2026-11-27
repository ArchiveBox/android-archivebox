package io.archivebox.app

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.web.assertion.WebViewAssertions.webMatches
import androidx.test.espresso.web.model.Atoms
import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.espresso.web.webdriver.DriverAtoms.findElement
import androidx.test.espresso.web.webdriver.DriverAtoms.webClick
import androidx.test.espresso.web.webdriver.Locator
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import org.hamcrest.Matchers.equalTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises search against a connected server independently of LAN discovery or gallery fixtures. */
@RunWith(AndroidJUnit4::class)
class ServerSearchIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun await(tag: String) = compose.waitUntil(30_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun connect() {
        val args = InstrumentationRegistry.getArguments()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = Uri.parse("archivebox://connect").buildUpon()
            .appendQueryParameter("server", requireNotNull(args.getString("serverUrl")))
            .appendQueryParameter("api_key", requireNotNull(args.getString("apiToken")))
            .build()
        context.startActivity(Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await("connection.save")
        compose.onNodeWithTag("connection.save").performScrollTo().performClick()
        await("connection.status")
    }

    private fun recreateActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single().recreate()
        }
        await("browser.ready")
    }

    private val currentUrl = Atoms.script("return location.href;", Atoms.castOrDie(String::class.java))

    private fun waitForSubmittedQuery(query: String): Uri {
        // The admin updates history when streamed results arrive; the document
        // remains ready throughout, so wait for the actual submitted URL.
        compose.waitUntil(30_000) {
            Uri.parse(onWebView().perform(currentUrl).get()).getQueryParameter("q") == query
        }
        return Uri.parse(onWebView().perform(currentUrl).get())
    }

    @Test fun homeSearchOpensWithKeyboardAndAcceptsTyping() {
        connect()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.onNodeWithTag("tab.Archive").performClick()
        val started = SystemClock.elapsedRealtime()
        compose.onNodeWithTag("sidebar.searchField").performClick()
        await("browser.ready")
        compose.waitUntil(10_000) {
            var visible = false
            instrumentation.runOnMainSync {
                val window = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single().window
                visible = ViewCompat.getRootWindowInsets(window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            visible
        }
        android.util.Log.i("ArchiveBoxSearch", "Home tap to visible IME: ${SystemClock.elapsedRealtime() - started} ms")
        val input = Atoms.script("return document.querySelector('form[role=search] input[name=q]').value;", Atoms.castOrDie(String::class.java))
        val device = UiDevice.getInstance(instrumentation)
        device.pressKeyCode(KeyEvent.KEYCODE_4)
        device.pressKeyCode(KeyEvent.KEYCODE_2)
        compose.waitUntil(5_000) { onWebView().perform(input).get() == "42" }
        submitServerSearch()
        assertEquals("42", waitForSubmittedQuery("42").getQueryParameter("q"))
    }

    @Test fun submitAndReopenQuotedQuery() {
        connect()
        compose.onNodeWithTag("tab.Search").performClick()
        await("browser.ready")
        val query = "title:\"a & b/日本語\""
        enterServerSearch(query)
        submitServerSearch()
        await("browser.ready")
        val submitted = waitForSubmittedQuery(query)
        // A recreated Activity must restore results, including the encoded expression.
        recreateActivity()
        onWebView().check(webMatches(currentUrl, equalTo(submitted.toString())))
        compose.onNodeWithTag("tab.Archive").performClick()
        compose.onNodeWithTag("sidebar.searchField").performClick()
        await("browser.ready")
        val input = Atoms.script("return document.querySelector('form[role=search] input[name=q]').value;", Atoms.castOrDie(String::class.java))
        onWebView().check(webMatches(input, equalTo(query)))
    }

    @Test fun nativeSearchStaysInSelectedTag() {
        connect()
        compose.onNodeWithTag("tab.Archive").performClick()
        compose.onNodeWithTag("home").performScrollToNode(hasTestTag("route.Tags"))
        compose.onNodeWithTag("route.Tags").performClick()
        await("browser.ready")
        onWebView().withElement(findElement(Locator.CSS_SELECTOR, "a[href*='tags__id__exact=']"))
            .perform(webClick())
        await("browser.ready")
        val filtered = Uri.parse(onWebView().perform(currentUrl).get())
        val tag = filtered.getQueryParameter("tags__id__exact")
        assertNotNull("The tag count link must open a tag-filtered Snapshot view", tag)

        compose.onNodeWithTag("tab.Search").performClick()
        // The native Search tab must focus the filter's existing form. Opening a
        // standalone editor here would appear functional while silently losing the tag.
        val fieldFocused = Atoms.script(
            "return document.activeElement.matches('[role=search] [role=combobox], [role=search] input[name=q]');",
            Atoms.castOrDie(Boolean::class.javaObjectType))
        compose.waitUntil(30_000) { onWebView().perform(fieldFocused).get() }
        onWebView().check(webMatches(currentUrl, equalTo(filtered.toString())))
        enterServerSearch("https")
        submitServerSearch()
        await("browser.ready")
        val submitted = waitForSubmittedQuery("https")
        assertEquals(filtered.path, submitted.path)
        assertEquals(tag, submitted.getQueryParameter("tags__id__exact"))
        assertEquals("https", submitted.getQueryParameter("q"))
        recreateActivity()
        onWebView().check(webMatches(currentUrl, equalTo(submitted.toString())))
    }
}
