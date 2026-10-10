package dev.terminal.app

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.MutableState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.SessionUi
import dev.terminal.app.ui.TerminalChromeColors
import dev.terminal.app.ui.TerminalDarkColors
import dev.terminal.app.ui.terminalDynamicColors
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalModalColorsUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun mainActivityModalsFollowThePaletteWhileOpenAndRestoreTheAppTheme() {
        // Exercise the actual screen and its sibling dialogs, without launching a runtime session.
        val session = SessionUi("modal-color-fixture", "AVF", "Modal color fixture", "STARTING", pendingLaunch = true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await("Runtime session state did not arrive") {
                var known = false
                scenario.onActivity { known = state<Boolean>(it, "workspaceSessionStateKnown").value }
                known
            }
            val original = mutableMapOf<String, Any?>()
            scenario.onActivity { activity ->
                for (name in listOf("sessions", "activeSessionId", "terminalChromeColors", "pendingTermination", "pendingRename"))
                    original[name] = state<Any?>(activity, name).value
                state<List<SessionUi>>(activity, "sessions").value = listOf(session)
                state<String?>(activity, "activeSessionId").value = session.id
                state<SessionUi?>(activity, "pendingTermination").value = session
            }
            try {
                for (background in listOf(Color(0xFF301B26), Color(0xFF163025), Color(0xFFF0E0D0))) {
                    val colors = terminalDynamicColors(background)
                    scenario.onActivity { state<TerminalChromeColors>(it, "terminalChromeColors").value = colors }
                    assertDialogColors(R.string.terminate_session_title, requireNotNull(colors.colorScheme), R.string.terminate)
                }
                // Both disabling the option and leaving alternate screen supply legacy colors.
                scenario.onActivity {
                    state<TerminalChromeColors>(it, "terminalChromeColors").value = TerminalChromeColors(Color(0xFFF0E0D0))
                }
                assertDialogColors(R.string.terminate_session_title, TerminalDarkColors, R.string.terminate)

                // No active session must not inherit a stale dynamic palette.
                scenario.onActivity {
                    state<TerminalChromeColors>(it, "terminalChromeColors").value = terminalDynamicColors(Color(0xFF301B26))
                    state<String?>(it, "activeSessionId").value = null
                }
                assertDialogColors(R.string.terminate_session_title, TerminalDarkColors, R.string.terminate)

                val colors = terminalDynamicColors(Color(0xFF163025))
                scenario.onActivity {
                    state<String?>(it, "activeSessionId").value = session.id
                    state<TerminalChromeColors>(it, "terminalChromeColors").value = colors
                    state<SessionUi?>(it, "pendingTermination").value = null
                    state<SessionUi?>(it, "pendingRename").value = session
                }
                assertDialogColors(R.string.rename_session_title, requireNotNull(colors.colorScheme))
                assertTrue("Rename dialog should still dismiss", clickableNode(context.getString(R.string.cancel))
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await("Rename dialog did not dismiss") {
                    var dismissed = false
                    scenario.onActivity { dismissed = state<SessionUi?>(it, "pendingRename").value == null }
                    dismissed
                }
            } finally {
                scenario.onActivity { activity ->
                    for ((name, value) in original) state<Any?>(activity, name).value = value
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> state(activity: MainActivity, name: String): MutableState<T> = MainActivity::class.java
        .getDeclaredField("${name}\$delegate").apply { isAccessible = true }.get(activity) as MutableState<T>

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { message }
            SystemClock.sleep(100)
        }
    }

    private fun node(text: String): AccessibilityNodeInfo {
        fun find(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            root ?: return null
            if (root.isVisibleToUser && root.text?.toString() == text) return root
            for (index in 0 until root.childCount) find(root.getChild(index))?.let { return it }
            return null
        }
        var result: AccessibilityNodeInfo? = null
        await("Dialog text was not visible: $text") {
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.clearCache()
            result = find(instrumentation.uiAutomation.rootInActiveWindow)
            result != null
        }
        return requireNotNull(result)
    }

    private fun assertDialogColors(title: Int, scheme: ColorScheme, confirm: Int? = null) {
        fun matches(actual: Int, expected: Color) = listOf(0, 8, 16).all { shift ->
            abs(((actual shr shift) and 255) - ((expected.toArgb() shr shift) and 255)) <= 4
        }
        val titleBounds = Rect()
        val buttonBounds = Rect()
        var surface = 0
        var button = 0
        await("Dialog colors did not update: surface ${scheme.surfaceContainerHigh}, action ${scheme.primary}") {
            node(context.getString(title)).getBoundsInScreen(titleBounds)
            if (confirm != null) clickableNode(context.getString(confirm)).getBoundsInScreen(buttonBounds)
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                val aboveTitle = (8 * context.resources.displayMetrics.density).toInt()
                surface = screenshot.getPixel(titleBounds.centerX(), titleBounds.top - aboveTitle)
                if (confirm != null) button = screenshot.getPixel(buttonBounds.centerX(), buttonBounds.top + buttonBounds.height() / 4)
            } finally { screenshot.recycle() }
            matches(surface, scheme.surfaceContainerHigh) && (confirm == null || matches(button, scheme.primary))
        }
        assertTrue(matches(surface, scheme.surfaceContainerHigh))
        if (confirm != null) assertTrue(matches(button, scheme.primary))
    }

    private fun clickableNode(text: String): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = node(text)
        while (result != null && !result.isClickable) result = result.parent
        return requireNotNull(result) { "Dialog action was not clickable: $text" }
    }
}
