package dev.terminal.app

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsGroupUiTest {
    @Test fun textSettingsSingleRowKeepsRoundedGroupCorners() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent = Intent(context, SettingsDetailActivity::class.java)
            .putExtra(SettingsDetailActivity.EXTRA_CATEGORY, SettingsDetailActivity.APPEARANCE)
            .putExtra(SettingsDetailActivity.EXTRA_SECTION_INDEX, 2)
        ActivityScenario.launch<SettingsDetailActivity>(intent).use {
            val labels = listOf(R.string.font_size)
            fun row(label: Int): AccessibilityNodeInfo? {
                fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    if (node == null) return null
                    if (node.isVisibleToUser && node.text?.toString() == context.getString(label)) return node
                    for (index in 0 until node.childCount) walk(node.getChild(index))?.let { return it }
                    return null
                }
                var node = walk(instrumentation.uiAutomation.rootInActiveWindow) ?: return null
                while (!node.isClickable) node = node.parent ?: return null
                return node
            }
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (labels.any { row(it) == null }) {
                check(SystemClock.elapsedRealtime() < deadline) { "Settings rows are not visible" }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(50)
            }
            instrumentation.waitForIdleSync()
            val bounds = labels.map { label -> Rect().also { row(label)!!.getBoundsInScreen(it) } }
            val density = context.resources.displayMetrics.density
            val first = bounds.first()
            val last = bounds.last()
            val inset = (2 * density).toInt()
            // Accessibility bounds are available before the Activity entry animation finishes.
            SystemClock.sleep(1000)
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                val surface = screenshot.getPixel(first.right - inset, first.centerY())
                val background = screenshot.getPixel(first.left - inset, first.centerY())
                assertNotEquals("Row surface differs from the page background", surface, background)
                assertEquals("Group's top corners remain large", background,
                    screenshot.getPixel(first.left + inset, first.top + inset))
                assertEquals("Group's bottom corners remain large", background,
                    screenshot.getPixel(last.left + inset, last.bottom - inset))
            } finally { screenshot.recycle() }
        }
    }
}
