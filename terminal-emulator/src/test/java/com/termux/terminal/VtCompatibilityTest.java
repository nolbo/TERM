package com.termux.terminal;

/** VT/xterm contracts used by full-screen applications, independent of application names. */
public class VtCompatibilityTest extends TerminalTestCase {
    public void test47PreservesAlternateTextWrapAndCurrentCursorAttributes() {
        withTerminalSized(5, 3).enterString("SHELL\033[2;2H\033[?47h").assertCursorAt(1, 1);
        enterString("\033[31m\033[Habcdef\033[3;3H\033[?47l").assertCursorAt(2, 2);
        assertLinesAre("SHELL", "     ", "     ");
        enterString("X").assertForegroundColorAt(2, 2, 1);
        enterString("\033[?47h").assertLinesAre("abcde", "f    ", "     ").assertLineWraps(true, false, false);
        enterString("\033[?47h").assertLinesAre("abcde", "f    ", "     ");
    }

    public void test1047ClearsOnExitWithoutSavingCursorOrClearingOnEntry() {
        withTerminalSized(5, 3).enterString("\033[?47habcdef\033[?47l\033[?1047h");
        assertLinesAre("abcde", "f    ", "     ");
        enterString("\033[3;3H\033[?1047l").assertCursorAt(2, 2);
        enterString("\033[?47h").assertLinesAre("     ", "     ", "     ").assertLineWraps(false, false, false);
    }

    public void test1049ClearsOnEntryAndRestoresShellIncludingPendingWrap() {
        withTerminalSized(5, 3).enterString("\033[32mSHELL\033[?1049h\033[Habcdef");
        enterString("\033[31m\033[?1049h").assertLinesAre("     ", "     ", "     ");
        enterString("\033[?1049lX").assertLinesAre("SHELL", "X    ", "     ");
        assertForegroundColorAt(1, 0, 2);
        assertTrue(mTerminal.getScreen().getLineWrap(0));
    }

    public void testCursorSaveSlotsStaySeparateOnTheTwoScreens() {
        withTerminalSized(8, 5).enterString("\033[2;3H\0337\033[?47h\033[4;6H\0337\033[H\0338").assertCursorAt(3, 5);
        enterString("\033[?47l\0338").assertCursorAt(1, 2);
    }

    public void testPrivateModeBufferRestoreDoesNotClearOrRestoreCursor() {
        withTerminalSized(5, 3).enterString("SHELL\033[?1049h\033[Habcdef\033[?1049s\033[?47l\033[3;3H\033[?47r");
        assertLinesAre("abcde", "f    ", "     ").assertCursorAt(2, 2);
        enterString("\033[?47l\033[?47s\033[?47h\033[?1047r").assertLinesAre("SHELL", "     ", "     ").assertCursorAt(2, 2);
    }

    public void testSavedPendingWrapAndCurrentAutowrapModeAreIndependent() {
        withTerminalSized(5, 3).enterString("abcde\0337\033[H\0338X").assertLinesAre("abcde", "X    ", "     ");
        withTerminalSized(5, 3).enterString("abcde\0337\033[?7l\033[H\0338X").assertLinesAre("abcdX", "     ", "     ");
        assertEnteringStringGivesResponse("\033[?7$p", "\033[?7;2$y");
    }

    public void testModeSaveRestoresDisabledValuesAndDoesNotHomeTheCursor() {
        withTerminalSized(8, 5).enterString("\033[?1l\033[?1007l\033[?1;1007s\033[?1;1007h\033[?1;1007r");
        assertFalse(mTerminal.isCursorKeysApplicationMode());
        assertFalse(mTerminal.isAlternateScrollEnabled());
        enterString("\033[?1h\033[?1s\033[?1l\033[?1r");
        assertTrue(mTerminal.isCursorKeysApplicationMode());
        enterString("\033[2;4r\033[?6h\033[2;3H\033[?6s\033[?6l\033[3;3H\033[?6r").assertCursorAt(2, 2);
    }

    public void testOriginModeCoordinatesAndReportsStayRelativeToBothMargins() {
        withTerminalSized(8, 6).enterString("\033[2;5r\033[?69h\033[3;7s\033[?6h").assertCursorAt(1, 2);
        enterString("\033[3;4H").assertCursorAt(3, 5);
        assertEnteringStringGivesResponse("\033[6n\033[?6n", "\033[3;4R\033[?3;4R");
        enterString("\033[2G").assertCursorAt(3, 3);
        enterString("\033[3`").assertCursorAt(3, 4);
        enterString("\033[2d").assertCursorAt(2, 4);
        enterString("\033[e").assertCursorAt(3, 4);
        enterString("\033[E").assertCursorAt(4, 2);
        enterString("\033[F").assertCursorAt(3, 2);
        enterString("\033[?6l").assertCursorAt(0, 0);
    }

    public void testRelativeMovementStopsAtMarginsFromInsideAndAtPageEdgesFromOutside() {
        withTerminalSized(8, 6).enterString("\033[2;5r\033[?69h\033[3;7s\033[3;4H\033[99A").assertCursorAt(1, 3);
        enterString("\033[99B").assertCursorAt(4, 3);
        enterString("\033[99C").assertCursorAt(4, 6);
        enterString("\033[99D").assertCursorAt(4, 2);
        enterString("\033[1;1H\033[99A\033[99D").assertCursorAt(0, 0);
        enterString("\033[6;8H\033[99B\033[99C").assertCursorAt(5, 7);
    }

    public void testInvalidMarginsLeaveTheCursorAndExistingRegionAlone() {
        withTerminalSized(8, 6).enterString("\033[2;5r\033[?69h\033[3;7s\033[?6h\033[2;2H");
        for (String invalid : new String[]{"\033[4;3r", "\033[3;3r", "\033[99r", "\033[6;3s", "\033[3;3s", "\033[99s"}) {
            enterString(invalid).assertCursorAt(2, 3);
        }
        enterString("\033[H").assertCursorAt(1, 2);
        enterString("\033[99;99H").assertCursorAt(4, 6);
    }

    public void testLineEditsOutsideRegionAreIgnored() {
        withTerminalSized(5, 5).enterString("11111\r\n22222\r\n33333\r\n44444\r\n55555\033[2;4r");
        enterString("\033[1;1H\033[L\033[M\033[5;1H\033[99L\033[99M");
        assertLinesAre("11111", "22222", "33333", "44444", "55555");
    }

    public void testLineEditsPreserveCellsOutsideHorizontalAndVerticalMargins() {
        withTerminalSized(5, 5).enterString("11111\r\n22222\r\n33333\r\n44444\r\n55555\033[2;4r\033[?69h\033[2;4s");
        enterString("\033[3;1H\033[L\033[M").assertLinesAre("11111", "22222", "33333", "44444", "55555");
        enterString("\033[3;3H\033[L").assertLinesAre("11111", "22222", "3   3", "43334", "55555");
        enterString("\033[M").assertLinesAre("11111", "22222", "33333", "4   4", "55555");
    }

    public void testCharacterEditsStopAtRightMargin() {
        withTerminalSized(6, 3).enterString("ABCDEF\033[?69h\033[2;5s\033[1;3H\033[P");
        assertLinesAre("ABDE F", "      ", "      ");
        enterString("\033[@").assertLinesAre("AB DEF", "      ", "      ");
        enterString("\033[1;6H\033[P\033[@").assertLinesAre("AB DEF", "      ", "      ");
    }

    public void testReverseIndexAboveTopMarginDoesNotScrollTheRegion() {
        withTerminalSized(3, 4).enterString("111\r\n222\r\n333\r\n444\033[2;3r\033[H\033M");
        assertLinesAre("111", "222", "333", "444").assertCursorAt(0, 0);
        enterString("\033[2;1H\033M").assertLinesAre("111", "   ", "222", "444").assertCursorAt(1, 0);
    }

    public void testIndexOutsideHorizontalMarginDoesNotScrollAndAutowrapBelowRegionDoesNotDamageIt() {
        withTerminalSized(5, 4).enterString("11111\r\n22222\r\n33333\r\n44444\033[2;3r\033[?69h\033[2;4s\033[3;1H\n");
        assertLinesAre("11111", "22222", "33333", "44444").assertCursorAt(2, 0);
        enterString("\033[2;1H\033M").assertCursorAt(1, 0);
        enterString("\033[?69l\033[4;5Hab").assertLinesAre("11111", "22222", "33333", "b444a");
    }

    public void testReverseWrapIsOptInAndKeepsLogicalLineMetadata() {
        withTerminalSized(5, 3).enterString("abcdef\b\b").assertCursorAt(1, 0);
        enterString("\033[?45h\b").assertCursorAt(0, 4);
        assertTrue(mTerminal.getScreen().getLineWrap(0));
        assertEnteringStringGivesResponse("\033[?45$p", "\033[?45;1$y");
        enterString("\033[2;2H\033[3D").assertCursorAt(0, 3);
        enterString("\033[?45l\033[2;1H\b").assertCursorAt(1, 0);
        enterString("\033[?45h\033[?7l\b").assertCursorAt(1, 0);
    }

    public void testReverseWrapConsumesPendingWrapAndStopsAtRegionTop() {
        withTerminalSized(5, 4).enterString("\033[?45habcde\b").assertCursorAt(0, 4);
        enterString("\b").assertCursorAt(0, 3);
        enterString("\033[Habcdef\033[2;4r\033[2;1H\b").assertCursorAt(1, 0);
        enterString("\033[3;1H\b").assertCursorAt(2, 0); // Hard line break cannot reverse-wrap.
    }

    public void testAnsiModesHandleMultipleParametersAndAnswerQueries() {
        withTerminalSized(5, 4).enterString("\033[4;20hAB\nC").assertLinesAre("AB   ", "C    ", "     ", "     ");
        assertEnteringStringGivesResponse("\033[4$p\033[20$p\033[999$p", "\033[4;1$y\033[20;1$y\033[999;0$y");
        enterString("\033[4;20lD\nE").assertLinesAre("AB   ", "CD   ", "  E  ", "     ");
        assertEnteringStringGivesResponse("\033[4$p\033[20$p", "\033[4;2$y\033[20;2$y");
    }

    public void testNewlineModeEncodesEnterAndUnbracketedPasteWhileKeepingApplicationKeypad() {
        withTerminalSized(5, 4);
        assertEquals("\r", mTerminal.getKeyCode(66, 0)); // Android KEYCODE_ENTER.
        enterString("\033[20h");
        assertEquals("\r\n", mTerminal.getKeyCode(66, 0));
        mTerminal.paste("a\r\nb\nc\r");
        assertEquals("a\r\nb\r\nc\r\n", mOutput.getOutputAndClear());
        enterString("\033[?2004h");
        mTerminal.paste("a\r\nb\nc\r");
        assertEquals("\033[200~a\r\nb\nc\r\033[201~", mOutput.getOutputAndClear());
        enterString("\033=");
        assertEquals("\033OM", mTerminal.getKeyCode(160, 0)); // Android KEYCODE_NUMPAD_ENTER.
    }

    public void testSoftResetPreservesDisplayedImagesTabsMousePasteAndInactiveSavedCursor() {
        withTerminalSized(12, 6).enterString("\033[3;4H\033[32m\0337\033[?1049h\033[>7u\033[?1003;1006;2004h");
        enterString("\033[3g\033[4G\033H\033[H\033_Ga=T,i=4,f=24,s=1,v=1,C=1,q=2;/wAA\033\\");
        enterString("\033[31;1m\033[?6;45h\033[4;20h\033[3;4H\033[!p").assertCursorAt(2, 3);
        assertTrue(mTerminal.isAlternateBufferActive());
        assertEquals(1, mTerminal.getGraphics().getPlacements().size());
        assertEquals(7, mTerminal.getKeyboard().flags());
        assertTrue(mTerminal.isMouseMotionTrackingActive());
        assertEnteringStringGivesResponse("\033[?2004$p\033[20$p\033[4$p\033[?45$p", "\033[?2004;1$y\033[20;1$y\033[4;2$y\033[?45;2$y");
        enterString("X").assertForegroundColorAt(2, 3, TextStyle.COLOR_INDEX_FOREGROUND);
        assertEquals(0, TextStyle.decodeEffect(getStyleAt(2, 3)));
        enterString("\033[H\t").assertCursorAt(0, 3);
        enterString("\033[?1049l").assertCursorAt(2, 3);
        enterString("Y").assertForegroundColorAt(2, 3, 2);
    }

    public void testRisReturnsToMainAndClearsBothScreensHistoryAndStyles() {
        withTerminalSized(5, 3).enterString("11111\r\n22222\r\n33333\r\n44444\033[?1049h\033[31;1mabcdef\033c");
        assertFalse(mTerminal.isAlternateBufferActive());
        assertEquals(0, mTerminal.getScreen().getActiveTranscriptRows());
        assertLinesAre("     ", "     ", "     ").assertCursorAt(0, 0);
        enterString("X").assertForegroundColorAt(0, 0, TextStyle.COLOR_INDEX_FOREGROUND);
        assertEquals(0, TextStyle.decodeEffect(getStyleAt(0, 0)));
        enterString("\033[?47h").assertLinesAre("     ", "     ", "     ").assertLineWraps(false, false, false);
    }
}
