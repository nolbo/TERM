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
            val colors = TerminalAppearance.PaletteColors("#123456", "#ABCDEF", "#00ff00")
            TerminalAppearance.saveCustomPalette(preferences, colors)
            assertEquals(TerminalAppearance.CUSTOM_PALETTE, preferences.getString(TerminalAppearance.KEY_PALETTE, null))
            assertEquals(TerminalAppearance.PaletteColors("#123456", "#ABCDEF", "#00FF00"), TerminalAppearance.paletteColors(preferences))
            assertTrue(TerminalAppearance.isHexColor("#aBc123"))
            assertFalse(TerminalAppearance.isHexColor("#12345"))
            assertFalse(TerminalAppearance.isHexColor("rgb:ff/ff/ff"))
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
