package io.archivebox.app

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.espresso.web.assertion.WebViewAssertions.webMatches
import androidx.test.espresso.web.model.Atoms
import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.hamcrest.Matchers.equalTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises search against a connected server independently of LAN discovery or gallery fixtures. */
@RunWith(AndroidJUnit4::class)
class ServerSearchIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun submitAndReopenQuotedQuery() {
        val args = InstrumentationRegistry.getArguments()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = Uri.parse("archivebox://connect").buildUpon()
            .appendQueryParameter("server", requireNotNull(args.getString("serverUrl")))
            .appendQueryParameter("api_key", requireNotNull(args.getString("apiToken")))
            .build()
        context.startActivity(Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun await(tag: String) = compose.waitUntil(30_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        await("connection.save")
        compose.onNodeWithTag("connection.save").performScrollTo().performClick()
        await("connection.status")
        compose.onNodeWithTag("tab.Search").performClick()
        await("browser.ready")
        val query = "title:\"a & b/日本語\""
        enterServerSearch(query)
        submitServerSearch()
        await("browser.ready")
        val result = Atoms.script("return new URL(location.href).searchParams.get('q');", Atoms.castOrDie(String::class.java))
        onWebView().check(webMatches(result, equalTo(query)))
        // A recreated Activity must restore results, including the encoded expression.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single().recreate()
        }
        await("browser.ready")
        onWebView().check(webMatches(result, equalTo(query)))
        compose.onNodeWithTag("tab.Archive").performClick()
        compose.onNodeWithTag("sidebar.searchField").performClick()
        await("browser.ready")
        val input = Atoms.script("return document.querySelector('form[role=search] input[name=q]').value;", Atoms.castOrDie(String::class.java))
        onWebView().check(webMatches(input, equalTo(query)))
    }
}
