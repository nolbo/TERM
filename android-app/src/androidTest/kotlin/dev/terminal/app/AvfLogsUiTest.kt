package dev.terminal.app

import android.app.ActivityManager
import android.content.ClipboardManager
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.AvfLogsScreen
import dev.terminal.app.ui.DroidTheme
import dev.terminal.runtime.lifecycle.ConnectionDiagnostics
import dev.terminal.runtime.lifecycle.ConnectionLogSnapshot
import dev.terminal.runtime.lifecycle.ConnectionLogStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AvfLogsUiTest {
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

    private fun waitFor(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            find(predicate)?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("Expected AVF log control was not found")
    }

    private fun click(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        while (current != null && !current.isClickable) current = current.parent
        assertTrue(requireNotNull(current).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun text(id: Int) = context.getString(id)
    private fun action(id: Int) = click(waitFor { node ->
        node.isEnabled && (node.text?.toString() == text(id) || node.contentDescription?.toString() == text(id)) &&
            generateSequence(node) { it.parent }.any { it.isClickable && it.isEnabled }
    })
    private fun query(value: String) {
        assertTrue(waitFor { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
    }

    @Test fun recordingPersistsAndFilteredCopyWorksWithoutStartingVmhost() {
        val store = ConnectionLogStore(context.filesDir, SystemClock::elapsedRealtimeNanos)
        val originallyEnabled = store.snapshot().enabled
        val manager = context.getSystemService(ActivityManager::class.java)
        fun vmhostRunning() = manager.runningAppProcesses.orEmpty().any { it.processName == context.packageName + ":vmhost" }
        fun artifacts() = listOf("vm", "shared-vms", ".virtiofs").associateWith {
            File(context.filesDir, it).let { directory -> directory.exists() to directory.list()?.toSet().orEmpty() }
        }
        val beforeProcess = vmhostRunning()
        val beforeFiles = artifacts()
        val marker = UUID.randomUUID().toString()
        try {
            store.setEnabled(true)
            ConnectionDiagnostics.record("test.avf.logs", marker)
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (store.snapshot().entries.none { it.contains(marker) } && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
            val expected = store.snapshot().entries.single { it.contains(marker) } + "\n"
            ActivityScenario.launch(AvfLogsActivity::class.java).use { scenario ->
                click(waitFor { it.isCheckable && it.isChecked && it.isEnabled })
                waitFor { it.isCheckable && !it.isChecked && it.isEnabled }
                assertFalse(ConnectionLogStore(context.filesDir, SystemClock::elapsedRealtimeNanos).snapshot().enabled)
                scenario.recreate()
                waitFor { it.isCheckable && !it.isChecked && it.isEnabled }
                query("no-matching-event-$marker")
                waitFor { it.text?.toString() == text(R.string.avf_logs_no_matches) }
                query(marker)
                action(R.string.avf_logs_copy)
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                assertEquals(expected, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            }
            assertEquals(beforeProcess, vmhostRunning())
            assertEquals(beforeFiles, artifacts())
        } finally { store.setEnabled(originallyEnabled) }
    }

    @Test fun deleteConfirmationCancelsSafelyAndSuccessfulClearShowsEmptyState() {
        // Render an isolated snapshot: never delete a device user's diagnostic history.
        var clearCount = 0
        var refreshCount = 0
        ActivityScenario.launch(AvfLogsActivity::class.java).use { scenario ->
            waitFor { it.isCheckable && it.isEnabled }
            scenario.onActivity { activity -> activity.setContent {
                var state by remember { mutableStateOf(ConnectionLogSnapshot(true,
                    listOf("time_ms=42 event=vm.start owner=fixture"))) }
                DroidTheme { AvfLogsScreen(state, false, false, null, {}, { refreshCount++ },
                    { state = state.copy(enabled = it) }, { clearCount++; state = state.copy(entries = emptyList()) }, {}) }
            } }
            waitFor { it.text?.toString()?.contains("owner=fixture") == true }
            action(R.string.refresh)
            assertEquals(1, refreshCount)
            action(R.string.avf_logs_clear)
            waitFor { it.text?.toString() == text(R.string.avf_logs_clear_confirmation) }
            action(R.string.cancel)
            assertEquals(0, clearCount)
            action(R.string.avf_logs_clear)
            action(R.string.delete)
            waitFor { it.text?.toString() == text(R.string.avf_logs_empty) }
            assertEquals(1, clearCount)
            assertNotNull(find { it.isCheckable && it.isChecked })
            for (id in listOf(R.string.avf_logs_copy, R.string.avf_logs_clear)) waitFor { node ->
                node.contentDescription?.toString() == text(id) &&
                    generateSequence(node) { it.parent }.any { !it.isEnabled }
            }
        }
    }
}
