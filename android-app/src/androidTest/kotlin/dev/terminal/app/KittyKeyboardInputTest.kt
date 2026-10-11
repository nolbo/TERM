package dev.terminal.app

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.emulator.TerminalSession
import dev.terminal.view.TerminalView
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KittyKeyboardInputTest {
    @Test fun negotiatedHardwareReleaseImeAndHotkeysReturnToLegacyAfterPop() {
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
                fun mode(text: String) { val data = text.toByteArray(); session.emulator.append(data, data.size); bytes.reset() }
                mode("\u001b[>3u")
                dispatchHotkeyShortcut(view, callbacks, "CTRL+C")
                assertEquals("\u001b[99;5u\u001b[99;5:3u", bytes.toString("UTF-8"))
                bytes.reset()
                val down = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE, 0)
                view.onKeyDown(KeyEvent.KEYCODE_ESCAPE, down)
                view.onKeyUp(KeyEvent.KEYCODE_ESCAPE, KeyEvent.changeAction(down, KeyEvent.ACTION_UP))
                assertEquals("\u001b[27u\u001b[27;1:3u", bytes.toString("UTF-8"))
                mode("\u001b[=31u")
                view.onCreateInputConnection(EditorInfo()).commitText("한글", 1)
                assertEquals("\u001b[0;;54620:44544u", bytes.toString("UTF-8"))
                mode("\u001b[<u")
                dispatchHotkeyShortcut(view, callbacks, "CTRL+C")
                assertEquals("\u0003", bytes.toString("UTF-8"))
            } finally { backend.close() }
        }
    }
}
