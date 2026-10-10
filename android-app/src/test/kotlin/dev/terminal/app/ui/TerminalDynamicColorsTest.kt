package dev.terminal.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import org.junit.Assert.*
import org.junit.Test

class TerminalDynamicColorsTest {
    @Test fun coloredBackgroundsGenerateDifferentExpressiveRoles() {
        val warm = terminalDynamicColors(Color(0xFF301B26))
        val cool = terminalDynamicColors(Color(0xFF163025))
        assertNotEquals(warm.accent, cool.accent)
        assertNotEquals(warm.buttonContainer, cool.buttonContainer)
        assertNotEquals(warm.selectedTabContainer, cool.selectedTabContainer)
        assertNotEquals(Color.White, warm.foreground)
        assertNotEquals(Color.Black, warm.foreground)
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
