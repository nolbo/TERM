package dev.terminal.app

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.DroidTheme
import dev.terminal.app.ui.ForwardedPortConnection
import dev.terminal.app.ui.ForwardedPortsScreen
import dev.terminal.app.ui.ForwardedPortsState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Recorded port snapshots exercise the UI without starting a VM or opening sockets. */
@RunWith(AndroidJUnit4::class)
class ForwardedPortsUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        instrumentation.waitForIdleSync()
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
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            find(predicate)?.let { return it }
            if (scroll) find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(50)
        }
        throw AssertionError("Forwarded port UI element did not appear")
    }

    private fun label(id: Int) = context.getString(id)
    private fun text(value: String) = waitFor { it.text?.toString() == value }
    private fun button(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable && current.parent != null) current = current.parent
        return current
    }

    @Test fun connectionsUseSettingsRowsAndCopyTheAndroidAddressThenRefresh() {
        val port = ForwardedPortConnection("127.0.0.1", 48217, 8080, "Development API")
        val state = mutableStateOf<ForwardedPortsState>(ForwardedPortsState.Ready(listOf(port)))
        val copied = AtomicReference<String>()
        val refreshed = AtomicInteger()
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { ForwardedPortsScreen(state.value, {}, {
                    refreshed.incrementAndGet()
                    state.value = ForwardedPortsState.Ready(emptyList())
                }, copied::set) }
            } }
            text(label(R.string.forwarded_ports_connections))
            text(context.getString(R.string.forwarded_ports_count, 1))
            text("Development API")
            text(port.androidAddress)
            text("8080")
            val addressTitle = text(label(R.string.forwarded_ports_android_address))
            assertFalse("Address row is read-only", addressTitle.isClickable)
            assertNull(find { it.isEditable })
            val copyLabel = context.getString(R.string.forwarded_ports_copy_address, port.androidAddress)
            val copy = waitFor { it.contentDescription?.toString() == copyLabel }
            assertTrue(button(copy).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertEquals(port.androidAddress, copied.get())
            // Keep the screenshot limited to public, fixed fixture data.
            SystemClock.sleep(1000)
            instrumentation.waitForIdleSync()
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                File(context.externalCacheDir, "forwarded-ports-fixture.png").outputStream().use {
                    assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { screenshot.recycle() }
            if (InstrumentationRegistry.getArguments().getString("preview") == "true") SystemClock.sleep(20_000)
            assertTrue(button(text(label(R.string.refresh))).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            text(label(R.string.forwarded_ports_empty))
            text(label(R.string.forwarded_ports_empty_hint))
            assertEquals(1, refreshed.get())
            assertNull(find { it.text?.toString() == port.androidAddress })
            assertNull(find { it.contentDescription?.toString() == copyLabel })
        }
    }

    @Test fun loadingEmptyAndFailedSnapshotsHaveDistinctStatesAndRefreshAvailability() {
        val state = mutableStateOf<ForwardedPortsState>(ForwardedPortsState.Connecting)
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { ForwardedPortsScreen(state.value, {}, {}, {}) }
            } }
            text(label(R.string.forwarded_ports_loading))
            assertFalse(button(text(label(R.string.refresh))).isEnabled)
            assertNull(find { it.text?.toString() == context.getString(R.string.forwarded_ports_count, 0) })
            scenario.onActivity { state.value = ForwardedPortsState.Ready(emptyList()) }
            text(label(R.string.forwarded_ports_empty))
            text(context.getString(R.string.forwarded_ports_count, 0))
            assertTrue(button(text(label(R.string.refresh))).isEnabled)
            scenario.onActivity { state.value = ForwardedPortsState.Unavailable }
            text(label(R.string.forwarded_ports_unavailable))
            text(label(R.string.forwarded_ports_unavailable_hint))
            assertNull(find { it.text?.toString() == context.getString(R.string.forwarded_ports_count, 0) })
            assertTrue(button(text(label(R.string.refresh))).isEnabled)
        }
    }

    @Test fun narrowLayoutAndLargeTextKeepTheFullAddressAndCopyButtonReachable() {
        val port = ForwardedPortConnection("2001:db8:1234:5678:90ab:cdef:1234:5678", 48217, 8080,
            "Development server with a long service label that must wrap")
        val copied = AtomicReference<String>()
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme {
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                        Box(Modifier.width(320.dp).fillMaxHeight()) {
                            ForwardedPortsScreen(ForwardedPortsState.Ready(listOf(port)), {}, {}, copied::set)
                        }
                    }
                }
            } }
            val copyLabel = context.getString(R.string.forwarded_ports_copy_address, port.androidAddress)
            val copy = button(waitFor(scroll = true) { it.contentDescription?.toString() == copyLabel })
            val copyBounds = Rect().also(copy::getBoundsInScreen)
            val addressBounds = Rect().also(text(port.androidAddress)::getBoundsInScreen)
            assertTrue("Copy button does not overlap the wrapped address", addressBounds.right <= copyBounds.left)
            val density = context.resources.displayMetrics.density
            assertTrue("Copy button stays within the narrow screen", copyBounds.right <= 320 * density + 1)
            assertTrue("Copy target remains at least 48dp", copyBounds.width() >= 48 * density - 1)
            assertTrue(copy.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertEquals(port.androidAddress, copied.get())
        }
    }
}
