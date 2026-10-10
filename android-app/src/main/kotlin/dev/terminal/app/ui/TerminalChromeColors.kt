package dev.terminal.app.ui

import androidx.compose.material3.ColorScheme
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
    val appBarIcon: Color = mutedForeground(background, foreground, minimumPercent = 70),
    val colorScheme: ColorScheme? = null,
    val buttonContainer: Color = colorScheme?.surfaceContainerHigh ?: container,
    val onButtonContainer: Color = colorScheme?.onSurface ?: onContainer,
    val selectedTabContainer: Color = colorScheme?.primaryContainer ?: container,
    val selectedTabContent: Color = colorScheme?.onPrimaryContainer ?: accent,
    val selectedTabSupporting: Color = colorScheme?.onPrimaryContainer ?: onContainer,
) {
    val darkSystemIcons = readableForeground(background) == Color.Black

    fun interpolate(to: TerminalChromeColors, fraction: Float): TerminalChromeColors {
        if (fraction <= 0f) return this
        if (fraction >= 1f) return to
        val background = androidx.compose.ui.graphics.lerp(background, to.background, fraction)
        return TerminalChromeColors(
            background,
            androidx.compose.ui.graphics.lerp(foreground, to.foreground, fraction),
            androidx.compose.ui.graphics.lerp(container, to.container, fraction),
            androidx.compose.ui.graphics.lerp(onContainer, to.onContainer, fraction),
            androidx.compose.ui.graphics.lerp(accent, to.accent, fraction),
            androidx.compose.ui.graphics.lerp(divider, to.divider, fraction),
            androidx.compose.ui.graphics.lerp(checkedContainer, to.checkedContainer, fraction),
            androidx.compose.ui.graphics.lerp(onCheckedContainer, to.onCheckedContainer, fraction),
            androidx.compose.ui.graphics.lerp(unselectedTabContent, to.unselectedTabContent, fraction),
            androidx.compose.ui.graphics.lerp(appBarIcon, to.appBarIcon, fraction),
            interpolateTerminalColorScheme(colorScheme, to.colorScheme, fraction, background),
            androidx.compose.ui.graphics.lerp(buttonContainer, to.buttonContainer, fraction),
            androidx.compose.ui.graphics.lerp(onButtonContainer, to.onButtonContainer, fraction),
            androidx.compose.ui.graphics.lerp(selectedTabContainer, to.selectedTabContainer, fraction),
            androidx.compose.ui.graphics.lerp(selectedTabContent, to.selectedTabContent, fraction),
            androidx.compose.ui.graphics.lerp(selectedTabSupporting, to.selectedTabSupporting, fraction),
        )
    }
}

internal fun colorContrast(first: Color, second: Color): Float {
    val a = first.luminance() + 0.05f
    val b = second.luminance() + 0.05f
    return maxOf(a, b) / minOf(a, b)
}

private fun readableForeground(background: Color): Color =
    if (colorContrast(Color.Black, background) >= colorContrast(Color.White, background)) Color.Black else Color.White

private fun mutedForeground(background: Color, foreground: Color, minimumPercent: Int = 60): Color =
    (minimumPercent..100 step 5).asSequence()
        .map { mix(background, foreground, it / 100f) }
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
