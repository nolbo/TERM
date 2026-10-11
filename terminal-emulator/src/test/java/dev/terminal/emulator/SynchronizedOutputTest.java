package dev.terminal.emulator;

public class SynchronizedOutputTest extends TerminalTestCase {
    public void testModeQueriesParsingResetAndReplay() {
        withTerminalSized(20, 8);
        enterString("\033[?2026$p"); assertEquals("\033[?2026;2$y", mOutput.getOutputAndClear());
        enterString("\033[?2026hhello\033[?2026$p");
        assertEquals("\033[?2026;1$y", mOutput.getOutputAndClear());
        assertTrue(mTerminal.getSynchronizedOutputDelayMillis() > 0);
        enterString("\033[?2026l\033[?2026$p"); assertEquals("\033[?2026;2$y", mOutput.getOutputAndClear());
        enterString("\033[?2026h\033c"); assertEquals(0, mTerminal.getSynchronizedOutputDelayMillis());
        byte[] replay = "\033[?2026hpartial replay".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mTerminal.appendReplay(replay, replay.length);
        assertEquals(0, mTerminal.getSynchronizedOutputDelayMillis());
        enterString("\033[?2026h"); mTerminal.resize(30, 8, 10, 20);
        assertEquals(0, mTerminal.getSynchronizedOutputDelayMillis());
    }
    public void testDeadlineCannotBeExtendedAndEndAllowsNextFrame() {
        long[] time = {100};
        SynchronizedOutput state = new SynchronizedOutput(() -> time[0]);
        state.begin(); time[0] = 900; state.begin();
        assertEquals(200, state.remainingMillis());
        time[0] = 1100; assertEquals(0, state.remainingMillis());
        state.begin(); assertEquals(1000, state.remainingMillis());
        state.end(); assertEquals(0, state.remainingMillis());
    }
    public void testReattachingUnchangedGeometryPreservesPresentationBarrier() {
        withTerminalSized(20, 8).enterString("\033[?2026hpartial");
        mTerminal.resize(20, 8, INITIAL_CELL_WIDTH_PIXELS, INITIAL_CELL_HEIGHT_PIXELS);
        assertTrue(mTerminal.getSynchronizedOutputDelayMillis() > 0);
        enterString("\033[?2026l");
        assertEquals(0, mTerminal.getSynchronizedOutputDelayMillis());
    }
}
