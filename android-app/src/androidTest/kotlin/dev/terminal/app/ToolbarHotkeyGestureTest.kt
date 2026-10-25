package dev.terminal.app

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.*
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolbarHotkeyGestureTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val density get() = context.resources.displayMetrics.density

    @Test fun releaseAfterDragAndFlingShowsCompleteKeysInDefaultAndExpandedViewports() {
        val mode = mutableStateOf(ToolbarSheetMode.Default)
        val layout = HotkeyLayout(columns = 2, keys = (1..12).map { Hotkey("Key $it", "F$it") })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.weight(1f))
                        TerminalToolbar(ToolbarConfig(tools = listOf(HOTKEY_TOOL)), mode.value,
                            { mode.value = it }, 240.dp, TerminalChromeColors(Color.Black)) { _, active ->
                            ToolbarHotkeys(layout, false, false, false, TerminalChromeColors(Color.Black), active, {})
                        }
                    }
                } }
            } }
            settle()
            fun viewport() = bounds(node { it.isScrollable })
            val firstViewport = viewport()
            drag(firstViewport, dy = -62 * density, holdAtEnd = true)
            assertWholeKeys()
            assertNotNull(find { it.contentDescription?.toString() == "Key 5: F5" })
            // Opposite drag exercises another snap after the first animation has completed.
            drag(viewport(), dy = 29 * density, holdAtEnd = true)
            assertWholeKeys()
            drag(viewport(), dy = -56 * density)
            assertWholeKeys()

            scenario.onActivity { mode.value = ToolbarSheetMode.Expanded }
            settle()
            assertEquals(216f * density, viewport().height().toFloat(), 1f)
            drag(viewport(), dy = 35 * density, holdAtEnd = true)
            assertWholeKeys()
            drag(viewport(), dy = -80 * density)
            assertWholeKeys()
            assertNotNull(find { it.contentDescription?.toString() == "Key 12: F12" })
        }
    }

    @Test fun heldNormalModifierAndRepeatingKeysBlockPagingAndReleaseRestoresIt() {
        val count = AtomicInteger()
        val control = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        val layout = mutableStateOf(HotkeyLayout(columns = 3, keys = listOf(
            Hotkey("Left", "LEFT"), Hotkey("Enter", "ENTER"), Hotkey("Ctrl", "CTRL"),
        )))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.weight(1f))
                        TerminalToolbar(ToolbarConfig(tools = listOf(HOTKEY_TOOL, HISTORY_TOOL)),
                            ToolbarSheetMode.Default, {}, 240.dp, TerminalChromeColors(Color.Black)) { id, active ->
                            if (id == HOTKEY_TOOL) ToolbarHotkeys(layout.value, control.value, false, false,
                                TerminalChromeColors(Color.Black), active && enabled.value) { key ->
                                if (key == TerminalToolbarKey.Control) control.value = !control.value else count.incrementAndGet()
                            }
                            else Text("Other tool", Modifier.fillMaxSize())
                        }
                    }
                } }
            } }
            settle()
            val page = bounds(node { it.isScrollable })
            for (description in listOf("Left: LEFT", "Enter: ENTER", "Ctrl: CTRL")) {
                val rect = bounds(node { it.contentDescription?.toString() == description })
                val before = count.get()
                val down = SystemClock.uptimeMillis()
                event(down, MotionEvent.ACTION_DOWN, rect.exactCenterX(), rect.exactCenterY())
                SystemClock.sleep(800)
                if (description == "Left: LEFT") assertTrue("Arrow hold must keep repeating", count.get() > before + 1)
                for (step in 1..12) {
                    event(down, MotionEvent.ACTION_MOVE, rect.exactCenterX() - page.width() * 0.65f * step / 12,
                        rect.exactCenterY())
                    SystemClock.sleep(16)
                }
                if (description == "Left: LEFT") {
                    val moved = count.get()
                    SystemClock.sleep(200)
                    assertTrue("Repeating must survive leaving the key", count.get() > moved)
                }
                event(down, MotionEvent.ACTION_UP, rect.exactCenterX() - page.width() * 0.65f, rect.exactCenterY())
                settle()
                assertNull("A held $description must not switch tools", find { it.text?.toString() == "Other tool" })
                switchTool(page, forward = true)
                node { it.text?.toString() == "Other tool" }
                switchTool(page, forward = false)
                node { it.contentDescription?.toString() == description }
            }
            // Hold one key while a second finger swipes elsewhere in the same tool.
            val enter = bounds(node { it.contentDescription?.toString() == "Enter: ENTER" })
            val held = SystemClock.uptimeMillis()
            event(held, MotionEvent.ACTION_DOWN, enter.exactCenterX(), enter.exactCenterY())
            SystemClock.sleep(350)
            val fingerY = page.bottom - 8 * density
            fun twoFingers(action: Int, secondX: Float) {
                val properties = Array(2) { index -> MotionEvent.PointerProperties().apply {
                    id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
                } }
                val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
                    x = enter.exactCenterX(); y = enter.exactCenterY(); pressure = 1f
                }, MotionEvent.PointerCoords().apply { x = secondX; y = fingerY; pressure = 1f })
                val event = MotionEvent.obtain(held, SystemClock.uptimeMillis(), action, 2, properties, coordinates,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            twoFingers(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), page.width() * 0.85f)
            repeat(12) { step ->
                twoFingers(MotionEvent.ACTION_MOVE, page.width() * (0.85f - 0.7f * (step + 1) / 12))
                SystemClock.sleep(16)
            }
            twoFingers(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), page.width() * 0.15f)
            event(held, MotionEvent.ACTION_UP, enter.exactCenterX(), enter.exactCenterY())
            settle()
            assertNull("A second finger must not page while a key is held", find { it.text?.toString() == "Other tool" })

            val ctrl = bounds(node { it.contentDescription?.toString() == "Ctrl: CTRL" })
            val previouslySelected = control.value
            val toggle = SystemClock.uptimeMillis()
            event(toggle, MotionEvent.ACTION_DOWN, ctrl.exactCenterX(), ctrl.exactCenterY())
            event(toggle, MotionEvent.ACTION_UP, ctrl.exactCenterX(), ctrl.exactCenterY())
            settle()
            assertEquals("A modifier toggles once on release", !previouslySelected, control.value)
            switchTool(page, forward = true)
            node { it.text?.toString() == "Other tool" }
            switchTool(page, forward = false)
            // Disable and replace a held key: neither path may leave a stale pager lock.
            for (replace in listOf(false, true)) {
                val rect = bounds(node { it.contentDescription?.toString() == "Left: LEFT" })
                val down = SystemClock.uptimeMillis()
                event(down, MotionEvent.ACTION_DOWN, rect.exactCenterX(), rect.exactCenterY())
                SystemClock.sleep(650)
                scenario.onActivity {
                    if (replace) layout.value = layout.value.copy(keys = layout.value.keys.mapIndexed { index, key ->
                        if (index == 0) key.copy(shortcut = "RIGHT") else key
                    }) else enabled.value = false
                }
                SystemClock.sleep(250)
                event(down, MotionEvent.ACTION_CANCEL, rect.exactCenterX(), rect.exactCenterY())
                settle()
                val stopped = count.get()
                switchTool(page, forward = true)
                node { it.text?.toString() == "Other tool" }
                assertEquals("Cancelled holds must stop repeating", stopped, count.get())
                switchTool(page, forward = false)
                scenario.onActivity { enabled.value = true }
                settle()
            }
        }
    }

    @Test fun heldKeysBlockVerticalScrollAndKeepInputThroughMovementInBothViewports() {
        val mode = mutableStateOf(ToolbarSheetMode.Default)
        val count = AtomicInteger()
        val control = mutableStateOf(false)
        val layout = HotkeyLayout(columns = 3, keys = listOf(
            Hotkey("F1", "F1"), Hotkey("F2", "F2"), Hotkey("F3", "F3"),
            Hotkey("Left", "LEFT"), Hotkey("Enter", "ENTER"), Hotkey("Ctrl", "CTRL"),
        ) + (4..18).map { Hotkey("F$it", "F$it") })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.weight(1f))
                        TerminalToolbar(ToolbarConfig(tools = listOf(HOTKEY_TOOL)), mode.value,
                            { mode.value = it }, 240.dp, TerminalChromeColors(Color.Black)) { _, active ->
                            ToolbarHotkeys(layout, control.value, false, false,
                                TerminalChromeColors(Color.Black), active) { key ->
                                if (key == TerminalToolbarKey.Control) control.value = !control.value
                                else count.incrementAndGet()
                            }
                        }
                    }
                } }
            } }
            settle()
            for (sheetMode in listOf(ToolbarSheetMode.Default, ToolbarSheetMode.Expanded)) {
                scenario.onActivity { mode.value = sheetMode }
                settle()
                val page = bounds(node { it.isScrollable })
                for (description in listOf("Left: LEFT", "Enter: ENTER", "Ctrl: CTRL")) {
                    val rect = bounds(node { it.contentDescription?.toString() == description })
                    val firstKey = bounds(node { it.contentDescription?.toString() == "F1: F1" })
                    val before = count.get()
                    val selected = control.value
                    val down = SystemClock.uptimeMillis()
                    event(down, MotionEvent.ACTION_DOWN, rect.exactCenterX(), rect.exactCenterY())
                    SystemClock.sleep(650)
                    val endX = page.left + 8 * density
                    val endY = page.top + 6 * density
                    repeat(12) { step ->
                        val progress = (step + 1) / 12f
                        event(down, MotionEvent.ACTION_MOVE,
                            rect.exactCenterX() + (endX - rect.exactCenterX()) * progress,
                            rect.exactCenterY() + (endY - rect.exactCenterY()) * progress)
                        SystemClock.sleep(16)
                    }
                    val moved = count.get()
                    SystemClock.sleep(200)
                    assertEquals("A held key must keep vertical scroll fixed in $sheetMode", firstKey,
                        bounds(node { it.contentDescription?.toString() == "F1: F1" }))
                    if (description == "Left: LEFT") {
                        assertTrue("Movement must not stop repeating", count.get() > moved)
                    }
                    event(down, MotionEvent.ACTION_UP, endX, endY)
                    settle()
                    when (description) {
                        "Left: LEFT" -> {
                            val released = count.get()
                            SystemClock.sleep(150)
                            assertEquals("Release must stop repeating", released, count.get())
                        }
                        "Enter: ENTER" -> assertEquals("Moved normal key fires once", before + 1, count.get())
                        else -> assertEquals("Moved modifier toggles once", !selected, control.value)
                    }
                    // Release may run the existing whole-key alignment animation.
                    assertEquals(40f * density,
                        bounds(node { it.contentDescription?.toString() == "F1: F1" }).height().toFloat(), 1f)
                }
                // A new quick vertical swipe is still allowed after all holds end.
                // Cross touch slop immediately, before injection latency can become a hold.
                drag(page, dy = -60 * density, holdAtEnd = true, steps = 3)
                assertNull("Release must restore vertical scrolling", find { it.contentDescription?.toString() == "F1: F1" })
                drag(page, dy = 60 * density, holdAtEnd = true, steps = 3)
                node { it.contentDescription?.toString() == "F1: F1" }
            }
        }
    }

    private fun assertWholeKeys() {
        val keys = nodes().filter { it.contentDescription?.toString()?.startsWith("Key ") == true }
        assertTrue("Expected visible shortcut keys", keys.isNotEmpty())
        keys.forEach { assertEquals("Clipped ${it.contentDescription}", 40f * density, bounds(it).height().toFloat(), 1f) }
    }

    private fun switchTool(page: Rect, forward: Boolean) {
        val down = SystemClock.uptimeMillis()
        val start = page.left + page.width() * if (forward) 0.85f else 0.15f
        val dx = page.width() * if (forward) -0.7f else 0.7f
        val y = page.bottom - 8 * density // Empty space below the key row still switches tools.
        event(down, MotionEvent.ACTION_DOWN, start, y)
        repeat(12) { step ->
            event(down, MotionEvent.ACTION_MOVE, start + dx * (step + 1) / 12, y)
            SystemClock.sleep(16)
        }
        event(down, MotionEvent.ACTION_UP, start + dx, y)
        settle()
    }

    private fun drag(page: Rect, dy: Float, holdAtEnd: Boolean = false, steps: Int = 12) {
        val down = SystemClock.uptimeMillis()
        val x = page.exactCenterX()
        val y = if (dy < 0) page.bottom - 10 * density else page.top + 10 * density
        event(down, MotionEvent.ACTION_DOWN, x, y)
        repeat(steps) { step ->
            event(down, MotionEvent.ACTION_MOVE, x, y + dy * (step + 1) / steps)
            SystemClock.sleep(16)
        }
        if (holdAtEnd) SystemClock.sleep(300)
        event(down, MotionEvent.ACTION_UP, x, y + dy)
        settle()
    }

    private fun event(down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
    }

    private fun settle() { SystemClock.sleep(900); instrumentation.waitForIdleSync() }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun nodes(): List<AccessibilityNodeInfo> {
        instrumentation.uiAutomation.clearCache()
        return buildList {
            fun visit(node: AccessibilityNodeInfo?) {
                if (node == null) return
                if (node.isVisibleToUser && !bounds(node).isEmpty) add(node)
                repeat(node.childCount) { visit(node.getChild(it)) }
            }
            visit(instrumentation.uiAutomation.rootInActiveWindow)
        }
    }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean) = nodes().firstOrNull(predicate)
    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            find(predicate)?.let { return it }
            SystemClock.sleep(50)
        }
        error("Missing toolbar node; visible: ${nodes().map { it.contentDescription ?: it.text }}")
    }
}
