package com.termux.terminal;

import java.nio.charset.StandardCharsets;

public class TranscriptReplayTest extends TerminalTestCase {
    public void testReplayRestoresTextAndModesWithoutAnsweringQueries() {
        withTerminalSized(80, 24);
        String history = "hello\033[31m red\033[0m\033[?2004h"
            + "\033[6n\033[c\033[>c\033]10;?\007\033]11;?\007"
            + "\033[18t\033P+q544e\033\\";
        byte[] bytes = history.getBytes(StandardCharsets.UTF_8);
        mTerminal.appendReplay(bytes, bytes.length);
        assertEquals("hello red", mTerminal.getScreen().getTranscriptText());
        assertEquals("", mOutput.getOutputAndClear());
        mTerminal.paste("input");
        assertEquals("\033[200~input\033[201~", mOutput.getOutputAndClear());
        enterString("\033[6n");
        assertEquals("\033[1;10R", mOutput.getOutputAndClear());
    }

    public void testReplayDoesNotRepeatClipboardOrBellEffects() {
        withTerminalSized(80, 24);
        byte[] bytes = "\007\033]52;c;dGV4dA==\007".getBytes(StandardCharsets.UTF_8);
        mTerminal.appendReplay(bytes, bytes.length);
        assertEquals(0, mOutput.bellsRung);
        assertTrue(mOutput.clipboardPuts.isEmpty());
        mTerminal.append(bytes, bytes.length);
        assertEquals(1, mOutput.bellsRung);
    }
}
