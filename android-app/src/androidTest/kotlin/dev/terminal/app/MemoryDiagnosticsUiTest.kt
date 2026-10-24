package dev.terminal.app

import android.content.*
import android.os.*
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import dev.terminal.app.ui.DroidTheme
import dev.terminal.app.ui.MemoryDiagnosticsScreen
import dev.terminal.app.ui.TerminalHapticFeedbackProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MemoryDiagnosticsUiTest {
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

    private fun row(section: Int, title: Int): AccessibilityNodeInfo {
        val label = context.getString(R.string.memory_progress_description, context.getString(section), context.getString(title))
        return nodeWithLabel(label)
    }

    private fun composition(section: Int) = nodeWithLabel(
        context.getString(R.string.memory_composition_bar_description, context.getString(section)))

    private fun nodeWithLabel(label: String): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            find { it.contentDescription?.toString() == label }?.let { return it }
            find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(100)
        }
        throw AssertionError("Memory row was not found: $label")
    }

    @Test fun stackedCompositionUsesPssAndExposesEveryCategoryAndDifference() {
        val mib = 1048576L
        val areas = listOf(10, 20, 5, 5, 10, 20, 20)
        fun process(scale: Long) = JSONObject().put("basis", "pss").put("total", 100 * scale * mib).apply {
            appMemoryKeys.drop(1).zip(areas).forEach { (key, value) -> put(key, value * scale * mib) }
        }
        fixture(MemorySnapshot(DeviceMemory.from(1000 * mib, 250 * mib), process(1), process(2), 1)) {
            for ((section, difference) in listOf(R.string.memory_app_total to 30,
                R.string.memory_ui_process to 10, R.string.memory_runtime_process to 20)) {
                val bar = composition(section)
                assertEquals(0.9f, requireNotNull(bar.rangeInfo).current, 0.0001f)
                assertFalse(bar.isClickable)
                assertTrue(bar.stateDescription.toString().contains(context.getString(R.string.memory_java)))
                assertTrue(bar.stateDescription.toString().contains(context.getString(R.string.memory_system)))
                val value = String.format(java.util.Locale.getDefault(), "%.1f MiB", difference.toDouble())
                assertTrue(bar.stateDescription.toString().contains(context.getString(R.string.memory_composition_difference, value)))
            }
        }
    }

    @Test fun excessiveSummaryValuesDoNotCreateANormalizedCompositionBar() {
        val mib = 1048576L
        val process = JSONObject().put("basis", "pss").put("total", 100 * mib).apply {
            appMemoryKeys.drop(1).forEach { put(it, 0) }
            put("java-heap", 60 * mib); put("native-heap", 50 * mib)
        }
        fixture(MemorySnapshot(null, process, process, 1)) {
            val bar = composition(R.string.memory_app_total)
            assertNull(bar.rangeInfo)
            assertEquals(context.getString(R.string.memory_composition_inconsistent), bar.stateDescription.toString())
            assertEquals(0.6f, requireNotNull(row(R.string.memory_app_total, R.string.memory_java).rangeInfo).current, 0.0001f)
        }
    }

    private fun fixture(snapshot: MemorySnapshot, check: () -> Unit) {
        ActivityScenario.launch(DiagnosticsActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    MemoryDiagnosticsScreen(snapshot, false, {}, {})
                } }
            } }
            check()
        }
    }

    private fun refreshFromScreen() {
        val label = context.getString(R.string.refresh)
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            var node = find { it.text?.toString() == label || it.contentDescription?.toString() == label }
            while (node != null && !node.isClickable) node = node.parent
            if (node?.isEnabled == true && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Refresh button was not available")
    }

    @Test fun usageBarsUsePhysicalCapacityAndAreasUseTheirOwnPssTotals() {
        val mib = 1048576L
        val ui = JSONObject().put("basis", "pss").put("total", 100 * mib)
            .put("java-heap", 20 * mib).put("stack", 0).put("graphics", 150 * mib)
        val runtime = JSONObject().put("basis", "pss").put("total", 200 * mib)
            .put("java-heap", 20 * mib).put("native-heap", 60 * mib).put("stack", 0)
        fixture(MemorySnapshot(DeviceMemory.from(1000 * mib, 250 * mib), ui, runtime, 1)) {
            fun ratio(section: Int, title: Int, expected: Float) {
                val node = row(section, title)
                val range = requireNotNull(node.rangeInfo) { "Missing usage range: $node" }
                assertEquals(0f, range.min, 0f)
                assertEquals(1f, range.max, 0f)
                assertEquals(expected, range.current, 0.0001f)
            }
            ratio(R.string.memory_device, R.string.memory_device_used, 0.75f)
            ratio(R.string.memory_app_total, R.string.memory_pss_total, 0.3f)
            ratio(R.string.memory_ui_process, R.string.memory_pss_total, 0.1f)
            ratio(R.string.memory_runtime_process, R.string.memory_pss_total, 0.2f)
            ratio(R.string.memory_app_total, R.string.memory_java, 40f / 300f)
            ratio(R.string.memory_ui_process, R.string.memory_java, 0.2f)
            ratio(R.string.memory_ui_process, R.string.memory_stack, 0f)
            val inconsistent = row(R.string.memory_ui_process, R.string.memory_graphics)
            assertNull(inconsistent.rangeInfo)
            assertTrue(inconsistent.stateDescription.toString().contains(context.getString(R.string.diagnostics_unknown)))
            ratio(R.string.memory_runtime_process, R.string.memory_native, 0.3f)
        }
    }

    @Test fun missingTotalsProcessesAndWrongMeasurementBasesShowUnknownWithoutBars() {
        val mib = 1048576L
        for (ui in listOf(
            JSONObject().put("basis", "pss").put("java-heap", 20 * mib),
            JSONObject().put("basis", "rss").put("total", 100 * mib).put("java-heap", 20 * mib),
        )) fixture(MemorySnapshot(null, ui, null, 1)) {
            for ((section, title) in listOf(
                R.string.memory_device to R.string.memory_device_used,
                R.string.memory_app_total to R.string.memory_pss_total,
                R.string.memory_runtime_process to R.string.memory_pss_total,
            )) {
                val unknown = row(section, title)
                assertNull(unknown.rangeInfo)
                assertTrue("Unknown usage state: ${unknown.stateDescription}",
                    unknown.stateDescription.toString().contains(context.getString(R.string.diagnostics_unknown)))
            }
            for (section in listOf(R.string.memory_app_total, R.string.memory_ui_process)) {
                val bar = composition(section)
                assertNull(bar.rangeInfo)
                assertEquals(context.getString(R.string.memory_composition_unavailable), bar.stateDescription.toString())
            }
            val unknown = row(R.string.memory_ui_process, R.string.memory_java)
            assertNull(unknown.rangeInfo)
            assertTrue(unknown.stateDescription.toString().contains(context.getString(R.string.diagnostics_unknown)))
        }
    }

    @Test fun samplesBothProcessesRefreshesAndStopsWhenInactiveWithoutChangingSessions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connected = CountDownLatch(1)
        lateinit var service: IRuntimeService
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = IRuntimeService.Stub.asInterface(binder); connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(connected.await(10, TimeUnit.SECONDS))
            val sessions = service.sessionsJson
            val state = service.environmentsJson
            ActivityScenario.launch(MemoryDiagnosticsActivity::class.java).use { scenario ->
                fun sample(condition: (MemorySnapshot) -> Boolean = { true }): MemorySnapshot {
                    val deadline = SystemClock.elapsedRealtime() + 8000
                    while (SystemClock.elapsedRealtime() < deadline) {
                        var snapshot: MemorySnapshot? = null
                        scenario.onActivity { snapshot = it.snapshot }
                        snapshot?.takeIf(condition)?.let { return it }
                        SystemClock.sleep(50)
                    }
                    throw AssertionError("Memory sample timed out")
                }
                val first = sample { it.runtime?.memoryBytes("total") != null }
                assertTrue(requireNotNull(first.device).total > 0)
                assertEquals(first.ui!!.getLong("total") + first.runtime!!.getLong("total"), first.app!!.getLong("total"))
                refreshFromScreen()
                val refreshed = sample { it.sampledAt > first.sampledAt }
                scenario.moveToState(Lifecycle.State.CREATED)
                var stopped: MemoryDiagnosticsActivity? = null
                scenario.onActivity { stopped = it; assertFalse(it.measuring) }
                val pausedSample = stopped!!.snapshot!!.sampledAt
                SystemClock.sleep(5500)
                scenario.onActivity { assertEquals(pausedSample, it.snapshot!!.sampledAt) }
                scenario.moveToState(Lifecycle.State.RESUMED)
                sample { it.sampledAt > refreshed.sampledAt }
                scenario.recreate()
                sample { it.runtime?.memoryBytes("total") != null }
            }
            assertEquals(sessions, service.sessionsJson)
            assertEquals(state, service.environmentsJson)
        } finally { context.unbindService(connection) }
    }
}
