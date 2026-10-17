package dev.terminal.app.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.scheme.SchemeMonochrome
import kotlin.math.abs

/** Bounded cache: output redraws reuse their palette, without another color-generation pass. */
private val terminalDynamicColorCache = object : LinkedHashMap<Int, TerminalChromeColors>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, TerminalChromeColors>) = size > 16
}

internal fun terminalDynamicColors(background: Color, defaultBackground: Color? = null): TerminalChromeColors {
    // Entering alternate screen alone must not recolor the normal terminal UI. Compare with
    // the configured session default, since the live palette can already contain an OSC color.
    if (defaultBackground?.toArgb() == background.toArgb()) return TerminalChromeColors(background)
    return cachedTerminalDynamicColors(background)
}

private fun cachedTerminalDynamicColors(background: Color): TerminalChromeColors = synchronized(terminalDynamicColorCache) {
    terminalDynamicColorCache.getOrPut(background.toArgb()) {
        val isDark = colorContrast(Color.White, background) >= colorContrast(Color.Black, background)
        val seed = Hct.fromInt(background.toArgb())
        // A near-gray seed has no meaningful hue for Expressive's hue rotations to preserve.
        val source = if (seed.chroma <= 8.0) SchemeMonochrome(
            sourceColorHct = seed,
            isDark = isDark,
            contrastLevel = 0.0,
            specVersion = ColorSpec.SpecVersion.SPEC_2025,
            platform = DynamicScheme.Platform.PHONE,
        ) else SchemeExpressive(
            sourceColorHct = seed,
            isDark = isDark,
            contrastLevel = 0.0,
            specVersion = ColorSpec.SpecVersion.SPEC_2025,
            platform = DynamicScheme.Platform.PHONE,
        )
        // Material's standard surface tones differ from an arbitrary TUI background. Keep the
        // canvas exact and adjust foreground tones in the generated HCT palettes for contrast.
        val container = Color(source.surfaceContainerLow)
        val sheet = terminalSheetBackground(background)
        // A generated surface tone can coincide with the exact TUI canvas. Keep the neutral
        // hue/chroma, but separate enabled controls from that canvas and pair their own text.
        // Leave room for rendered color quantization while displaying at least 2:1 contrast.
        val button = terminalRoleWithContrast(Color(source.surfaceContainerHigh), listOf(background, sheet), 2.2f)
        val foreground = terminalRoleForeground(Color(source.onSurface), background, sheet)
        val supporting = terminalRoleForeground(Color(source.onSurfaceVariant), background, container, sheet)
        val primary = terminalRoleForeground(Color(source.primary), background, container, sheet)
        val scheme = (if (isDark) darkColorScheme() else lightColorScheme()).copy(
            primary = primary,
            onPrimary = terminalRoleForeground(Color(source.onPrimary), primary),
            primaryContainer = Color(source.primaryContainer),
            onPrimaryContainer = terminalRoleForeground(Color(source.onPrimaryContainer), Color(source.primaryContainer)),
            inversePrimary = Color(source.inversePrimary),
            // Legacy menus/tonal controls use secondary, while Expressive menus use tertiary.
            // Share the primary family with selected tabs across both sets of defaults.
            secondary = primary,
            onSecondary = terminalRoleForeground(Color(source.onPrimary), primary),
            secondaryContainer = Color(source.primaryContainer),
            onSecondaryContainer = terminalRoleForeground(Color(source.onPrimaryContainer), Color(source.primaryContainer)),
            tertiary = primary,
            onTertiary = terminalRoleForeground(Color(source.onPrimary), primary),
            tertiaryContainer = Color(source.primaryContainer),
            onTertiaryContainer = terminalRoleForeground(Color(source.onPrimaryContainer), Color(source.primaryContainer)),
            background = background,
            onBackground = foreground,
            surface = background,
            onSurface = terminalRoleForeground(Color(source.onSurface), background, container, sheet),
            surfaceVariant = Color(source.surfaceVariant),
            onSurfaceVariant = supporting,
            surfaceTint = primary,
            inverseSurface = Color(source.inverseSurface),
            inverseOnSurface = Color(source.inverseOnSurface),
            error = Color(source.error),
            onError = Color(source.onError),
            errorContainer = Color(source.errorContainer),
            onErrorContainer = Color(source.onErrorContainer),
            outline = Color(source.outline),
            outlineVariant = Color(source.outlineVariant),
            scrim = Color(source.scrim),
            surfaceBright = Color(source.surfaceBright),
            surfaceDim = Color(source.surfaceDim),
            surfaceContainerLowest = Color(source.surfaceContainerLowest),
            surfaceContainerLow = container,
            surfaceContainer = Color(source.surfaceContainer),
            surfaceContainerHigh = Color(source.surfaceContainerHigh),
            surfaceContainerHighest = Color(source.surfaceContainerHighest),
            primaryFixed = Color(source.primaryFixed),
            primaryFixedDim = Color(source.primaryFixedDim),
            onPrimaryFixed = Color(source.onPrimaryFixed),
            onPrimaryFixedVariant = Color(source.onPrimaryFixedVariant),
            secondaryFixed = Color(source.primaryFixed),
            secondaryFixedDim = Color(source.primaryFixedDim),
            onSecondaryFixed = Color(source.onPrimaryFixed),
            onSecondaryFixedVariant = Color(source.onPrimaryFixedVariant),
            tertiaryFixed = Color(source.primaryFixed),
            tertiaryFixedDim = Color(source.primaryFixedDim),
            onTertiaryFixed = Color(source.onPrimaryFixed),
            onTertiaryFixedVariant = Color(source.onPrimaryFixedVariant),
        )
        TerminalChromeColors(
            background = background,
            foreground = foreground,
            container = container,
            onContainer = scheme.onSurface,
            accent = scheme.primary,
            divider = scheme.outlineVariant,
            checkedContainer = scheme.primary,
            onCheckedContainer = scheme.onPrimary,
            unselectedTabContent = supporting,
            appBarIcon = supporting,
            colorScheme = scheme,
            buttonContainer = button,
            onButtonContainer = terminalRoleForeground(Color(source.onSurface), button),
        )
    }
}

/** Find the nearest readable tone while retaining the role's generated hue and chroma. */
private fun terminalRoleForeground(color: Color, background: Color, container: Color = background,
    sheet: Color = background): Color =
    terminalRoleWithContrast(color, listOf(background, container, sheet), 4.5f)

private fun terminalRoleWithContrast(color: Color, backgrounds: List<Color>, minimumContrast: Float): Color {
    fun readable(candidate: Color) = backgrounds.all { colorContrast(candidate, it) >= minimumContrast }
    if (readable(color)) return color
    val source = Hct.fromInt(color.toArgb())
    fun atTone(tone: Double) = Color(Hct.from(source.hue, source.chroma, tone).toInt())
    val endpoints = listOf(0.0, 100.0)
    val endpoint = endpoints.filter { readable(atTone(it)) }.minByOrNull { abs(it - source.tone) }
        ?: return endpoints.map(::atTone).maxBy { candidate -> backgrounds.minOf { colorContrast(candidate, it) } }
    var readableTone = endpoint
    var unreadableTone = source.tone
    repeat(12) {
        val tone = (readableTone + unreadableTone) / 2
        if (readable(atTone(tone))) readableTone = tone else unreadableTone = tone
    }
    return atTone(readableTone)
}

/** Use the existing palette transition clock for Material defaults as well as explicit colors. */
internal fun interpolateTerminalColorScheme(
    from: ColorScheme?, to: ColorScheme?, fraction: Float, background: Color,
): ColorScheme? {
    if (from == null && to == null) return null
    val start = from ?: TerminalDarkColors
    val end = to ?: TerminalDarkColors
    return start.copy(
        primary = lerp(start.primary, end.primary, fraction),
        onPrimary = lerp(start.onPrimary, end.onPrimary, fraction),
        primaryContainer = lerp(start.primaryContainer, end.primaryContainer, fraction),
        onPrimaryContainer = lerp(start.onPrimaryContainer, end.onPrimaryContainer, fraction),
        inversePrimary = lerp(start.inversePrimary, end.inversePrimary, fraction),
        secondary = lerp(start.secondary, end.secondary, fraction),
        onSecondary = lerp(start.onSecondary, end.onSecondary, fraction),
        secondaryContainer = lerp(start.secondaryContainer, end.secondaryContainer, fraction),
        onSecondaryContainer = lerp(start.onSecondaryContainer, end.onSecondaryContainer, fraction),
        tertiary = lerp(start.tertiary, end.tertiary, fraction),
        onTertiary = lerp(start.onTertiary, end.onTertiary, fraction),
        tertiaryContainer = lerp(start.tertiaryContainer, end.tertiaryContainer, fraction),
        onTertiaryContainer = lerp(start.onTertiaryContainer, end.onTertiaryContainer, fraction),
        background = background,
        onBackground = lerp(start.onBackground, end.onBackground, fraction),
        surface = background,
        onSurface = lerp(start.onSurface, end.onSurface, fraction),
        surfaceVariant = lerp(start.surfaceVariant, end.surfaceVariant, fraction),
        onSurfaceVariant = lerp(start.onSurfaceVariant, end.onSurfaceVariant, fraction),
        surfaceTint = lerp(start.surfaceTint, end.surfaceTint, fraction),
        inverseSurface = lerp(start.inverseSurface, end.inverseSurface, fraction),
        inverseOnSurface = lerp(start.inverseOnSurface, end.inverseOnSurface, fraction),
        error = lerp(start.error, end.error, fraction),
        onError = lerp(start.onError, end.onError, fraction),
        errorContainer = lerp(start.errorContainer, end.errorContainer, fraction),
        onErrorContainer = lerp(start.onErrorContainer, end.onErrorContainer, fraction),
        outline = lerp(start.outline, end.outline, fraction),
        outlineVariant = lerp(start.outlineVariant, end.outlineVariant, fraction),
        scrim = lerp(start.scrim, end.scrim, fraction),
        surfaceBright = lerp(start.surfaceBright, end.surfaceBright, fraction),
        surfaceDim = lerp(start.surfaceDim, end.surfaceDim, fraction),
        surfaceContainerLowest = lerp(start.surfaceContainerLowest, end.surfaceContainerLowest, fraction),
        surfaceContainerLow = lerp(start.surfaceContainerLow, end.surfaceContainerLow, fraction),
        surfaceContainer = lerp(start.surfaceContainer, end.surfaceContainer, fraction),
        surfaceContainerHigh = lerp(start.surfaceContainerHigh, end.surfaceContainerHigh, fraction),
        surfaceContainerHighest = lerp(start.surfaceContainerHighest, end.surfaceContainerHighest, fraction),
        primaryFixed = lerp(start.primaryFixed, end.primaryFixed, fraction),
        primaryFixedDim = lerp(start.primaryFixedDim, end.primaryFixedDim, fraction),
        onPrimaryFixed = lerp(start.onPrimaryFixed, end.onPrimaryFixed, fraction),
        onPrimaryFixedVariant = lerp(start.onPrimaryFixedVariant, end.onPrimaryFixedVariant, fraction),
        secondaryFixed = lerp(start.secondaryFixed, end.secondaryFixed, fraction),
        secondaryFixedDim = lerp(start.secondaryFixedDim, end.secondaryFixedDim, fraction),
        onSecondaryFixed = lerp(start.onSecondaryFixed, end.onSecondaryFixed, fraction),
        onSecondaryFixedVariant = lerp(start.onSecondaryFixedVariant, end.onSecondaryFixedVariant, fraction),
        tertiaryFixed = lerp(start.tertiaryFixed, end.tertiaryFixed, fraction),
        tertiaryFixedDim = lerp(start.tertiaryFixedDim, end.tertiaryFixedDim, fraction),
        onTertiaryFixed = lerp(start.onTertiaryFixed, end.onTertiaryFixed, fraction),
        onTertiaryFixedVariant = lerp(start.onTertiaryFixedVariant, end.onTertiaryFixedVariant, fraction),
    )
}
