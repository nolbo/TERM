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
import dev.terminal.app.ui.LocalServicesScreen
import dev.terminal.app.ui.LocalServicesSnapshot
import dev.terminal.app.ui.ServiceEnvironment
import dev.terminal.runtime.forwarding.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Recorded port snapshots exercise the UI without starting a VM or opening sockets. */
@RunWith(AndroidJUnit4::class)
class LocalServicesUiTest {
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
        throw AssertionError("Local service UI element did not appear")
    }

    private fun label(id: Int) = context.getString(id)
    private fun text(value: String) = waitFor { it.text?.toString() == value }
    private fun button(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable && current.parent != null) current = current.parent
        return current
    }

    private fun service() = LocalService("avf:fixture", "fixture-env", "AVF", "Development API", 8080, "::1",
        ServiceSource.AVF_AGENT, ServiceObservation.LISTENING, ForwardingState.ACTIVE, 48217,
        ApplicationProtocol.HTTP)
    private fun snapshot(services: List<LocalService>) = LocalServicesSnapshot(services,
        listOf(ServiceEnvironment("fixture-env", "Development VM", "AVF")), mapOf("fixture-env" to "CONNECTED"), null)

    @Test fun detailsCopyOnlyTheActualAndroidAddressAndOpenExplicitHttpThenRefresh() {
        val port = service()
        val state = mutableStateOf(snapshot(listOf(port)))
        val copied = AtomicReference<String>()
        val opened = AtomicReference<String>()
        val refreshed = AtomicInteger()
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { LocalServicesScreen(state.value, false, false, false, null, {}, {
                    refreshed.incrementAndGet(); state.value = snapshot(emptyList())
                }, { _, _, _, _ -> }, copied::set, { opened.set(it.browserUrl) }, {}) }
            } }
            text("Development API")
            text(requireNotNull(port.androidAddress))
            assertTrue(button(text("Development API")).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            text(label(R.string.local_services_android_address))
            assertTrue(button(waitFor(scroll = true) { it.text?.toString() == label(R.string.local_services_copy_address) })
                .performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertEquals("127.0.0.1:48217", copied.get())
            assertTrue(button(waitFor(scroll = true) { it.text?.toString() == label(R.string.local_services_open_browser) })
                .performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertEquals("http://127.0.0.1:48217/", opened.get())
            assertTrue(button(text(label(R.string.close))).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(button(text(label(R.string.refresh))).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            text(label(R.string.local_services_empty))
            assertEquals(1, refreshed.get())
            assertNull(find { it.text?.toString() == port.androidAddress })
        }
    }

    @Test fun loadingEmptyAndFailedSnapshotsAreDistinctAndNeverGuessAnActiveService() {
        val state = mutableStateOf<LocalServicesSnapshot?>(null)
        val loading = mutableStateOf(true)
        val failed = mutableStateOf(false)
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { LocalServicesScreen(state.value, loading.value, failed.value, false, null, {}, {},
                    { _, _, _, _ -> }, {}, {}, {}) }
            } }
            assertFalse(button(text(label(R.string.local_services_loading))).isEnabled)
            assertNull(find { it.text?.toString() == label(R.string.local_services_empty) })
            scenario.onActivity { state.value = snapshot(emptyList()); loading.value = false }
            text(label(R.string.local_services_empty))
            assertTrue(button(text(label(R.string.refresh))).isEnabled)
            scenario.onActivity { state.value = null; failed.value = true }
            text(label(R.string.local_services_unavailable))
            assertNull(find { it.text?.toString() == label(R.string.local_services_empty) })
        }
    }

    @Test fun narrowLayoutAndLargeTextKeepDetailsAndCopyActionReachable() {
        val copied = AtomicReference<String>()
        val port = service().copy(name = "Development server with a long service label that must wrap",
            sourceAddress = "2001:db8:1234:5678:90ab:cdef:1234:5678")
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme {
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                        Box(Modifier.width(320.dp).fillMaxHeight()) {
                            LocalServicesScreen(snapshot(listOf(port)), false, false, false, null, {}, {},
                                { _, _, _, _ -> }, copied::set, {}, {})
                        }
                    }
                }
            } }
            val entry = waitFor(scroll = true) { it.text?.toString() == port.name }
            assertTrue(button(entry).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val copy = button(waitFor(scroll = true) { it.text?.toString() == label(R.string.local_services_copy_address) })
            assertTrue(copy.isEnabled)
            assertTrue(copy.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertEquals("127.0.0.1:48217", copied.get())
        }
    }
}
