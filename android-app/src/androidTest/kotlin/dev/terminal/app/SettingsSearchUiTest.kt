package dev.terminal.app

import android.os.Bundle
import android.os.SystemClock
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
