package dev.terminal.emulator;

import java.nio.charset.StandardCharsets;

public class Osc8HyperlinkTest extends TerminalTestCase {
    private static final String URI = "https://example.com/actual;path?q=한";
    private static final String START = "\033]8;id=shared:unknown=value;" + URI + "\033\\";
    private static final String END = "\033]8;;\033\\";

    private void assertLink(int row, int column, String uri) {
        assertEquals("row=" + row + ", column=" + column, uri, mTerminal.getScreen().getHyperlinkAt(row, column));
    }

    /** ASCII fixtures: collect linked cells in transcript order, including linked spaces. */
    private void assertLinkedText(String expected) {
        TerminalBuffer screen = mTerminal.getScreen();
        StringBuilder linked = new StringBuilder();
        for (int row = -screen.getActiveTranscriptRows(); row < mTerminal.mRows; row++) {
            TerminalRow line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            for (int column = 0; column < mTerminal.mColumns; column++) {
                String uri = screen.getHyperlinkAt(row, column);
                if (uri != null) {
                    assertEquals(URI, uri);
                    linked.append(line.mText[line.findStartOfColumn(column)]);
                }
            }
        }
        assertEquals(expected, linked.toString());
    }

    public void testStartEndAndParametersWithBothTerminators() {
        for (String terminator : new String[]{"\007", "\033\\"}) {
            withTerminalSized(30, 3);
            enterString("-\033]8;id=shared:ignored=yes;" + URI + terminator + "label" +
                "\033]8;id=shared;" + terminator + "+");
            assertLinesAre("-label+                       ", "                              ", "                              ");
            assertLink(0, 0, null);
            for (int column = 1; column <= 5; column++) assertLink(0, column, URI);
            assertLink(0, 6, null);
            enterString("\033]8;id=shared;http://different.example\007X" + END + "Y");
            assertLink(0, 7, "http://different.example");
            assertLink(0, 8, null);
        }
    }

    public void testWrappedWideSupplementaryAndCombiningCells() {
        withTerminalSized(6, 4);
        enterString(">" + START + "A한😀e\u0301Z" + END + ".");
        assertLinesAre(">A한😀", "e\u0301Z.   ", "      ", "      ");
        assertLink(0, 0, null);
        for (int column = 1; column < 6; column++) assertLink(0, column, URI);
        assertLink(1, 0, URI);
        assertLink(1, 1, URI);
        assertLink(1, 2, null);
        resize(9, 4);
        for (int column = 1; column <= 7; column++) assertLink(0, column, URI);
        assertLink(0, 8, null);
        resize(6, 4);
        assertLink(0, 5, URI);
        assertLink(1, 0, URI);
        assertLink(1, 2, null);
    }

    public void testScrollbackAndRepeatedReflowPreserveOnlyLinkedRange() {
        withTerminalSized(6, 3);
        enterString(START + "abcdefghij" + END + "\r\none\r\ntwo\r\nthree");
        assertTrue(mTerminal.getScreen().getActiveTranscriptRows() > 0);
        assertLinkedText("abcdefghij");
        resize(4, 4);
        assertLinkedText("abcdefghij");
        resize(9, 5);
        assertLinkedText("abcdefghij");
        resize(5, 3);
        assertLinkedText("abcdefghij");
    }

    public void testLinkedSpacesSurviveReflow() {
        for (String label : new String[]{"a   ", "    "}) {
            withTerminalSized(8, 4);
            enterString(START + label + END + "\r\nnext");
            resize(3, 4);
            assertLinkedText(label);
            resize(8, 4);
            assertLinkedText(label);
        }
    }

    public void testReflowPreservesHyperlinkToRightOfCursor() {
        withTerminalSized(20, 4);
        enterString("prefix" + START + "label   " + END + "\r");
        resize(9, 4);
        assertLinkedText("label   ");
        assertCursorAt(0, 0);
        resize(20, 4);
        assertLinkedText("label   ");
        assertCursorAt(0, 0);
    }

    public void testInsertDeleteOverwriteAndEraseMoveOrClearMetadata() {
        withTerminalSized(12, 4);
        enterString(START + "abcde" + END + "\033[1;3H\033[@");
        assertLink(0, 2, null);
        assertLink(0, 3, URI);
        assertLinkedText("abcde");
        enterString("\033[P");
        assertLink(0, 2, URI);
        assertLink(0, 5, null);
        assertLinkedText("abcde");
        enterString("\033[1;2HX\033[1;4H\033[K");
        assertLink(0, 1, null);
        assertLinkedText("ac");
        enterString("\033[2J");
        assertLinkedText("");
    }

    public void testWideCellOverwriteClearsBothHalvesAndCombiningKeepsBaseLink() {
        withTerminalSized(12, 4);
        enterString(START + "한😀" + END + "\033[1;2HX");
        assertLink(0, 0, null);
        assertLink(0, 1, null);
        assertLink(0, 2, URI);
        assertLink(0, 3, URI);
        enterString("\033[1;3HY");
        assertLink(0, 2, null);
        assertLink(0, 3, null);
        enterString("\r" + START + "e" + END + "\u0301");
        assertLink(0, 0, URI);
        enterString("x" + START + "\u0301" + END);
        assertLink(0, 1, null);
    }

    public void testSgrAndOtherOscDoNotEndLinkButResetDoes() {
        withTerminalSized(12, 4);
        enterString(START + "a\033[0mb\033]0;title\007c" + END + "d");
        assertLinkedText("abc");
        enterString(START + "e\033cf");
        assertLink(0, 0, null);
        enterString("\033[2J");
        assertLinkedText("");
    }

    public void testRecycledScrollbackRowsDoNotKeepLinks() {
        withTerminalSized(8, 3);
        enterString(START + "old" + END + "\r\n");
        for (int i = 0; i < mTerminal.getScreen().mTotalRows + 2; i++) enterString("new\r\n");
        assertLinkedText("");
    }

    public void testFragmentedStartDoesNotCommitUntilTerminator() {
        byte[] start = START.getBytes(StandardCharsets.UTF_8);
        for (int split = 0; split < start.length; split++) {
            withTerminalSized(20, 4);
            mTerminal.append(start, split);
            assertLink(0, 0, null);
            assertCursorAt(0, 0);
            byte[] rest = new byte[start.length - split];
            System.arraycopy(start, split, rest, 0, rest.length);
            mTerminal.append(rest, rest.length);
            enterString("label" + END + "X");
            assertLinkedText("label");
            assertLink(0, 5, null);
        }
    }

    public void testMalformedAndCancelledSequencesCannotLeakPriorLinkOrMoveCursor() {
        for (String malformed : new String[]{
            "\033]8;id=missing-uri\007", "\033]8\007",
            "\033]8;;https://bad.example/\n\r\t\007",
            "\033]8;;https://bad.example/\033[2J\007",
            "\033]8;;https://bad.example/\033\007",
            "\033]8;;https://bad.example/\033\033\\",
            "\033]8;;https://bad.example/\u007f\007",
            "\033]8;;https://bad.example/\u009d\007",
            "\033]8;;https://bad.example/\030", "\033]8;;https://bad.example/\032"
        }) {
            withTerminalSized(20, 4);
            enterString(START + "A" + malformed + "B");
            assertLinkedText("A");
            assertLineIs(0, "AB                  ");
            assertLink(0, 1, null);
            enterString(START + "C" + END);
            assertLink(0, 2, URI);
        }
    }

    public void testOversizedOscIsDrainedAndOverflowedOpcodeCannotBecomeOsc8() {
        StringBuilder large = new StringBuilder("\033]8;;https://bad.example/");
        for (int i = 0; i < 9000; i++) large.append('x');
        for (String terminator : new String[]{"\007", "\033\\"}) {
            withTerminalSized(20, 4);
            enterString(START + "A" + large + terminator + "B");
            assertLinkedText("A");
            assertLineIs(0, "AB                  ");
            enterString(START + "C" + END);
            assertLink(0, 2, URI);
        }
        withTerminalSized(20, 4);
        enterString("\033]4294967304;;https://bad.example\007text");
        assertLinkedText("");
        assertLineIs(0, "text                ");
    }

    public void testMalformedUtf8InsideHyperlinkIsDiscardedWithoutPrintingReplacement() {
        for (byte[] invalid : new byte[][]{
            {(byte) 0xc2, 'x'}, {(byte) 0xff}, {(byte) 0xc0, (byte) 0xaf},
            {(byte) 0xed, (byte) 0xa0, (byte) 0x80}
        }) {
            withTerminalSized(20, 4);
            enterString(START + "A\033]8;;https://bad.example/");
            mTerminal.append(invalid, invalid.length);
            enterString("\033\\B");
            assertLineIs(0, "AB                  ");
            assertLinkedText("A");
        }
    }

    public void testDestinationsAreMetadataOnlyAndOutOfRangeCellsHaveNoLinks() {
        withTerminalSized(20, 4);
        enterString("\033]8;;file:///private\007label" + END);
        assertLink(0, 0, "file:///private");
        assertEquals("", mOutput.getOutputAndClear());
        assertLink(-1, 0, null);
        assertLink(4, 0, null);
        assertLink(0, -1, null);
        assertLink(0, 20, null);
    }
}
