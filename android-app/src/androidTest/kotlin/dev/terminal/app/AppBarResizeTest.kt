package dev.terminal.app

import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import dev.terminal.app.ui.DroidTheme
import dev.terminal.app.ui.TerminalHapticFeedbackProvider
import dev.terminal.app.ui.TerminalToolbar
import dev.terminal.app.ui.TerminalChromeColors
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Double tapping terminal content must leave toolbar and terminal geometry unchanged. */
@RunWith(AndroidJUnit4::class)
class AppBarResizeTest {
    @Test fun doubleTapDoesNotChangeToolbarOrTerminalHeight() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val visible = mutableStateOf(true)
        lateinit var terminal: TerminalView
        lateinit var backend: RemoteTerminalBackend
        var initialHeight = 0
        var initialTop = 0
        var barHeight = 0
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                backend = RemoteTerminalBackend(write = {}, resize = { _, _ -> })
                val session = TerminalSession("", "", emptyArray(), emptyArray(), 100, TerminalCallbacks {}, backend)
                barHeight = (104 * activity.resources.displayMetrics.density).toInt()
                activity.setContent {
                    DroidTheme { TerminalHapticFeedbackProvider {
                        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                            AndroidView(factory = { context ->
                                TerminalView(context, null).apply {
                                    terminal = this
                                    setTerminalViewClient(TerminalInputCallbacks())
                                    setTextSize(16)
                                    attachSession(session)
                                }
                            }, modifier = Modifier.weight(1f).fillMaxWidth())
                            TerminalToolbar(ToolbarConfig().visibleConfig(),
                                if (visible.value) ToolbarSheetMode.Default else ToolbarSheetMode.Hidden,
                                { visible.value = it != ToolbarSheetMode.Hidden }, 400.dp,
                                TerminalChromeColors(Color.Black)) { _, _ -> Text("Session tool") }

                        }
                    } }
                }
            }
            fun settle() { SystemClock.sleep(1500); instrumentation.waitForIdleSync() }
            fun top(): Int = IntArray(2).also { terminal.getLocationOnScreen(it) }[1]
            fun doubleTap() {
                val start = SystemClock.uptimeMillis()
                listOf(0L to MotionEvent.ACTION_DOWN, 40L to MotionEvent.ACTION_UP,
                    160L to MotionEvent.ACTION_DOWN, 200L to MotionEvent.ACTION_UP).forEach { (offset, action) ->
                    val event = MotionEvent.obtain(start, start + offset, action, 200f, 200f, 0)
                    try { terminal.onTouchEvent(event) } finally { event.recycle() }
                }
            }
            try {
                settle()
                scenario.onActivity {
                    initialHeight = terminal.height
                    initialTop = top()
                    assertTrue(initialHeight > barHeight)
                    doubleTap()
                }
                settle()
                scenario.onActivity {
                    assertTrue(visible.value)
                    assertEquals(initialHeight, terminal.height)
                    assertEquals(initialTop, top())
                    assertTrue("System status bar inset remains", top() > 0)
                    doubleTap()
                }
                settle()
                scenario.onActivity {
                    assertTrue(visible.value)
                    assertEquals(initialHeight, terminal.height)
                    assertEquals(initialTop, top())
                }
            } finally { scenario.onActivity { backend.close() } }
        }
    }
}
