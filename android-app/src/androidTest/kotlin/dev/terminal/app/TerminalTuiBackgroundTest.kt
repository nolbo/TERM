package dev.terminal.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import com.termux.view.TerminalRenderer
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TextStyle
import org.junit.Assert.*
import org.junit.Test

class TerminalTuiBackgroundTest {
    private fun emulator() = TerminalEmulator(object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }, 80, 24, 10, 20, 1000, TerminalCallbacks {})

    private fun TerminalEmulator.output(text: String) {
        val bytes = text.toByteArray()
        append(bytes, bytes.size)
    }

    @Test fun optInOnlyFollowsAlternateScreenAndRestoresPaletteOnExit() {
        val emulator = emulator()
        val original = emulator.mColors.mCurrentColors.clone()
        emulator.output("\u001b[48;2;18;52;86m\u001b[2J")
        assertEquals(original[TextStyle.COLOR_INDEX_BACKGROUND], terminalChromeBackground(emulator, true))
        emulator.output("\u001b[?1049h\u001b[48;2;18;52;86m\u001b[2J")
        assertEquals(original[TextStyle.COLOR_INDEX_BACKGROUND], terminalChromeBackground(emulator, false))
        assertEquals(0xFF123456.toInt(), terminalChromeBackground(emulator, true))
        assertArrayEquals(original, emulator.mColors.mCurrentColors)
        emulator.output("\u001b[?1049l")
        assertEquals(original[TextStyle.COLOR_INDEX_BACKGROUND], terminalChromeBackground(emulator, true))
    }

    @Test fun dominantBackgroundIgnoresStatusLinesAndUpdatesWithTheme() {
        val emulator = emulator()
        emulator.output("\u001b[?1049h\u001b[48;2;18;52;86m\u001b[2J" +
            "\u001b[H\u001b[41m\u001b[2K\u001b[24;1H\u001b[42m\u001b[2K")
        assertEquals(0xFF123456.toInt(), terminalChromeBackground(emulator, true))
        emulator.output("\u001b[48;2;240;224;208m\u001b[2J")
        assertEquals(0xFFF0E0D0.toInt(), terminalChromeBackground(emulator, true))
    }

    @Test fun indexedOscAndInverseColorsMatchRenderer() {
        val emulator = emulator()
        emulator.output("\u001b[?1049h\u001b[44m\u001b[2J")
        assertEquals(emulator.mColors.mCurrentColors[4], terminalChromeBackground(emulator, true))
        emulator.output("\u001b[48;5;123m\u001b[2J")
        assertEquals(emulator.mColors.mCurrentColors[123], terminalChromeBackground(emulator, true))
        emulator.output("\u001b]4;123;#123456\u0007")
        assertEquals(0xFF123456.toInt(), terminalChromeBackground(emulator, true))
        emulator.output("\u001b[0;31;7;1m\u001b[2J")
        assertEquals(emulator.mColors.mCurrentColors[9], terminalChromeBackground(emulator, true))
        emulator.output("\u001b[?5h")
        assertEquals(emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND], terminalChromeBackground(emulator, true))
        emulator.output("\u001b[?5l\u001b[0m\u001b]11;#ABCDEF\u0007\u001b[2J")
        assertEquals(0xFFABCDEF.toInt(), terminalChromeBackground(emulator, true))
    }

    @Test fun canvasFillFollowsTuiWithoutRecoloringCellsAndRestoresWhenDisabled() {
        val emulator = emulator()
        val palette = emulator.mColors.mCurrentColors.clone()
        val renderer = TerminalRenderer(20, Typeface.MONOSPACE)
        val spacing = TerminalRenderer::class.java.getDeclaredField("mFontLineSpacing")
            .apply { isAccessible = true }.getInt(renderer)
        val top = TerminalRenderer::class.java.getDeclaredField("mFontLineSpacingAndAscent")
            .apply { isAccessible = true }.getInt(renderer)
        fun render(): Bitmap = Bitmap.createBitmap(1500, 1000, Bitmap.Config.ARGB_8888).also {
            renderer.render(emulator, Canvas(it), 0, -1, -1, -1, -1)
        }
        emulator.output("\u001b[?1049h\u001b[?25l\u001b[48;2;18;52;86m\u001b[2J" +
            "\u001b[2;1H\u001b[0m\u001b[2K")
        val tuiColor = terminalChromeBackground(emulator, true)
        renderer.setCanvasBackgroundOverride(tuiColor)
        render().also {
            assertEquals(tuiColor, it.getPixel(0, 0))
            assertEquals(tuiColor, it.getPixel(1499, 100))
            assertEquals(tuiColor, it.getPixel(0, 999))
            assertEquals(tuiColor, it.getPixel(2, top + spacing / 2))
            assertEquals(palette[TextStyle.COLOR_INDEX_BACKGROUND], it.getPixel(2, top + spacing + spacing / 2))
            it.recycle()
        }
        // Reverse video must not repaint the canvas fill after it has been overridden.
        emulator.output("\u001b[?5h")
        renderer.setCanvasBackgroundOverride(terminalChromeBackground(emulator, true))
        render().also {
            assertEquals(palette[TextStyle.COLOR_INDEX_FOREGROUND], it.getPixel(1499, 100))
            it.recycle()
        }
        emulator.output("\u001b[?5l")
        renderer.setCanvasBackgroundOverride(null)
        render().also {
            assertEquals(palette[TextStyle.COLOR_INDEX_BACKGROUND], it.getPixel(1499, 100))
            assertEquals(tuiColor, it.getPixel(2, top + spacing / 2))
            it.recycle()
        }
        assertArrayEquals(palette, emulator.mColors.mCurrentColors)
    }
}
