package dev.terminal.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Scroller
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import org.junit.Assert.*
import org.junit.Test

class SmoothScrollingTest {
    private fun scroller(view: TerminalView) = TerminalView::class.java.getDeclaredField("mScroller")
        .apply { isAccessible = true }.get(view) as Scroller

    private fun fling(view: TerminalView, velocity: Float) {
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 40f, 100f, 0)
        try {
            val listener = listener(view)
            listener.javaClass.getDeclaredMethod("onFling", MotionEvent::class.java,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(listener, event, 0f, velocity)
        } finally { event.recycle() }
    }

    private fun listener(view: TerminalView): Any {
        val recognizer = TerminalView::class.java.getDeclaredField("mGestureRecognizer")
            .apply { isAccessible = true }.get(view)
        return requireNotNull(recognizer.javaClass.getDeclaredField("mListener")
            .apply { isAccessible = true }.get(recognizer))
    }

    private fun drag(view: TerminalView, distance: Float) {
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 40f, 100f, 0)
        try {
            val listener = listener(view)
            listener.javaClass.getDeclaredMethod("onScroll", MotionEvent::class.java,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(listener, event, 0f, distance)
        } finally { event.recycle() }
    }

    private fun session() = TerminalSession("", "/", emptyArray(), emptyArray(), 1000,
        TerminalCallbacks {}, RemoteTerminalBackend(write = {}, resize = { _, _ -> }))

    private fun append(view: TerminalView, session: TerminalSession, text: String) {
        val bytes = text.toByteArray()
        session.emulator.append(bytes, bytes.size)
        view.onScreenUpdated()
    }

    @Test fun flingVelocityCurveDoesNotDependOnDistanceToEitherEdge() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = TerminalView(instrumentation.targetContext, null).apply {
                setTerminalViewClient(TerminalInputCallbacks()); setTextSize(20)
                setSmoothScrollingEnabled(true); layout(0, 0, 800, 400)
            }
            val session = session(); view.attachSession(session)
            append(view, session, (1..200).joinToString("\r\n") { "line-$it" })
            val history = session.emulator.screen.activeTranscriptRows
            val spacing = view.getPointY(1).toFloat()
            for (velocity in listOf(-1500f, -8000f, 1500f, 8000f)) {
                fun trajectory(row: Int): Pair<Int, Int> {
                    view.topRow = row
                    drag(view, spacing / 2)
                    fling(view, velocity)
                    val scroller = scroller(view)
                    val result = (scroller.finalY - scroller.startY) to scroller.duration
                    drag(view, 0f) // Cancel before starting another fling.
                    return result
                }
                val middle = trajectory(-history / 2)
                assertEquals("Same velocity must keep its trajectory near the oldest row",
                    middle, trajectory(-history + 1))
                assertEquals("Same velocity must keep its trajectory near the latest row",
                    middle, trajectory(-1))
            }
        }
    }

    @Test fun flingStopsAtEitherEdgeAndReverseDragMovesImmediately() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            for (velocity in listOf(-8000f, 8000f)) {
                lateinit var view: TerminalView
                var history = 0
                var spacing = 0f
                scenario.onActivity { activity ->
                    view = TerminalView(activity, null).apply {
                        setTerminalViewClient(TerminalInputCallbacks()); setTextSize(20)
                        setSmoothScrollingEnabled(true); layout(0, 0, 800, 400)
                    }
                    activity.setContentView(view, ViewGroup.LayoutParams(800, 400))
                    val session = session(); view.attachSession(session)
                    append(view, session, (1..200).joinToString("\r\n") { "line-$it" })
                    history = session.emulator.screen.activeTranscriptRows
                    spacing = view.getPointY(1).toFloat()
                    view.topRow = if (velocity > 0) -history + 1 else -1
                    drag(view, spacing / 2)
                    fling(view, velocity)
                }
                // Allow the attached view's real frame callbacks to reach the nearby edge.
                SystemClock.sleep(100)
                scenario.onActivity {
                    assertEquals(if (velocity > 0) -history else 0, view.topRow)
                    assertEquals(0, view.getPointY(view.topRow))
                    assertTrue("Fling must stop as soon as it reaches the history edge", scroller(view).isFinished)
                    drag(view, if (velocity > 0) spacing / 2 else -spacing / 2)
                    assertTrue("Reverse drag must immediately move away from the edge", view.getPointY(view.topRow) < 0)
                }
            }
        }
    }

    @Test fun fractionalMotionRendersAndKeepsLinksAndTabAnchorsAligned() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = TerminalView(instrumentation.targetContext, null).apply {
                setTerminalViewClient(TerminalInputCallbacks()); setTextSize(20)
                setSmoothScrollingEnabled(true); layout(0, 0, 800, 400)
            }
            val first = session(); val second = session()
            view.attachSession(first)
            append(view, first, (1..200).joinToString("\r\n") { "https://example.com/$it" })
            view.topRow = -20
            val spacing = view.getPointY(-19).toFloat()
            val before = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
            val after = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(before))
            drag(view, spacing / 2)
            assertEquals(-20, view.topRow)
            assertEquals(Math.round(-spacing / 2), view.getPointY(-20))
            view.draw(Canvas(after))
            assertFalse("A partial-row drag must change the rendered frame", before.sameAs(after))
            val baseline = view.mRenderer.javaClass.getDeclaredField("mFontLineSpacingAndAscent")
                .apply { isAccessible = true }.getInt(view.mRenderer).toFloat()
            val y = baseline + spacing / 4
            val expected = first.emulator.screen.getSelectedText(0, -20, 50, -20).trim()
            assertEquals(expected, view.getWebLinkAt(40f, y))
            val next = first.emulator.screen.getSelectedText(0, -19, 50, -19).trim()
            assertEquals(next, view.getWebLinkAt(40f, y + spacing))
            view.attachSession(second); view.attachSession(first)
            assertEquals(-20, view.topRow)
            assertEquals(Math.round(-spacing / 2), view.getPointY(-20))
            append(view, first, "\r\nnew-output")
            assertEquals(-21, view.topRow)
            assertEquals(Math.round(-spacing / 2), view.getPointY(-21))
            assertEquals(expected, view.getWebLinkAt(40f, y))
            view.scrollToLatest()
            assertEquals(0, view.topRow); assertEquals(0, view.getPointY(0))
            before.recycle(); after.recycle()
        }
    }

    @Test fun boundsClearHistoryAndDisabledModeNeverLeaveFractionalOffsets() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = TerminalView(instrumentation.targetContext, null).apply {
                setTerminalViewClient(TerminalInputCallbacks()); setTextSize(20)
                layout(0, 0, 800, 400)
            }
            val session = session(); view.attachSession(session)
            append(view, session, (1..200).joinToString("\r\n") { "line-$it" })
            view.topRow = -20
            val spacing = view.getPointY(-19).toFloat()
            drag(view, spacing / 2)
            assertEquals(-20, view.topRow); assertEquals(0, view.getPointY(-20))
            view.setSmoothScrollingEnabled(true)
            drag(view, spacing / 2)
            assertTrue(view.getPointY(-20) < 0)
            view.setSmoothScrollingEnabled(false)
            assertEquals(0, view.getPointY(view.topRow))
            view.setSmoothScrollingEnabled(true)
            drag(view, -1_000_000f)
            assertEquals(-session.emulator.screen.activeTranscriptRows, view.topRow)
            assertEquals(0, view.getPointY(view.topRow))
            drag(view, 1_000_000f)
            assertEquals(0, view.topRow); assertEquals(0, view.getPointY(0))
            view.topRow = -20; drag(view, spacing / 2)
            append(view, session, "\u001b[3J")
            assertEquals(0, view.topRow); assertEquals(0, view.getPointY(0))
            append(view, session, "\u001b[?1049h")
            drag(view, -spacing / 2)
            assertEquals(0, view.topRow); assertEquals(0, view.getPointY(0))
        }
    }
}
