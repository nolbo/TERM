package dev.terminal.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Local terminal chrome colors; settings and popup surfaces retain the app theme. */
internal data class TerminalChromeColors(val background: Color) {
    val foreground = readableForeground(background)
    val container = mix(background, foreground, 0.14f)
    val onContainer = readableForeground(container)
    val accent = readableAccent(container)
    val divider = mix(background, foreground, 0.4f)
    val checkedContainer = readableAccent(background)
    val onCheckedContainer = readableForeground(checkedContainer)
    val darkSystemIcons = foreground == Color.Black
}

internal fun colorContrast(first: Color, second: Color): Float {
    val a = first.luminance() + 0.05f
    val b = second.luminance() + 0.05f
    return maxOf(a, b) / minOf(a, b)
}

private fun readableForeground(background: Color): Color =
    if (colorContrast(Color.Black, background) >= colorContrast(Color.White, background)) Color.Black else Color.White

private fun readableAccent(background: Color): Color =
    listOf(Color(0xFF8AB4F8), Color(0xFF124A91)).firstOrNull { colorContrast(it, background) >= 4.5f }
        ?: readableForeground(background)

private fun mix(background: Color, foreground: Color, fraction: Float) = Color(
    red = background.red * (1 - fraction) + foreground.red * fraction,
    green = background.green * (1 - fraction) + foreground.green * fraction,
    blue = background.blue * (1 - fraction) + foreground.blue * fraction,
)
