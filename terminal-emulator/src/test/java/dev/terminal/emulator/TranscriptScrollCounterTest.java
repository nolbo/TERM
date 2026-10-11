package dev.terminal.emulator;

public class TranscriptScrollCounterTest extends TerminalTestCase {
    public void testHistoryMovementIsIndependentOfDirtyAndSelectionNotifications() {
        withTerminalSized(10, 4);
        enterString("a\r\nb\r\nc\r\nd\r\ne");
        assertEquals(1, mTerminal.getTranscriptScrollCounter());
        mTerminal.clearScrollCounter();
        enterString("\033]0;title\007\033[?2026h\033_Ga=d,d=I,i=1,q=2;\033\\\033[?2026l");
        assertEquals(1, mTerminal.getTranscriptScrollCounter());
        enterString("\033[?69h\033[2;9s\033[4;2H\n");
        assertTrue(mTerminal.getScrollCounter() > 0);
        assertEquals(1, mTerminal.getTranscriptScrollCounter());
        enterString("\033[?69l\033[4;1H\n");
        assertEquals(2, mTerminal.getTranscriptScrollCounter());
        enterString("\033[?1049h\033[4;1H\n");
        assertEquals(2, mTerminal.getTranscriptScrollCounter());
    }
    public void testHeightResizeMovesTranscriptCoordinatesWithoutCountingOutput() {
        withTerminalSized(10, 4).enterString("a\r\nb\r\nc\r\nd\r\ne\r\nf");
        long output = mTerminal.getTranscriptScrollCounter();
        long shift = mTerminal.getTranscriptRowShiftCounter();
        mTerminal.resize(10, 5, INITIAL_CELL_WIDTH_PIXELS, INITIAL_CELL_HEIGHT_PIXELS);
        assertEquals(output, mTerminal.getTranscriptScrollCounter());
        assertEquals(shift - 1, mTerminal.getTranscriptRowShiftCounter());
        mTerminal.resize(10, 4, INITIAL_CELL_WIDTH_PIXELS, INITIAL_CELL_HEIGHT_PIXELS);
        assertEquals(shift, mTerminal.getTranscriptRowShiftCounter());
    }
    public void testMainHistoryCoordinatesFollowHeightChangeWhileAlternateScreenIsActive() {
        withTerminalSized(10, 4).enterString("a\r\nb\r\nc\r\nd\r\ne\r\nf");
        long shift = mTerminal.getTranscriptRowShiftCounter();
        enterString("\033[?1049h");
        mTerminal.resize(10, 5, INITIAL_CELL_WIDTH_PIXELS, INITIAL_CELL_HEIGHT_PIXELS);
        assertEquals(shift, mTerminal.getTranscriptRowShiftCounter());
        enterString("\033[?1049l");
        assertEquals(shift - 1, mTerminal.getTranscriptRowShiftCounter());
    }
}
