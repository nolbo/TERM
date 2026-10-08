package dev.terminal.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import com.termux.terminal.CommandMarker
import com.termux.terminal.TerminalCheckpoint
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class TerminalHistoryScrollbarTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var view: TerminalView
    private lateinit var backend: RemoteTerminalBackend
    private val output = ByteArrayOutputStream()

    @Test fun ordinaryScrollRevealsThinThumbWithLargeHitAreaAndDragTraversesHistory() = withTerminal {
        assertFalse(view.isHistoryScrollbarVisible)
        scroll(-3f)
        waitForVisible()
        main {
            render()
            val thumb = bounds("thumb")
            val dp = view.resources.displayMetrics.density
            assertEquals(6 * dp, thumb.width(), 0.1f)
            assertTrue(thumb.height() >= 32 * dp)
            view.setHistoryScrollbarSnapEnabled(false)
            // Start well to the left of the 6dp drawing, inside its 48dp touch target.
            val drag = Drag(view.width - 30 * dp, thumb.centerY())
            drag.move(-1000f)
            assertTrue(view.isHistoryScrollbarDragging)
            assertEquals(-view.mEmulator.screen.activeTranscriptRows, view.topRow)
            drag.move(10000f)
            assertEquals(0, view.topRow)
            drag.finish()
            assertFalse(view.isHistoryScrollbarDragging)
            assertEquals(0, output.size())
        }
    }

    @Test fun idleFadesTrackButKeepsLatestActionAvailable() = withTerminal {
        ActivityScenario.launch(AppInfoActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContentView(view) }
            instrumentation.waitForIdleSync()
            scroll(-3f)
            waitForVisible()
            val deadline = SystemClock.uptimeMillis() + 4000
            var visible = true
            while (visible && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(50)
                main { visible = view.isHistoryScrollbarVisible }
            }
            main {
                assertFalse(view.isHistoryScrollbarVisible)
                assertTrue(view.topRow < 0)
                render()
                val latest = bounds("latest")
                tap(latest.centerX(), latest.centerY())
                assertEquals(0, view.topRow)
            }
        }
    }

    @Test fun releaseSnapsOnlyWithinTwoRowsAndCanBeDisabled() = withTerminal {
        scroll(-3f)
        waitForVisible()
        main {
            val target = view.mEmulator.history.markers[10]
            for (enabled in listOf(true, false)) {
                view.scrollToLatest()
                view.setHistoryScrollbarSnapEnabled(enabled)
                // The bar is still visible from the preceding ordinary scroll.
                render()
                val thumb = bounds("thumb")
                val destination = markerY(target) + (number("trackBottom") - number("trackTop") - number("thumbHeight")) /
                    view.mEmulator.screen.activeTranscriptRows
                val drag = Drag(view.width - 20 * density(), thumb.centerY())
                drag.move(destination)
                drag.move(destination) // Pause at the destination instead of flinging past it.
                drag.finish()
                assertEquals(target.anchor.row!! + if (enabled) 0 else 1, view.topRow)
            }
        }
    }

    @Test fun markerTapAndPreviousNextActionsJumpToAnchorsIncludingAfterReflow() = withTerminal {
        scroll(-3f)
        waitForVisible()
        main {
            val markers = view.mEmulator.history.markers
            val middle = markers[markers.size / 2]
            assertTrue(view.jumpToCommand(middle))
            assertEquals(middle.anchor.row, view.topRow)
            assertTrue(view.scrollToPreviousCommand())
            assertEquals(markers[markers.size / 2 - 1].anchor.row, view.topRow)
            assertTrue(view.scrollToNextCommand())
            assertEquals(middle.anchor.row, view.topRow)
            view.mEmulator.resize(view.mEmulator.mColumns / 2, view.mEmulator.mRows, 1, 1)
            view.onScreenUpdated()
            assertTrue(view.jumpToCommand(middle))
            assertEquals(middle.anchor.row, view.topRow)
            render()
            val target = markers[3]
            val y = markerY(target)
            tap(view.width - 24 * density(), y)
            assertEquals("Marker tap resolves retained history", target.anchor.row!!, view.topRow)
            val info = AccessibilityNodeInfo.obtain()
            view.onInitializeAccessibilityNodeInfo(info)
            assertTrue(info.actionList.any { it.id == com.termux.view.R.id.history_previous_command })
            assertTrue(view.performAccessibilityAction(com.termux.view.R.id.history_latest_output, null))
            assertEquals(0, view.topRow)
        }
    }

    @Test fun freshOutputAccumulatesThroughTrimButResizeDoesNotCountAsNewOutput() = withTerminal {
        scroll(-4f)
        waitForVisible()
        main {
            val top = view.topRow
            append("new-1\r\nnew-2\r\nnew-3\r\n")
            assertEquals(3L, view.pendingHistoryOutputRows)
            assertEquals(top - 3, view.topRow)
            view.mEmulator.resize(view.mEmulator.mColumns - 2, view.mEmulator.mRows + 1, 1, 1)
            view.onScreenUpdated()
            assertEquals(3L, view.pendingHistoryOutputRows)
            repeat(1100) { append("trim\r\n") }
            assertEquals(1103L, view.pendingHistoryOutputRows)
            render()
            val latest = bounds("latest")
            tap(latest.centerX(), latest.centerY())
            assertEquals(0, view.topRow)
            assertEquals(0L, view.pendingHistoryOutputRows)
        }
    }

    @Test fun alternateMouseAndApplicationOwnedViewportsHideOverlayAndPreserveWheelInput() = withTerminal {
        scroll(-3f)
        waitForVisible()
        main {
            append("\u001b[?1049h")
            assertFalse(view.isHistoryScrollbarVisible)
            assertFalse(view.jumpToCommand(view.mEmulator.history.markers.first()))
            append("\u001b[?1049l\u001b[?1003h\u001b[?1006h")
            assertFalse(view.isHistoryScrollbarVisible)
            assertFalse(view.scrollToPreviousCommand())
            scroll(-1f)
            assertTrue(output.toString("UTF-8").contains("\u001b[<64;"))
            output.reset()
            append("\u001b[?1003l\u001b[2;5r")
            assertFalse(view.isHistoryScrollbarVisible)
            append("\u001b[r\u001b[?1h")
            assertFalse(view.isHistoryScrollbarVisible)
        }
    }

    @Test fun checkpointAndSessionSwitchPreserveMarkersAndCancelOldDrag() = withTerminal {
        scroll(-3f)
        waitForVisible()
        main {
            val markers = view.mEmulator.history.markers
            val time = markers.first().startedAt
            val state = ByteArrayOutputStream().also { TerminalCheckpoint.write(view.mEmulator, it) }
            view.mEmulator.restoreState(TerminalCheckpoint.read(ByteArrayInputStream(state.toByteArray())))
            view.onScreenUpdated()
            assertEquals(time, view.mEmulator.history.markers.first().startedAt)
            assertTrue(view.jumpToCommand(view.mEmulator.history.markers[4]))
            render()
            val thumb = bounds("thumb")
            val drag = Drag(view.width - 20 * density(), thumb.centerY())
            drag.move(thumb.centerY() - 100)
            assertTrue(view.isHistoryScrollbarDragging)
            val otherBackend = RemoteTerminalBackend(write = {}, resize = { _, _ -> })
            val other = TerminalSession("", "/", emptyArray(), emptyArray(), 1000, TerminalCallbacks {}, otherBackend)
            val original = view.currentSession
            try {
                view.attachSession(other)
                assertFalse(view.isHistoryScrollbarDragging)
                assertFalse(view.isHistoryScrollbarVisible)
                view.attachSession(original)
                assertEquals(markers.size, view.mEmulator.history.markers.size)
                assertTrue(view.jumpToCommand(view.mEmulator.history.markers[4]))
            } finally { otherBackend.close() }
        }
    }

    @Test fun appTakingViewportDuringDragCancelsWithoutLeakingMousePress() = withTerminal {
        scroll(-3f)
        waitForVisible()
        main {
            render()
            val thumb = bounds("thumb")
            val drag = Drag(view.width - 20 * density(), thumb.centerY())
            drag.move(thumb.centerY() - 100)
            append("\u001b[?1003h\u001b[?1006h")
            drag.finish()
            assertFalse(view.isHistoryScrollbarDragging)
            assertEquals(0, output.size())
        }
    }

    private fun withTerminal(test: () -> Unit) {
        main {
            output.reset()
            backend = RemoteTerminalBackend(write = { output.write(it) }, resize = { _, _ -> })
            val session = TerminalSession("", "/", emptyArray(), emptyArray(), 1000, TerminalCallbacks {}, backend)
            view = TerminalView(instrumentation.targetContext, null).apply {
                setTerminalViewClient(TerminalInputCallbacks())
                setTextSize(20)
                layout(0, 0, 1000, 1400)
                attachSession(session)
            }
            repeat(40) { command ->
                append("\u001b]133;A\u0007$ command\r\n\u001b]133;C\u0007")
                repeat(4) { append("output-$command-$it\r\n") }
                append("\u001b]133;D;${command % 3}\u0007")
            }
        }
        try { test() } finally { main { backend.close() } }
    }

    private fun main(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else instrumentation.runOnMainSync(block)
    }
    private fun append(text: String) {
        val bytes = text.toByteArray()
        view.mEmulator.append(bytes, bytes.size)
        view.onScreenUpdated()
    }
    private fun density() = view.resources.displayMetrics.density
    private fun overlay(): Any = checkNotNull(TerminalView::class.java.getDeclaredField("mHistoryScrollbar")
        .apply { isAccessible = true }.get(view))
    private fun bounds(name: String): RectF = overlay().let { bar ->
        RectF(bar.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(bar) as RectF)
    }
    private fun number(name: String): Float = overlay().let { bar ->
        bar.javaClass.getDeclaredField(name).apply { isAccessible = true }.getFloat(bar)
    }
    private fun markerY(marker: CommandMarker): Float {
        val height = number("thumbHeight")
        val top = number("trackTop")
        return top + height / 2 + (number("trackBottom") - top - height) *
            (1 + marker.anchor.row!!.toFloat() / view.mEmulator.screen.activeTranscriptRows)
    }
    private fun render() {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        bitmap.recycle()
    }
    private fun scroll(amount: Float) = main {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply { x = 300f; y = 300f; setAxisValue(MotionEvent.AXIS_VSCROLL, -amount) })
        val event = MotionEvent.obtain(0, SystemClock.uptimeMillis(), MotionEvent.ACTION_SCROLL, 1, properties,
            coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        view.onGenericMotionEvent(event)
        event.recycle()
    }
    private fun waitForVisible() {
        val until = SystemClock.uptimeMillis() + 2000
        while (SystemClock.uptimeMillis() < until) {
            var visible = false
            main { visible = view.isHistoryScrollbarVisible }
            if (visible) return
            SystemClock.sleep(20)
        }
        fail("Scrollbar did not fade in")
    }
    private fun tap(x: Float, y: Float) { Drag(x, y).finish() }
    private inner class Drag(private val x: Float, private var y: Float) {
        private val start = SystemClock.uptimeMillis()
        private var time = start
        init { event(MotionEvent.ACTION_DOWN) }
        fun move(destination: Float) { y = destination; time += 30; event(MotionEvent.ACTION_MOVE) }
        fun finish() { time += 30; event(MotionEvent.ACTION_UP) }
        private fun event(action: Int) {
            val event = MotionEvent.obtain(start, time, action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            view.onTouchEvent(event)
            event.recycle()
        }
    }
}
