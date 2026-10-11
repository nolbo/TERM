package dev.terminal.app

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.emulator.TerminalSession
import dev.terminal.view.TerminalView
import org.junit.Assert.*
import org.junit.Test

class TerminalLinksTest {
    @Test fun confirmedTapOpensButDoubleTapScrollAndDisabledDoNot() = checkGestures(
        "https://example.com/path", "https://example.com/path",
    )

    @Test fun osc8TapUsesDestinationButDoubleTapScrollDisabledMouseAndSelectionDoNot() = checkGestures(
        "\u001b]8;id=tap;https://actual.example/destination\u001b\\https://example.com/path\u001b]8;;\u0007",
        "https://actual.example/destination",
    )

    private fun checkGestures(text: String, expected: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var view: TerminalView
        lateinit var backend: RemoteTerminalBackend
        var enabled = true
        val opened = mutableListOf<String>()
        val input = StringBuilder()
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
            backend = RemoteTerminalBackend(write = { input.append(it.toString(Charsets.UTF_8)) }, resize = { _, _ -> })
            val session = TerminalSession("", "", emptyArray(), emptyArray(), 100, TerminalCallbacks {}, backend)
            view = TerminalView(instrumentation.targetContext, null)
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            view.setTerminalViewClient(TerminalInputCallbacks(
                onShowKeyboard = { keyboard++ },
                onWebLink = { if (enabled && terminalLinkIntent(it) != null) opened.add(it) else false },
            ))
            view.setTextSize(20)
            view.setSmoothScrollingEnabled(true)
            view.layout(0, 0, 800, 400)
            view.attachSession(session)
            val bytes = text.toByteArray()
            session.emulator.append(bytes, bytes.size)
            assertEquals("https://example.com/path", view.getWebLinkAt(40f, 15f))
            assertEquals(expected, view.getLinkAt(40f, 15f))
        }
        try {
            gesture { event(MotionEvent.ACTION_DOWN, 0); event(MotionEvent.ACTION_UP, 40) }
            assertEquals(listOf(expected), opened)
            assertEquals(0, keyboard)
            opened.clear()
            gesture {
                event(MotionEvent.ACTION_DOWN, 0); event(MotionEvent.ACTION_UP, 40)
                event(MotionEvent.ACTION_DOWN, 150); event(MotionEvent.ACTION_UP, 190)
            }
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
            enabled = true
            instrumentation.runOnMainSync {
                val bytes = "\u001b[?1000h\u001b[?1006h".toByteArray()
                view.currentSession.emulator.append(bytes, bytes.size)
                input.clear()
            }
            gesture { event(MotionEvent.ACTION_DOWN, 0); event(MotionEvent.ACTION_UP, 40) }
            assertTrue(opened.isEmpty())
            assertTrue(input.toString().contains("\u001b[<0;"))
            instrumentation.runOnMainSync {
                val bytes = "\u001b[?1000l".toByteArray()
                view.currentSession.emulator.append(bytes, bytes.size)
                val e = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 40f, 15f, 0)
                try { view.startTextSelectionMode(e) } finally { e.recycle() }
                assertTrue(view.isSelectingText)
            }
            // Selection intentionally ignores dismissal during the first 300 ms.
            SystemClock.sleep(350)
            gesture { event(MotionEvent.ACTION_DOWN, 0); event(MotionEvent.ACTION_UP, 40) }
            assertTrue(opened.isEmpty())
            assertFalse(view.isSelectingText)
        } finally { instrumentation.runOnMainSync { backend.close() } }
    }

    @Test fun schemeValidationUsesBrowsableViewIntentForWebDestinationsOnly() {
        for (url in listOf("http://example.com", "https://example.com/a;b?q=x", "HTTPS://example.com/한")) {
            val intent = terminalLinkIntent(url)
            assertNotNull(url, intent)
            assertEquals(Intent.ACTION_VIEW, intent!!.action)
            assertEquals(url, intent.data.toString())
            assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
            assertNull(intent.component)
        }
        for (url in listOf("file:///tmp/a", "content://example/a", "intent://example/#Intent;scheme=https;end",
            "javascript:alert(1)", "mailto:a@example.com", "ftp://example.com", "//example.com", "https:///path",
            "https:", "https://", "https://example.com/\npath", "https://example.com/ bad")) {
            assertNull(url, terminalLinkIntent(url))
        }
    }

    @Test fun explicitMetadataWinsAndRegexIsUsedOnlyForUnlinkedCells() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val backend = RemoteTerminalBackend(write = {}, resize = { _, _ -> })
            try {
                val session = TerminalSession("", "", emptyArray(), emptyArray(), 100, TerminalCallbacks {}, backend)
                val view = TerminalView(instrumentation.targetContext, null).apply {
                    setTerminalViewClient(TerminalInputCallbacks())
                    setTextSize(20)
                    layout(0, 0, 800, 400)
                    attachSession(session)
                }
                fun append(text: String) { val bytes = text.toByteArray(); session.emulator.append(bytes, bytes.size) }
                for (destination in listOf("https://actual.example", "file:///private", "content://private/a", "intent://private")) {
                    append("\u001b[H\u001b[2J\u001b]8;id=test;$destination\u0007https://label.example\u001b]8;;\u001b\\ https://fallback.example.")
                    assertEquals("https://label.example", view.getWebLinkAt(40f, 15f))
                    assertEquals(destination, view.getLinkAt(40f, 15f))
                    assertEquals("https://fallback.example", view.getLinkAt(view.getPointX(23) + 1f, 15f))
                    if (!destination.startsWith("https:")) assertNull(terminalLinkIntent(view.getLinkAt(40f, 15f)))
                }
                // Non-URL labels, wide cells, viewport insets, and visible scrollback use cell metadata.
                append("\u001b[H\u001b[2J\u001b]8;;https://actual.example\u001b\\한😀 label\u001b]8;;\u0007")
                assertNull(view.getWebLinkAt(40f, 15f))
                assertEquals("https://actual.example", view.getLinkAt(40f, 15f))
                val rows = session.emulator.mRows
                repeat(rows) { append("\r\nline") }
                view.setTopRow(-1)
                assertEquals("https://actual.example", view.getLinkAt(40f, 15f))
                view.setScrollbackTopInset(63)
                assertNull(view.getLinkAt(40f, 15f))
                assertEquals("https://actual.example", view.getLinkAt(40f, 78f))
                assertNull(view.getLinkAt(-1f, 78f))
                assertNull(view.getLinkAt(900f, 78f))
            } finally { backend.close() }
        }
    }
}
