package dev.terminal.app

import org.junit.Assert.*
import org.junit.Test

class PalettePickerStateTest {
    @Test fun rgbRoundTripsAcrossColorCube() {
        for (r in 0..255 step 17) for (g in 0..255 step 17) for (b in 0..255 step 17) {
            val hex = "#%02X%02X%02X".format(r, g, b)
            val state = PalettePickerState.fromHex(hex)
            assertEquals(hex, state.hex)
            assertEquals(r.toString(), state.redDraft)
            assertEquals(g.toString(), state.greenDraft)
            assertEquals(b.toString(), state.blueDraft)
            assertTrue(state.canApply)
        }
    }

    @Test fun hueSurvivesBlackGrayAndWhiteGestures() {
        val blue = PalettePickerState.fromHex("#0000FF")
        val black = blue.changeHsv(v = 0f).changeHsv(h = 120f)
        assertEquals("#000000", black.hex)
        assertEquals("#00FF00", black.changeHsv(v = 1f).hex)
        val gray = blue.changeHsv(s = 0f, v = 0.5f).changeHsv(h = 120f)
        assertEquals("#808080", gray.hex)
        assertEquals("#008000", gray.changeHsv(s = 1f).hex)
        assertEquals("#00FF00", gray.changeHsv(v = 1f).changeHsv(s = 1f).hex)
    }

    @Test fun exactInputsSynchronizeWithoutDiscardingIncompleteDrafts() {
        val start = PalettePickerState.fromHex("#123456")
        val incomplete = start.editHex("#FF")
        assertFalse(incomplete.canApply)
        assertEquals(start.rgb, incomplete.rgb)
        val red = incomplete.editHex("ff0000")
        assertEquals("#FF0000", red.hex)
        assertEquals("255", red.redDraft)
        val invalid = red.editRgb(1, "256")
        assertFalse(invalid.canApply)
        assertEquals(red.rgb, invalid.rgb)
        val yellow = invalid.editRgb(1, "255")
        assertEquals("#FFFF00", yellow.hexDraft)
        assertTrue(yellow.canApply)
        val blank = yellow.editRgb(2, "")
        assertFalse(blank.canApply)
        assertEquals(yellow.rgb, blank.rgb)
        assertTrue(blank.changeHsv(h = 240f).canApply)
        assertEquals("#0000FF", blank.changeHsv(h = 240f).hex)
    }

    @Test fun achromaticTextInputKeepsChosenHueAndBlackKeepsSaturation() {
        val blue = PalettePickerState.fromHex("#0000FF")
        assertEquals("#0000FF", blue.editHex("#000000").changeHsv(v = 1f).hex)
        assertEquals("#000080", blue.editHex("#808080").changeHsv(s = 1f).hex)
    }

    @Test fun rangesAndSyntaxAreBounded() {
        for (hex in listOf("", "#123", "#12345678", "GG0000", "##123456")) {
            assertNull(PalettePickerState.parseHex(hex))
        }
        for (rgb in listOf("", "-1", "+1", "256", "1.5", "999999999999", " 1")) {
            assertNull(PalettePickerState.parseChannel(rgb))
        }
        val red = PalettePickerState.fromHex("#FF0000")
        assertEquals("#FFFFFF", red.changeHsv(s = -1f, v = 2f).hex)
        assertEquals("#FF0000", red.changeHsv(h = 360f, s = 2f).hex)
    }
}
