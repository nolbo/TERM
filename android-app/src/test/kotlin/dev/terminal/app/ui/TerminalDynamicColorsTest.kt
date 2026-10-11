package dev.terminal.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class TerminalDynamicColorsTest {
    @Test fun normalControlsStayInTheBackgroundColorFamily() {
        for (background in listOf(Color(0xFF301B26), Color(0xFF163025), Color(0xFF1E2431),
            Color(0xFFF0E0D0), Color(0xFF407840))) {
            val source = Hct.fromInt(background.toArgb())
            val button = Hct.fromInt(terminalDynamicColors(background).buttonContainer.toArgb())
            val difference = abs(source.hue - button.hue)
            assertTrue("Routine controls rotate away from $background: hue ${button.hue} versus ${source.hue}",
                minOf(difference, 360 - difference) <= 15)
            assertTrue("Routine controls should use subdued surface colors for $background", button.chroma <= 24)
        }
    }

    @Test fun coloredBackgroundsGenerateDifferentExpressiveRoles() {
        val warm = terminalDynamicColors(Color(0xFF301B26))
        val cool = terminalDynamicColors(Color(0xFF163025))
        assertNotEquals(warm.accent, cool.accent)
        assertNotEquals(warm.buttonContainer, cool.buttonContainer)
        assertNotEquals(warm.selectedTabContainer, cool.selectedTabContainer)
        assertNotEquals(Color.White, warm.foreground)
        assertNotEquals(Color.Black, warm.foreground)
    }

    @Test fun normalControlsRemainDistinctFromLightDarkAndMidtoneCanvases() {
        val samples = mutableListOf(Color(0xFF301B26), Color(0xFF163025), Color(0xFFF0E0D0))
        for (r in 0..255 step 85) for (g in 0..255 step 85) for (b in 0..255 step 85)
            samples += Color(r, g, b)
        for (gray in 0..255) samples += Color(gray, gray, gray)
        for (background in samples) {
            val colors = terminalDynamicColors(background)
            val surfaceContrast = colorContrast(colors.buttonContainer, background)
            assertTrue("Button blends into $background: surface contrast $surfaceContrast",
                surfaceContrast >= 2f)
            assertTrue("Button blends into the toolbar sheet for $background",
                colorContrast(colors.buttonContainer, colors.sheetBackground) >= 2f)
            assertTrue("Button label loses contrast on $background",
                colorContrast(colors.onButtonContainer, colors.buttonContainer) >= 4.5f)
        }
    }

    @Test fun generatedRolesKeepTheExactTuiBackground() {
        val background = Color(0xFF407840)
        val colors = terminalDynamicColors(background)
        assertEquals(background, colors.background)
        assertEquals(background, requireNotNull(colors.colorScheme).background)
        assertEquals(background, colors.colorScheme.surface)
        assertNotEquals(colors.container, colors.buttonContainer)
        assertNotEquals(colors.buttonContainer, colors.selectedTabContainer)
    }

    @Test fun rolesRemainReadableOnLightDarkSaturatedAndMidtoneBackgrounds() {
        val samples = mutableListOf<Color>()
        for (r in 0..255 step 85) for (g in 0..255 step 85) for (b in 0..255 step 85)
            samples += Color(r, g, b)
        for (gray in 0..255 step 17) samples += Color(gray, gray, gray)
        samples += listOf(Color(0xFF747474), Color(0xFF777777), Color(0xFF407840))
        for (background in samples) {
            val colors = terminalDynamicColors(background)
            listOf(
                colors.foreground to background,
                colors.unselectedTabContent to background,
                colors.appBarIcon to background,
                colors.foreground to colors.sheetBackground,
                colors.unselectedTabContent to colors.sheetBackground,
                colors.appBarIcon to colors.sheetBackground,
                colors.accent to colors.sheetBackground,
                colors.accent to background,
                colors.accent to colors.container,
                colors.onContainer to colors.container,
                colors.onButtonContainer to colors.buttonContainer,
                colors.onCheckedContainer to colors.checkedContainer,
                colors.selectedTabContent to colors.selectedTabContainer,
            ).forEach { (foreground, surface) ->
                assertTrue("Insufficient role contrast for $background: $foreground on $surface",
                    colorContrast(foreground, surface) >= 4.5f)
            }
        }
    }

    @Test fun repeatedOutputFramesReuseGeneratedColors() {
        val background = Color(0xFF123456)
        assertSame(terminalDynamicColors(background), terminalDynamicColors(background))
    }

    @Test fun modalContentAndActionsRemainReadableOnGeneratedSurfaces() {
        val samples = mutableListOf(Color(0xFF301B26), Color(0xFF163025), Color(0xFFF0E0D0))
        for (r in 0..255 step 85) for (g in 0..255 step 85) for (b in 0..255 step 85)
            samples += Color(r, g, b)
        for (gray in 0..255 step 17) samples += Color(gray, gray, gray)
        for (background in samples) {
            val scheme = requireNotNull(terminalDynamicColors(background).colorScheme)
            listOf(
                scheme.onSurface to scheme.surfaceContainerHigh,
                scheme.onSurfaceVariant to scheme.surfaceContainerHigh,
                scheme.primary to scheme.surfaceContainerHigh,
                scheme.onSurface to scheme.surfaceContainerHighest,
                scheme.onSurfaceVariant to scheme.surfaceContainerHighest,
                scheme.primary to scheme.surfaceContainerHighest,
                scheme.onPrimary to scheme.primary,
            ).forEach { (foreground, surface) ->
                assertTrue("Modal loses contrast for $background: $foreground on $surface",
                    colorContrast(foreground, surface) >= 4.5f)
            }
        }
    }

    @Test fun paletteTransitionsCarryAllMaterialRolesOnTheSameClock() {
        val from = terminalDynamicColors(Color(0xFF123456))
        val to = terminalDynamicColors(Color(0xFFF0E0D0))
        val frame = from.interpolate(to, 0.5f)
        val scheme = requireNotNull(frame.colorScheme)
        assertEquals(lerp(from.background, to.background, 0.5f), frame.background)
        assertEquals(frame.background, scheme.background)
        assertEquals(frame.background, scheme.surface)
        assertEquals(frame.accent, scheme.primary)
        assertEquals(lerp(from.buttonContainer, to.buttonContainer, 0.5f), frame.buttonContainer)
        assertEquals(lerp(from.sheetBackground, to.sheetBackground, 0.5f), frame.sheetBackground)
        assertEquals(lerp(requireNotNull(from.colorScheme).tertiaryFixed,
            requireNotNull(to.colorScheme).tertiaryFixed, 0.5f), scheme.tertiaryFixed)
        assertSame(from, from.interpolate(to, 0f))
        assertSame(to, from.interpolate(to, 1f))
    }

    @Test fun disablingDynamicColorsRestoresTheLegacyRoles() {
        val background = Color(0xFF123456)
        val dynamic = terminalDynamicColors(background)
        val legacy = TerminalChromeColors(background)
        assertNull(legacy.colorScheme)
        assertSame(legacy, dynamic.interpolate(legacy, 1f))
        assertEquals(legacy.container, legacy.buttonContainer)
        assertEquals(legacy.accent, legacy.selectedTabContent)
        val frame = dynamic.interpolate(legacy, 0.5f)
        assertEquals(lerp(dynamic.buttonContainer, legacy.buttonContainer, 0.5f), frame.buttonContainer)
        assertEquals(lerp(dynamic.selectedTabContent, legacy.selectedTabContent, 0.5f), frame.selectedTabContent)
        assertEquals(lerp(dynamic.selectedTabSupporting, legacy.selectedTabSupporting, 0.5f), frame.selectedTabSupporting)
    }
}
