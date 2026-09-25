package com.termux.terminal;

import org.junit.Test;
import java.util.Properties;
import static org.junit.Assert.*;

public class SessionPaletteTest {
    @Test public void defaultsAndOscResetsAreIndependentAcrossSessions() {
        TerminalColors first = new TerminalColors();
        TerminalColors second = new TerminalColors();
        Properties dark = new Properties();
        dark.setProperty("background", "#123456");
        dark.setProperty("color1", "#223344");
        Properties light = new Properties();
        light.setProperty("background", "#EFEFEF");
        light.setProperty("color1", "#556677");
        assertTrue(first.setDefaultColors(dark));
        assertTrue(second.setDefaultColors(light));
        first.tryParseColor(1, "#ABCDEF");
        assertFalse(first.setDefaultColors(dark));
        assertEquals(0xFFABCDEF, first.mCurrentColors[1]);
        second.reset();
        assertEquals(0xFF556677, second.mCurrentColors[1]);
        first.reset(1);
        assertEquals(0xFF223344, first.mCurrentColors[1]);
        first.reset();
        assertEquals(0xFF123456, first.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);
        assertEquals(0xFFEFEFEF, second.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);
        assertTrue(first.setDefaultColors(light));
        assertEquals(0xFFEFEFEF, first.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);
    }
}
