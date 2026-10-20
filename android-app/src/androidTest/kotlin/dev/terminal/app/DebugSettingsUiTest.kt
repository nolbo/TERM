package dev.terminal.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
class DebugSettingsUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

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
        throw AssertionError("Expected debug settings element not found")
    }

    private fun label(resource: Int) = waitFor { it.text?.toString() == context.getString(resource) }

    private fun click(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                assertTrue(current.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                return
            }
            current = current.parent
        }
        throw AssertionError("Debug settings row is not clickable")
    }

    @Test fun onboardingSearchOpensDebugSectionBeforeExplicitPreviewAndPreservesCompletion() {
        val preferences = context.getSharedPreferences("setup-state", Context.MODE_PRIVATE)
        val before = preferences.all
        val preview = instrumentation.addMonitor(SetupActivity::class.java.name, null, false)
        val debug = instrumentation.addMonitor(DebugSettingsActivity::class.java.name, null, false)
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use {
                val query = context.getString(R.string.debug_open_setup)
                assertTrue(waitFor { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
                }))
                click(waitFor { !it.isEditable && it.text?.toString() == query })
                val debugActivity = checkNotNull(debug.waitForActivityWithTimeout(5000))
                label(R.string.debug_recreate_activity)
                assertEquals(0, preview.hits)
                click(label(R.string.debug_open_setup))
                val setup = checkNotNull(preview.waitForActivityWithTimeout(5000))
                assertTrue(setup.intent.getBooleanExtra(SetupActivity.EXTRA_SHOW_SETUP, false))
                assertEquals(before, preferences.all)
                instrumentation.runOnMainSync { setup.finish() }
                label(R.string.debug_recreate_activity)
                instrumentation.runOnMainSync { debugActivity.finish() }
            }
        } finally {
            instrumentation.removeMonitor(preview)
            instrumentation.removeMonitor(debug)
        }
    }

    @Test fun copiedInformationIncludesInstalledBuildAndSectionSurvivesRecreation() {
        val intent = Intent(context, DebugSettingsActivity::class.java)
            .putExtra(SettingsDetailActivity.EXTRA_SECTION_INDEX, 2)
        ActivityScenario.launch<DebugSettingsActivity>(intent).use { scenario ->
            label(R.string.debug_copy_info)
            click(label(R.string.debug_copy_info))
            scenario.onActivity {
                val report = it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString()
                assertTrue(report.contains(BuildConfig.VERSION_NAME))
                assertTrue(report.contains(BuildConfig.UPDATE_REVISION))
                assertTrue(report.contains(context.packageName))
                assertFalse(report.contains("setup-state"))
            }
            scenario.recreate()
            label(R.string.debug_copy_info)
            assertNull(find { it.text?.toString() == context.getString(R.string.debug_open_setup) })
        }
    }

    @Test fun recreationActionKeepsScreenAndDiagnosticsShortcutsOpenTheirActivities() {
        ActivityScenario.launch(DebugSettingsActivity::class.java).use {
            lateinit var original: DebugSettingsActivity
            it.onActivity { activity -> original = activity }
            click(label(R.string.debug_recreate_activity))
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (!original.isDestroyed && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertTrue(original.isDestroyed)
            label(R.string.debug_open_setup)
            it.onActivity { activity -> assertNotSame(original, activity) }
        }
        for ((title, destination) in listOf(
            R.string.memory_diagnostics_title to MemoryDiagnosticsActivity::class.java,
            R.string.crash_reports_title to CrashReportsActivity::class.java,
        )) {
            val intent = Intent(context, DebugSettingsActivity::class.java)
                .putExtra(SettingsDetailActivity.EXTRA_SECTION_INDEX, 2)
            val monitor = instrumentation.addMonitor(destination.name, null, false)
            try {
                ActivityScenario.launch<DebugSettingsActivity>(intent).use {
                    click(label(title))
                    val activity = checkNotNull(monitor.waitForActivityWithTimeout(5000))
                    instrumentation.runOnMainSync { activity.finish() }
                    label(R.string.debug_copy_info)
                }
            } finally { instrumentation.removeMonitor(monitor) }
        }
    }

    @Test fun appInfoNoLongerContainsOnboardingShortcut() {
        ActivityScenario.launch(AppInfoActivity::class.java).use {
            label(R.string.app_version)
            assertNull(find { it.text?.toString() == context.getString(R.string.debug_open_setup) })
        }
    }
}
