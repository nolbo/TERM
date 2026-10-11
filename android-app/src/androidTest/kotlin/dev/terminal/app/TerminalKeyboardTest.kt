package dev.terminal.app

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.view.TerminalView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Requires an unlocked device with a software IME enabled. Never types shell commands. */
@RunWith(AndroidJUnit4::class)
class TerminalKeyboardTest {
    @Test fun entryAndActivityRecreationConnectTheKeyboard() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitKeyboard(scenario, true)
            scenario.onActivity { activity ->
                val terminal = requireNotNull(findTerminal(activity.window.decorView))
                assertTrue(terminal.isFocusableInTouchMode)
                assertTrue(terminal.hasFocus())
                assertTrue(terminal.onCheckIsTextEditor())
            }
            scenario.recreate()
            awaitKeyboard(scenario, true)
        }
    }

    @Test fun backHidesKeyboardAndTerminalTapReopensIt() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitKeyboard(scenario, true)
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitKeyboard(scenario, false)
            // Allow IME insets/recomposition to settle. A refresh must not reopen it.
            repeat(10) {
                SystemClock.sleep(100)
                assertTrue("Keyboard reopened after Back", !keyboardVisible(scenario))
            }
            scenario.onActivity { activity ->
                val terminal = requireNotNull(findTerminal(activity.window.decorView))
                val now = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(now, now + 20, action, terminal.width / 2f, terminal.height / 2f, 0)
                    try { terminal.dispatchTouchEvent(event) } finally { event.recycle() }
                }
            }
            awaitKeyboard(scenario, true)
        }
    }

    private fun awaitKeyboard(scenario: ActivityScenario<MainActivity>, visible: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (keyboardVisible(scenario) == visible) return
            SystemClock.sleep(50)
        }
        throw AssertionError("IME visibility did not become $visible")
    }

    private fun keyboardVisible(scenario: ActivityScenario<MainActivity>): Boolean {
        var visible = false
        scenario.onActivity { activity ->
            visible = ViewCompat.getRootWindowInsets(activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return visible
    }

    private fun findTerminal(view: View): TerminalView? {
        if (view is TerminalView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findTerminal(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
