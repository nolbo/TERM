package dev.terminal.app

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import org.junit.Assert.*
import org.junit.Test

class TerminalLinksTest {
    @Test fun confirmedTapOpensButDoubleTapScrollAndDisabledDoNot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var view: TerminalView
        lateinit var backend: RemoteTerminalBackend
        var enabled = true
        val opened = mutableListOf<String>()
        var doubles = 0
        var keyboard = 0
        var start = 0L
        fun event(action: Int, offset: Long, y: Float = 15f) {
            val e = MotionEvent.obtain(start, start + offset, action, 40f, y, 0)
            try { view.onTouchEvent(e) } finally { e.recycle() }
        }
        fun gesture(block: () -> Unit) {
            instrumentation.runOnMainSync { start = SystemClock.uptimeMillis(); block() }
            SystemClock.sleep(500)
            instrumentation.waitForIdleSync()
        }
        instrumentation.runOnMainSync {
            backend = RemoteTerminalBackend(write = {}, resize = { _, _ -> })
            val session = TerminalSession("", "", emptyArray(), emptyArray(), 100, TerminalCallbacks {}, backend)
            view = TerminalView(instrumentation.targetContext, null)
            view.setTerminalViewClient(TerminalInputCallbacks(
                onShowKeyboard = { keyboard++ },
                onWebLink = { if (enabled) opened.add(it) else false },
                onToggleTerminalChrome = { doubles++ },
            ))
            view.setTextSize(20)
            view.layout(0, 0, 800, 400)
            view.attachSession(session)
            val bytes = "https://example.com/path".toByteArray()
            session.emulator.append(bytes, bytes.size)
            assertEquals("https://example.com/path", view.getWebLinkAt(40f, 15f))
        }
        try {
            gesture { event(MotionEvent.ACTION_DOWN, 0); event(MotionEvent.ACTION_UP, 40) }
            assertEquals(listOf("https://example.com/path"), opened)
            assertEquals(0, keyboard)
            opened.clear()
            gesture {
                event(MotionEvent.ACTION_DOWN, 0); event(MotionEvent.ACTION_UP, 40)
                event(MotionEvent.ACTION_DOWN, 150); event(MotionEvent.ACTION_UP, 190)
            }
            assertEquals(1, doubles)
            assertTrue(opened.isEmpty())
            gesture {
                event(MotionEvent.ACTION_DOWN, 0)
                event(MotionEvent.ACTION_MOVE, 80, 180f)
                event(MotionEvent.ACTION_UP, 140, 200f)
            }
            assertTrue(opened.isEmpty())
            enabled = false
            gesture { event(MotionEvent.ACTION_DOWN, 0); event(MotionEvent.ACTION_UP, 40) }
            assertTrue(opened.isEmpty())
            assertEquals(1, keyboard)
        } finally { instrumentation.runOnMainSync { backend.close() } }
    }
}
