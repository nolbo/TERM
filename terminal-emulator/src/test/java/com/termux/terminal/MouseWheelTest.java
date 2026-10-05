package com.termux.terminal;

public class MouseWheelTest extends TerminalTestCase {
    public void testAllModifierCombinationsAndWheelDirectionsUseSgrButtonBits() {
        assertEquals(4, TerminalEmulator.MOUSE_MODIFIER_SHIFT);
        assertEquals(8, TerminalEmulator.MOUSE_MODIFIER_ALT);
        assertEquals(16, TerminalEmulator.MOUSE_MODIFIER_CTRL);
        withTerminalSized(80, 24).enterString("\033[?1000h\033[?1006h");
        int[] expectedMasks = {0, 4, 8, 12, 16, 20, 24, 28};
        for (int combination = 0; combination < expectedMasks.length; combination++) {
            int modifiers = ((combination & 1) != 0 ? TerminalEmulator.MOUSE_MODIFIER_SHIFT : 0)
                | ((combination & 2) != 0 ? TerminalEmulator.MOUSE_MODIFIER_ALT : 0)
                | ((combination & 4) != 0 ? TerminalEmulator.MOUSE_MODIFIER_CTRL : 0);
            for (int button = 64; button <= 67; button++) {
                mTerminal.sendMouseEvent(button | modifiers, 7, 12, true);
                assertEquals("\033[<" + (button + expectedMasks[combination]) + ";7;12M", mOutput.getOutputAndClear());
            }
        }
    }

    public void testModifiedWheelLegacyEncodingAndTrackingGate() {
        withTerminalSized(80, 24).enterString("\033[?1000h");
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_WHEELUP_BUTTON | TerminalEmulator.MOUSE_MODIFIER_SHIFT, 7, 12, true);
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_WHEELDOWN_BUTTON | TerminalEmulator.MOUSE_MODIFIER_SHIFT, 7, 12, true);
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_WHEELLEFT_BUTTON | TerminalEmulator.MOUSE_MODIFIER_ALT, 7, 12, true);
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_WHEELRIGHT_BUTTON | TerminalEmulator.MOUSE_MODIFIER_CTRL, 7, 12, true);
        assertEquals("\033[Md',\033[Me',\033[Mj',\033[Ms',", mOutput.getOutputAndClear());
        enterString("\033[?1000l");
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_WHEELUP_BUTTON | TerminalEmulator.MOUSE_MODIFIER_SHIFT, 7, 12, true);
        assertEquals("", mOutput.getOutputAndClear());
    }

    public void testHorizontalSgrWheelForEachTrackingModeAndScreen() {
        assertEquals(66, TerminalEmulator.MOUSE_WHEELLEFT_BUTTON);
        assertEquals(67, TerminalEmulator.MOUSE_WHEELRIGHT_BUTTON);
        for (int mode : new int[]{1000, 1002, 1003}) {
            for (boolean alternate : new boolean[]{false, true}) {
                withTerminalSized(80, 24).enterString("\033[?" + mode + "h\033[?1006h");
                if (alternate) enterString("\033[?1049h");
                mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_WHEELLEFT_BUTTON, 7, 12, true);
                mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_WHEELRIGHT_BUTTON, 7, 12, true);
                assertEquals("\033[<66;7;12M\033[<67;7;12M", mOutput.getOutputAndClear());
            }
        }
    }

    public void testHorizontalWheelRequiresTrackingEvenWithSgrAndAlternateScroll() {
        withTerminalSized(80, 24).enterString("\033[?1006h\033[?1049h\033[?1007h");
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_WHEELLEFT_BUTTON, 7, 12, true);
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_WHEELRIGHT_BUTTON, 7, 12, true);
        assertEquals("", mOutput.getOutputAndClear());
        enterString("\033[?1000h\033[?1000l");
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_WHEELRIGHT_BUTTON, 7, 12, true);
        assertEquals("", mOutput.getOutputAndClear());
    }

    public void testHorizontalWheelReusesLegacyButtonEncoding() {
        withTerminalSized(80, 24).enterString("\033[?1000h");
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_WHEELLEFT_BUTTON, 7, 12, true);
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_WHEELRIGHT_BUTTON, 7, 12, true);
        // Legacy encoding adds 32 to the button and the one-based coordinates.
        assertEquals("\033[Mb',\033[Mc',", mOutput.getOutputAndClear());
    }

    public void testHorizontalSgrCoordinatesAreClamped() {
        withTerminalSized(80, 24).enterString("\033[?1000h\033[?1006h");
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_WHEELLEFT_BUTTON, 0, -10, true);
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_WHEELRIGHT_BUTTON, 100, 30, true);
        assertEquals("\033[<66;1;1M\033[<67;80;24M", mOutput.getOutputAndClear());
    }
}
