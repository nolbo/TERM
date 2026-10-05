package com.termux.terminal;

public class BackgroundMetadataTest extends TerminalTestCase {
    public void testOscQueriesAndInvalidColorsDoNotDeclareBackground() {
        withTerminalSized(80, 24);
        enterString("\033]11;?\007\033]10;?\033\\\033]11;invalid\007\033[48;2;33;58;43m");
        assertFalse(mTerminal.mColors.isBackgroundColorSet());
        assertTrue(mOutput.getOutputAndClear().contains("\033]11;rgb:"));
        enterString("\033]11;#000000\007");
        assertTrue(mTerminal.mColors.isBackgroundColorSet());
        enterString("\033]110\007\033]104;2\007");
        assertTrue(mTerminal.mColors.isBackgroundColorSet());
        enterString("\033]111\007");
        assertFalse(mTerminal.mColors.isBackgroundColorSet());
        enterString("\033]10;#ffffff;#000000\007");
        assertTrue(mTerminal.mColors.isBackgroundColorSet());
        enterString("\033c");
        assertFalse(mTerminal.mColors.isBackgroundColorSet());
    }

    public void testFullDisplayEraseIsSeparateFromLocalDiffAndStatusLines() {
        withTerminalSized(80, 24);
        enterString("\033[?1049h\033[48;2;18;52;86m\033[2J");
        long base = mTerminal.getScreen().getLastFullScreenClearStyle();
        enterString("\033[42m");
        for (int row = 1; row <= 24; row++) enterString("\033[" + row + ";1H\033[2K");
        assertEquals(base, mTerminal.getScreen().getLastFullScreenClearStyle().longValue());
        enterString("\033[48;2;240;224;208m\033[H\033[J");
        assertEquals(0xFFF0E0D0, TextStyle.decodeBackColor(mTerminal.getScreen().getLastFullScreenClearStyle()));
        enterString("\033[41m\033[24;80H\033[1J");
        assertEquals(1, TextStyle.decodeBackColor(mTerminal.getScreen().getLastFullScreenClearStyle()));
    }

    public void testNewAlternateApplicationAndSeparateBuffersDoNotShareEraseHints() {
        withTerminalSized(80, 24);
        enterString("\033[44m\033[2J");
        Long mainStyle = mTerminal.getScreen().getLastFullScreenClearStyle();
        enterString("\033[?1049h");
        assertNull(mTerminal.getScreen().getLastFullScreenClearStyle());
        enterString("\033[42m\033[2J\033[?1049l");
        assertEquals(mainStyle, mTerminal.getScreen().getLastFullScreenClearStyle());
        enterString("\033[?1049h");
        assertNull(mTerminal.getScreen().getLastFullScreenClearStyle());
    }
}
