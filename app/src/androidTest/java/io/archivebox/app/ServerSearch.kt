package io.archivebox.app

import android.content.ClipData
import android.content.ClipboardManager
import android.view.KeyEvent
import android.webkit.WebView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.web.assertion.WebViewAssertions.webMatches
import androidx.test.espresso.web.model.Atoms
import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.espresso.web.webdriver.DriverAtoms.findElement
import androidx.test.espresso.web.webdriver.DriverAtoms.webClick
import androidx.test.espresso.web.webdriver.Locator
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlin.math.roundToInt
import org.hamcrest.Matchers.equalTo
import org.json.JSONArray

/** Use the real WebView input and keyboard so non-ASCII and URL punctuation survive submission. */
internal fun enterServerSearch(query: String) {
    // Older supported servers fall back to their ordinary Snapshot search form.
    val editor = "form[role=search] [role=combobox], form[role=search] input[name=q]:not(.abx-search-field input)"
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device = UiDevice.getInstance(instrumentation)
    // Espresso's webClick synthesizes a DOM click, so Android's actual IME may still
    // target a native view. Tap the rendered field, accounting for WebView density.
    val center = Atoms.script(
        """function(e) {
            const r = e.getBoundingClientRect();
            return JSON.stringify([(r.left + r.width/2) * devicePixelRatio, (r.top + r.height/2) * devicePixelRatio]);
        }""", Atoms.castOrDie(String::class.java))
    val point = JSONArray(onWebView().withElement(findElement(Locator.CSS_SELECTOR, editor)).perform(center).get())
    val offset = IntArray(2)
    onView(isAssignableFrom(WebView::class.java)).check { view, _ -> view.getLocationOnScreen(offset) }
    device.click(offset[0] + point.getDouble(0).roundToInt(), offset[1] + point.getDouble(1).roundToInt())
    device.waitForIdle()
    instrumentation.runOnMainSync {
        instrumentation.targetContext.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Search", query))
    }
    device.pressKeyCode(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON)
    device.waitForIdle()
    val value = Atoms.script("function(element) { return element.value; }", Atoms.castOrDie(String::class.java))
    onWebView().withElement(findElement(Locator.CSS_SELECTOR, "form[role=search] input[name=q]"))
        .check(webMatches(value, equalTo(query)))
}

internal fun submitServerSearch() {
    onWebView().withElement(findElement(Locator.CSS_SELECTOR, "form[role=search] :is(button,input)[type=submit]"))
        .perform(webClick())
}
