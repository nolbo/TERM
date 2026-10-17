package dev.terminal.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalChromeColorsTest {
    @Test fun readableAcrossLightDarkAndSaturatedBackgrounds() {
        for (r in 0..255 step 17) for (g in 0..255 step 17) for (b in 0..255 step 17) {
            val colors = TerminalChromeColors(Color(r, g, b))
            listOf(
                colors.foreground to colors.background,
                colors.unselectedTabContent to colors.background,
                colors.appBarIcon to colors.background,
                colors.foreground to colors.sheetBackground,
                colors.unselectedTabContent to colors.sheetBackground,
                colors.unfocusedSplitTabContent to colors.selectedTabContainer,
                colors.appBarIcon to colors.sheetBackground,
                colors.checkedContainer to colors.sheetBackground,
                colors.onContainer to colors.container,
                colors.accent to colors.container,
                colors.onCheckedContainer to colors.checkedContainer,
            ).forEach { (foreground, background) ->
                assertTrue("Insufficient contrast for $r,$g,$b", colorContrast(foreground, background) >= 4.5f)
            }
            assertNotEquals("Split focus must remain visible for $r,$g,$b",
                colors.selectedTabContent.toArgb(), colors.unfocusedSplitTabContent.toArgb())
        }
    }

    @Test fun tabsAndAppBarIconsHaveLessContrastThanPrimaryContent() {
        for (background in listOf(Color.Black, Color.White, Color(0xFF121212))) {
            val colors = TerminalChromeColors(background)
            assertTrue(colorContrast(colors.unselectedTabContent, background) <
                colorContrast(colors.appBarIcon, background))
            assertTrue(colorContrast(colors.appBarIcon, background) <
                colorContrast(colors.foreground, background))
        }
    }

    @Test fun systemIconsFollowBackground() {
        assertTrue(TerminalChromeColors(Color.White).darkSystemIcons)
        assertTrue(!TerminalChromeColors(Color.Black).darkSystemIcons)
        assertTrue(TerminalChromeColors(Color.White).container != TerminalChromeColors(Color.Black).container)
    }

    @Test fun sheetIsSubtlyDistinctRegardlessOfDynamicColorsAndKeepsTheCanvasExact() {
        for (background in listOf(Color.Black, Color.White, Color(0xFF301B26), Color(0xFF163025),
            Color(0xFF747474), Color(0xFF777777), Color(0xFFF0E0D0), Color.Blue)) {
            val legacy = TerminalChromeColors(background)
            val dynamic = terminalDynamicColors(background)
            assertEquals(background, legacy.background)
            assertEquals(background, dynamic.background)
            assertEquals(legacy.sheetBackground, dynamic.sheetBackground)
            assertNotEquals(background.toArgb(), legacy.sheetBackground.toArgb())
            assertTrue("The sheet offset should be subtle for $background",
                colorContrast(background, legacy.sheetBackground) <= 1.4f)
        }
    }

    @Test fun sheetFollowsThePaletteTransitionClock() {
        val from = TerminalChromeColors(Color.Black)
        val to = terminalDynamicColors(Color(0xFFF0E0D0))
        assertEquals(lerp(from.sheetBackground, to.sheetBackground, 0.5f),
            from.interpolate(to, 0.5f).sheetBackground)
    }
}
