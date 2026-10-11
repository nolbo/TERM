package dev.terminal.app

import android.view.InputDevice
import android.view.MotionEvent
import android.view.KeyEvent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.emulator.TerminalSession
import dev.terminal.view.TerminalView
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProgramScrollDeviceTest {
    @Test fun newlineModeAppliesToEnterControlMAndPasteThroughTheView() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val writes = ByteArrayOutputStream()
        val chunks = mutableListOf<ByteArray>()
        instrumentation.runOnMainSync {
            val view = TerminalView(instrumentation.targetContext, null).apply {
                setTerminalViewClient(TerminalInputCallbacks()); setTextSize(20); layout(0, 0, 800, 600)
            }
            val backend = RemoteTerminalBackend(write = { writes.write(it); chunks += it.copyOf() }, resize = { _, _ -> })
            val session = TerminalSession("", "/", emptyArray(), emptyArray(), 1000, TerminalCallbacks {}, backend)
            view.attachSession(session)
            try {
                val bytes = "\u001b[20h".toByteArray()
                session.emulator.append(bytes, bytes.size)
                assertTrue(view.handleKeyCode(KeyEvent.KEYCODE_ENTER, 0))
                view.inputCodePoint('m'.code, true, false)
                view.pasteText("one\ntwo")
                assertEquals("\r\n\r\none\r\ntwo", writes.toString("UTF-8"))
                assertEquals("Each input action must reach the transport in one write", 3, chunks.size)
            } finally { backend.close() }
        }
    }

    @Test fun authoritativeExitedScreenKeepsItsResizeSubscriptionUntilDetach() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val backend = RemoteTerminalBackend(write = {}, resize = { _, _ -> }, authoritative = true)
        lateinit var session: TerminalSession
        try {
            instrumentation.runOnMainSync {
                session = TerminalSession("", "/", emptyArray(), emptyArray(), 1000, TerminalCallbacks {}, backend)
                session.initializeEmulator(80, 24, 10, 20)
                backend.exit(7)
            }
            instrumentation.waitForIdleSync()
            assertFalse(backend.isRunning())
            assertTrue(session.emulator.screen.transcriptText.contains("Process completed (code 7)"))
            instrumentation.runOnMainSync { backend.resized(80, 30, 10, 20) }
            instrumentation.waitForIdleSync()
            assertEquals(30, session.emulator.mRows)
            backend.close()
            instrumentation.runOnMainSync { backend.resized(80, 32, 10, 20) }
            instrumentation.waitForIdleSync()
            assertEquals(30, session.emulator.mRows)
        } finally { backend.close() }
    }

    @Test fun wheelAndTouchRoutesRespectProgramModesAndFractionalWheelInput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val writes = ByteArrayOutputStream()
        instrumentation.runOnMainSync {
            val view = TerminalView(instrumentation.targetContext, null).apply {
                setTerminalViewClient(TerminalInputCallbacks()); setTextSize(20); layout(0, 0, 800, 600)
            }
            val backend = RemoteTerminalBackend(write = { writes.write(it) }, resize = { _, _ -> })
            val session = TerminalSession("", "/", emptyArray(), emptyArray(), 1000, TerminalCallbacks {}, backend)
            view.attachSession(session)
            fun append(text: String) {
                val bytes = text.toByteArray(); session.emulator.append(bytes, bytes.size); view.onScreenUpdated()
            }
            fun wheel(vertical: Float, horizontal: Float = 0f) {
                val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
                val coords = MotionEvent.PointerCoords().apply {
                    x = 40f; y = 120f
                    setAxisValue(MotionEvent.AXIS_VSCROLL, vertical); setAxisValue(MotionEvent.AXIS_HSCROLL, horizontal)
                }
                val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_SCROLL, 1, arrayOf(properties), arrayOf(coords),
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
                try { assertTrue(view.onGenericMotionEvent(event)) } finally { event.recycle() }
            }
            try {
                append((1..200).joinToString("\r\n") { "line-$it" })
                view.topRow = -20
                append("\u001b[?1003h\u001b[?1006h")
                assertEquals(0, view.topRow)
                wheel(0f, 1f)
                assertEquals(3, writes.toString("UTF-8").split("\u001b[<67;").size - 1)
                writes.reset()
                repeat(3) { wheel(0.125f) }
                assertEquals(1, writes.toString("UTF-8").split("\u001b[<64;").size - 1)
                writes.reset()
                wheel(-2f)
                assertEquals(5, writes.toString("UTF-8").split("\u001b[<65;").size - 1)
                writes.reset()
                val downTime = SystemClock.uptimeMillis()
                for ((index, step) in listOf(MotionEvent.ACTION_DOWN to 100f, MotionEvent.ACTION_MOVE to 180f,
                    MotionEvent.ACTION_MOVE to 320f, MotionEvent.ACTION_UP to 320f).withIndex()) {
                    val event = MotionEvent.obtain(downTime, downTime + index * 20L, step.first, 40f, step.second, 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { view.onTouchEvent(event) } finally { event.recycle() }
                }
                assertTrue("touch drag must reach program scrollback", "\u001b[<64;" in writes.toString("UTF-8"))
                writes.reset()
                append("\u001b[?1004h")
                view.onWindowFocusChanged(false)
                view.onWindowFocusChanged(true)
                assertEquals("\u001b[O\u001b[I", writes.toString("UTF-8"))
                writes.reset()
                append("\u001b[?1004l")
                append("\u001b[?1003l\u001b[?1049h\u001b[?1007l")
                wheel(1f)
                assertEquals(0, writes.size())
                append("\u001b[?1007h\u001b[?1h")
                wheel(1f)
                assertEquals("\u001bOA".repeat(3), writes.toString("UTF-8"))
            } finally { backend.close() }
        }
    }

    @Test fun mixedLiveReplayAndLiveFramesKeepOrderAndSuppressOnlyHistoricalQueries() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val writes = ByteArrayOutputStream()
        val backend = RemoteTerminalBackend(write = { writes.write(it) }, resize = { _, _ -> })
        lateinit var session: TerminalSession
        instrumentation.runOnMainSync {
            session = TerminalSession("", "/", emptyArray(), emptyArray(), 1000, TerminalCallbacks {}, backend)
            session.initializeEmulator(80, 24, 10, 20)
            // All arrive before the main looper drains any message. The old live queue
            // could consume the final frame before processing the intervening replay.
            backend.output("A".toByteArray())
            backend.output("\u001b[2J\u001b[HR\u001b[6n".toByteArray(), replay = true)
            backend.output("L\u001b[6n".toByteArray())
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals("RL", session.emulator.screen.getSelectedText(0, 0, 1, 0))
            assertEquals("\u001b[1;3R", writes.toString("UTF-8"))
        }
        backend.close()
    }
}
