package dev.terminal.app

import android.os.Bundle
import android.os.SystemClock
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsSearchUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && predicate(node)) return node
            for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun waitFor(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            find(predicate)?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("Expected settings UI element not found")
    }

    private fun click(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                assertTrue(current.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                return
            }
            current = current.parent
        }
        throw AssertionError("Settings result is not clickable")
    }

    @Test fun overviewGroupsRelatedCategoriesWithSmallGapsAndNoArrows() {
        val context = instrumentation.targetContext
        ActivityScenario.launch(SettingsActivity::class.java).use {
            val labels = listOf(R.string.storage_title, R.string.profiles,
                R.string.settings_language_title, R.string.settings_terminal_title)
            val rows = labels.map { label ->
                var node = waitFor { it.text?.toString() == context.getString(label) }
                while (!node.isClickable) node = checkNotNull(node.parent)
                Rect().also { node.getBoundsInScreen(it) }
            }
            val density = context.resources.displayMetrics.density
            assertEquals("Storage and profiles share a group", 2f * density, (rows[1].top - rows[0].bottom).toFloat(), 1f)
            assertEquals("Related groups stay separate", 16f * density, (rows[2].top - rows[1].bottom).toFloat(), 1f)
            assertEquals("Language and terminal share a group", 2f * density, (rows[3].top - rows[2].bottom).toFloat(), 1f)
            SystemClock.sleep(1000)
            val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                val first = rows[0]
                val last = rows[1]
                val inset = (2f * density).toInt()
                val surface = screenshot.getPixel(first.right - inset, first.centerY())
                val gap = screenshot.getPixel(first.centerX(), (first.bottom + last.top) / 2)
                assertNotEquals("Gaps expose the page background", surface, gap)
                assertEquals("Outer top corners remain rounded", gap, screenshot.getPixel(first.left + inset, first.top + inset))
                assertEquals("Adjacent bottom corners stay small", surface, screenshot.getPixel(first.left + inset, first.bottom - inset))
                assertEquals("Adjacent top corners stay small", surface, screenshot.getPixel(last.left + inset, last.top + inset))
                assertEquals("Outer bottom corners remain rounded", gap, screenshot.getPixel(last.left + inset, last.bottom - inset))
                val language = rows[2]
                for (x in (20f * density).toInt()..(36f * density).toInt()) {
                    for (y in -(8f * density).toInt()..(8f * density).toInt()) {
                        assertEquals("Category trailing area has no arrow", surface,
                            screenshot.getPixel(language.right - x, language.centerY() + y))
                    }
                }
            } finally { screenshot.recycle() }
            click(waitFor { it.text?.toString() == context.getString(R.string.settings_language_title) })
            waitFor { it.text?.toString() == context.getString(R.string.settings_language_support) }
        }
    }

    @Test fun searchSurvivesRecreationAndOpensNestedAppearance() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            var font = ""
            var appearance = ""
            var back = ""
            scenario.onActivity {
                font = it.getString(R.string.font_size)
                appearance = it.getString(R.string.settings_appearance_title)
                back = it.getString(R.string.back)
            }
            val input = waitFor { it.isEditable }
            assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, font)
            }))
            waitFor { it.text?.toString() == font && !it.isEditable }
            scenario.recreate()
            waitFor { it.isEditable && it.text?.toString() == font }
            click(waitFor { it.text?.toString() == font && !it.isEditable })
            waitFor { it.text?.toString() == appearance }
            // The text section must already be visible without scrolling the appearance screen.
            waitFor { it.text?.toString() == font }
            click(waitFor { it.contentDescription?.toString() == back })
            waitFor { it.isEditable && it.text?.toString() == font }
        }
    }
}
