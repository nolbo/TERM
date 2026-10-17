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
    val checkedContainer: Color = readableAccent(background, terminalSheetBackground(background)),
    val onCheckedContainer: Color = readableForeground(checkedContainer),
    val unselectedTabContent: Color = mutedForeground(background, foreground),
    val appBarIcon: Color = mutedForeground(background, foreground, minimumPercent = 70),
    val colorScheme: ColorScheme? = null,
    val buttonContainer: Color = colorScheme?.surfaceContainerHigh ?: container,
    val onButtonContainer: Color = colorScheme?.onSurface ?: onContainer,
    val selectedTabContainer: Color = colorScheme?.primaryContainer ?: container,
    val selectedTabContent: Color = colorScheme?.onPrimaryContainer ?: accent,
    val selectedTabSupporting: Color = colorScheme?.onPrimaryContainer ?: onContainer,
    val sheetBackground: Color = terminalSheetBackground(background),
) {
    val darkSystemIcons = readableForeground(background) == Color.Black
    val unfocusedSplitTabContent: Color
        get() {
            val foreground = readableForeground(selectedTabContainer)
            val muted = (60..100).asSequence()
                .map { mix(selectedTabContainer, foreground, it / 100f) }
                .firstOrNull { colorContrast(it, selectedTabContainer) >= 4.5f } ?: foreground
            val alternative = if (foreground == Color.White) Color.Black else Color.White
            return if (muted == selectedTabContent && colorContrast(alternative, selectedTabContainer) >= 4.5f)
                alternative else muted
        }

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
            androidx.compose.ui.graphics.lerp(sheetBackground, to.sheetBackground, fraction),
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

/** A subtle surface offset shared by shell and dynamic TUI chrome, with readable content. */
internal fun terminalSheetBackground(background: Color): Color {
    val foreground = readableForeground(background)
    val tinted = mix(background, foreground, 0.06f)
    // Near the black/white contrast crossover, move away from the text instead.
    return if (colorContrast(foreground, tinted) >= 4.5f) tinted
        else mix(background, if (foreground == Color.White) Color.Black else Color.White, 0.06f)
}

private fun mutedForeground(background: Color, foreground: Color, minimumPercent: Int = 60): Color =
    (minimumPercent..100 step 5).asSequence()
        .map { mix(background, foreground, it / 100f) }
        .firstOrNull { colorContrast(it, background) >= 4.5f &&
            colorContrast(it, terminalSheetBackground(background)) >= 4.5f }
        ?: foreground

private fun readableAccent(background: Color, secondaryBackground: Color = background): Color =
    listOf(Color(0xFF8AB4F8), Color(0xFF124A91)).firstOrNull { colorContrast(it, background) >= 4.5f &&
        colorContrast(it, secondaryBackground) >= 4.5f }
        ?: readableForeground(background)

private fun mix(background: Color, foreground: Color, fraction: Float) = Color(
    red = background.red * (1 - fraction) + foreground.red * fraction,
    green = background.green * (1 - fraction) + foreground.green * fraction,
    blue = background.blue * (1 - fraction) + foreground.blue * fraction,
)
