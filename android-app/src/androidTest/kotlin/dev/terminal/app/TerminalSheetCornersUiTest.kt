package dev.terminal.app

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.DroidTheme
import dev.terminal.app.ui.TerminalChromeColors
import dev.terminal.app.ui.TerminalSheetBackdrop
import dev.terminal.app.ui.TerminalToolbar
import dev.terminal.emulator.TerminalSession
import dev.terminal.view.TerminalView
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalSheetCornersUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun cornersFollowAdjacentTuiCanvasesAcrossBothAxesFocusAndSheetSizes() {
        val stacked = mutableStateOf(true)
        val mode = mutableStateOf(ToolbarSheetMode.Default)
        val sheet = mutableStateOf(TerminalChromeColors(Color.Black))
        val backdrop = TerminalSheetBackdrop()
        val views = arrayOfNulls<TerminalView>(2)
        val transitions = arrayOfNulls<TerminalPaletteTransition>(2)
        val palettes = arrayOfNulls<TerminalChromeColors>(2)
        val backends = Array(2) { RemoteTerminalBackend(write = {}, resize = { _, _ -> }) }
        lateinit var sessions: Array<TerminalSession>
        lateinit var canvasHost: LinearLayout
        var focus = 0
        instrumentation.runOnMainSync {
            sessions = Array(2) { index -> TerminalSession("", "/", emptyArray(), emptyArray(), 1000,
                TerminalCallbacks {}, backends[index]) }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    DroidTheme {
                        Column(Modifier.fillMaxSize().background(sheet.value.background)) {
                            AndroidView(factory = { context ->
                                LinearLayout(context).apply {
                                    repeat(2) { index ->
                                        val terminal = TerminalView(context, null).apply {
                                            setTerminalViewClient(TerminalInputCallbacks())
                                            setTextSize(20)
                                            attachSession(sessions[index])
                                        }
                                        views[index] = terminal
                                        transitions[index] = TerminalPaletteTransition(followTuiBackground = { true }) { colors ->
                                            backdrop.update(terminal, colors.background)
                                            palettes[index] = colors
                                            if (focus == index) sheet.value = colors
                                        }
                                        addView(terminal)
                                    }
                                }.also { canvasHost = it }
                            }, update = { host ->
                                host.orientation = if (stacked.value) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                                repeat(2) { index ->
                                    host.getChildAt(index).layoutParams = LinearLayout.LayoutParams(
                                        if (stacked.value) LinearLayout.LayoutParams.MATCH_PARENT else 0,
                                        if (stacked.value) 0 else LinearLayout.LayoutParams.MATCH_PARENT,
                                        if (index == 0) 0.35f else 0.65f)
                                }
                            }, modifier = Modifier.weight(1f).fillMaxWidth())
                            TerminalToolbar(ToolbarConfig(tools = listOf(HOTKEY_TOOL)), mode.value,
                                {}, 400.dp, sheet.value, animateModeChanges = false,
                                cornerColors = backdrop.colors(sheet.value.background)) { _, _ -> }
                        }
                    }
                } }
                await("Both terminal canvases are placed") {
                    var ready = false
                    scenario.onActivity { ready = views.all { it != null && it.width > 0 && it.height > 0 } }
                    ready
                }
                fun background(index: Int, rgb: String) {
                    scenario.onActivity {
                        val bytes = "\u001b[?1049h\u001b[48;2;${rgb}m\u001b[2J".toByteArray()
                        sessions[index].emulator.append(bytes, bytes.size)
                        requireNotNull(transitions[index]).show(requireNotNull(views[index]), animate = false)
                    }
                }
                val first = Color(0xFF301B26)
                var second = Color(0xFF163025)
                background(0, "48;27;38")
                background(1, "22;48;37")
                for (vertical in listOf(true, false)) for (selected in 0..1) {
                    for (size in listOf(ToolbarSheetMode.Default, ToolbarSheetMode.Expanded, ToolbarSheetMode.Hidden)) {
                        scenario.onActivity {
                            stacked.value = vertical
                            focus = selected
                            sheet.value = requireNotNull(palettes[selected])
                            mode.value = size
                        }
                        assertCorners(canvasHost, if (vertical) second else first, second, sheet.value.sheetBackground)
                    }
                }
                // A non-focused pane can change its TUI palette without changing sheet content colors.
                scenario.onActivity { focus = 0; sheet.value = requireNotNull(palettes[0]); stacked.value = true }
                second = Color(0xFF205080)
                background(1, "32;80;128")
                assertCorners(canvasHost, second, second, requireNotNull(palettes[0]).sheetBackground)
                scenario.onActivity { stacked.value = false }
                assertCorners(canvasHost, first, second, requireNotNull(palettes[0]).sheetBackground)
            }
        } finally {
            instrumentation.runOnMainSync {
                transitions.forEach { it?.cancel() }
                views.forEach { it?.attachSession(null) }
            }
            backends.forEach { it.close() }
        }
    }

    private fun await(message: String, details: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue(message + details(), condition())
    }

    private fun assertCorners(host: View, left: Color, right: Color, sheet: Color) {
        fun matches(pixel: Int, color: Color) = listOf(0, 8, 16).all { shift ->
            abs(((pixel shr shift) and 255) - ((color.toArgb() shr shift) and 255)) <= 5
        }
        var pixels = emptyList<Int>()
        await("Sheet corners must continue adjacent canvases: $left / $right",
            details = { "; pixels=${pixels.map { it.toUInt().toString(16) }}" }) {
            instrumentation.waitForIdleSync()
            val bounds = Rect()
            var visible = false
            instrumentation.runOnMainSync { visible = host.getGlobalVisibleRect(bounds) }
            if (!visible) return@await false
            // Use the canvas/sheet layout boundary, independent of expanded accessibility touch bounds.
            val sheetTop = bounds.bottom
            val handleCenter = sheetTop + (12 * host.resources.displayMetrics.density).roundToInt()
            val image = instrumentation.uiAutomation.takeScreenshot() ?: return@await false
            try {
                pixels = listOf(image.getPixel(bounds.left + 1, sheetTop + 1),
                    image.getPixel(bounds.right - 2, sheetTop + 1),
                    image.getPixel(bounds.left + bounds.width() / 4, handleCenter),
                    image.getPixel(bounds.left + 1, sheetTop - 1),
                    image.getPixel(bounds.right - 2, sheetTop - 1))
                matches(pixels[0], left) && matches(pixels[1], right) && matches(pixels[2], sheet) &&
                    matches(pixels[3], left) && matches(pixels[4], right)
            } finally { image.recycle() }
        }
    }
}
