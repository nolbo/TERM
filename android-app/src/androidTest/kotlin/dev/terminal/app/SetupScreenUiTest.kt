package dev.terminal.app

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.DroidTheme
import dev.terminal.app.ui.SetupScreen
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class SetupScreenUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun label(id: Int) = context.getString(id)
    private fun setupIntent() = Intent(context, SetupActivity::class.java)
        .putExtra(SetupActivity.EXTRA_SHOW_SETUP, true)

    @Test fun primaryActionIsVisibleAndRecreationPreservesCompletionState() {
        val preferences = context.getSharedPreferences("setup-state", Context.MODE_PRIVATE)
        val original = preferences.all.toMap()
        ActivityScenario.launch<SetupActivity>(setupIntent()).use { scenario ->
            val start = waitFor { it.text?.toString() == label(R.string.start_terminal) }
            find { it.text?.toString() == label(R.string.setup_linux_title) }?.let { optional ->
                val startBounds = Rect().also(start::getBoundsInScreen)
                val optionalBounds = Rect().also(optional::getBoundsInScreen)
                assertTrue("Start is presented before optional Linux setup", startBounds.bottom < optionalBounds.top)
            }
            assertEquals(original, preferences.all)
            scenario.recreate()
            waitFor { it.text?.toString() == label(R.string.start_terminal) }
            assertEquals("Viewing or recreating setup does not complete it", original, preferences.all)
        }
    }

    @Test fun compactLargeTextKeepsStartAndLinuxGuidanceReachable() {
        val starts = AtomicInteger()
        ActivityScenario.launch<SetupActivity>(setupIntent()).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val density = LocalDensity.current
                    DroidTheme {
                        CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                            Box(Modifier.width(320.dp).height(420.dp).semantics {
                                contentDescription = "Compact setup fixture"
                            }) {
                                SetupScreen(onStart = { starts.incrementAndGet() })
                            }
                        }
                    }
                }
            }
            waitFor { it.contentDescription?.toString() == "Compact setup fixture" }
            click(scrollTo(R.string.start_terminal))
            assertEquals(1, starts.get())
            scrollTo(R.string.setup_rootless_description)
            scrollTo(R.string.setup_avf_description)
        }
    }

    private fun scrollTo(id: Int): AccessibilityNodeInfo {
        repeat(15) {
            find { it.text?.toString() == label(id) }?.let { return it }
            val scroll = waitFor { it.isScrollable }
            assertTrue("Setup remains scrollable to ${label(id)}",
                scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            instrumentation.waitForIdleSync()
            SystemClock.sleep(100)
        }
        error("Setup action is unreachable: ${label(id)}")
    }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.isVisibleToUser && predicate(node)) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun waitFor(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            find(predicate)?.let { return it }
            SystemClock.sleep(50)
        }
        error("Expected setup content was not visible")
    }

    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        assertTrue(requireNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
}
