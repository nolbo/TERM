package com.termux.terminal;

public class SequentialPasteTest extends TerminalTestCase {
    private void prompt() { enterString("\033]133;B\007"); }

    public void testWaitsForPromptNotOutputAndLeavesLastLineUnsubmitted() {
        withTerminalSized(80, 24);
        prompt();
        mTerminal.paste("sleep 10\r\necho second\necho last\n  \n");
        assertEquals("sleep 10\r", mOutput.getOutputAndClear());
        enterString("progress\r\n$ > # ");
        assertEquals("", mOutput.getOutputAndClear());
        prompt();
        assertEquals("echo second\r", mOutput.getOutputAndClear());
        prompt();
        assertEquals("echo last", mOutput.getOutputAndClear());
        prompt();
        assertEquals("", mOutput.getOutputAndClear());
    }

    public void testContinuationUsesRealEnterEvenWithBracketedPaste() {
        withTerminalSized(80, 24);
        enterString("\033[?2004h");
        prompt();
        mTerminal.paste("echo one\\\n two");
        assertEquals("\033[200~echo one\\\033[201~\r", mOutput.getOutputAndClear());
        enterString("\033[?2004l");
        enterString("> \033]133;B");
        assertEquals("", mOutput.getOutputAndClear());
        enterString("\007");
        assertEquals("\033[200~ two\033[201~", mOutput.getOutputAndClear());
    }

    public void testManualInputCancelsQueue() {
        withTerminalSized(80, 24);
        prompt();
        mTerminal.paste("sleep 10\necho canceled");
        mOutput.getOutputAndClear();
        mTerminal.onUserInput();
        prompt();
        assertEquals("", mOutput.getOutputAndClear());
    }

    public void testReplayAndResetCannotResumeQueue() {
        withTerminalSized(80, 24);
        prompt();
        mTerminal.paste("first\nlast");
        mOutput.getOutputAndClear();
        byte[] replay = "\033]133;B\007".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mTerminal.appendReplay(replay, replay.length);
        prompt();
        assertEquals("", mOutput.getOutputAndClear());
        mTerminal.paste("first\nlast");
        mOutput.getOutputAndClear();
        mTerminal.reset();
        prompt();
        assertEquals("", mOutput.getOutputAndClear());
    }
}
