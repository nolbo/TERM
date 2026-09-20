package dev.terminal.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Verify the bytes at the PTY backend boundary, without executing clipboard contents. */
@RunWith(AndroidJUnit4::class)
class PasteInputTest {
    @Test fun explicitPasteAndImeUseOneFrameWithOriginalNewlines() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val writes = mutableListOf<String>()
                val backend = RemoteTerminalBackend(write = { writes += it.toString(Charsets.UTF_8) }, resize = { _, _ -> })
                val terminal = TerminalSession("", "/", emptyArray(), emptyArray(), 100, TerminalCallbacks {}, backend)
                val view = TerminalView(activity, null).apply {
                    setTerminalViewClient(TerminalInputCallbacks())
                    setTextSize(20)
                    layout(0, 0, 1000, 800)
                    attachSession(terminal)
                }
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val previous = clipboard.primaryClip
                try {
                    for (bracketed in listOf(true, false)) {
                        val mode = "\u001b[?1049h\u001b[?2004${if (bracketed) "h" else "l"}".toByteArray()
                        terminal.emulator.append(mode, mode.size)
                        for (payload in listOf("echo one\\\r\n two", "echo first\necho last\n\n")) {
                            clipboard.setPrimaryClip(ClipData.newPlainText("paste-test", payload))
                            val connection = view.onCreateInputConnection(EditorInfo())
                            val actions = listOf<() -> Unit>(
                                { view.pasteText(payload) },
                                { view.pasteFromClipboard() },
                                { connection.commitText(payload, 1) },
                                { view.onKeyDown(KeyEvent.KEYCODE_UNKNOWN, KeyEvent(0, payload, 0, 0)) },
                                { connection.performContextMenuAction(android.R.id.paste) },
                                { connection.performContextMenuAction(android.R.id.pasteAsPlainText) },
                                { view.onKeyDown(KeyEvent.KEYCODE_PASTE, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PASTE)) },
                                { view.onKeyDown(KeyEvent.KEYCODE_V, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, 0,
                                    KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)) },
                                { view.onKeyDown(KeyEvent.KEYCODE_INSERT, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_INSERT, 0,
                                    KeyEvent.META_SHIFT_ON)) },
                            )
                            for (action in actions) {
                                writes.clear()
                                action()
                                val expected = if (bracketed) "\u001b[200~$payload\u001b[201~"
                                    else payload.replace("\r\n", "\r").replace("\n", "\r")
                                assertEquals(listOf(expected), writes)
                            }
                        }
                    }
                } finally {
                    if (previous == null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(previous)
                    backend.close()
                }
            }
        }
    }
}
