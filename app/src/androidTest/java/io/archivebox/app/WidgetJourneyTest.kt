package io.archivebox.app

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Launcher interaction has its own lifecycle, outside a Compose ActivityScenario. */
@RunWith(AndroidJUnit4::class)
class WidgetJourneyTest {
    @Test fun installWidgetAndOpenItsActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        device.pressHome()
        device.waitForIdle()
        val widgetSearch = By.res("io.archivebox.app", "widget_search")
        if (!device.hasObject(widgetSearch)) {
            // The workspace center can land on an app icon after prior activity.
            // Press empty wallpaper above the icon rows to open launcher settings.
            device.swipe(device.displayWidth / 2, device.displayHeight / 3,
                device.displayWidth / 2, device.displayHeight / 3, 100)
            val widgets = device.wait(Until.findObject(By.textContains("Widgets")), 5_000)
            if (widgets == null) {
                val menu = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(output, "widget-menu-failure.png").outputStream().use { assertTrue(menu.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                menu.recycle()
                throw AssertionError("Launcher Widgets action missing")
            }
            widgets.click()
            requireNotNull(device.wait(Until.findObject(By.desc("Browse widgets")), 5_000)) { "Widget Browse tab missing" }.click()
            val archiveBox = device.wait(Until.findObject(By.textContains("ArchiveBox")), 5_000)
            if (archiveBox == null) {
                val picker = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(output, "widget-picker-failure.png").outputStream().use { assertTrue(picker.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                picker.recycle()
                device.dumpWindowHierarchy(File(output, "widget-picker-failure.xml"))
                throw AssertionError("ArchiveBox missing from actual widget picker")
            }
            archiveBox.click()
            requireNotNull(device.wait(Until.findObject(By.res("com.android.launcher3.widgetpicker", "widget_preview")), 5_000)) { "Widget preview missing" }.click()
            requireNotNull(device.wait(Until.findObject(By.desc("Add ArchiveBox widget")), 5_000)) { "Widget Add action missing" }.click()
        }
        requireNotNull(device.wait(Until.findObject(widgetSearch), 5_000)) { "Widget was not installed on the launcher" }
        device.waitForIdle()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(output, "widget.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        device.findObject(widgetSearch).click()
        assertTrue("Widget Search action must open native Search Archive", device.wait(Until.hasObject(By.text("Search Archive")), 5_000))
        device.pressHome()
        requireNotNull(device.wait(Until.findObject(By.res("io.archivebox.app", "widget_add")), 5_000)).click()
        assertTrue("Widget Save action must open the real save sheet", device.wait(Until.hasObject(By.text("Save to ArchiveBox")), 5_000))
    }
}
