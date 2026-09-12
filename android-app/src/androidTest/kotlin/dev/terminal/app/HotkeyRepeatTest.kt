package dev.terminal.app

import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HotkeyRepeatTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun holdsRepeatButReleaseCancelDisableAndReplacementStopThem() {
        val count = AtomicInteger()
        val enabled = mutableStateOf(true)
        val generation = mutableStateOf(0)
        val composedGeneration = AtomicInteger(-1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        val renderedGeneration = generation.value
                        SideEffect { composedGeneration.set(renderedGeneration) }
                        key(generation.value) {
                            HotkeyGrid(HotkeyLayout(columns = 4, keys = listOf(
                                Hotkey("LEFT", "LEFT"), Hotkey("BS", "BACKSPACE"),
                                Hotkey("DEL", "DELETE"), Hotkey("ENTER", "ENTER"),
                            )), enabled = enabled.value) { count.incrementAndGet() }
                        }
                    }
                }
            }
            val left = bounds("LEFT: LEFT")
            val down = press(left)
            SystemClock.sleep(950)
            assertTrue("Holding LEFT must repeat", count.get() >= 3)
            release(down, left)
            assertStable(count)
            val beforeTap = count.get()
            val tap = press(left)
            SystemClock.sleep(50)
            release(tap, left)
            assertEquals("Short tap must send exactly one key", beforeTap + 1, count.get())

            for (label in listOf("BS: BACKSPACE", "DEL: DELETE")) {
                val rect = bounds(label)
                val before = count.get()
                val held = press(rect)
                SystemClock.sleep(850)
                assertTrue("$label must repeat", count.get() > before + 1)
                event(held, MotionEvent.ACTION_CANCEL, rect)
                assertStable(count)
            }
            val enter = bounds("ENTER: ENTER")
            val beforeEnter = count.get()
            val heldEnter = press(enter)
            SystemClock.sleep(850)
            assertEquals("ENTER must not repeat", beforeEnter, count.get())
            release(heldEnter, enter)
            assertEquals(beforeEnter + 1, count.get())

            val heldDisable = press(left)
            SystemClock.sleep(650)
            instrumentation.runOnMainSync { enabled.value = false }
            instrumentation.waitForIdleSync()
            assertStable(count)
            event(heldDisable, MotionEvent.ACTION_CANCEL, left)
            instrumentation.runOnMainSync { enabled.value = true }
            instrumentation.waitForIdleSync()

            val heldReplace = press(left)
            SystemClock.sleep(650)
            instrumentation.runOnMainSync { generation.value++ }
            // UI-thread idleness alone does not wait for the next Compose frame/disposal.
            val deadline = SystemClock.uptimeMillis() + 2000
            while (composedGeneration.get() != generation.value && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(16)
            }
            assertEquals(generation.value, composedGeneration.get())
            assertStable(count)
            event(heldReplace, MotionEvent.ACTION_CANCEL, left)
        }
    }

    private fun assertStable(count: AtomicInteger) {
        instrumentation.waitForIdleSync()
        val stopped = count.get()
        SystemClock.sleep(250)
        assertEquals("Input continued after gesture ended", stopped, count.get())
    }

    private fun press(rect: Rect): Long = SystemClock.uptimeMillis().also { event(it, MotionEvent.ACTION_DOWN, rect) }
    private fun release(down: Long, rect: Rect) {
        event(down, MotionEvent.ACTION_UP, rect)
        instrumentation.waitForIdleSync()
    }
    private fun event(down: Long, action: Int, rect: Rect) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
        try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
    }

    private fun bounds(description: String): Rect {
        fun find(node: AccessibilityNodeInfo): Rect? {
            if (node.contentDescription?.toString() == description) return Rect().also(node::getBoundsInScreen)
            for (i in 0 until node.childCount) node.getChild(i)?.let { find(it)?.let { rect -> return rect } }
            return null
        }
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.uiAutomation.rootInActiveWindow?.let { find(it) }?.takeIf { it.height() > 20 }?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("No key: $description")
    }
}
