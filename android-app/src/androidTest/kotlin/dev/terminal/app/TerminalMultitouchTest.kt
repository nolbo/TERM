package dev.terminal.app

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ActivityScenario
import dev.terminal.app.ui.DroidTheme
import dev.terminal.app.ui.TerminalToolbar
import dev.terminal.app.ui.ToolbarHotkeys
import dev.terminal.app.ui.TerminalChromeColors
import androidx.compose.ui.unit.dp
import dev.terminal.app.ui.TerminalHapticFeedbackProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalMultitouchTest {
    @Test fun twoFingerTranslationDoesNotLeakInputOrChangeScale() = withTerminal { view, scales, bytes ->
        gesture(view, 0f, 180f)
        gesture(view, 0f, -180f)
        gesture(view, 180f, 0f)
        assertTrue(scales.all { kotlin.math.abs(it - 1f) < 0.001f })
        assertEquals(0, bytes.size())
    }

    @Test fun pinchStillScalesAndMultitouchTailDoesNotSendMouseInput() = withTerminal { view, scales, bytes ->
        gesture(view, 0f, 10f, cancel = true)
        gesture(view, 0f, 180f, thirdFinger = true)
        gesture(view, 0f, 0f, pinch = true)
        assertTrue(scales.any { kotlin.math.abs(it - 1f) > 0.01f })
        assertEquals(0, bytes.size())
    }

    @Test fun terminalSwipesLeaveEveryToolbarStateAndHeightUnchanged() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mode = mutableStateOf(ToolbarSheetMode.Default)
        lateinit var terminal: TerminalView
        lateinit var backend: RemoteTerminalBackend
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                backend = RemoteTerminalBackend(write = {}, resize = { _, _ -> })
                val session = TerminalSession("", "", emptyArray(), emptyArray(), 100, TerminalCallbacks {}, backend)
                activity.setContent {
                    DroidTheme { TerminalHapticFeedbackProvider {
                        Column(Modifier.fillMaxSize()) {
                            AndroidView(factory = { context ->
                                TerminalView(context, null).apply {
                                    terminal = this
                                    setTerminalViewClient(TerminalInputCallbacks())
                                    setTextSize(16)
                                    attachSession(session)
                                }
                            }, modifier = Modifier.weight(1f).fillMaxWidth())
                            TerminalToolbar(config = ToolbarConfig(), mode = mode.value,
                                onModeChange = { mode.value = it },
                                maxHeight = 400.dp, colors = TerminalChromeColors(Color.Black)) { _, interactive ->
                                ToolbarHotkeys(HotkeyLayout(), false, false, false, TerminalChromeColors(Color.Black), interactive, {})
                            }
                        }
                    } }
                }
            }
            fun settle() { SystemClock.sleep(1000); instrumentation.waitForIdleSync() }
            try {
                for (expected in ToolbarSheetMode.entries) {
                    scenario.onActivity { mode.value = expected }
                    settle()
                    var height = 0
                    scenario.onActivity {
                        height = terminal.height
                        gesture(terminal, 0f, 180f)
                        gesture(terminal, 0f, -180f)
                    }
                    settle()
                    scenario.onActivity {
                        assertEquals(expected, mode.value)
                        assertEquals(height, terminal.height)
                    }
                }
            } finally { scenario.onActivity { backend.close() } }
        }
    }

    private fun withTerminal(block: (TerminalView, MutableList<Float>, ByteArrayOutputStream) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val bytes = ByteArrayOutputStream()
            val backend = RemoteTerminalBackend(write = { bytes.write(it) }, resize = { _, _ -> })
            val scales = mutableListOf<Float>()
            val session = TerminalSession("", "", emptyArray(), emptyArray(), 100, TerminalCallbacks {}, backend)
            val view = TerminalView(instrumentation.targetContext, null)
            view.setTerminalViewClient(TerminalInputCallbacks(
                onPinchScale = { scales += it; 1f },
            ))
            view.setTextSize(16)
            view.layout(0, 0, 1000, 1000)
            view.attachSession(session)
            // Mouse-reporting programs must not receive a click on the final finger lift.
            session.emulator.append("\u001b[?1000h".toByteArray(), 8)
            try { block(view, scales, bytes) } finally { backend.close() }
        }
    }

    private fun gesture(view: TerminalView, dx: Float, dy: Float, cancel: Boolean = false,
                        pinch: Boolean = false, thirdFinger: Boolean = false) {
        val start = SystemClock.uptimeMillis()
        var tick = 0L
        val density = view.resources.displayMetrics.density
        fun dispatch(action: Int, count: Int, fraction: Float) {
            val properties = Array(count) { index -> MotionEvent.PointerProperties().apply {
                id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
            } }
            val coordinates = Array(count) { index -> MotionEvent.PointerCoords().apply {
                x = 300f + index * 200f + dx * density * fraction +
                    (if (pinch) (if (index == 0) -150f else 150f) * density * fraction else 0f)
                y = 400f + dy * density * fraction
                pressure = 1f; size = 1f
            } }
            val event = MotionEvent.obtain(start, start + tick, action, count, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { view.onTouchEvent(event) } finally { event.recycle() }
            tick += 20
        }
        dispatch(MotionEvent.ACTION_DOWN, 1, 0f)
        dispatch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 0f)
        if (thirdFinger) dispatch(MotionEvent.ACTION_POINTER_DOWN or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 3, 0f)
        for (step in 1..10) dispatch(MotionEvent.ACTION_MOVE, if (thirdFinger) 3 else 2, step / 10f)
        if (cancel) {
            dispatch(MotionEvent.ACTION_CANCEL, 2, 1f)
        } else {
            if (thirdFinger) dispatch(MotionEvent.ACTION_POINTER_UP or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 3, 1f)
            dispatch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 1f)
            dispatch(MotionEvent.ACTION_MOVE, 1, 1f)
            dispatch(MotionEvent.ACTION_UP, 1, 1f)
        }
    }
}
