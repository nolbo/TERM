package com.termux.terminal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import junit.framework.TestCase;

public class KittyControlStreamTest extends TestCase {
    public void testRemoteCommandFramingAtEveryByteBoundaryAndUtf8() {
        String json = "{\"cmd\":\"set-window-title\",\"payload\":{\"title\":\"М한글\"}}";
        byte[] input = ("before\033P@kitty-cmd" + json + "\033\\after").getBytes(StandardCharsets.UTF_8);
        for (int split = 0; split <= input.length; split++) {
            KittyControlStream stream = new KittyControlStream();
            List<KittyControlStream.Part> parts = new ArrayList<>(stream.accept(java.util.Arrays.copyOfRange(input, 0, split)));
            parts.addAll(stream.accept(java.util.Arrays.copyOfRange(input, split, input.length)));
            ByteArrayOutputStream normal = new ByteArrayOutputStream(); List<String> commands = new ArrayList<>();
            for (KittyControlStream.Part part : parts) {
                if (part.bytes != null) normal.write(part.bytes, 0, part.bytes.length); else commands.add(part.command);
            }
            assertEquals("beforeafter", new String(normal.toByteArray(), StandardCharsets.UTF_8));
            assertEquals(java.util.Collections.singletonList(json), commands);
        }
    }

    public void testNestedStringsCannotExecuteOrChangeKeyboardMode() {
        for (String input : new String[]{"\033]0;М\033P@kitty-cmd{}\033\\\007", "\033_Gq=2;\033[>31u\033\\", "\033Pq\033[>31u\033\\"}) {
            KittyControlStream stream = new KittyControlStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (KittyControlStream.Part part : stream.accept(input.getBytes(StandardCharsets.UTF_8))) {
                assertNull(part.command); output.write(part.bytes, 0, part.bytes.length);
            }
            assertEquals(input, new String(output.toByteArray(), StandardCharsets.UTF_8));
            assertEquals(0, stream.keyboard().flags());
        }
    }

    public void testKeyboardModesAreObservedAndBytesRemainUnchanged() {
        KittyControlStream s = new KittyControlStream();
        String input = "\033[>7u\033[?1049h\033[=31u\033[?1049l";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (KittyControlStream.Part p : s.accept(input.getBytes(StandardCharsets.UTF_8))) out.write(p.bytes, 0, p.bytes.length);
        assertEquals(input, new String(out.toByteArray(), StandardCharsets.UTF_8));
        assertEquals(7, s.keyboard().flags());
        s.accept("\033[?1049h".getBytes(StandardCharsets.UTF_8)); assertEquals(31, s.keyboard().flags());
        s.accept("\033c".getBytes(StandardCharsets.UTF_8)); assertEquals(0, s.keyboard().flags());
    }

    public void testIncompleteRequestsNeverExecuteAndOrdinaryPrefixesSurviveEof() {
        KittyControlStream s = new KittyControlStream();
        s.accept("\033P@kit".getBytes(StandardCharsets.UTF_8));
        assertEquals("\033P@kit", new String(s.finish(), StandardCharsets.UTF_8));
        s.accept("\033P@kitty-cmd{}".getBytes(StandardCharsets.UTF_8));
        assertEquals(0, s.finish().length);
        s.accept("\033[>3u\033[<0u".getBytes(StandardCharsets.UTF_8));
        assertEquals(3, s.keyboard().flags());
    }

    public void testOversizedAndCancelledCommandsDoNotExecuteTheirTail() {
        KittyControlStream s = new KittyControlStream();
        String longCommand = "\033P@kitty-cmd" + "a".repeat(KittyControlStream.MAX_COMMAND_BYTES + 1) + "\033\\safe";
        List<KittyControlStream.Part> parts = s.accept(longCommand.getBytes(StandardCharsets.UTF_8));
        assertEquals(2, parts.size()); assertEquals("", parts.get(0).command);
        assertEquals("safe", new String(parts.get(1).bytes, StandardCharsets.UTF_8));
        parts = s.accept("\033P@kitty-cmd{\030safe".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, parts.size()); assertEquals("safe", new String(parts.get(0).bytes, StandardCharsets.UTF_8));
    }
}
