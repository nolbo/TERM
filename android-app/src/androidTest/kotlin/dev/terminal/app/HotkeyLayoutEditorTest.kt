package dev.terminal.app

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.TerminalHapticFeedbackProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Exercises real move/resize gestures and restores the original preferences after every test. */
@RunWith(AndroidJUnit4::class)
class HotkeyLayoutEditorTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val density get() = context.resources.displayMetrics.density
    private val initial = HotkeyLayout(columns = 4, keys = listOf(
        Hotkey("A", "A", bounds = HotkeyBounds(0f, 0f)),
        Hotkey("B", "B", bounds = HotkeyBounds(3f, 0f)),
    ))

    @Test fun keySnapsIntoDistantEmptySpaceWithoutMovingItsNeighborAndSaves() = withEditor { _ ->
        val a = keyBounds(0, "A")
        val b = keyBounds(1, "B")
        val pitch = (b.left - a.left) / 3f
        drag(a, a.exactCenterX() + pitch * 1.25f, a.exactCenterY() + 44 * density * 2.25f)
        val moved = keyBounds(0, "A")
        assertTrue(moved.top > b.bottom + 40 * density)
        assertEquals(b, keyBounds(1, "B"))
        save()
        val layout = readHotkeyLayout(preferences())
        assertEquals(listOf("A", "B"), layout.keys.map { it.name })
        assertEquals(HotkeyBounds(1f, 2f), layout.keys[0].bounds)
        assertEquals(initial.keys[1], layout.keys[1])
    }

    @Test fun cornerDragChangesWidthAndHeightAndKeepsTheOppositeCorner() = withEditor { _ ->
        val a = keyBounds(0, "A")
        val b = keyBounds(1, "B")
        tap(a)
        val corner = awaitLabel(context.getString(R.string.hotkey_resize_handle, "A", context.getString(R.string.hotkey_bottom_right)))
        val pitch = (b.left - a.left) / 3f
        drag(corner, corner.exactCenterX() + pitch * 1.25f, corner.exactCenterY() + 44 * density * 1.65f, longPress = false)
        val resized = keyBounds(0, "A")
        assertEquals(a.left, resized.left)
        assertEquals(a.top, resized.top)
        assertTrue(resized.width() > a.width() * 1.7)
        assertTrue(resized.height() > a.height() * 2)
        save()
        val bounds = readHotkeyLayout(preferences()).keys[0].bounds!!
        assertEquals(0f, bounds.x, 0.01f)
        assertEquals(0f, bounds.y, 0.01f)
        assertEquals(2f, bounds.width, 0f)
        assertEquals(3f, bounds.height, 0f)
    }

    @Test fun gesturesShorterThanHalfACellKeepTheKeyUnchanged() = withEditor { _ ->
        val a = keyBounds(0, "A")
        val pitch = (keyBounds(1, "B").left - a.left) / 3f
        drag(a, a.exactCenterX() + pitch * 0.3f, a.exactCenterY() + 44 * density * 0.3f)
        assertEquals(a, keyBounds(0, "A"))
        tap(a)
        val corner = awaitLabel(context.getString(R.string.hotkey_resize_handle, "A", context.getString(R.string.hotkey_bottom_right)))
        drag(corner, corner.exactCenterX() + pitch * 0.3f, corner.exactCenterY() + 44 * density * 0.3f, longPress = false)
        assertEquals(a, keyBounds(0, "A"))
        save()
        assertEquals(initial, readHotkeyLayout(preferences()))
    }

    @Test fun accessibilityActionsMoveAndResizeByOneCell() = withEditor { _ ->
        for (label in listOf(R.string.hotkey_move_down, R.string.hotkey_move_right, R.string.hotkey_wider, R.string.hotkey_taller)) {
            var node = findNode(context.getString(R.string.hotkey_edit_description, 1, "A", "A"))
            var performed = false
            while (node != null && !performed) {
                val action = node.actionList.firstOrNull { it.label?.toString() == context.getString(label) }
                if (action != null) performed = node.performAction(action.id)
                node = node.parent
            }
            assertTrue("Missing or rejected action: ${context.getString(label)}", performed)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(180)
        }
        save()
        val layout = readHotkeyLayout(preferences())
        assertEquals(HotkeyBounds(1f, 1f, 2f, 2f), layout.keys[0].bounds)
        assertEquals(initial.keys[1], layout.keys[1])
    }

    @Test fun cancelledMoveAndOverlappingDropLeaveTheDraftAndPreferencesUnchanged() = withEditor { _ ->
        val a = keyBounds(0, "A")
        val b = keyBounds(1, "B")
        drag(a, a.exactCenterX(), a.exactCenterY() + 90 * density, cancel = true)
        assertEquals(a, keyBounds(0, "A"))
        drag(a, b.exactCenterX(), b.exactCenterY())
        assertEquals(a, keyBounds(0, "A"))
        assertEquals(b, keyBounds(1, "B"))
        save()
        assertEquals(initial, readHotkeyLayout(preferences()))
    }

    @Test fun draftPlacementSurvivesRecreationAndCancelDoesNotPersistIt() = withEditor { scenario ->
        val a = keyBounds(0, "A")
        drag(a, a.exactCenterX(), a.exactCenterY() + 44 * density * 2.5f)
        scenario.recreate()
        scrollToPreview()
        assertTrue(keyBounds(0, "A").top > keyBounds(1, "B").bottom + 40 * density)
        assertEquals(initial, readHotkeyLayout(preferences()))
        tap(awaitLabel(context.getString(R.string.cancel)))
        tap(awaitLabel(context.getString(R.string.confirm)))
        assertEquals(initial, readHotkeyLayout(preferences()))
    }

    @Test fun renderedKeysKeepTheirGapsHeightsAndInputTargets() {
        val selected = AtomicInteger(-1)
        val layout = HotkeyLayout(columns = 4, keys = listOf(
            Hotkey("A", "A", bounds = HotkeyBounds(0f, 0f)),
            Hotkey("B", "B", bounds = HotkeyBounds(2.5f, 3.25f, 1.25f, 2.5f)),
        ))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme { TerminalHapticFeedbackProvider {
                    HotkeyGrid(layout, modifier = Modifier.padding(32.dp)) { selected.set(it) }
                } }
            } }
            val a = awaitLabel("A: A")
            val b = awaitLabel("B: B")
            assertEquals(44 * density * 3.25f, (b.top - a.top).toFloat(), 2f)
            assertEquals((44 * 2.5f - 4) * density, b.height().toFloat(), 2f)
            assertTrue(b.left > a.right)
            val hole = Rect(a.left, a.bottom + 10, a.right, b.top - 10)
            tap(hole)
            assertEquals(-1, selected.get())
            tap(b)
            assertEquals(1, selected.get())
        }
    }

    private fun preferences() = context.getSharedPreferences(SettingsDetailActivity.TERMINAL_PREFERENCES, 0)
    private fun save() = tap(awaitLabel(context.getString(R.string.save)))

    private fun withEditor(block: (ActivityScenario<SettingsDetailActivity>) -> Unit) {
        val original = preferences().getString(KEY_HOTKEYS, null)
        preferences().edit().putString(KEY_HOTKEYS, serializeHotkeyLayout(initial)).commit()
        try {
            val intent = Intent(context, SettingsDetailActivity::class.java)
                .putExtra(SettingsDetailActivity.EXTRA_CATEGORY, SettingsDetailActivity.HOTKEYS)
            ActivityScenario.launch<SettingsDetailActivity>(intent).use { scenario ->
                scenario.onActivity {
                    androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView)
                        .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
                }
                SystemClock.sleep(600)
                scrollToPreview()
                block(scenario)
            }
        } finally {
            preferences().edit().apply { if (original == null) remove(KEY_HOTKEYS) else putString(KEY_HOTKEYS, original) }.commit()
        }
    }

    private fun scrollToPreview() {
        repeat(6) {
            instrumentation.waitForIdleSync()
            val a = findLabel(context.getString(R.string.hotkey_edit_description, 1, "A", "A"))
            val screen = Rect().also { awaitRoot().getBoundsInScreen(it) }
            if (a != null && a.height() > 35 * density && a.top > screen.top + 80 * density && a.bottom < screen.bottom - 250 * density) return
            val down = SystemClock.uptimeMillis()
            val x = screen.width() * 0.8f
            val y = screen.height() * 0.8f
            event(down, MotionEvent.ACTION_DOWN, x, y)
            repeat(12) { step ->
                event(down, MotionEvent.ACTION_MOVE, x, y - screen.height() * 0.35f * (step + 1) / 12f)
                SystemClock.sleep(16)
            }
            event(down, MotionEvent.ACTION_UP, x, y - screen.height() * 0.35f)
            SystemClock.sleep(250)
        }
        throw AssertionError("Preview is not visible")
    }

    private fun keyBounds(index: Int, name: String) = awaitLabel(context.getString(R.string.hotkey_edit_description, index + 1, name, name))
    private fun awaitRoot(): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.uiAutomation.rootInActiveWindow?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("Active window did not appear")
    }

    private fun awaitLabel(label: String): Rect {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            findLabel(label)?.takeIf { it.width() > 0 && it.height() > 0 }?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("UI label did not appear: $label")
    }

    private fun findLabel(label: String): Rect? = findNode(label)?.let { node -> Rect().also(node::getBoundsInScreen) }

    private fun findNode(label: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            node.refresh()
            if (node.text?.toString() == label || node.contentDescription?.toString() == label)
                return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }

    private fun tap(rect: Rect) {
        val down = SystemClock.uptimeMillis()
        event(down, MotionEvent.ACTION_DOWN, rect.exactCenterX(), rect.exactCenterY())
        event(down, MotionEvent.ACTION_UP, rect.exactCenterX(), rect.exactCenterY())
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
    }

    private fun drag(from: Rect, x: Float, y: Float, longPress: Boolean = true, cancel: Boolean = false) {
        val down = SystemClock.uptimeMillis()
        event(down, MotionEvent.ACTION_DOWN, from.exactCenterX(), from.exactCenterY())
        if (longPress) SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150)
        repeat(16) { step ->
            val fraction = (step + 1) / 16f
            event(down, MotionEvent.ACTION_MOVE,
                from.exactCenterX() + (x - from.exactCenterX()) * fraction,
                from.exactCenterY() + (y - from.exactCenterY()) * fraction)
            SystemClock.sleep(20)
        }
        event(down, if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, x, y)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(180)
    }

    private fun event(down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
    }
}
