package dev.terminal.app

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
}
