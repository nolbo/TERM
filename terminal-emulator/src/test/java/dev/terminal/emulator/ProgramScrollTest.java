package dev.terminal.emulator;

import java.util.Locale;

public class ProgramScrollTest extends TerminalTestCase {
    public void testCodexPointerCaptureSelectsAnyEventModeAndSgrWheel() {
        withTerminalSized(80, 24);
        enterString("\033[?1000h\033[?1002h\033[?1003h\033[?1006h\033[?1007l");
        assertTrue(mTerminal.isMouseTrackingActive());
        assertTrue(mTerminal.isMouseMotionTrackingActive());
        assertFalse(mTerminal.isAlternateScrollEnabled());
        assertEnteringStringGivesResponse("\033[?1000$p\033[?1002$p\033[?1003$p\033[?1007$p",
            "\033[?1000;2$y\033[?1002;2$y\033[?1003;1$y\033[?1007;2$y");
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_WHEELUP_BUTTON, 7, 12, true);
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_WHEELDOWN_BUTTON, 7, 12, true);
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_MOVED, 7, 12, true);
        assertEquals("\033[<64;7;12M\033[<65;7;12M\033[<35;7;12M", mOutput.getOutputAndClear());
        enterString("\033[?1003l\033[?1002l\033[?1000l\033[?1006l");
        assertFalse(mTerminal.isMouseTrackingActive());
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_WHEELUP_BUTTON, 1, 1, true);
        assertEquals("", mOutput.getOutputAndClear());
    }

    public void testMouseModesAreExclusiveAndMotionIsFiltered() {
        withTerminalSized(80, 24).enterString("\033[?1003h\033[?1002h\033[?1006h");
        assertFalse(mTerminal.isMouseMotionTrackingActive());
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_MOVED, 1, 1, true);
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, 2, 3, true);
        assertEquals("\033[<32;2;3M", mOutput.getOutputAndClear());
        enterString("\033[?1000h");
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, 1, 1, true);
        assertEquals("", mOutput.getOutputAndClear());
        assertEnteringStringGivesResponse("\033[?1003$p\033[?1002$p\033[?1000$p",
            "\033[?1003;2$y\033[?1002;2$y\033[?1000;1$y");
    }

    public void testSgrCoordinatesStayAsciiUnderNonLatinLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("ar"));
            withTerminalSized(80, 24).enterString("\033[?1003h\033[?1006h");
            mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_WHEELUP_BUTTON, 7, 12, true);
            assertEquals("\033[<64;7;12M", mOutput.getOutputAndClear());
        } finally { Locale.setDefault(previous); }
    }

    public void testReplayRestoresProgramScrollModesWithoutHistoricalReplies() {
        withTerminalSized(80, 24);
        String setup = "\033[?1049h\033[?1003h\033[?1006h\033[?1007l\033[?2004h\033[?1003$p";
        byte[] bytes = setup.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mTerminal.appendReplay(bytes, bytes.length);
        assertTrue(mTerminal.isAlternateBufferActive());
        assertTrue(mTerminal.isMouseMotionTrackingActive());
        assertFalse(mTerminal.isAlternateScrollEnabled());
        assertEquals("", mOutput.getOutputAndClear());
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_WHEELUP_BUTTON, 1, 1, true);
        mTerminal.paste("hello");
        assertEquals("\033[<64;1;1M\033[200~hello\033[201~", mOutput.getOutputAndClear());
        mTerminal.reset();
        assertFalse(mTerminal.isMouseTrackingActive());
        assertTrue(mTerminal.isAlternateScrollEnabled());
    }

    public void testFocusReportingIsOptInAndSurvivesReplay() {
        withTerminalSized(80, 24);
        mTerminal.sendFocusEvent(true);
        assertEquals("", mOutput.getOutputAndClear());
        byte[] setup = "\033[?1004h".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mTerminal.appendReplay(setup, setup.length);
        assertEquals("", mOutput.getOutputAndClear());
        mTerminal.sendFocusEvent(false);
        mTerminal.sendFocusEvent(true);
        assertEquals("\033[O\033[I", mOutput.getOutputAndClear());
        enterString("\033[?1004l");
        mTerminal.sendFocusEvent(false);
        assertEquals("", mOutput.getOutputAndClear());
    }
}
