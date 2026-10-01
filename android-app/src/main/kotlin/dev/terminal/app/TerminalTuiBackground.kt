package dev.terminal.app

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle

/** Renderer-only inference: never changes the palette exposed to the terminal application. */
internal fun terminalChromeBackground(
    emulator: TerminalEmulator,
    followTuiBackground: Boolean,
    palette: IntArray = emulator.mColors.mCurrentColors,
): Int {
    val fallback = palette[TextStyle.COLOR_INDEX_BACKGROUND]
    if (!followTuiBackground || !emulator.isAlternateBufferActive) return fallback

    // Bound work per output frame, and sample the whole screen rather than a status line/corner.
    val rows = minOf(emulator.mRows, 32)
    val columns = minOf(emulator.mColumns, 32)
    val counts = linkedMapOf<Int, Int>()
    for (y in 0 until rows) for (x in 0 until columns) {
        val row = ((2L * y + 1) * emulator.mRows / (2 * rows)).toInt()
        val column = ((2L * x + 1) * emulator.mColumns / (2 * columns)).toInt()
        val style = emulator.screen.getStyleAt(row, column)
        val effects = TextStyle.decodeEffect(style)
        val inverse = emulator.isReverseVideo xor (effects and TextStyle.CHARACTER_ATTRIBUTE_INVERSE != 0)
        var color = if (inverse) TextStyle.decodeForeColor(style) else TextStyle.decodeBackColor(style)
        if (color ushr 24 != 0xff) {
            if (inverse && color < 8 && effects and
                (TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_BLINK) != 0) color += 8
            color = palette[color]
        }
        counts[color] = (counts[color] ?: 0) + 1
    }
    // Prefer the palette background on a tie; otherwise keep the first sampled dominant color.
    var result = fallback
    var largest = counts[fallback] ?: 0
    counts.forEach { (color, count) ->
        if (count > largest) { result = color; largest = count }
    }
    return result
}
