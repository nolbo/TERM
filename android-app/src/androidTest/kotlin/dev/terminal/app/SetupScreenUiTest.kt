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
            val start = waitFor { it.text?.toString() == label(R.string.setup_title) }
            val windowBounds = bounds(instrumentation.uiAutomation.rootInActiveWindow)
            val startBounds = bounds(requireNotNull(clickableAncestor(start)))
            assertTrue("Get started is anchored at the bottom of the screen",
                startBounds.centerY() > windowBounds.bottom - windowBounds.height() / 5)
            listOf(R.string.runtime_native_title, R.string.runtime_rootless_title, R.string.runtime_avf_title).forEach { id ->
                val runtime = waitFor { it.text?.toString() == label(id) }
                assertNull("Runtime introductions have no launch action", clickableAncestor(runtime))
                assertTrue("Runtime introductions precede the bottom action", bounds(runtime).bottom <= startBounds.top)
            }
            listOf(R.string.setup_intro, R.string.setup_linux_title, R.string.setup_linux_later).forEach { id ->
                assertNull("Removed onboarding guidance stays hidden: ${label(id)}",
                    find { it.text?.toString() == label(id) })
            }
            assertEquals(original, preferences.all)
            scenario.recreate()
            waitFor { it.text?.toString() == label(R.string.setup_title) }
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
            val fixture = waitFor { it.contentDescription?.toString() == "Compact setup fixture" }
            val start = waitFor { it.text?.toString() == label(R.string.setup_title) }
            val startBounds = bounds(requireNotNull(clickableAncestor(start)))
            assertTrue("Get started fits inside the compact viewport", bounds(fixture).contains(startBounds))
            scrollTo(R.string.setup_rootless_description)
            scrollTo(R.string.setup_avf_description)
            val pinnedStart = waitFor { it.text?.toString() == label(R.string.setup_title) }
            assertEquals("Scrolling the introduction does not move the bottom action",
                startBounds, bounds(requireNotNull(clickableAncestor(pinnedStart))))
            assertEquals("Reading guidance does not start the terminal", 0, starts.get())
            click(pinnedStart)
            assertEquals(1, starts.get())
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

    private fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        return target
    }

    private fun click(node: AccessibilityNodeInfo) {
        assertTrue(requireNotNull(clickableAncestor(node)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
}
