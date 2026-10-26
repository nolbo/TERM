package dev.terminal.emulator;

import junit.framework.TestCase;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SessionActivityEventTest extends TestCase {
    private static class Output extends TerminalOutput {
        final List<String> events = new ArrayList<>();
        @Override public void onSessionActivity(String command) { events.add(command); }
        @Override public void write(byte[] data, int offset, int count) {}
        @Override public void titleChanged(String oldTitle, String newTitle) {}
        @Override public void onCopyTextToClipboard(String text) {}
        @Override public void onPasteTextFromClipboard() {}
        @Override public void onBell() {}
        @Override public void onColorsChanged() {}
    }
    public void testFragmentedActivityIsParsedOnceAndNeverEmittedByReplayOrMirror() {
        byte[] bytes = ("plain 한글\033]0;title\007\033Pignored\007\033\\"
            + "\033]133;C\007text\033]133;D;7\033\\\007\033]777;notify;title;body\007")
            .getBytes(StandardCharsets.UTF_8);
        List<String> expected = Arrays.asList("133;C", "133;D;7", "", "777;notify;title;body");
        for (int split = 0; split <= bytes.length; split++) {
            Output output = new Output();
            TerminalEmulator emulator = new TerminalEmulator(output, 40, 8, 0, 0, 100, null);
            byte[] first = Arrays.copyOfRange(bytes, 0, split), second = Arrays.copyOfRange(bytes, split, bytes.length);
            emulator.append(first, first.length); emulator.append(second, second.length);
            assertEquals("split " + split, expected, output.events);
            output.events.clear();
            emulator.appendReplay(bytes, bytes.length);
            emulator.appendMirror(bytes, bytes.length);
            assertTrue(output.events.isEmpty());
        }
    }
    public void testMalformedCanceledAndOversizedNotificationsRemainIgnored() {
        Output output = new Output();
        TerminalEmulator emulator = new TerminalEmulator(output, 40, 8, 0, 0, 100, null);
        byte[] bytes = ("\033[1;\007m\033]777;notify;bad\033X\007\033]777;notify;canceled\030"
            + "\033]777;notify;" + "x".repeat(2000) + "\007").getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
        assertTrue(output.events.isEmpty());
    }
}
