package dev.terminal.emulator;

public class KittyKeyboardTest extends TerminalTestCase {
    public void testNegotiationModesStacksScreensAndReset() {
        withTerminalSized(20, 8);
        enterString("\033[?u"); assertEquals("\033[?0u", mOutput.getOutputAndClear());
        enterString("\033[=3u\033[>8u\033[=16;2u\033[=8;3u\033[?u");
        assertEquals("\033[?16u", mOutput.getOutputAndClear());
        enterString("\033[<u\033[?u"); assertEquals("\033[?3u", mOutput.getOutputAndClear());
        enterString("\033[?1049h\033[?u\033[>31u\033[?1049l\033[?u");
        assertEquals("\033[?0u\033[?3u", mOutput.getOutputAndClear());
        enterString("\033[<99u\033[?u\033c\033[?u"); assertEquals("\033[?0u\033[?0u", mOutput.getOutputAndClear());
    }

    public void testBoundedStackAndUnknownFlags() {
        KittyKeyboard k = new KittyKeyboard();
        for (int n = 0; n < 10000; n++) k.push(n);
        k.pop(32); assertEquals(0, k.flags());
        k.set(255, 1); assertEquals(31, k.flags());
        k.set(0, 9); assertEquals(31, k.flags());
    }

    public void testControlShiftAndAltAreUnambiguous() {
        KittyKeyboard k = new KittyKeyboard(); k.set(1, 1);
        assertEquals("\033[105;5u", k.encode(KittyKeyboard.Key.text('i'), 4, 1, 0, 0, ""));
        assertEquals("\033[97;6u", k.encode(KittyKeyboard.Key.text('a'), 5, 1, 'A', 0, ""));
        assertEquals("\033[91;3u", k.encode(KittyKeyboard.Key.text('['), 2, 1, 0, 0, ""));
        assertEquals("A", k.encode(KittyKeyboard.Key.text('a'), 65, 1, 'A', 0, "A"));
        assertEquals("\033[27u", k.encode(new KittyKeyboard.Key(27, 'u', true, false), 0, 1, 0, 0, ""));
        assertEquals("\r", k.encode(new KittyKeyboard.Key(13, 'u', true, false), 64, 1, 0, 0, ""));
        assertEquals("\033[13;5u", k.encode(new KittyKeyboard.Key(13, 'u', true, false), 4, 1, 0, 0, ""));
    }

    public void testRepeatReleaseAlternatesAndAssociatedText() {
        KittyKeyboard k = new KittyKeyboard(); k.set(31, 1);
        assertEquals("\033[97:65;2;65u", k.encode(KittyKeyboard.Key.text('a'), 1, 1, 'A', 'a', "A"));
        assertEquals("\033[97;1:2;97u", k.encode(KittyKeyboard.Key.text('a'), 0, 2, 0, 0, "a"));
        assertEquals("\033[97;1:3u", k.encode(KittyKeyboard.Key.text('a'), 0, 3, 0, 0, "a"));
        assertEquals("\033[1089::99;5u", k.encode(KittyKeyboard.Key.text(1089), 4, 1, 0, 'c', ""));
        assertEquals("\033[1;6:3A", k.encode(new KittyKeyboard.Key(1, 'A', true, false), 5, 3, 0, 0, ""));
        assertEquals("\033[57442;5u", k.encode(new KittyKeyboard.Key(57442, 'u', true, true), 4, 1, 0, 0, ""));
        assertEquals("\033[0;;54620:44544u", k.committedText("한글"));
        assertNull(k.committedText("\u001bsecret"));
    }

    public void testLegacyFallbackAndRecoveryKeysHaveNoRelease() {
        KittyKeyboard k = new KittyKeyboard();
        assertNull(k.encode(KittyKeyboard.Key.text('c'), 4, 1, 0, 0, ""));
        assertEquals("", k.encode(KittyKeyboard.Key.text('c'), 4, 3, 0, 0, ""));
        k.set(3, 1);
        assertEquals("", k.encode(new KittyKeyboard.Key(13, 'u', true, false), 0, 3, 0, 0, ""));
        assertEquals("", k.encode(KittyKeyboard.Key.text('a'), 0, 3, 0, 0, "a"));
        assertEquals("\033[1;1:2A", k.encode(new KittyKeyboard.Key(1, 'A', true, false), 0, 2, 0, 0, ""));
    }
}
