package dev.terminal.app

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.DroidTheme
import dev.terminal.app.ui.TerminalChromeColors
import dev.terminal.app.ui.TerminalDarkColors
import dev.terminal.app.ui.TerminalHapticFeedbackProvider
import dev.terminal.app.ui.TerminalToolbar
import dev.terminal.app.ui.TerminalToolbarKey
import dev.terminal.app.ui.ToolbarHotkeys
import dev.terminal.app.ui.terminalDynamicColors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalDynamicColorsUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun hotkeysUseDynamicRolesAndReactToThemeAndCheckedState() {
        val background = mutableStateOf(Color(0xFF301B26))
        val dynamic = mutableStateOf(true)
        val control = mutableStateOf(false)
        val outsideScheme = AtomicReference<ColorScheme>()
        val toolbarScheme = AtomicReference<ColorScheme>()
        val layout = HotkeyLayout(columns = 2, keys = listOf(Hotkey("CTRL", "CTRL"), Hotkey("A", "a")))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DroidTheme { TerminalHapticFeedbackProvider {
                        val outer = MaterialTheme.colorScheme
                        SideEffect { outsideScheme.set(outer) }
                        val colors = if (dynamic.value) terminalDynamicColors(background.value)
                            else TerminalChromeColors(background.value)
                        Column(Modifier.fillMaxSize().background(colors.background)) {
                            Spacer(Modifier.weight(1f))
                            TerminalToolbar(ToolbarConfig(tools = listOf(HOTKEY_TOOL)),
                                ToolbarSheetMode.Default, {}, 400.dp, colors,
                                animateModeChanges = false) { _, active ->
                                val inner = MaterialTheme.colorScheme
                                SideEffect { toolbarScheme.set(inner) }
                                ToolbarHotkeys(layout, control.value, false, false, colors, active) { key ->
                                    if (key == TerminalToolbarKey.Control) control.value = !control.value
                                }
                            }
                        }
                    } }
                }
            }
            val warm = terminalDynamicColors(background.value)
            assertButtonColor("CTRL: CTRL", warm.buttonContainer)
            assertButtonColor("A: a", warm.buttonContainer)
            assertEquals(warm.colorScheme?.primary, toolbarScheme.get().primary)
            assertEquals(TerminalDarkColors, outsideScheme.get())

            assertTrue(button("CTRL: CTRL").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertButtonColor("CTRL: CTRL", warm.checkedContainer)
            assertTrue(button("CTRL: CTRL").isChecked)

            scenario.onActivity { background.value = Color(0xFF163025) }
            val cool = terminalDynamicColors(Color(0xFF163025))
            assertButtonColor("A: a", cool.buttonContainer)
            assertButtonColor("CTRL: CTRL", cool.checkedContainer)
            assertEquals(cool.colorScheme?.primary, toolbarScheme.get().primary)
            assertNotEquals(warm.buttonContainer, cool.buttonContainer)
            assertEquals(TerminalDarkColors, outsideScheme.get())

            scenario.onActivity { dynamic.value = false }
            val legacy = TerminalChromeColors(Color(0xFF163025))
            assertButtonColor("A: a", legacy.buttonContainer)
            assertButtonColor("CTRL: CTRL", legacy.checkedContainer)
            assertEquals(TerminalDarkColors, toolbarScheme.get())
            assertEquals(TerminalDarkColors, outsideScheme.get())
        }
    }

    private fun button(description: String): AccessibilityNodeInfo {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.contentDescription?.toString() == description && node.isVisibleToUser) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.clearCache()
            var node = find(instrumentation.uiAutomation.rootInActiveWindow)
            while (node != null && !node.isClickable) node = node.parent
            if (node != null) return node
            SystemClock.sleep(100)
        }
        error("Hotkey $description was not visible")
    }

    private fun assertButtonColor(description: String, expected: Color) {
        val bounds = Rect()
        val target = expected.toArgb()
        val deadline = SystemClock.elapsedRealtime() + 5000
        var actual = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            button(description).getBoundsInScreen(bounds)
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                // Sample above the label and away from rounded edges, after color/morph motion.
                actual = screenshot.getPixel(bounds.centerX(), bounds.top + bounds.height() / 4)
            } finally {
                screenshot.recycle()
            }
            if (listOf(0, 8, 16).all { shift ->
                abs(((actual shr shift) and 255) - ((target shr shift) and 255)) <= 2
            }) return
            SystemClock.sleep(100)
        }
        assertEquals("Rendered $description container", target, actual)
    }
}
