package dev.terminal.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalRenderer
import com.termux.view.TerminalView
import org.junit.Assert.*
import org.junit.Test

class TerminalViewportAlignmentTest {
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
