package dev.terminal.app

import android.os.Bundle
import android.content.Intent
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
        instrumentation.uiAutomation.clearCache()
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

    @Test fun removedNativeSettingsEntryOpensCommonTerminalSettings() {
        val context = instrumentation.targetContext
        val intent = Intent(context, SettingsDetailActivity::class.java)
            .putExtra(SettingsDetailActivity.EXTRA_CATEGORY, "native")
            .putExtra(SettingsDetailActivity.EXTRA_SECTION_INDEX, 1)
        ActivityScenario.launch<SettingsDetailActivity>(intent).use { scenario ->
            waitFor { it.text?.toString() == context.getString(R.string.settings_terminal_title) }
            waitFor { it.text?.toString() == context.getString(R.string.usb_title) }
            scenario.onActivity {
                assertEquals(SettingsDetailActivity.TERMINAL, it.intent.getStringExtra(SettingsDetailActivity.EXTRA_CATEGORY))
                assertEquals(0, it.intent.getIntExtra(SettingsDetailActivity.EXTRA_SECTION_INDEX, -1))
            }
            assertNull(find { it.text?.toString() in listOf("Native 셸", "Native shell") })
        }
    }

    @Test fun overviewGroupsRelatedCategoriesWithSmallGapsAndNoArrows() {
        val context = instrumentation.targetContext
        ActivityScenario.launch(SettingsActivity::class.java).use {
            waitFor { it.text?.toString() == context.getString(R.string.storage_title) }
            SystemClock.sleep(1000)
            instrumentation.waitForIdleSync()
            val labels = listOf(R.string.storage_title, R.string.profiles, R.string.workspaces_title,
                R.string.shared_folders_title,
                R.string.settings_language_title, R.string.settings_terminal_title)
            val rows = labels.map { label ->
                var node = waitFor { it.text?.toString() == context.getString(label) }
                while (!node.isClickable) node = checkNotNull(node.parent)
                Rect().also { node.getBoundsInScreen(it) }
            }
            val density = context.resources.displayMetrics.density
            for (index in 1..2) {
                assertEquals("Environment categories share a group", 2f * density,
                    (rows[index].top - rows[index - 1].bottom).toFloat(), 1f)
            }
            assertEquals("Shared directories start a separate group", 16f * density, (rows[3].top - rows[2].bottom).toFloat(), 1f)
            assertEquals("Shared directories is above app language", 16f * density, (rows[4].top - rows[3].bottom).toFloat(), 1f)
            assertEquals("Language and terminal share a group", 2f * density, (rows[5].top - rows[4].bottom).toFloat(), 1f)
            assertNull(find { it.text?.toString() == context.getString(R.string.local_services_title) })
            val first = rows[0]
            val adjacent = rows[1]
            val inset = (2f * density).toInt()
            val deadline = SystemClock.elapsedRealtime() + 5000
            var screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            // Accessibility rows can precede presentation of the new window's frame.
            while (screenshot.getPixel(first.right - inset, first.centerY()) ==
                screenshot.getPixel(first.centerX(), (first.bottom + adjacent.top) / 2) &&
                SystemClock.elapsedRealtime() < deadline) {
                screenshot.recycle()
                SystemClock.sleep(250)
                screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            }
            try {
                val last = rows[labels.indexOf(R.string.settings_terminal_title)]
                val surface = screenshot.getPixel(first.right - inset, first.centerY())
                val gap = screenshot.getPixel(first.centerX(), (first.bottom + adjacent.top) / 2)
                assertNotEquals("Gaps expose the page background; rows=$rows bitmap=${screenshot.width}x${screenshot.height}", surface, gap)
                assertEquals("Outer top corners remain rounded", gap, screenshot.getPixel(first.left + inset, first.top + inset))
                assertEquals("Adjacent bottom corners stay small", surface, screenshot.getPixel(first.left + inset, first.bottom - inset))
                assertEquals("Adjacent top corners stay small", surface, screenshot.getPixel(adjacent.left + inset, adjacent.top + inset))
                assertEquals("Outer bottom corners remain rounded", gap, screenshot.getPixel(last.left + inset, last.bottom - inset))
                val language = rows[labels.indexOf(R.string.settings_language_title)]
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

    @Test fun localServicesSearchRequiresAvfAndOpensItsSettingsSection() {
        val context = instrumentation.targetContext
        ActivityScenario.launch(SettingsActivity::class.java).use {
            assertNull(find { node -> node.text?.toString() == context.getString(R.string.local_services_title) })
            val query = context.getString(R.string.local_services_title)
            assertTrue(waitFor { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
            }))
            waitFor { it.isEditable && it.text?.toString() == query }
            if (!SessionProfiles(context).hasAvfPrivileges()) {
                waitFor { node -> node.text?.toString() == context.getString(R.string.settings_search_no_results) }
            } else {
                click(waitFor { node -> !node.isEditable && node.text?.toString() == query })
                waitFor { node -> node.text?.toString() == context.getString(R.string.section_execution) }
                val monitor = instrumentation.addMonitor(LocalServicesActivity::class.java.name, null, false)
                try {
                    click(waitFor { node -> node.text?.toString() == query })
                    val dashboard = monitor.waitForActivityWithTimeout(5000)
                    assertNotNull(dashboard)
                    instrumentation.runOnMainSync { dashboard.finish() }
                } finally { instrumentation.removeMonitor(monitor) }
            }
        }
    }
}
