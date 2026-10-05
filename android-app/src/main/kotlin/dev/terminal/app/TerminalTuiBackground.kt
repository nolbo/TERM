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

    // OSC declares the terminal-wide default. An OSC query does not constitute a declaration.
    if (emulator.mColors.isBackgroundColorSet) return if (emulator.isReverseVideo)
        palette[TextStyle.COLOR_INDEX_FOREGROUND] else fallback

    // A full display erase supplies a stable canvas background. Local diff/status line fills
    // cannot replace it, even if they subsequently cover all the visible cells.
    emulator.screen.lastFullScreenClearStyle?.let { style ->
        return resolveTuiBackground(style, emulator.isReverseVideo, palette)
    }

    // Some TUIs paint cells without erasing the display. Require agreement across the screen,
    // preserving SGR's default-background identity instead of treating every RGB as equivalent.
    val rows = minOf(emulator.mRows, 32)
    val columns = minOf(emulator.mColumns, 32)
    val counts = linkedMapOf<Int, Int>()
    val rowCoverage = mutableMapOf<Int, Int>()
    for (y in 0 until rows) {
        val row = sampleTuiCoordinate(y, rows, emulator.mRows)
        val rowColors = mutableSetOf<Int>()
        for (x in 0 until columns) {
            val column = sampleTuiCoordinate(x, columns, emulator.mColumns)
            val style = emulator.screen.getStyleAt(row, column)
            if (tuiBackgroundColorIndex(style, emulator.isReverseVideo) == TextStyle.COLOR_INDEX_BACKGROUND)
                return fallback
            val color = resolveTuiBackground(style, emulator.isReverseVideo, palette)
            counts[color] = (counts[color] ?: 0) + 1
            rowColors += color
        }
        // Count presence once per sampled row, rather than giving long colored lines extra weight.
        rowColors.forEach { color -> rowCoverage[color] = (rowCoverage[color] ?: 0) + 1 }
    }
    val candidate = counts.maxByOrNull { it.value } ?: return fallback
    return candidate.key.takeIf {
        candidate.value * 5 >= rows * columns * 4 &&
            (rowCoverage[it] ?: 0) >= maxOf(1, rows - 2)
    } ?: fallback
}

private fun sampleTuiCoordinate(sample: Int, count: Int, size: Int): Int =
    if (count == 1) 0 else (sample.toLong() * (size - 1) / (count - 1)).toInt()

private fun tuiBackgroundColorIndex(style: Long, reverseVideo: Boolean): Int {
    val effects = TextStyle.decodeEffect(style)
    val inverse = reverseVideo xor (effects and TextStyle.CHARACTER_ATTRIBUTE_INVERSE != 0)
    var color = if (inverse) TextStyle.decodeForeColor(style) else TextStyle.decodeBackColor(style)
    if (color ushr 24 != 0xff && inverse && color < 8 && effects and
        (TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_BLINK) != 0) color += 8
    return color
}

private fun resolveTuiBackground(style: Long, reverseVideo: Boolean, palette: IntArray): Int {
    val color = tuiBackgroundColorIndex(style, reverseVideo)
    return if (color ushr 24 == 0xff) color else palette[color]
}
