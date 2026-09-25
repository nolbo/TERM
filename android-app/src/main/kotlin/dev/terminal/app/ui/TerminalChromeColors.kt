package dev.terminal.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Local terminal chrome colors; shared by tabs, hotkeys, and the new-session menu. */
internal data class TerminalChromeColors(
    val background: Color,
    val foreground: Color = readableForeground(background),
    val container: Color = mix(background, foreground, 0.14f),
    val onContainer: Color = readableForeground(container),
    val accent: Color = readableAccent(container),
    val divider: Color = mix(background, foreground, 0.4f),
    val checkedContainer: Color = readableAccent(background),
    val onCheckedContainer: Color = readableForeground(checkedContainer),
    val unselectedTabContent: Color = mutedForeground(background, foreground),
) {
    val darkSystemIcons = readableForeground(background) == Color.Black

    fun interpolate(to: TerminalChromeColors, fraction: Float) = TerminalChromeColors(
        androidx.compose.ui.graphics.lerp(background, to.background, fraction),
        androidx.compose.ui.graphics.lerp(foreground, to.foreground, fraction),
        androidx.compose.ui.graphics.lerp(container, to.container, fraction),
        androidx.compose.ui.graphics.lerp(onContainer, to.onContainer, fraction),
        androidx.compose.ui.graphics.lerp(accent, to.accent, fraction),
        androidx.compose.ui.graphics.lerp(divider, to.divider, fraction),
        androidx.compose.ui.graphics.lerp(checkedContainer, to.checkedContainer, fraction),
        androidx.compose.ui.graphics.lerp(onCheckedContainer, to.onCheckedContainer, fraction),
        androidx.compose.ui.graphics.lerp(unselectedTabContent, to.unselectedTabContent, fraction),
    )
}

internal fun colorContrast(first: Color, second: Color): Float {
    val a = first.luminance() + 0.05f
    val b = second.luminance() + 0.05f
    return maxOf(a, b) / minOf(a, b)
}

private fun readableForeground(background: Color): Color =
    if (colorContrast(Color.Black, background) >= colorContrast(Color.White, background)) Color.Black else Color.White

private fun mutedForeground(background: Color, foreground: Color): Color =
    (12..20).asSequence()
        .map { mix(background, foreground, it / 20f) }
        .firstOrNull { colorContrast(it, background) >= 4.5f }
        ?: foreground

private fun readableAccent(background: Color): Color =
    listOf(Color(0xFF8AB4F8), Color(0xFF124A91)).firstOrNull { colorContrast(it, background) >= 4.5f }
        ?: readableForeground(background)

private fun mix(background: Color, foreground: Color, fraction: Float) = Color(
    red = background.red * (1 - fraction) + foreground.red * fraction,
    green = background.green * (1 - fraction) + foreground.green * fraction,
    blue = background.blue * (1 - fraction) + foreground.blue * fraction,
)
