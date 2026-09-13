package dev.terminal.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalAppearanceTest {
    @Test fun customPalettePersistsValidRgbColorsAndRejectsInvalidValues() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("palette-test-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE)
        try {
            val ansi = List(16) { index -> "#%06X".format(0x101010 + index) }
            val colors = TerminalAppearance.PaletteColors("#123456", "#ABCDEF", "#00ff00", ansi)
            TerminalAppearance.saveCustomPalette(preferences, colors)
            assertEquals(TerminalAppearance.CUSTOM_PALETTE, preferences.getString(TerminalAppearance.KEY_PALETTE, null))
            val restored = TerminalAppearance.paletteColors(preferences)
            assertEquals(
                TerminalAppearance.PaletteColors("#123456", "#ABCDEF", "#00FF00", ansi),
                restored,
            )
            ansi.forEachIndexed { index, color -> assertEquals(color, restored.asProperties().getProperty("color$index")) }
            assertTrue(TerminalAppearance.isHexColor("#aBc123"))
            assertFalse(TerminalAppearance.isHexColor("#12345"))
            assertFalse(TerminalAppearance.isHexColor("rgb:ff/ff/ff"))
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
