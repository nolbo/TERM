package dev.terminal.emulator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

public class TerminalSearchTest extends TerminalTestCase {
    private List<TerminalSearch.Match> find(String query, TerminalSearch.Scope scope) {
        return TerminalSearch.find(mTerminal.getHistory(), query, scope);
    }

    private void assertCells(TerminalSearch.Match match, String expected) {
        assertEquals(expected, mTerminal.getScreen().getSelectedText(match.startColumn, match.startRow,
            match.endColumn - 1, match.endRow));
    }

    public void testOrdinaryOutputFindsEveryOccurrenceWithoutShellIntegration() {
        withTerminalSized(40, 4);
        enterString("startup needle NEEDLE\r\nother text");
        List<TerminalSearch.Match> hits = find("needle", TerminalSearch.Scope.OUTPUT);
        assertEquals(2, hits.size());
        assertEquals(0, hits.get(0).startRow);
        assertEquals(8, hits.get(0).startColumn);
        assertEquals(15, hits.get(1).startColumn);
        assertCells(hits.get(0), "needle");
        assertCells(hits.get(1), "NEEDLE");
        assertTrue(find("needle", TerminalSearch.Scope.COMMANDS).isEmpty());
        assertEquals(2, find("needle", TerminalSearch.Scope.ALL).size());
        assertTrue(find("", TerminalSearch.Scope.ALL).isEmpty());
    }

    public void testScopesUseInputCellsAndReturnTheOutputOccurrenceInsteadOfItsCommand() {
        withTerminalSized(50, 8);
        enterString("unmarked needle\r\n\033]133;A\007prompt needle $ \033]133;B\007echo needle\r\n\033]133;C\007"
            + "first\r\nsecond\r\nneedle needle\r\n\033]133;D;0\007");
        List<TerminalSearch.Match> commands = find("needle", TerminalSearch.Scope.COMMANDS);
        List<TerminalSearch.Match> output = find("needle", TerminalSearch.Scope.OUTPUT);
        assertEquals(1, commands.size());
        assertEquals(1, commands.get(0).startRow);
        assertEquals(3, output.size());
        assertEquals(0, output.get(0).startRow);
        assertEquals(4, output.get(1).startRow);
        assertEquals(4, output.get(2).startRow);
        assertEquals(7, output.get(2).startColumn);
        assertTrue(output.get(1).startRow > mTerminal.getHistory().getMarkers().get(0).anchor.getRow());
        assertEquals(5, find("needle", TerminalSearch.Scope.ALL).size());
    }

    public void testLegacyPromptIsExcludedFromCommandAndOutputScopes() {
        withTerminalSized(60, 4);
        enterString("\033]133;A\007needle@host $ echo needle\r\n\033]133;C\007needle\r\n\033]133;D;0\007");
        List<TerminalSearch.Match> commands = find("needle", TerminalSearch.Scope.COMMANDS);
        List<TerminalSearch.Match> output = find("needle", TerminalSearch.Scope.OUTPUT);
        assertEquals(1, commands.size());
        assertEquals(19, commands.get(0).startColumn);
        assertEquals(1, output.size());
        assertEquals(1, output.get(0).startRow);
    }

    public void testLegacyBashCommandsRemainSearchableInScrollback() throws Exception {
        withTerminalSized(60, 4);
        for (String command : new String[]{"ls -al", "ls -al /usr/bin"}) {
            enterString("\033]133;A\007root@ubuntu:~# " + command + "\r\n\033]133;C\007");
            for (int i = 0; i < 20; i++) enterString("listed-file-" + i + "\r\n");
            enterString("\033]133;D;0\007");
        }
        assertEquals("ls -al", mTerminal.getHistory().getMarkers().get(0).getCommandText());
        assertEquals("ls -al /usr/bin", mTerminal.getHistory().getMarkers().get(1).getCommandText());
        for (CommandMarker marker : mTerminal.getHistory().getMarkers()) assertTrue(marker.anchor.getRow() < 0);
        List<TerminalSearch.Match> hits = find("ls", TerminalSearch.Scope.COMMANDS);
        assertEquals(2, hits.size());
        for (TerminalSearch.Match hit : hits) { assertTrue(hit.startRow < 0); assertCells(hit, "ls"); }
        assertTrue(find("root@ubuntu", TerminalSearch.Scope.COMMANDS).isEmpty());
        assertTrue(find("ls -al", TerminalSearch.Scope.OUTPUT).isEmpty());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        TerminalCheckpoint.write(mTerminal, bytes);
        mTerminal.restoreState(TerminalCheckpoint.read(new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals(2, find("ls", TerminalSearch.Scope.COMMANDS).size());
        resize(12, 6);
        assertEquals(2, find("ls", TerminalSearch.Scope.COMMANDS).size());
        resize(80, 4);
        assertEquals(2, find("ls", TerminalSearch.Scope.COMMANDS).size());
    }

    public void testSoftWrapsUnicodeCellsCombiningMarksAndOverlappingMatches() {
        withTerminalSized(6, 5);
        enterString("abcdNEEDLEzz");
        TerminalSearch.Match wrapped = find("needle", TerminalSearch.Scope.ALL).get(0);
        assertEquals(0, wrapped.startRow); assertEquals(4, wrapped.startColumn);
        assertEquals(1, wrapped.endRow); assertEquals(4, wrapped.endColumn);
        assertCells(wrapped, "NEEDLE");
        withTerminalSized(40, 4);
        enterString("前😀éneedle界\r\nababa\r\nnee\r\ndle");
        TerminalSearch.Match unicode = find("😀én", TerminalSearch.Scope.ALL).get(0);
        assertEquals(2, unicode.startColumn); assertEquals(6, unicode.endColumn);
        assertCells(unicode, "😀én");
        assertEquals(1, find("needle", TerminalSearch.Scope.ALL).size());
        List<TerminalSearch.Match> overlapping = find("aba", TerminalSearch.Scope.ALL);
        assertEquals(2, overlapping.size());
        assertEquals(0, overlapping.get(0).startColumn);
        assertEquals(2, overlapping.get(1).startColumn);
    }

    public void testSearchUsesRetainedInputBeyondTheBoundedGraphPreview() {
        withTerminalSized(100, 80);
        enterString("\033]133;A\007$ \033]133;B\007");
        for (int i = 0; i < 4200; i++) enterString("x");
        enterString("needle\r\n\033]133;C\007\033]133;D;0\007");
        assertFalse(mTerminal.getHistory().getMarkers().get(0).getCommandText().contains("needle"));
        List<TerminalSearch.Match> hits = find("needle", TerminalSearch.Scope.COMMANDS);
        assertEquals(1, hits.size());
        assertCells(hits.get(0), "needle");
    }

    public void testFreshPositionsAfterReflowCheckpointAndTrim() throws Exception {
        mTerminal = new TerminalEmulator(mOutput, 20, 8, INITIAL_CELL_WIDTH_PIXELS, INITIAL_CELL_HEIGHT_PIXELS, 100, null);
        enterString("\033]133;A\007$ \033]133;B\007printf needle\r\n\033]133;C\007long prefix needle suffix\r\n\033]133;D;0\007");
        assertEquals(2, find("needle", TerminalSearch.Scope.ALL).size());
        resize(7, 8);
        List<TerminalSearch.Match> reflowed = find("needle", TerminalSearch.Scope.ALL);
        assertEquals(2, reflowed.size());
        for (TerminalSearch.Match match : reflowed) assertCells(match, "needle");
        assertEquals(1, find("needle", TerminalSearch.Scope.COMMANDS).size());
        assertEquals(1, find("needle", TerminalSearch.Scope.OUTPUT).size());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        TerminalCheckpoint.write(mTerminal, bytes);
        mTerminal.restoreState(TerminalCheckpoint.read(new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals(2, find("needle", TerminalSearch.Scope.ALL).size());
        resize(40, 8);
        assertEquals(1, find("needle", TerminalSearch.Scope.OUTPUT).size());
        for (int i = 0; i < 150; i++) enterString("trim\r\n");
        assertTrue(find("needle", TerminalSearch.Scope.ALL).isEmpty());
        assertTrue(mTerminal.getHistory().getMarkers().isEmpty());
    }
}
