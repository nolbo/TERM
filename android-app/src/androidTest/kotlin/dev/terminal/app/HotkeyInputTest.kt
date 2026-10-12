package dev.terminal.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Captures terminal bytes with an in-memory backend; never sends commands to a live shell. */
@RunWith(AndroidJUnit4::class)
class HotkeyInputTest {
    @Test fun chordsSendExactTerminalBytesAndReplaceLatchedModifiers() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val bytes = ByteArrayOutputStream()
            val backend = RemoteTerminalBackend(write = { bytes.write(it) }, resize = { _, _ -> })
            val callbacks = TerminalInputCallbacks {}
            val session = TerminalSession("", "", emptyArray(), emptyArray(), 100, TerminalCallbacks {}, backend)
            val view = TerminalView(instrumentation.targetContext, null)
            view.setTerminalViewClient(callbacks)
            view.setTextSize(16)
            view.layout(0, 0, 800, 400)
            view.attachSession(session)
            try {
                fun expect(chord: String, expected: String) {
                    bytes.reset()
                    dispatchHotkeyShortcut(view, callbacks, chord)
                    assertArrayEquals(chord, expected.toByteArray(), bytes.toByteArray())
                }
                callbacks.altPressed = true
                expect("CTRL+C", "\u0003")
                assertFalse(callbacks.altPressed)
                expect("CTRL+D", "\u0004")
                expect("ALT+B", "\u001bb")
                expect("SHIFT+TAB", "\u001b[Z")
                expect("CTRL+UP", "\u001b[1;5A")
                expect("CTRL+ALT+LEFT", "\u001b[1;7D")
                expect("SHIFT+1", "!")
                expect("CTRL+SPACE", "\u0000")
                expect("ENTER", "\r")
                callbacks.controlPressed = true
                expect("C", "\u0003")
            } finally {
                backend.close()
            }
        }
    }
}
