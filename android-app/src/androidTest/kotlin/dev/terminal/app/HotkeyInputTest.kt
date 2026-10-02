package dev.terminal.app

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
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
    @Test fun standaloneShiftAppliesToNextToolbarHardwareOrImeInputAndThenClears() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bytes = ByteArrayOutputStream()
        val backend = RemoteTerminalBackend(write = { bytes.write(it) }, resize = { _, _ -> })
        val callbacks = TerminalInputCallbacks {}
        lateinit var view: TerminalView
        lateinit var session: TerminalSession
        instrumentation.runOnMainSync {
            session = TerminalSession("", "", emptyArray(), emptyArray(), 100, TerminalCallbacks {}, backend)
            view = TerminalView(instrumentation.targetContext, null)
            view.setTerminalViewClient(callbacks)
            view.setTextSize(16)
            view.layout(0, 0, 800, 400)
            view.attachSession(session)
        }
        try {
            fun expect(expected: String, control: Boolean = false, alt: Boolean = false, input: () -> Unit) {
                instrumentation.runOnMainSync {
                    bytes.reset()
                    callbacks.shiftPressed = true
                    callbacks.controlPressed = control
                    callbacks.altPressed = alt
                    input()
                    assertArrayEquals(expected.toByteArray(), bytes.toByteArray())
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    assertFalse(callbacks.shiftPressed)
                    assertFalse(callbacks.controlPressed)
                    assertFalse(callbacks.altPressed)
                    bytes.reset()
                    dispatchHotkeyShortcut(view, callbacks, "C")
                    assertEquals("c", bytes.toString("UTF-8"))
                }
            }
            expect("C") { dispatchHotkeyShortcut(view, callbacks, "C") }
            expect("!") { dispatchHotkeyShortcut(view, callbacks, "1") }
            expect("\u001b[Z") { dispatchHotkeyShortcut(view, callbacks, "TAB") }
            expect("\u001b[1;8D", control = true, alt = true) { dispatchHotkeyShortcut(view, callbacks, "LEFT") }
            expect("\u001bb") { dispatchHotkeyShortcut(view, callbacks, "ALT+B") }
            expect("\u0003") { dispatchHotkeyShortcut(view, callbacks, "CTRL+C") }
            expect("!") { view.onKeyDown(KeyEvent.KEYCODE_1, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1)) }
            expect("\u001b[Z") { view.onKeyDown(KeyEvent.KEYCODE_TAB, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB)) }
            expect("A") { view.onCreateInputConnection(EditorInfo()).commitText("a", 1) }
            // Negotiated IME text must also honor SHIFT rather than bypass modifier handling.
            instrumentation.runOnMainSync {
                val mode = "\u001b[>1u".toByteArray()
                session.emulator.append(mode, mode.size)
            }
            expect("A") { view.onCreateInputConnection(EditorInfo()).commitText("a", 1) }
        } finally {
            instrumentation.runOnMainSync { backend.close() }
        }
    }

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
