package dev.terminal.app.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalChromeColorsTest {
    @Test fun readableAcrossLightDarkAndSaturatedBackgrounds() {
        for (r in 0..255 step 17) for (g in 0..255 step 17) for (b in 0..255 step 17) {
            val colors = TerminalChromeColors(Color(r, g, b))
            listOf(
                colors.foreground to colors.background,
                colors.unselectedTabContent to colors.background,
                colors.onContainer to colors.container,
                colors.accent to colors.container,
                colors.onCheckedContainer to colors.checkedContainer,
            ).forEach { (foreground, background) ->
                assertTrue("Insufficient contrast for $r,$g,$b", colorContrast(foreground, background) >= 4.5f)
            }
        }
    }

    @Test fun inactiveTabsHaveLessContrastThanPrimaryContent() {
        for (background in listOf(Color.Black, Color.White, Color(0xFF121212))) {
            val colors = TerminalChromeColors(background)
            assertTrue(colorContrast(colors.unselectedTabContent, background) <
                colorContrast(colors.foreground, background))
        }
    }

    @Test fun systemIconsFollowBackground() {
        assertTrue(TerminalChromeColors(Color.White).darkSystemIcons)
        assertTrue(!TerminalChromeColors(Color.Black).darkSystemIcons)
        assertTrue(TerminalChromeColors(Color.White).container != TerminalChromeColors(Color.Black).container)
    }
}
