package dev.terminal.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import org.junit.Assert.*
import org.junit.Test

class SmoothScrollingTest {
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
