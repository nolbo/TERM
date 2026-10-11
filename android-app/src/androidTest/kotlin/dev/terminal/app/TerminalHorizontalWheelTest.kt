package dev.terminal.app

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.Scroller
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.emulator.TerminalSession
import dev.terminal.view.TerminalView
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class TerminalHorizontalWheelTest {
    @Test fun allHotkeyModifierCombinationsApplyToTouchMouseAndTrackpadWheelsWithoutConsumption() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var stateChanges = 0
        val callbacks = TerminalInputCallbacks(onModifierStateChanged = { stateChanges++ })
        withTerminal(callbacks) { view, output ->
            append(view, "\u001b[?1003h\u001b[?1006h")
            val distance = 2.25f * spacing(view)
            val directions = listOf(64 to (0f to distance), 65 to (0f to -distance),
                66 to (distance to 0f), 67 to (-distance to 0f))
            val masks = listOf(0, 4, 8, 12, 16, 20, 24, 28)
            for (combination in masks.indices) {
                callbacks.shiftPressed = combination and 1 != 0
                callbacks.altPressed = combination and 2 != 0
                callbacks.controlPressed = combination and 4 != 0
                for ((button, delta) in directions) {
                    output.reset()
                    val drag = Drag(view)
                    val anchor = drag.move(delta.first, delta.second)
                    drag.finish(cancel = true)
                    assertEquals("touch button $button, mask ${masks[combination]}",
                        anchor.sequence(button + masks[combination]).repeat(2), output.toString("UTF-8"))
                    for (source in listOf(InputDevice.SOURCE_MOUSE, InputDevice.SOURCE_TOUCHPAD)) {
                        output.reset()
                        val cell = wheel(view,
                            horizontal = when (button) { 66 -> -1f; 67 -> 1f; else -> 0f },
                            vertical = when (button) { 64 -> 1f; 65 -> -1f; else -> 0f }, source = source)
                        assertEquals("physical button $button, mask ${masks[combination]}, source $source",
                            cell.sequence(button + masks[combination]).repeat(3), output.toString("UTF-8"))
                    }
                    assertEquals(combination and 1 != 0, callbacks.readShiftKey())
                    assertEquals(combination and 2 != 0, callbacks.readAltKey())
                    assertEquals(combination and 4 != 0, callbacks.readControlKey())
                }
            }
        }
        // Keyboard consumption posts to the main looper: drain it before checking persistence.
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertTrue(callbacks.readShiftKey())
            assertTrue(callbacks.readAltKey())
            assertTrue(callbacks.readControlKey())
            assertEquals("Wheel input must never schedule modifier consumption", 0, stateChanges)
        }
    }

    @Test fun wheelReadsHotkeyChangesDuringDragAndStopsApplyingModifiersWhenToggledOff() {
        val callbacks = TerminalInputCallbacks()
        withTerminal(callbacks) { view, output ->
            append(view, "\u001b[?1000h\u001b[?1006h")
            callbacks.shiftPressed = true
            val drag = Drag(view)
            val cell = drag.move(0f, 2.25f * spacing(view))
            assertEquals(cell.sequence(68).repeat(2), output.toString("UTF-8"))
            output.reset()
            callbacks.altPressed = true
            callbacks.controlPressed = true
            drag.move(0f, spacing(view))
            assertEquals(cell.sequence(92), output.toString("UTF-8"))
            output.reset()
            callbacks.shiftPressed = false
            callbacks.altPressed = false
            callbacks.controlPressed = false
            drag.move(0f, spacing(view))
            drag.finish(cancel = true)
            assertEquals(cell.sequence(64), output.toString("UTF-8"))
            output.reset()
            callbacks.shiftPressed = true
            val physical = wheel(view, horizontal = 0f, vertical = -1f)
            assertEquals(physical.sequence(69).repeat(3), output.toString("UTF-8"))
            output.reset()
            callbacks.shiftPressed = false
            wheel(view, horizontal = 0f, vertical = -1f)
            assertEquals(physical.sequence(65).repeat(3), output.toString("UTF-8"))
        }
    }

    @Test fun touchDirectionsEmitExactSgrWheelPressesInEveryTrackingMode() {
        for (mode in listOf(1000, 1002, 1003)) withTerminal { view, output ->
            append(view, "\u001b[?$mode" + "h\u001b[?1006h\u001b[?1049h")
            val spacing = spacing(view)
            val leftward = Drag(view)
            val rightSequence = leftward.move(-3.25f * spacing, spacing / 4).sequence(67)
            leftward.finish()
            assertEquals(rightSequence.repeat(3), output.toString("UTF-8"))
            assertTrue("A horizontal release must not start a vertical fling", scroller(view).isFinished)
            output.reset()
            val rightward = Drag(view)
            val leftSequence = rightward.move(2.25f * spacing, -spacing / 4).sequence(66)
            rightward.finish()
            assertEquals(leftSequence.repeat(2), output.toString("UTF-8"))
        }
    }

    @Test fun touchAccumulatesPartialStepsReversesAndReportsFastMovement() = withTerminal { view, output ->
        append(view, "\u001b[?1000h\u001b[?1006h")
        val spacing = spacing(view)
        val drag = Drag(view)
        val anchor = drag.move(-2.25f * spacing).sequence(67)
        assertEquals(anchor.repeat(2), output.toString("UTF-8"))
        output.reset()
        drag.move(-0.35f * spacing)
        assertEquals(0, output.size())
        drag.move(-0.65f * spacing)
        assertEquals(anchor, output.toString("UTF-8"))
        output.reset()
        drag.move(0.8f * spacing)
        assertEquals("Reversal must discard the previous direction's partial step", 0, output.size())
        drag.move(0.45f * spacing)
        assertEquals(anchor.replace("<67;", "<66;"), output.toString("UTF-8"))
        output.reset()
        drag.move(6.5f * spacing)
        drag.finish()
        assertEquals(anchor.replace("<67;", "<66;").repeat(6), output.toString("UTF-8"))
    }

    @Test fun touchAxisStaysLockedAndResetsAfterCancel() = withTerminal { view, output ->
        append(view, "\u001b[?1000h\u001b[?1006h")
        val spacing = spacing(view)
        val horizontal = Drag(view)
        val anchor = horizontal.move(-2.25f * spacing).sequence(67)
        horizontal.move(-spacing, 5 * spacing)
        horizontal.finish(cancel = true)
        assertEquals(anchor.repeat(3), output.toString("UTF-8"))
        output.reset()
        val vertical = Drag(view)
        val up = vertical.move(spacing / 4, 2.25f * spacing).sequence(64)
        vertical.move(-8 * spacing, spacing)
        vertical.finish(cancel = true)
        assertEquals(up.repeat(3), output.toString("UTF-8"))
        output.reset()
        val nextHorizontal = Drag(view)
        val nextAnchor = nextHorizontal.move(-2.8f * spacing).sequence(67)
        nextHorizontal.finish(cancel = true)
        assertEquals(nextAnchor.repeat(2), output.toString("UTF-8"))
    }

    @Test fun shellHorizontalSwipesAndWheelsLeaveScrollbackAndAlternateScreenAlone() = withTerminal { view, output ->
        append(view, (1..200).joinToString("\r\n") { "line-$it" })
        for (smooth in listOf(false, true)) {
            view.setSmoothScrollingEnabled(smooth)
            view.topRow = -20
            val before = view.getPointY(-20)
            val drag = Drag(view)
            drag.move(-100f, 5f)
            drag.move(-30f, 150f)
            drag.finish()
            wheel(view, horizontal = 2f)
            wheel(view, horizontal = -2f, source = InputDevice.SOURCE_TOUCHPAD)
            assertEquals(-20, view.topRow)
            assertEquals(before, view.getPointY(-20))
            assertTrue(scroller(view).isFinished)
            assertEquals(0, output.size())
        }
        append(view, "\u001b[?1049h\u001b[?1007h\u001b[?1006h")
        val drag = Drag(view)
        drag.move(100f, 5f)
        drag.move(30f, 150f)
        drag.finish()
        wheel(view, horizontal = 1f)
        assertEquals(0, output.size())
        assertEquals(0, view.topRow)
    }

    @Test fun rightEdgeStillScrollsHistoryWithoutAScrollbar() = withTerminal { view, output ->
        append(view, (1..200).joinToString("\r\n") { "line-$it" })
        for (smooth in listOf(false, true)) {
            view.setSmoothScrollingEnabled(smooth)
            view.topRow = -80
            assertFalse(view.isVerticalScrollBarEnabled)
            val spacing = spacing(view)
            val drag = Drag(view, x = view.width - 8 * view.resources.displayMetrics.density)
            drag.move(0f, 4 * spacing)
            val older = view.topRow
            assertTrue("Dragging down at the right edge reaches older output", older < -80)
            drag.move(0f, -6 * spacing)
            assertTrue("Reversing the drag reaches newer output", view.topRow > older)
            drag.finish(cancel = true)
            assertTrue(scroller(view).isFinished)
            assertEquals(0, output.size())
        }
    }

    @Test fun mouseAndTrackpadWheelsPreserveFractionsDirectionsAndCurrentCoordinates() = withTerminal { view, output ->
        append(view, "\u001b[?1000h\u001b[?1006h")
        val right = wheel(view, horizontal = 0.125f).sequence(67)
        assertEquals(0, output.size())
        repeat(2) { wheel(view, horizontal = 0.125f) }
        assertEquals(right, output.toString("UTF-8"))
        output.reset()
        wheel(view, horizontal = -0.25f)
        assertEquals(0, output.size())
        wheel(view, horizontal = -0.125f)
        assertEquals(right.replace("<67;", "<66;"), output.toString("UTF-8"))
        output.reset()
        wheel(view, horizontal = -2f)
        assertEquals(right.replace("<67;", "<66;").repeat(6), output.toString("UTF-8"))
        output.reset()
        val atNewPosition = wheel(view, horizontal = 1f, vertical = 1f, x = 240f).sequence(67)
        assertEquals(atNewPosition.replace("<67;", "<64;").repeat(3) + atNewPosition.repeat(3),
            output.toString("UTF-8"))
        output.reset()
        val trackpad = wheel(view, horizontal = -1f, source = InputDevice.SOURCE_TOUCHPAD, x = 340f).sequence(66)
        assertEquals(trackpad.repeat(3), output.toString("UTF-8"))
    }

    @Test fun disabledTrackingAndSessionSwitchDiscardHorizontalWheelRemainders() = withTerminal { view, output ->
        append(view, "\u001b[?1000h\u001b[?1006h")
        wheel(view, horizontal = 0.25f)
        append(view, "\u001b[?1000l")
        wheel(view, horizontal = 0.25f)
        append(view, "\u001b[?1000h")
        wheel(view, horizontal = 0.125f)
        assertEquals(0, output.size())
        val first = view.mTermSession
        val backend = RemoteTerminalBackend(write = { output.write(it) }, resize = { _, _ -> })
        try {
            val second = TerminalSession("", "/", emptyArray(), emptyArray(), 1000, TerminalCallbacks {}, backend)
            view.attachSession(second)
            view.attachSession(first)
            wheel(view, horizontal = 0.25f)
            assertEquals(0, output.size())
        } finally { backend.close() }
    }

    @Test fun actualMouseDragStillReportsButtonMotionWithoutWheelEvents() = withTerminal { view, output ->
        append(view, "\u001b[?1002h\u001b[?1006h")
        val drag = Drag(view, InputDevice.SOURCE_MOUSE)
        drag.move(-100f, 5f)
        drag.finish()
        val codes = Regex("\u001b\\[<(\\d+);\\d+;\\d+[Mm]").findAll(output.toString("UTF-8"))
            .map { it.groupValues[1].toInt() }.toList()
        assertTrue(codes.contains(0))
        assertTrue(codes.contains(32))
        assertTrue(codes.all { it == 0 || it == 32 })
    }

    private fun withTerminal(callbacks: TerminalInputCallbacks = TerminalInputCallbacks(),
                             block: (TerminalView, ByteArrayOutputStream) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val output = ByteArrayOutputStream()
            val backend = RemoteTerminalBackend(write = { output.write(it) }, resize = { _, _ -> })
            val session = TerminalSession("", "/", emptyArray(), emptyArray(), 1000, TerminalCallbacks {}, backend)
            val view = TerminalView(instrumentation.targetContext, null).apply {
                setTerminalViewClient(callbacks)
                setTextSize(20)
                layout(0, 0, 1000, 600)
                attachSession(session)
            }
            try { block(view, output) } finally { backend.close() }
        }
    }

    private fun append(view: TerminalView, text: String) {
        val bytes = text.toByteArray()
        view.mEmulator.append(bytes, bytes.size)
        view.onScreenUpdated()
    }

    private fun spacing(view: TerminalView) = (view.getPointY(1) - view.getPointY(0)).toFloat()

    private fun scroller(view: TerminalView) = TerminalView::class.java.getDeclaredField("mScroller")
        .apply { isAccessible = true }.get(view) as Scroller

    private data class Cell(val column: Int, val row: Int) {
        fun sequence(button: Int) = "\u001b[<$button;$column;${row}M"
    }

    private fun cell(view: TerminalView, event: MotionEvent): Cell {
        val position = view.getColumnAndRow(event, false)
        return Cell((position[0] + 1).coerceIn(1, view.mEmulator.mColumns),
            (position[1] + 1).coerceIn(1, view.mEmulator.mRows))
    }

    private inner class Drag(val view: TerminalView, val source: Int = InputDevice.SOURCE_TOUCHSCREEN,
                             private var x: Float = 500f) {
        private val start = SystemClock.uptimeMillis()
        private var tick = 0L
        private var y = 200f

        init { dispatch(MotionEvent.ACTION_DOWN) }

        fun move(dx: Float, dy: Float = 0f): Cell {
            x += dx; y += dy
            return dispatch(MotionEvent.ACTION_MOVE)
        }

        fun finish(cancel: Boolean = false) { dispatch(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP) }

        private fun dispatch(action: Int): Cell {
            val properties = MotionEvent.PointerProperties().apply {
                id = 0; toolType = if (source == InputDevice.SOURCE_MOUSE) MotionEvent.TOOL_TYPE_MOUSE else MotionEvent.TOOL_TYPE_FINGER
            }
            val coords = MotionEvent.PointerCoords().apply { x = this@Drag.x; y = this@Drag.y; pressure = 1f }
            val buttons = if (source == InputDevice.SOURCE_MOUSE && action != MotionEvent.ACTION_UP) MotionEvent.BUTTON_PRIMARY else 0
            val event = MotionEvent.obtain(start, start + tick, action, 1, arrayOf(properties), arrayOf(coords),
                0, buttons, 1f, 1f, 0, 0, source, 0)
            tick += 20
            return try { val cell = cell(view, event); view.onTouchEvent(event); cell } finally { event.recycle() }
        }
    }

    private fun wheel(view: TerminalView, horizontal: Float, vertical: Float = 0f,
                      source: Int = InputDevice.SOURCE_MOUSE, x: Float = 40f): Cell {
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x; y = 120f
            setAxisValue(MotionEvent.AXIS_HSCROLL, horizontal)
            setAxisValue(MotionEvent.AXIS_VSCROLL, vertical)
        }
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_SCROLL, 1, arrayOf(properties), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, source, 0)
        return try { val cell = cell(view, event); assertTrue(view.onGenericMotionEvent(event)); cell } finally { event.recycle() }
    }
}
