package dev.terminal.app

import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.emulator.TerminalSession
import dev.terminal.view.TerminalView
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalDoubleTapTest {
    @Test fun doubleTapHasNoActionAndSendsNoTerminalInput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val bytes = ByteArrayOutputStream()
            val backend = RemoteTerminalBackend(write = { bytes.write(it) }, resize = { _, _ -> })
            val callbacks = TerminalInputCallbacks()
            val session = TerminalSession("", "", emptyArray(), emptyArray(), 100, TerminalCallbacks {}, backend)
            val view = TerminalView(instrumentation.targetContext, null)
            view.setTerminalViewClient(callbacks)
            view.setTextSize(16)
            view.layout(0, 0, 800, 400)
            view.attachSession(session)
            try {
                val start = 1_000L
                fun dispatch(action: Int, time: Long) {
                    val event = MotionEvent.obtain(start, time, action, 400f, 200f, 0)
                    try {
                        view.onTouchEvent(event)
                    } finally {
                        event.recycle()
                    }
                }
                dispatch(MotionEvent.ACTION_DOWN, start)
                dispatch(MotionEvent.ACTION_UP, start + 40)
                dispatch(MotionEvent.ACTION_DOWN, start + 160)
                dispatch(MotionEvent.ACTION_UP, start + 200)

                val event = MotionEvent.obtain(start, start + 200, MotionEvent.ACTION_UP, 400f, 200f, 0)
                try { org.junit.Assert.assertFalse(callbacks.onDoubleTap(event)) } finally { event.recycle() }
                assertTrue(bytes.size() == 0)
            } finally {
                backend.close()
            }
        }
    }
}
