package com.termux.terminal;

import junit.framework.TestCase;
import java.nio.charset.StandardCharsets;

public class TerminalReplayBufferTest extends TestCase {
    private void append(TerminalReplayBuffer buffer, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) buffer.append(new byte[]{b}, 1);
    }
    private String snapshot(TerminalReplayBuffer buffer) { return new String(buffer.snapshot(), StandardCharsets.UTF_8); }

    public void testCompleteGraphicsSurviveReplayAndGrowth() {
        TerminalReplayBuffer buffer = new TerminalReplayBuffer(16384);
        String data = "\033Pq" + new String(new char[9000]).replace('\0', '?') + "\033\\";
        append(buffer, data);
        assertEquals(data, snapshot(buffer));
    }

    public void testTruncationNeverExposesSixelTail() {
        TerminalReplayBuffer buffer = new TerminalReplayBuffer(32);
        append(buffer, "\033Pq" + new String(new char[80]).replace('\0', '?'));
        assertEquals("\033_", snapshot(buffer));
        append(buffer, "\033\\SAFE");
        assertEquals("SAFE", snapshot(buffer));
    }

    public void testChunkedKittyRetainedAsOneTransaction() {
        TerminalReplayBuffer buffer = new TerminalReplayBuffer(64);
        append(buffer, "\033_Ga=T,i=1,f=24,s=1,v=1,m=1;" + new String(new char[40]).replace('\0', 'A') + "\033\\");
        append(buffer, "\033_Gm=0;AAAA\033\\SAFE");
        assertEquals("SAFE", snapshot(buffer));
    }

    public void testUtf8AndCsiBoundaries() {
        TerminalReplayBuffer buffer = new TerminalReplayBuffer(16);
        append(buffer, "가나다라마바");
        assertEquals("나다라마바", snapshot(buffer));
        append(buffer, "\033[12345678901234567890;31mOK");
        assertEquals("OK", snapshot(buffer));
    }

    public void testTrimmedOscPreservesBellTermination() {
        TerminalReplayBuffer buffer = new TerminalReplayBuffer(16);
        append(buffer, "\033]0;abcdefghijklmnopqrstuvwxyz");
        assertEquals("\033]999;", snapshot(buffer));
        append(buffer, "\007OK");
        assertEquals("OK", snapshot(buffer));
    }

    public void testIncompleteEscapeAndLiveTailCanBeParsed() {
        TerminalReplayBuffer buffer = new TerminalReplayBuffer(16);
        append(buffer, "\033Pq????????????????????????\033");
        assertEquals("\033_\033", snapshot(buffer));
        append(buffer, "\\OK");
        assertEquals("OK", snapshot(buffer));
    }
}
