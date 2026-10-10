package dev.terminal.app

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TextStyle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalTuiBackgroundInferenceTest {
    private val originalBackground = 0xFF123456.toInt()

    private fun emulator() = TerminalEmulator(object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }, 80, 24, 10, 20, 1000, TerminalCallbacks {}).apply {
        output("\u001b[?1049h\u001b[48;2;18;52;86m\u001b[2J")
    }

    private fun TerminalEmulator.output(text: String) {
        val bytes = text.toByteArray()
        append(bytes, bytes.size)
    }

    private fun TerminalEmulator.paintRows(sgr: String, first: Int = 1, last: Int = 24) {
        output("\u001b[${sgr}m")
        for (row in first..last) output("\u001b[$row;1H\u001b[2K")
    }

    @Test fun fullThemeRepaintReplacesStaleEraseWithoutChangingGuestPalette() {
        val emulator = emulator()
        val eraseStyle = emulator.screen.lastFullScreenClearStyle
        val palette = emulator.mColors.mCurrentColors.clone()
        emulator.paintRows("48;2;240;224;208")
        assertEquals(0xFFF0E0D0.toInt(), terminalChromeBackground(emulator, true))
        emulator.paintRows("48;5;123")
        assertEquals(palette[123], terminalChromeBackground(emulator, true))
        assertEquals(eraseStyle, emulator.screen.lastFullScreenClearStyle)
        assertArrayEquals(palette, emulator.mColors.mCurrentColors)
    }

    @Test fun themeRepaintCanKeepSeparatelyColoredStatusRows() {
        val emulator = emulator()
        emulator.paintRows("48;2;240;224;208")
        emulator.paintRows("41", 1, 1)
        emulator.paintRows("42", 24, 24)
        assertEquals(0xFFF0E0D0.toInt(), terminalChromeBackground(emulator, true))
    }

    @Test fun themeRepaintCanReturnToDefaultBackground() {
        val emulator = emulator()
        emulator.paintRows("0")
        emulator.paintRows("41", 1, 1)
        emulator.paintRows("42", 24, 24)
        assertEquals(emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND],
            terminalChromeBackground(emulator, true))
    }

    @Test fun partialHighlightsKeepEraseBackgroundWhileItRemainsVisible() {
        val emulator = emulator()
        emulator.paintRows("48;2;33;58;43", 1, 23)
        assertEquals(originalBackground, terminalChromeBackground(emulator, true))
        emulator.paintRows("0", 1, 23)
        assertEquals(originalBackground, terminalChromeBackground(emulator, true))
    }

    @Test fun mixedRepaintAndDefaultGutterDoNotReplaceEraseBackground() {
        val emulator = emulator()
        emulator.paintRows("41", 1, 12)
        emulator.paintRows("42", 13, 24)
        assertEquals(originalBackground, terminalChromeBackground(emulator, true))
        emulator.paintRows("48;2;240;224;208")
        emulator.output("\u001b[12;1H\u001b[0m ")
        assertEquals(originalBackground, terminalChromeBackground(emulator, true))
    }

    @Test fun explicitOscBackgroundStillWinsOverRepaintedTheme() {
        val emulator = emulator()
        emulator.output("\u001b]11;#000000\u0007")
        emulator.paintRows("48;2;240;224;208")
        assertEquals(0xFF000000.toInt(), terminalChromeBackground(emulator, true))
        emulator.output("\u001b]111\u0007")
        assertEquals(0xFFF0E0D0.toInt(), terminalChromeBackground(emulator, true))
    }
}
