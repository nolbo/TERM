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
import dev.terminal.app.ui.TerminalChrome
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real layout + gesture regression: hidden chrome must release its height. */
@RunWith(AndroidJUnit4::class)
class AppBarResizeTest {
    @Test fun doubleTapReleasesAndRestoresAppBarHeight() {
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
                barHeight = (56 * activity.resources.displayMetrics.density).toInt()
                activity.setContent {
                    DroidTheme { TerminalHapticFeedbackProvider {
                        Column(Modifier.fillMaxSize()) {
                            TerminalChrome(
                                sessions = emptyList(), activeSessionId = null,
                                appBarContentVisible = visible.value,
                                terminatingSessionIds = emptySet(), runtimeChoices = emptyList(),
                                newSessionMenuExpanded = false, onSelect = {}, onRename = {},
                                onTerminate = {}, onNew = {}, onDismissNewSession = {},
                                onSelectRuntime = {}, onSettings = {},
                            )
                            AndroidView(factory = { context ->
                                TerminalView(context, null).apply {
                                    terminal = this
                                    setTerminalViewClient(TerminalInputCallbacks(onToggleTerminalChrome = {
                                        visible.value = !visible.value
                                    }))
                                    setTextSize(16)
                                    attachSession(session)
                                }
                            }, modifier = Modifier.weight(1f).fillMaxWidth())
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
                    assertFalse(visible.value)
                    assertEquals(initialHeight + barHeight, terminal.height)
                    assertEquals(initialTop - barHeight, top())
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
