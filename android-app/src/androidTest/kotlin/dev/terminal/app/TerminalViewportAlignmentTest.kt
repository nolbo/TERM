package dev.terminal.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.emulator.TerminalSession
import dev.terminal.view.TerminalRenderer
import dev.terminal.view.TerminalView
import org.junit.Assert.*
import org.junit.Test

class TerminalViewportAlignmentTest {
    @Test fun toolbarResizeKeepsPixelsAndInputStableUntilGeometryIsApplied() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val resizes = mutableListOf<Int>()
            val backend = RemoteTerminalBackend(write = {}, resize = { _, rows -> resizes += rows })
            try {
                val session = TerminalSession("", "", emptyArray(), emptyArray(), 100, TerminalCallbacks {}, backend)
                val view = TerminalView(instrumentation.targetContext, null).apply {
                    setTerminalViewClient(TerminalInputCallbacks())
                    setTextSize(20)
                    setTerminalGridBottomAligned(true)
                    setScrollbackTopInset(63)
                    layout(0, 0, 800, 500)
                    attachSession(session)
                }
                val bytes = ("\u001b[?25l\u001b[48;2;18;52;86m\u001b[2J" +
                    "\u001b[Hhttps://example.com/path\u001b[2;1H\u001b[48;2;86;52;18m\u001b[2K").toByteArray()
                session.emulator.append(bytes, bytes.size)
                val origin = view.terminalContentTopInset
                val spacing = view.mRenderer.fontLineSpacing
                val lineOffset = TerminalRenderer::class.java.getDeclaredField("mFontLineSpacingAndAscent")
                    .apply { isAccessible = true }.getInt(view.mRenderer)
                val linkY = origin + lineOffset + spacing / 2f
                val pointY = view.getPointY(1)
                val rows = session.emulator.mRows
                resizes.clear()
                fun pixels(): IntArray {
                    val bitmap = Bitmap.createBitmap(800, view.height, Bitmap.Config.ARGB_8888)
                    return try {
                        view.draw(Canvas(bitmap))
                        IntArray(800 * 200).also { bitmap.getPixels(it, 0, 800, 0, 0, 800, 200) }
                    } finally { bitmap.recycle() }
                }
                val before = pixels()
                // Cross several row boundaries in both directions, as dragging and settling do.
                for (height in (500 downTo 300) + (300..550)) {
                    view.layout(0, 0, 800, height)
                    assertEquals("Origin moved at toolbar height $height", origin, view.terminalContentTopInset)
                    assertEquals(pointY, view.getPointY(1))
                    assertEquals(rows, session.emulator.mRows)
                    assertArrayEquals("Rendered rows moved at toolbar height $height", before, pixels())
                    assertEquals(1, view.getCursorY(pointY + 40f))
                    assertEquals("https://example.com/path", view.getWebLinkAt(40f, linkY))
                    val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 40f, linkY, 0)
                    try {
                        assertEquals(0, view.getColumnAndRow(event, false)[1])
                        assertEquals(0, view.getColumnAndRow(event, true)[1])
                    } finally { event.recycle() }
                }
                assertTrue("Transient frames must not resize the PTY", resizes.isEmpty())
                view.updateSize()
                assertEquals(view.height, view.terminalContentTopInset + lineOffset + session.emulator.mRows * spacing)
                assertEquals(1, resizes.size)
                val settledRows = session.emulator.mRows
                val settledOrigin = view.terminalContentTopInset
                // Geometry can remain in the same row count while the bottom slack changes.
                val sameRowsHeight = 63 + lineOffset + settledRows * spacing
                view.layout(0, 0, 800, sameRowsHeight)
                assertEquals(settledOrigin, view.terminalContentTopInset)
                view.updateSize()
                assertEquals(63, view.terminalContentTopInset)
                assertEquals(settledRows, session.emulator.mRows)
                assertEquals(1, resizes.size)
            } finally { backend.close() }
        }
    }

    @Test fun bottomAlignmentKeepsRenderingAndInputOnTheSameRows() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val backend = RemoteTerminalBackend(write = {}, resize = { _, _ -> })
            try {
                val session = TerminalSession("", "", emptyArray(), emptyArray(), 100, TerminalCallbacks {}, backend)
                val view = TerminalView(instrumentation.targetContext, null).apply {
                    setTerminalViewClient(TerminalInputCallbacks())
                    setTextSize(20)
                    setTerminalGridBottomAligned(true)
                    layout(0, 0, 800, 400)
                    attachSession(session)
                }
                val renderer = TerminalView::class.java.getDeclaredField("mRenderer")
                    .apply { isAccessible = true }.get(view) as TerminalRenderer
                val lineOffset = TerminalRenderer::class.java.getDeclaredField("mFontLineSpacingAndAscent")
                    .apply { isAccessible = true }.getInt(renderer)
                for (safeInset in listOf(0, 63)) for (height in listOf(300, 401, 750)) {
                    view.setScrollbackTopInset(safeInset)
                    view.layout(0, 0, 800, height)
                    view.updateSize()
                    val bytes = "\u001b[?1049h\u001b[?25l\u001b[48;2;18;52;86m\u001b[2J\u001b[Hhttps://example.com/path".toByteArray()
                    session.emulator.append(bytes, bytes.size)
                    val origin = view.terminalContentTopInset
                    val spacing = renderer.fontLineSpacing
                    assertEquals(height, origin + lineOffset + session.emulator.mRows * spacing)
                    assertTrue(origin >= safeInset && origin < safeInset + spacing)
                    val bitmap = Bitmap.createBitmap(800, height, Bitmap.Config.ARGB_8888)
                    try {
                        view.draw(Canvas(bitmap))
                        assertEquals("Last row fills the bottom pixel", 0xFF123456.toInt(), bitmap.getPixel(2, height - 1))
                    } finally { bitmap.recycle() }
                    for (row in listOf(0, session.emulator.mRows - 1)) {
                        val y = origin + lineOffset + (row + 0.5f) * spacing
                        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 2f, y, 0)
                        try {
                            assertEquals(row, view.getColumnAndRow(event, false)[1])
                            assertEquals(row, view.getColumnAndRow(event, true)[1])
                        } finally { event.recycle() }
                        assertEquals(row, view.getCursorY(view.getPointY(row) + 40f))
                    }
                    assertEquals("https://example.com/path", view.getWebLinkAt(40f, origin + lineOffset + spacing / 2f))
                }
            } finally { backend.close() }
        }
    }
}
