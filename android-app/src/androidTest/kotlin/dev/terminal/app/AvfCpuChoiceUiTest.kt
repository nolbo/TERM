package dev.terminal.app

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.*
import dev.terminal.runtime.avf.AvfResourcePolicy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/** Exercises the actual choice component on stock targets without granting AVF permissions. */
@RunWith(AndroidJUnit4::class)
class AvfCpuChoiceUiTest {
    companion object {
        val committed = AtomicReference("2")
        @JvmStatic fun showPreview(activity: DiagnosticsActivity) {
            committed.set(AvfResourcePolicy.cpuTopology(8).toString())
            activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    var selected by remember { mutableStateOf(committed.get()) }
                    SettingsChoiceRow(stringResource(R.string.virtual_cpu), stringResource(R.string.virtual_cpu_support),
                        listOf(SettingsChoice(stringResource(R.string.avf_cpu_single), "1"),
                            SettingsChoice(stringResource(R.string.avf_cpu_host), "2")), selected) {
                        selected = it; committed.set(it)
                    }
                } }
            }
        }
    }
    @Test fun supportedCpuChoicesCommitOnlyOnSave() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun find(node: android.view.accessibility.AccessibilityNodeInfo?, text: String): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && node.text?.toString()?.contains(text) == true) return node
            for (index in 0 until node.childCount) find(node.getChild(index), text)?.let { return it }
            return null
        }
        fun click(id: Int) {
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync(); instrumentation.uiAutomation.clearCache()
                var node = find(instrumentation.uiAutomation.rootInActiveWindow, context.getString(id))
                while (node != null && !node.isClickable) node = node.parent
                if (node?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true) return
                android.os.SystemClock.sleep(50)
            }
            throw AssertionError("Choice is unavailable: ${context.getString(id)}")
        }
        ActivityScenario.launch(DiagnosticsActivity::class.java).use { scenario ->
            scenario.onActivity(::showPreview)
            click(R.string.virtual_cpu); click(R.string.avf_cpu_single); click(R.string.cancel)
            assertEquals("2", committed.get())
            click(R.string.virtual_cpu); click(R.string.avf_cpu_single); click(R.string.save)
            assertEquals("1", committed.get())
        }
    }
}
