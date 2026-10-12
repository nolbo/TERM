package dev.terminal.app

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses real long-press touch events. Restores the user's toolbar preferences after each test. */
@RunWith(AndroidJUnit4::class)
class HotkeyReorderTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val initial = HotkeyLayout(columns = 3, keys = listOf(
        Hotkey("A", "A", 2), Hotkey("B", "B"), Hotkey("C", "C"), Hotkey("D", "D"),
    ))

    @Test fun longPressMovesWideKeyAcrossRowsAndSavesWithoutOpeningEditor() = withEditor { _ ->
        val source = keyBounds(1, "A")
        val target = keyBounds(4, "D")
        drag(source, target, cancel = false)
        keyBounds(4, "A")
        assertNull(findLabel("핫키 편집"))
        tap(awaitLabel("저장"))
        val prefs = instrumentation.targetContext.getSharedPreferences(SettingsDetailActivity.TERMINAL_PREFERENCES, 0)
        assertEquals(listOf("B", "C", "D", "A"), readHotkeyLayout(prefs).keys.map { it.name })
        assertEquals(2, readHotkeyLayout(prefs).keys.last().span)
    }

    @Test fun cancelledDragLeavesOrderAndShortTapStillEdits() = withEditor { _ ->
        drag(keyBounds(1, "A"), keyBounds(4, "D"), cancel = true)
        assertNull(findLabel("핫키 편집"))
        tap(keyBounds(1, "A"))
        awaitLabel("핫키 편집")
    }

    @Test fun draftOrderSurvivesActivityRecreation() = withEditor { scenario ->
        drag(keyBounds(1, "A"), keyBounds(4, "D"), cancel = false)
        keyBounds(4, "A")
        scenario.recreate()
        scrollToPreview()
        keyBounds(4, "A")
        val prefs = instrumentation.targetContext.getSharedPreferences(SettingsDetailActivity.TERMINAL_PREFERENCES, 0)
        assertEquals(initial, readHotkeyLayout(prefs)) // A drag edits only the draft until Save.
    }

    private fun withEditor(block: (ActivityScenario<SettingsDetailActivity>) -> Unit) {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences(SettingsDetailActivity.TERMINAL_PREFERENCES, 0)
        val original = prefs.getString(KEY_HOTKEYS, null)
        prefs.edit().putString(KEY_HOTKEYS, serializeHotkeyLayout(initial)).commit()
        try {
            val intent = Intent(context, SettingsDetailActivity::class.java)
                .putExtra(SettingsDetailActivity.EXTRA_CATEGORY, SettingsDetailActivity.HOTKEYS)
            ActivityScenario.launch<SettingsDetailActivity>(intent).use { scenario ->
                awaitLabel("핫키 툴바")
                scenario.onActivity {
                    androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView)
                        .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
                }
                // Activity and IME transitions must finish before sampling touch coordinates.
                SystemClock.sleep(800)
                scrollToPreview()
                block(scenario)
            }
        } finally {
            prefs.edit().apply {
                if (original == null) remove(KEY_HOTKEYS) else putString(KEY_HOTKEYS, original)
            }.commit()
        }
    }

    private fun scrollToPreview() {
        val minimumHeight = (48 * instrumentation.targetContext.resources.displayMetrics.density).toInt()
        // Accessibility may briefly expose the destination clipped to one pixel during launch.
        // Wait for the complete two-row grid instead of dragging to an off-screen coordinate.
        repeat(6) {
            instrumentation.waitForIdleSync()
            val last = findLabel("4번째 키 D 편집, D") ?: findLabel("4번째 키 A 편집, A")
            if (last != null && last.height() >= minimumHeight) return
            val screen = Rect().also { instrumentation.uiAutomation.rootInActiveWindow.getBoundsInScreen(it) }
            val down = SystemClock.uptimeMillis()
            val x = screen.width() * 0.8f
            val y = screen.height() * 0.7f
            event(down, MotionEvent.ACTION_DOWN, x, y)
            repeat(12) { step ->
                event(down, MotionEvent.ACTION_MOVE, x, y - screen.height() * 0.35f * (step + 1) / 12f)
                SystemClock.sleep(16)
            }
            event(down, MotionEvent.ACTION_UP, x, y - screen.height() * 0.35f)
            SystemClock.sleep(400)
        }
        throw AssertionError("Preview not fully visible: minimum=$minimumHeight, last=${findLabel("4번째 키 D 편집, D")}, moved=${findLabel("4번째 키 A 편집, A")}")
    }

    private fun keyBounds(position: Int, name: String): Rect = awaitLabel("${position}번째 키 $name 편집, $name")

    private fun awaitLabel(label: String): Rect {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            findLabel(label)?.let { return it }
            SystemClock.sleep(50)
        }
        val labels = mutableListOf<String>()
        findNode {
            if (!it.contentDescription.isNullOrBlank() || !it.text.isNullOrBlank()) {
                labels.add("${it.text ?: it.contentDescription}: ${Rect().also { bounds -> it.getBoundsInScreen(bounds) }}")
            }
            false
        }
        throw AssertionError("UI label did not appear: $label; visible=$labels")
    }

    private fun findLabel(label: String): Rect? = findNode {
        it.text?.toString() == label || it.contentDescription?.toString() == label
    }?.let { node -> Rect().also { node.getBoundsInScreen(it) } }

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            node.refresh()
            if (predicate(node)) return node
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
    }

    private fun drag(from: Rect, to: Rect, cancel: Boolean) {
        val down = SystemClock.uptimeMillis()
        event(down, MotionEvent.ACTION_DOWN, from.exactCenterX(), from.exactCenterY())
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150)
        repeat(12) { step ->
            val progress = (step + 1) / 12f
            event(down, MotionEvent.ACTION_MOVE,
                from.exactCenterX() + (to.exactCenterX() - from.exactCenterX()) * progress,
                from.exactCenterY() + (to.exactCenterY() - from.exactCenterY()) * progress)
            SystemClock.sleep(20)
        }
        event(down, if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, to.exactCenterX(), to.exactCenterY())
        instrumentation.waitForIdleSync()
    }

    private fun event(down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
    }
}
