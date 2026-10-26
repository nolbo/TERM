package dev.terminal.emulator;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;

public class TerminalCheckpointTest extends TerminalTestCase {
    private byte[] checkpoint(TerminalEmulator emulator) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TerminalCheckpoint.write(emulator, out);
        return out.toByteArray();
    }
    private TerminalEmulator restore(TerminalEmulator original, MockTerminalOutput output) throws IOException {
        TerminalEmulator mirror = new TerminalEmulator(output, 5, 3, 1, 1, 100, null);
        mirror.restoreState(TerminalCheckpoint.read(new ByteArrayInputStream(checkpoint(original))));
        return mirror;
    }
    private void sameState(TerminalEmulator first, TerminalEmulator second) throws IOException {
        try { compare(first, second, new java.util.IdentityHashMap<>()); }
        catch (ReflectiveOperationException e) { throw new IOException(e); }
    }
    private void compare(Object first, Object second, java.util.IdentityHashMap<Object, Object> visited) throws ReflectiveOperationException {
        if (first == null) { assertNull(second); return; }
        assertNotNull(second);
        if (first instanceof CharSequence) { assertEquals(first.toString(), second.toString()); return; }
        if (first instanceof ByteArrayOutputStream) {
            assertTrue(Arrays.equals(((ByteArrayOutputStream) first).toByteArray(), ((ByteArrayOutputStream) second).toByteArray())); return;
        }
        if (first instanceof java.util.Map) {
            java.util.Map<?, ?> a = (java.util.Map<?, ?>) first, b = (java.util.Map<?, ?>) second;
            assertEquals(a.keySet(), b.keySet());
            for (Object key : a.keySet()) compare(a.get(key), b.get(key), visited);
            return;
        }
        if (first instanceof Iterable) {
            java.util.Iterator<?> a = ((Iterable<?>) first).iterator(), b = ((Iterable<?>) second).iterator();
            while (a.hasNext()) { assertTrue(b.hasNext()); compare(a.next(), b.next(), visited); }
            assertFalse(b.hasNext()); return;
        }
        if (first.getClass().isArray()) {
            int size = java.lang.reflect.Array.getLength(first); assertEquals(size, java.lang.reflect.Array.getLength(second));
            for (int i = 0; i < size; i++) compare(java.lang.reflect.Array.get(first, i), java.lang.reflect.Array.get(second, i), visited);
            return;
        }
        if (!first.getClass().getName().startsWith("dev.terminal.emulator.")) { assertEquals(first, second); return; }
        Object previous = visited.put(first, second);
        if (previous != null) { assertSame("Checkpoint graph aliases", previous, second); return; }
        if (first instanceof TerminalRow) {
            assertTrue(Arrays.equals(((TerminalRow) first).mStyle, ((TerminalRow) second).mStyle));
        }
        for (java.lang.reflect.Field field : first.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || java.lang.reflect.Modifier.isTransient(field.getModifiers())) continue;
            field.setAccessible(true); compare(field.get(first), field.get(second), visited);
        }
    }

    private static void append(TerminalEmulator emulator, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
    }

    public void testRowStyleRunsAndDenseStylesRoundTripWithoutLosingCellMetadata() throws Exception {
        for (int width : new int[]{1, 2, 80, 200}) {
            for (int pattern = 0; pattern < 4; pattern++) {
                TerminalRow row = new TerminalRow(width, TextStyle.NORMAL);
                for (int column = 0; column < width; column++) {
                    long style = pattern == 0 ? TextStyle.NORMAL : pattern == 1 ? column / 20 :
                        pattern == 2 ? column % 2 : Long.MIN_VALUE + column * 123456789L;
                    row.setChar(column, 'a' + column % 26, style, 0xFF123456, "https://example.com/" + column);
                }
                row.mLineWrap = true;
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (ObjectOutputStream out = new ObjectOutputStream(bytes)) { out.writeObject(row); }
                TerminalRow copy;
                try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                    copy = (TerminalRow) in.readObject();
                }
                compare(row, copy, new java.util.IdentityHashMap<>());
            }
        }
    }

    public void testUniformRowCaptureAvoidsPerCellStylePayload() throws Exception {
        TerminalRow[] rows = new TerminalRow[100];
        for (int i = 0; i < rows.length; i++) rows[i] = new TerminalRow(200, TextStyle.NORMAL);
        ByteArrayOutputStream uniform = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(uniform)) { out.writeObject(rows); }
        for (TerminalRow row : rows) for (int i = 0; i < row.mStyle.length; i++) row.mStyle[i] = i;
        ByteArrayOutputStream dense = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(dense)) { out.writeObject(rows); }
        assertTrue("Uniform styles should avoid the dense long array payload", uniform.size() * 2 < dense.size());
    }

    public void testHyperlinksInBothScreensScrollbackAndActiveDestinationSurviveCheckpoint() throws Exception {
        withTerminalSized(8, 4);
        enterString("\033]8;id=main;https://main.example\033\\abcdefghij\033]8;;\007\r\n1\r\n2\r\n3\r\n4");
        assertTrue(mTerminal.getScreen().getActiveTranscriptRows() > 0);
        enterString("\033[?1049h\033[H\033]8;id=alt;https://alt.example\007ALT");
        TerminalEmulator mirror = restore(mTerminal, new MockTerminalOutput());
        sameState(mTerminal, mirror);
        String suffix = "X\033]8;;\033\\Y\033[?1049l";
        append(mTerminal, suffix);
        append(mirror, suffix);
        sameState(mTerminal, mirror);
        assertEquals("https://main.example", mirror.getScreen().getHyperlinkAt(-2, 0));
        append(mirror, "\033[?47h");
        assertEquals("https://alt.example", mirror.getScreen().getHyperlinkAt(0, 3));
        assertNull(mirror.getScreen().getHyperlinkAt(0, 4));
    }

    public void testEveryByteCheckpointPreservesPartialHyperlinkStartAndEnd() throws Exception {
        byte[] input = "\033]8;id=one:unknown=x;https://example.com/한;a\033\\한label\033]8;;\007X".getBytes(StandardCharsets.UTF_8);
        for (int split = 0; split <= input.length; split++) {
            withTerminalSized(10, 4);
            mTerminal.append(Arrays.copyOf(input, split), split);
            TerminalEmulator mirror = restore(mTerminal, new MockTerminalOutput());
            byte[] suffix = Arrays.copyOfRange(input, split, input.length);
            mTerminal.append(suffix, suffix.length);
            mirror.append(suffix, suffix.length);
            sameState(mTerminal, mirror);
            assertEquals("https://example.com/한;a", mirror.getScreen().getHyperlinkAt(0, 0));
            assertEquals("https://example.com/한;a", mirror.getScreen().getHyperlinkAt(0, 6));
            assertNull(mirror.getScreen().getHyperlinkAt(0, 7));
        }
    }

    public void testDiscardedHyperlinkStateSurvivesCheckpointUntilTerminator() throws Exception {
        withTerminalSized(20, 4);
        enterString("\033]8;;https://old.example\007A\033]8;;https://bad.example/\n");
        TerminalEmulator mirror = restore(mTerminal, new MockTerminalOutput());
        String suffix = "tail\033\\B";
        append(mTerminal, suffix);
        append(mirror, suffix);
        sameState(mTerminal, mirror);
        assertEquals("AB", mirror.getScreen().getTranscriptText());
        assertNull(mirror.getScreen().getHyperlinkAt(0, 1));
    }

    public void testBackgroundDeclarationsAndWholeScreenEraseSurviveCheckpoint() throws Exception {
        withTerminalSized(80, 24);
        enterString("\033[?1049h\033[48;2;18;52;86m\033[2J\033]11;#000000\007");
        TerminalEmulator mirror = restore(mTerminal, new MockTerminalOutput());
        assertTrue(mirror.mColors.isBackgroundColorSet());
        assertEquals(mTerminal.getScreen().getLastFullScreenClearStyle(),
            mirror.getScreen().getLastFullScreenClearStyle());
        append(mirror, "\033]111\007");
        assertFalse(mirror.mColors.isBackgroundColorSet());
        assertEquals(0xFF123456, TextStyle.decodeBackColor(mirror.getScreen().getLastFullScreenClearStyle()));
        sameState(mTerminal, restore(mTerminal, new MockTerminalOutput()));
    }

    public void testBothScreensHistorySavedCursorModesColorsAndBindings() throws Exception {
        withTerminalSized(30, 8);
        Properties palette = new Properties(); palette.setProperty("foreground", "#112233");
        mTerminal.mColors.setDefaultColors(palette);
        for (int i = 0; i < 150; i++) enterString("line" + i + " 한😀é\r\n");
        enterString("\033[38;2;12;34;56;58;2;90;87;65m\033[3;4H\0337\033(0\033[2;7r");
        enterString("\033]0;main-title\007\033[22;0t\033[>7u\033[?1049h\033[>31u");
        enterString("\033[?1003h\033[?1006h\033[?1007l\033[?1004h\033[?2004h\033[5 q");
        enterString("\033]4;3;#abc123\007ALT\033[4;5H\0337\033[2;6r");
        MockTerminalOutput out = new MockTerminalOutput();
        TerminalEmulator mirror = restore(mTerminal, out);
        sameState(mTerminal, mirror);
        assertTrue(mirror.isAlternateBufferActive());
        assertTrue(mirror.isMouseMotionTrackingActive());
        mirror.sendMouseEvent(TerminalEmulator.MOUSE_WHEELUP_BUTTON, 1, 1, true);
        mirror.sendFocusEvent(true); mirror.paste("hello");
        assertEquals("\033[<64;1;1M\033[I\033[200~hello\033[201~", out.getOutputAndClear());
        String finish = "\0338\033[?1049l\033[<1u\033[23;0t\0338\033[6n";
        mOutput.getOutputAndClear(); append(mTerminal, finish); append(mirror, finish);
        assertEquals(mOutput.getOutputAndClear(), out.getOutputAndClear());
        sameState(mTerminal, mirror);
        assertTrue(mirror.getScreen().getTranscriptText().contains("line0"));
    }

    public void testEveryByteCheckpointPreservesPartialUtf8CsiOscDcsAndGraphics() throws Exception {
        String input = "한😀é\033[38;2;12;34;56m\033[4;5H\033]0;title한\033\\" +
            "\033P$qm\033\\\033P0;1q\"1;1;3;6#1;2;100;0;0#1!3@\033\\" +
            "\033_Ga=T,i=4,f=24,s=2,v=1,m=1;/wAA\033\\\033_Gm=0;AP8A\033\\\033[6n";
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        for (int split = 0; split <= bytes.length; split++) {
            withTerminalSized(20, 8);
            mTerminal.append(Arrays.copyOf(bytes, split), split);
            MockTerminalOutput out = new MockTerminalOutput();
            TerminalEmulator mirror = restore(mTerminal, out);
            mOutput.getOutputAndClear();
            byte[] suffix = Arrays.copyOfRange(bytes, split, bytes.length);
            mTerminal.append(suffix, suffix.length); mirror.append(suffix, suffix.length);
            assertEquals("split=" + split, mOutput.getOutputAndClear(), out.getOutputAndClear());
            sameState(mTerminal, mirror);
            assertEquals(2, mirror.getGraphics().getPlacements().size());
        }
    }

    public void testVtSavedModesNewlineAndPendingWrapSurviveCheckpoint() throws Exception {
        withTerminalSized(5, 4);
        enterString("\033[?1l\033[?1s\033[?1h\033[32mSHELL\033[?1049h\033[?47s\033[?45h\033[20h\033[Habcde\0337\033[3;");
        MockTerminalOutput out = new MockTerminalOutput();
        TerminalEmulator mirror = restore(mTerminal, out);
        sameState(mTerminal, mirror);
        String continuation = "2H\0338\b\033[?1r\033[20$p\033[?45$p\033[?1$p\033[?1049lX";
        append(mTerminal, continuation);
        append(mirror, continuation);
        assertEquals("\033[20;1$y\033[?45;1$y\033[?1;2$y", mOutput.getOutputAndClear());
        assertEquals("\033[20;1$y\033[?45;1$y\033[?1;2$y", out.getOutputAndClear());
        sameState(mTerminal, mirror);
        assertLinesAre("SHELL", "X    ", "     ", "     ");
        assertForegroundColorAt(1, 0, 2);
    }

    public void testMirrorSuppressesOnlyParsedRepliesAndCheckpointHasNoSideEffects() throws Exception {
        withTerminalSized(20, 8); enterString("hello\033[?1003h\033[?1006h\033[?2026h");
        MockTerminalOutput out = new MockTerminalOutput();
        TerminalEmulator mirror = restore(mTerminal, out);
        assertTrue(mirror.getSynchronizedOutputDelayMillis() > 0);
        byte[] live = "\033[6n\007\033]52;c;aGVsbG8=\007\033[?2026lDONE".getBytes(StandardCharsets.UTF_8);
        mirror.appendMirror(live, live.length);
        assertEquals("", out.getOutputAndClear());
        assertEquals(1, out.bellsRung);
        assertEquals(0L, mirror.getSynchronizedOutputDelayMillis());
        mirror.sendMouseEvent(TerminalEmulator.MOUSE_WHEELUP_BUTTON, 1, 1, true);
        assertEquals("\033[<64;1;1M", out.getOutputAndClear());
    }

    public void testRejectsUnknownVersionAndForeignSerializedClasses() throws Exception {
        try { TerminalCheckpoint.read(new ByteArrayInputStream(new byte[4])); fail(); }
        catch (IOException expected) {}
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new DataOutputStream(bytes).writeInt(0x54534331);
        try (ObjectOutputStream out = new ObjectOutputStream(new java.util.zip.GZIPOutputStream(bytes))) {
            out.writeObject(new java.io.File("/untrusted"));
        }
        try { TerminalCheckpoint.read(new ByteArrayInputStream(bytes.toByteArray())); fail(); }
        catch (InvalidClassException expected) {}
    }
}
