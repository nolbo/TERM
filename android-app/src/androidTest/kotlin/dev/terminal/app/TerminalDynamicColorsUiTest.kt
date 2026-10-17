package dev.terminal.app

import android.graphics.Bitmap
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
import dev.terminal.app.ui.ToolbarHistoryTool
import dev.terminal.app.ui.colorContrast
import dev.terminal.app.ui.terminalDynamicColors
import dev.terminal.app.ui.terminalSheetBackground
import dev.terminal.app.ui.WorkspaceSelector
import dev.terminal.runtime.workspace.Workspace
import dev.terminal.runtime.workspace.WorkspaceRegistry
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalDynamicColorsUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun workspaceMenuUsesTheTabHighlightAndUpdatesWhileOpen() {
        val background = mutableStateOf(Color(0xFF301B26))
        val workspace = Workspace("981e8b9d-cbed-4c75-9561-bdb44c12e668", "Color fixture", false)
        val state = WorkspaceRegistry.State(workspaces = listOf(workspace))
        var selected: String? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    val colors = terminalDynamicColors(background.value)
                    MaterialTheme(colorScheme = requireNotNull(colors.colorScheme)) {
                        Column(Modifier.fillMaxSize().background(colors.background)) {
                            Spacer(Modifier.weight(1f))
                            WorkspaceSelector(state, enabled = true, select = { selected = it },
                                create = {}, tint = colors.appBarIcon)
                        }
                    }
                } }
            } }
            val title = instrumentation.targetContext.getString(R.string.workspace_unassigned)
            val description = instrumentation.targetContext.getString(R.string.workspace_selector, title)
            assertTrue(button(description).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            for (sample in listOf(Color(0xFF301B26), Color(0xFF163025), Color(0xFF777879), Color(0xFFF0E0D0))) {
                scenario.onActivity { background.value = sample }
                assertMenuColor(title, terminalDynamicColors(sample).selectedTabContainer)
            }
            assertTrue(controlNode { it.text?.toString() == workspace.name }
                .performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            assertEquals(workspace.id, selected)
        }
    }

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
            assertButtonColor("CTRL: CTRL", warm.buttonContainer, warm.background)
            assertButtonColor("A: a", warm.buttonContainer, warm.background)
            assertEquals(warm.colorScheme?.primary, toolbarScheme.get().primary)
            assertEquals(TerminalDarkColors, outsideScheme.get())

            assertTrue(button("CTRL: CTRL").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertButtonColor("CTRL: CTRL", warm.checkedContainer, warm.background)
            assertTrue(button("CTRL: CTRL").isChecked)

            scenario.onActivity { background.value = Color(0xFF163025) }
            val cool = terminalDynamicColors(Color(0xFF163025))
            assertButtonColor("A: a", cool.buttonContainer, cool.background)
            assertButtonColor("CTRL: CTRL", cool.checkedContainer, cool.background)
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

    @Test fun searchFilterButtonsShareTheSurfacePaletteAndKeepScopeSelection() {
        val background = mutableStateOf(Color(0xFF301B26))
        val dynamic = mutableStateOf(true)
        val context = instrumentation.targetContext
        val scopeMenu = context.getString(R.string.history_scope_choose)
        var scopeTitle = context.getString(R.string.history_scope_commands)
        fun scopeDescription() = context.getString(R.string.history_scope, scopeTitle)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DroidTheme { TerminalHapticFeedbackProvider {
                        val colors = if (dynamic.value) terminalDynamicColors(background.value)
                            else TerminalChromeColors(background.value)
                        Column(Modifier.fillMaxSize().background(colors.background)) {
                            Spacer(Modifier.weight(1f))
                            TerminalToolbar(ToolbarConfig(tools = listOf(HISTORY_TOOL)),
                                ToolbarSheetMode.Default, {}, 400.dp, colors,
                                animateModeChanges = false) { _, active ->
                                ToolbarHistoryTool(active, onReturnToTerminal = {}, terminal = { null })
                            }
                        }
                    } }
                }
            }
            for ((index, sample) in listOf(Color(0xFF301B26), Color(0xFF163025), Color(0xFF3D3D3D),
                Color(0xFFF0E0D0)).withIndex()) {
                scenario.onActivity { background.value = sample }
                val colors = terminalDynamicColors(sample)
                assertButtonColor(scopeDescription(), colors.buttonContainer, sample)
                assertButtonColor(scopeMenu, colors.buttonContainer, sample)
                // Both halves still open the scope menu after changing the TUI palette.
                assertTrue(button(if (index % 2 == 0) scopeDescription() else scopeMenu)
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK))
                assertMenuColor(scopeTitle, colors.selectedTabContainer)
                val title = context.getString(if (index % 2 == 0) R.string.history_scope_output else R.string.history_scope_all)
                assertTrue(controlNode { it.text?.toString() == title }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                scopeTitle = title
                assertButtonColor(scopeDescription(), colors.buttonContainer, sample)
                assertButtonColor(scopeMenu, colors.buttonContainer, sample)
            }
            scenario.onActivity { dynamic.value = false }
            val legacy = TerminalChromeColors(background.value)
            assertButtonColor(scopeDescription(), legacy.buttonContainer)
            assertButtonColor(scopeMenu, legacy.buttonContainer)
        }
    }

    @Test fun sheetStaysDistinctWithAndWithoutDynamicColorsInEverySize() {
        val background = mutableStateOf(Color.Black)
        val dynamic = mutableStateOf(false)
        val mode = mutableStateOf(ToolbarSheetMode.Default)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    val colors = if (dynamic.value) terminalDynamicColors(background.value)
                        else TerminalChromeColors(background.value)
                    Column(Modifier.fillMaxSize().background(colors.background)) {
                        Spacer(Modifier.weight(1f))
                        TerminalToolbar(ToolbarConfig(tools = listOf(HOTKEY_TOOL)),
                            mode.value, {}, 400.dp, colors, animateModeChanges = false) { _, _ -> }
                    }
                } }
            } }
            for (sample in listOf(Color.Black, Color(0xFF301B26), Color(0xFF777777), Color(0xFFF0E0D0))) {
                for (enabled in listOf(false, true)) {
                    val colors = if (enabled) terminalDynamicColors(sample) else TerminalChromeColors(sample)
                    for (size in listOf(ToolbarSheetMode.Hidden, ToolbarSheetMode.Default, ToolbarSheetMode.Expanded)) {
                        scenario.onActivity { background.value = sample; dynamic.value = enabled; mode.value = size }
                        val label = instrumentation.targetContext.getString(when (size) {
                            ToolbarSheetMode.Hidden -> R.string.toolbar_hidden
                            ToolbarSheetMode.Default -> R.string.toolbar_default
                            ToolbarSheetMode.Expanded -> R.string.toolbar_expanded
                        })
                        assertSheetColor(colors, label)
                    }
                }
            }
        }
    }

    private fun button(description: String): AccessibilityNodeInfo =
        controlNode { it.contentDescription?.toString() == description }

    private fun assertMenuColor(title: String, expected: Color) {
        val bounds = Rect()
        val target = expected.toArgb()
        val deadline = SystemClock.elapsedRealtime() + 5000
        var actual = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            controlNode(requireClick = false) { it.text?.toString() == title }.getBoundsInScreen(bounds)
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                actual = screenshot.getPixel(bounds.centerX(), bounds.top + bounds.height() / 8)
            } finally { screenshot.recycle() }
            if (listOf(0, 8, 16).all { shift ->
                abs(((actual shr shift) and 255) - ((target shr shift) and 255)) <= 5
            }) return
            SystemClock.sleep(100)
        }
        assertEquals("Rendered selected menu row $title at $bounds", target, actual)
    }

    private fun controlNode(requireClick: Boolean = true, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (predicate(node) && node.isVisibleToUser) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.clearCache()
            var node = find(instrumentation.uiAutomation.rootInActiveWindow)
            // A selected Material menu row is checkable but deliberately has no click action.
            while (node != null && !node.isClickable && (requireClick || !node.isCheckable)) node = node.parent
            if (node != null) return node
            SystemClock.sleep(100)
        }
        val details = buildString {
            fun visit(node: AccessibilityNodeInfo?, depth: Int = 0) {
                if (node == null || length > 2500) return
                append("\n${" ".repeat(depth)}${node.text} / ${node.contentDescription}" +
                    " clickable=${node.isClickable} selected=${node.isSelected}")
                for (index in 0 until node.childCount) visit(node.getChild(index), depth + 1)
            }
            visit(instrumentation.uiAutomation.rootInActiveWindow)
        }
        instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
            try {
                instrumentation.targetContext.cacheDir.resolve("terminal-menu-color-failure.png")
                    .outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { screenshot.recycle() }
        }
        error("Required dynamic-color control was not visible:$details")
    }

    private fun assertButtonColor(description: String, expected: Color, background: Color? = null) {
        fun matches(actual: Int, target: Int) = listOf(0, 8, 16).all { shift ->
            // GPU color conversion shifts a channel by up to five levels on the device.
            // The contrast requirement still uses the rendered pixels without a tolerance.
            abs(((actual shr shift) and 255) - ((target shr shift) and 255)) <= 5
        }
        val bounds = Rect()
        val target = expected.toArgb()
        val deadline = SystemClock.elapsedRealtime() + 5000
        var actual = 0
        var canvas = 0
        var sheet = 0
        val handleBounds = Rect()
        while (SystemClock.elapsedRealtime() < deadline) {
            button(description).getBoundsInScreen(bounds)
            button(instrumentation.targetContext.getString(R.string.toolbar_handle)).getBoundsInScreen(handleBounds)
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                // Sample above the label and away from rounded edges, after color/morph motion.
                actual = screenshot.getPixel(bounds.centerX(), bounds.top + bounds.height() / 4)
                canvas = screenshot.getPixel(bounds.centerX(), screenshot.height / 2)
                sheet = screenshot.getPixel(handleBounds.left + handleBounds.width() / 4, handleBounds.centerY())
            } finally {
                screenshot.recycle()
            }
            if (matches(actual, target) && (background == null ||
                (matches(canvas, background.toArgb()) &&
                    matches(sheet, terminalSheetBackground(background).toArgb()) &&
                    colorContrast(Color(actual), Color(canvas)) >= 2f &&
                    colorContrast(Color(actual), Color(sheet)) >= 2f))) return
            SystemClock.sleep(100)
        }
        instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
            try {
                instrumentation.targetContext.cacheDir.resolve("terminal-controls-contrast-failure.png")
                    .outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                screenshot.recycle()
            }
        }
        assertEquals("Rendered $description container at $bounds, canvas ${canvas.toUInt().toString(16)}", target, actual)
        if (background != null) {
            assertTrue("Rendered TUI canvas", matches(canvas, background.toArgb()))
            assertTrue("Rendered $description blends into its canvas", colorContrast(Color(actual), Color(canvas)) >= 2f)
            assertTrue("Rendered $description blends into its sheet", colorContrast(Color(actual), Color(sheet)) >= 2f)
        }
    }

    private fun assertSheetColor(colors: TerminalChromeColors, modeLabel: String) {
        fun matches(actual: Int, expected: Color) = listOf(0, 8, 16).all { shift ->
            abs(((actual shr shift) and 255) - ((expected.toArgb() shr shift) and 255)) <= 4
        }
        val deadline = SystemClock.elapsedRealtime() + 5000
        val bounds = Rect()
        var actualSheet = 0
        var canvas = 0
        var corner = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val handle = button(instrumentation.targetContext.getString(R.string.toolbar_handle))
            handle.getBoundsInScreen(bounds)
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                val x = bounds.left + bounds.width() / 4
                actualSheet = screenshot.getPixel(x, bounds.centerY())
                canvas = screenshot.getPixel(x, bounds.top / 2)
                corner = screenshot.getPixel(bounds.left + 1, bounds.top + 1)
            } finally { screenshot.recycle() }
            if (handle.stateDescription?.toString() == modeLabel &&
                matches(actualSheet, colors.sheetBackground) && matches(canvas, colors.background) &&
                matches(corner, colors.background) && actualSheet != canvas) return
            SystemClock.sleep(100)
        }
        error("$modeLabel sheet ${actualSheet.toUInt().toString(16)}, canvas ${canvas.toUInt().toString(16)}, " +
            "corner ${corner.toUInt().toString(16)}; expected ${colors.sheetBackground}")
    }
}
