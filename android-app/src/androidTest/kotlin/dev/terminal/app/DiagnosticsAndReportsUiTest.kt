package dev.terminal.app

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DiagnosticsAndReportsUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && predicate(node)) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun waitFor(scroll: Boolean = false, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            find(predicate)?.let { return it }
            if (scroll) find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(100)
        }
        throw AssertionError("Expected diagnostic or report UI was not found")
    }

    private fun click(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        while (current != null && !current.isClickable) current = current.parent
        assertTrue(requireNotNull(current).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test fun settingsSearchOpensCommonDiagnosticsWithoutForwardedPorts() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            val input = waitFor { it.isEditable }
            assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    context.getString(R.string.settings_diagnostics_title))
            }))
            click(waitFor { !it.isEditable && it.text?.toString() == context.getString(R.string.settings_diagnostics_title) })
            listOf(R.string.diagnostics_state_title, R.string.diagnostics_detail_title,
                R.string.diagnostics_image_title, R.string.wake_policy, R.string.diagnostics_sessions_title).forEach { title ->
                val row = waitFor(scroll = true) { it.text?.toString() == context.getString(title) }
                assertFalse("Diagnostic information has no click action", row.isClickable)
            }
            assertNull(find { it.text?.toString() in setOf(context.getString(R.string.diagnostics_ports),
                context.getString(R.string.forwarded_ports_title)) })
            waitFor(scroll = true) { it.text?.toString() == context.getString(R.string.crash_reports_title) }
        }
    }

    @Test fun reportsRetainIndependentDetailsAfterCopyCloseAndScreenRecreation() {
        // Fixtures do not prune any reports already present on the target device.
        val directory = File(context.filesDir, "crash-reports")
        val store = CrashReportStore(directory, retainedReports = Int.MAX_VALUE)
        store.importPending(context)
        val marker = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis() + 60_000
        val first = store.record("first fixture stack $marker\n" + " at fixture.frame\n".repeat(200),
            "First fixture $marker", timestamp + 1)
        val second = store.record("second fixture stack $marker", "Second fixture $marker", timestamp)
        try {
            ActivityScenario.launch(DiagnosticsActivity::class.java).use { scenario ->
                waitFor(scroll = true) { it.text?.toString() == first.summary }
                scenario.recreate()
                click(waitFor(scroll = true) { it.text?.toString() == first.summary })
                waitFor { it.text?.toString() == first.text }
                click(waitFor { it.text?.toString() == context.getString(R.string.copy_error) })
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                assertEquals(first.text, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
                click(waitFor { it.text?.toString() == context.getString(R.string.close) })
                click(waitFor(scroll = true) { it.text?.toString() == second.summary })
                waitFor { it.text?.toString() == second.text }
                assertNull(find { it.text?.toString() == first.text })
                click(waitFor { it.text?.toString() == context.getString(R.string.close) })
                assertEquals(first, CrashReportStore.from(context).read(first.id))
                assertEquals(second, CrashReportStore.from(context).read(second.id))
            }
        } finally {
            File(directory, "${first.id}.json").delete()
            File(directory, "${second.id}.json").delete()
        }
    }

    @Test fun forwardedPortsRejectDevicesWithoutAvfPrivileges() {
        assumeFalse(SessionProfiles(context).hasAvfPrivileges())
        ActivityScenario.launch(ForwardedPortsActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
    }
}
