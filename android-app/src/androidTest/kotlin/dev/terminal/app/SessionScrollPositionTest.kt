package dev.terminal.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.emulator.TerminalSession
import dev.terminal.view.TerminalView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionScrollPositionTest {
    @Test fun petRefreshesPreserveHistoryAndNewLinesAnchorTheSameText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = TerminalView(instrumentation.targetContext, null).apply {
                setTerminalViewClient(TerminalInputCallbacks()); setTextSize(20); layout(0, 0, 800, 600)
            }
            val backend = RemoteTerminalBackend(write = {}, resize = { _, _ -> })
            val session = TerminalSession("", "/", emptyArray(), emptyArray(), 1000, TerminalCallbacks {}, backend)
            view.attachSession(session)
            fun append(text: String) { val bytes = text.toByteArray(); session.emulator.append(bytes, bytes.size); view.onScreenUpdated() }
            fun firstLine() = session.emulator.screen.getSelectedText(0, view.topRow, 20, view.topRow)
            try {
                append((1..200).joinToString("\r\n") { "line-$it" })
                view.topRow = -40
                val original = firstLine()
                repeat(50) {
                    append("\u001b[?2026h\u001b_Ga=d,d=I,i=1,q=2;\u001b\\")
                    append("\u001b_Ga=T,i=1,q=2,f=24,s=1,v=1,C=1;/wAA\u001b\\\u001b[?2026l")
                    assertEquals(-40, view.topRow)
                    assertEquals(original, firstLine())
                }
                append("\r\nnew-1\r\nnew-2\r\nnew-3")
                assertEquals(-43, view.topRow)
                assertEquals(original, firstLine())
                view.topRow = 0
                append("\r\nfollow-latest")
                assertEquals(0, view.topRow)
                view.topRow = -20
                view.inputCodePoint('x'.code, false, false)
                assertEquals(0, view.topRow)
                view.topRow = -30
                append((1..1200).joinToString("\r\n") { "overflow-$it" })
                assertEquals(-session.emulator.screen.activeTranscriptRows, view.topRow)
                append("\u001b[3J")
                assertEquals(0, view.topRow)
            } finally { backend.close() }
        }
    }

    @Test fun backgroundOutputAdvancesSavedAnchorWithoutPullingTabToBottom() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = TerminalView(instrumentation.targetContext, null).apply {
                setTerminalViewClient(TerminalInputCallbacks()); setTextSize(20); layout(0, 0, 800, 600)
            }
            fun session() = TerminalSession("", "/", emptyArray(), emptyArray(), 1000, TerminalCallbacks {},
                RemoteTerminalBackend(write = {}, resize = { _, _ -> }))
            val first = session(); val second = session()
            view.attachSession(first)
            val initial = (1..200).joinToString("\r\n") { "row-$it" }.toByteArray()
            first.emulator.append(initial, initial.size)
            view.onScreenUpdated(); view.topRow = -40
            val text = first.emulator.screen.getSelectedText(0, -40, 20, -40)
            view.attachSession(second)
            val update = "\r\nbg-1\r\nbg-2".toByteArray()
            first.emulator.append(update, update.size)
            view.attachSession(first)
            assertEquals(-42, view.topRow)
            assertEquals(text, first.emulator.screen.getSelectedText(0, view.topRow, 20, view.topRow))
            view.onScreenUpdated()
            assertEquals(-42, view.topRow)
        }
    }

    @Test fun tabRoundTripsKeepIndependentPositionsIncludingAfterLayoutChange() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = TerminalView(instrumentation.targetContext, null).apply {
                setTerminalViewClient(TerminalInputCallbacks())
                setTextSize(20)
                layout(0, 0, 800, 600)
            }
            fun session(): TerminalSession = TerminalSession(
                "", "/", emptyArray(), emptyArray(), 1000, TerminalCallbacks {},
                RemoteTerminalBackend(write = {}, resize = { _, _ -> }),
            )
            fun fill(session: TerminalSession) {
                val output = (1..200).joinToString("\r\n") { "line-$it" }.toByteArray()
                session.emulator.append(output, output.size)
            }
            val first = session()
            val second = session()
            view.attachSession(first)
            fill(first)
            view.topRow = -40
            val firstText = first.emulator.screen.getSelectedText(0, -40, 30, -40)
            view.attachSession(second)
            assertEquals(0, view.topRow)
            fill(second)
            view.topRow = -15
            val secondText = second.emulator.screen.getSelectedText(0, -15, 30, -15)
            repeat(3) {
                view.attachSession(first)
                assertEquals(-40, view.topRow)
                view.attachSession(second)
                assertEquals(-15, view.topRow)
            }
            view.layout(0, 0, 800, 500)
            view.updateSize()
            assertEquals(secondText, second.emulator.screen.getSelectedText(0, view.topRow, 30, view.topRow))
            view.attachSession(first)
            assertEquals(firstText, first.emulator.screen.getSelectedText(0, view.topRow, 30, view.topRow))
            view.attachSession(second)
            // A background session can lose history; never restore an invalid row.
            val clear = "\u001b[3J".toByteArray()
            first.emulator.append(clear, clear.size)
            view.attachSession(first)
            assertEquals(0, view.topRow)
            view.attachSession(second)
            assertEquals(secondText, second.emulator.screen.getSelectedText(0, view.topRow, 30, view.topRow))
        }
    }
}
