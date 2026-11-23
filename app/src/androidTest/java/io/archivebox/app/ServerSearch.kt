package io.archivebox.app

import android.content.ClipData
import android.content.ClipboardManager
import android.view.KeyEvent
import androidx.test.espresso.web.assertion.WebViewAssertions.webMatches
import androidx.test.espresso.web.model.Atoms
import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.espresso.web.webdriver.DriverAtoms.findElement
import androidx.test.espresso.web.webdriver.DriverAtoms.webClick
import androidx.test.espresso.web.webdriver.Locator
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.hamcrest.Matchers.equalTo

/** Use the real WebView input and keyboard so non-ASCII and URL punctuation survive submission. */
internal fun enterServerSearch(query: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    // Older supported servers fall back to their ordinary Snapshot search form.
    val editor = "form[role=search] [role=combobox], form[role=search] input[name=q]:not(.abx-search-field input)"
    onWebView().withElement(findElement(Locator.CSS_SELECTOR, editor)).perform(webClick())
    instrumentation.runOnMainSync {
        val clipboard = instrumentation.targetContext.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Archive search", query))
    }
    UiDevice.getInstance(instrumentation).pressKeyCode(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON)
    val value = Atoms.script("function(element) { return element.value; }", Atoms.castOrDie(String::class.java))
    onWebView().withElement(findElement(Locator.CSS_SELECTOR, "form[role=search] input[name=q]"))
        .check(webMatches(value, equalTo(query)))
}

internal fun submitServerSearch() {
    onWebView().withElement(findElement(Locator.CSS_SELECTOR, "form[role=search] button[type=submit]"))
        .perform(webClick())
}
