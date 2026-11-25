package io.archivebox.app

import android.content.ClipData
import android.content.ClipboardManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.web.assertion.WebViewAssertions.webMatches
import androidx.test.espresso.web.model.Atoms
import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.espresso.web.webdriver.DriverAtoms.findElement
import androidx.test.espresso.web.webdriver.Locator
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
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
    fun editorCenter(): Pair<Int, Int> {
        val point = JSONArray(onWebView().withElement(findElement(Locator.CSS_SELECTOR, editor)).perform(center).get())
        val offset = IntArray(2)
        onView(isAssignableFrom(WebView::class.java)).check { view, _ -> view.getLocationOnScreen(offset) }
        return Pair(offset[0] + point.getDouble(0).roundToInt(), offset[1] + point.getDouble(1).roundToInt())
    }
    val (x, y) = editorCenter()
    device.click(x, y)
    device.waitForIdle()
    instrumentation.runOnMainSync {
        instrumentation.targetContext.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Search", query))
    }
    // Opening the keyboard can move the editor. Find its position again before long-pressing.
    val (focusedX, focusedY) = editorCenter()
    check(device.swipe(focusedX, focusedY, focusedX, focusedY, 160)) { "Could not long-press the search editor" }
    val pasteLabel = instrumentation.targetContext.getString(android.R.string.paste)
    val paste = requireNotNull(device.wait(Until.findObject(
        By.res("android:id/floating_toolbar_menu_item_text").text(pasteLabel),
    ), 5_000)) { "The search editor's native Paste action was not available" }
    // Activate the actual toolbar button. Tapping its coordinates can hit the system
    // clipboard overlay or miss while the toolbar repositions above the keyboard.
    val pasteButton = generateSequence(paste.accessibilityNodeInfo) { it.parent }.first { it.isClickable }
    check(pasteButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "The search editor's Paste action failed" }
    device.waitForIdle()
    val value = Atoms.script("function(element) { return element.value; }", Atoms.castOrDie(String::class.java))
    onWebView().withElement(findElement(Locator.CSS_SELECTOR, "form[role=search] input[name=q]"))
        .check(webMatches(value, equalTo(query)))
}

internal fun submitServerSearch() {
    // The focused editor submits on Enter; the admin submit button is visually hidden.
    UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressKeyCode(KeyEvent.KEYCODE_ENTER)
}
