package dev.terminal.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.view.TerminalRenderer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalSelectionRenderingTest {
    private val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    @Test fun multilineHighlightMatchesSeparateRowSelections() {
        checkSelection(topRow = 0, startRow = 0, endRow = 2, startColumn = 6, endColumn = 2)
    }

    @Test fun scrollbackHighlightMatchesSeparateRowSelections() {
        checkSelection(topRow = -2, startRow = -2, endRow = 0, startColumn = 6, endColumn = 2)
    }

    @Test fun singleLineHighlightRemainsBounded() {
        checkSelection(topRow = 0, startRow = 1, endRow = 1, startColumn = 2, endColumn = 6)
    }

    private fun checkSelection(topRow: Int, startRow: Int, endRow: Int, startColumn: Int, endColumn: Int) {
        val terminal = TerminalEmulator(output, 10, 4, 12, 24, 100, null)
        // Include wide glyphs and enough lines to populate scrollback. Hide the cursor.
        val bytes = "\u001b[?25l一23456789\r\n二23456789\r\n三23456789\r\n四23456789\r\n五23456789\r\n六23456789".toByteArray()
        terminal.append(bytes, bytes.size)
        val renderer = TerminalRenderer(20, Typeface.MONOSPACE)
        fun render(y1: Int, y2: Int, x1: Int, x2: Int): Bitmap =
            Bitmap.createBitmap(240, 200, Bitmap.Config.ARGB_8888).also {
                renderer.render(terminal, Canvas(it), topRow, y1, y2, x1, x2)
            }
        val actual = render(startRow, endRow, startColumn, endColumn)
        val unselected = render(-1, -1, -1, -1)
        // Compose the expected image from independent, already-working single-row selections.
        val expectedPixels = IntArray(actual.width * actual.height)
        unselected.getPixels(expectedPixels, 0, actual.width, 0, 0, actual.width, actual.height)
        for (row in startRow..endRow) {
            val singleRow = render(row, row, if (row == startRow) startColumn else 0,
                if (row == endRow) endColumn else 9)
            for (y in 0 until actual.height) for (x in 0 until actual.width) {
                val pixel = singleRow.getPixel(x, y)
                if (pixel != unselected.getPixel(x, y)) expectedPixels[y * actual.width + x] = pixel
            }
            singleRow.recycle()
        }
        for (y in 0 until actual.height) for (x in 0 until actual.width) {
            assertEquals("pixel ($x, $y), viewport $topRow", expectedPixels[y * actual.width + x], actual.getPixel(x, y))
        }
        actual.recycle()
        unselected.recycle()
    }
}
