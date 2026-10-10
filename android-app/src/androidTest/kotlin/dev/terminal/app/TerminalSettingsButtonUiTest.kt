package dev.terminal.app

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.DroidTheme
import dev.terminal.app.ui.SessionUi
import dev.terminal.app.ui.TerminalChromeColors
import dev.terminal.app.ui.TerminalEmptyState
import dev.terminal.app.ui.TerminalHapticFeedbackProvider
import dev.terminal.app.ui.TerminalToolbar
import dev.terminal.app.ui.ToolbarSessionsTool
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalSettingsButtonUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun settingsButtonOpensDirectlyFromDefaultExpandedAndInitialEmptyScreens() {
        for (mode in listOf(ToolbarSheetMode.Default, ToolbarSheetMode.Expanded, null)) {
            val clicks = AtomicInteger()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val openSettings = {
                        clicks.incrementAndGet()
                        activity.startActivity(Intent(activity, SettingsActivity::class.java))
                    }
                    activity.setContent {
                        DroidTheme { TerminalHapticFeedbackProvider {
                            if (mode == null) {
                                TerminalEmptyState("", {}, openSettings)
                            } else {
                                Column(Modifier.fillMaxSize()) {
                                    Spacer(Modifier.weight(1f))
                                    TerminalToolbar(ToolbarConfig(tools = listOf(SESSIONS_TOOL)), mode,
                                        {}, 400.dp, TerminalChromeColors(Color.Black)) { _, active ->
                                        ToolbarSessionsTool(listOf(SessionUi("fixture", "NATIVE", "Shell", "RUNNING")),
                                            "fixture", active, emptySet(), emptyList(), {}, {}, false,
                                            {}, {}, {}, {}, {}, openSettings)
                                    }
                                }
                            }
                        } }
                    }
                }
                val monitor = instrumentation.addMonitor(SettingsActivity::class.java.name, null, false)
                try {
                    assertTrue(settingsButton().performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    val settings = monitor.waitForActivityWithTimeout(5000)
                    assertNotNull("Settings opens with one click from ${mode ?: "initial empty screen"}", settings)
                    assertEquals(1, clicks.get())
                    instrumentation.runOnMainSync { requireNotNull(settings).finish() }
                } finally {
                    instrumentation.removeMonitor(monitor)
                }
            }
        }
    }

    private fun settingsButton(): AccessibilityNodeInfo {
        val label = instrumentation.targetContext.getString(R.string.settings)
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.contentDescription?.toString() == label) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            var node = find(instrumentation.uiAutomation.rootInActiveWindow)
            while (node != null && !node.isClickable) node = node.parent
            if (node != null) return node
            SystemClock.sleep(100)
        }
        error("Settings button was not visible")
    }
}
