package dev.terminal.emulator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class TerminalHistoryTest extends TerminalTestCase {
    private List<CommandMarker> markers() { return mTerminal.getHistory().getMarkers(); }

    public void testOscBoundariesAttachToPromptWithoutCapturingCommandText() {
        withTerminalSized(30, 4);
        enterString("\033]133;A\007$ secret-command\r\n\033]133;");
        assertTrue(markers().isEmpty());
        enterString("C\033\\");
        CommandMarker marker = markers().get(0);
        assertEquals(Integer.valueOf(0), marker.anchor.getRow());
        assertTrue(marker.startedAt > 0);
        assertTrue(marker.isRunning());
        enterString("\033]133;C;duplicate\007output\r\n\033]133;D;7;ignored\033\\");
        assertEquals(1, markers().size());
        assertFalse(marker.isRunning());
        assertTrue(marker.isFailed());
        assertEquals(Integer.valueOf(7), marker.getExitCode());
        for (java.lang.reflect.Field field : CommandMarker.class.getDeclaredFields())
            assertFalse("Metadata must not capture command strings", field.getType() == String.class);
    }

    public void testFallbackUnknownExitPromptAndMalformedFrames() {
        withTerminalSized(20, 4);
        enterString("\033]133;Coffee\007\033]133;C\030ignored");
        assertTrue(markers().isEmpty());
        enterString("\033]133;C\007\033]133;D;invalid\007");
        assertFalse(markers().get(0).isRunning());
        assertNull(markers().get(0).getExitCode());
        enterString("\r\n\033]133;C\007\033]133;A\007");
        assertEquals(2, markers().size());
        assertFalse(markers().get(1).isRunning());
        assertNull(markers().get(1).getExitCode());
    }

    public void testCommandTextUsesInputBoundariesWithoutKeepingAnotherCopy() {
        withTerminalSized(80, 4);
        enterString("\033]133;A\007odd custom prompt → \033]133;B\007CONFIG='private value' /usr/bin/git status --token=secret\r\n\033]133;C\007");
        CommandMarker marker = markers().get(0);
        assertEquals("CONFIG='private value' /usr/bin/git status --token=secret", marker.getCommandText());
        enterString("\033]133;D;7\007output must not become the name\r\n");
        assertEquals("CONFIG='private value' /usr/bin/git status --token=secret", marker.getCommandText());
        enterString("\033]133;A\007prompt \033]133;B\007'/opt/My tools/my tool' --private=argument\r\n\033]133;C\007");
        assertEquals("'/opt/My tools/my tool' --private=argument", markers().get(1).getCommandText());
        for (java.lang.reflect.Field field : CommandMarker.class.getDeclaredFields())
            assertFalse("Names must be derived from retained cells", field.getType() == String.class);
    }

    public void testLegacyPromptNamesAndUnknownInputNeverUseExecutionOutput() {
        withTerminalSized(80, 5);
        enterString("\033]133;A\007\033[31muser@host:/tmp $ \033[0mcargo test --secret=value\r\n\033]133;C\007output\r\n\033]133;D;0\007");
        assertEquals("cargo test --secret=value", markers().get(0).getCommandText());
        enterString("\033]133;A\007# printf '$ other'\r\n\033]133;C\007\033]133;D;0\007");
        assertEquals("printf '$ other'", markers().get(1).getCommandText());
        enterString("\033]133;C\007not-a-command-name\r\n");
        assertNull(markers().get(2).getCommandText());
    }

    public void testCommandTextFollowsReflowAndReleasesInputAnchorsWithTrim() {
        withTerminalSized(40, 5);
        enterString("\033]133;A\007$ \033]133;B\007/usr/bin/tool😀 --private=argument\r\n\033]133;C\007\033]133;D;0\007");
        CommandMarker marker = markers().get(0);
        assertEquals("/usr/bin/tool😀 --private=argument", marker.getCommandText());
        resize(7, 5);
        assertEquals("/usr/bin/tool😀 --private=argument", marker.getCommandText());
        resize(50, 6);
        assertEquals("/usr/bin/tool😀 --private=argument", marker.getCommandText());
        marker.anchor.release();
        assertNull(marker.getCommandText());
        assertNull(marker.inputStart.line);
        assertNull(marker.inputEnd.line);
        assertTrue(markers().isEmpty());
    }

    public void testInputBoundaryCheckpointPreservesCommandTextAndPendingInput() throws Exception {
        withTerminalSized(30, 5);
        enterString("\033]133;A\007$ \033]133;B\007first --private=value\r\n\033]133;C\007\033]133;D;1\007\033]133;A\007$ \033]133;B\007second --secret=value\r\n");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        TerminalCheckpoint.write(mTerminal, bytes);
        mTerminal.restoreState(TerminalCheckpoint.read(new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals("first --private=value", markers().get(0).getCommandText());
        enterString("\033]133;C\007");
        assertEquals("second --secret=value", markers().get(1).getCommandText());
    }

    public void testCommandDisplayRetainsArguments() {
        withTerminalSized(200, 4);
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 100; i++) name.append('x');
        enterString("\033]133;A\007$ \033]133;B\007" + name + "; printf private-arguments\r\n\033]133;C\007");
        assertEquals(name + "; printf private-arguments", markers().get(0).getCommandText());
    }

    public void testTrimInvalidatesIdentityBeforeRingRowIsReused() {
        TerminalBuffer buffer = new TerminalBuffer(8, 8, 3);
        TerminalHistory history = buffer.getHistory();
        history.commandStarted(0, 0);
        HistoryAnchor anchor = history.getMarkers().get(0).anchor;
        CommandMarker marker = history.getMarkers().get(0);
        for (int i = 0; i < 5; i++) buffer.scrollDownOneLine(0, 3, TextStyle.NORMAL);
        assertEquals(Integer.valueOf(-5), anchor.getRow());
        buffer.scrollDownOneLine(0, 3, TextStyle.NORMAL);
        assertNull(anchor.getRow());
        assertNull(marker.getCommandText());
        assertNull(marker.inputEnd.line);
        assertTrue(history.getMarkers().isEmpty());
        history.commandStarted(0, 0);
        assertNotSame(anchor, history.getMarkers().get(0).anchor);
    }

    public void testClearTranscriptAndSameRowSpamRemainBounded() {
        TerminalBuffer buffer = new TerminalBuffer(8, 8, 3);
        TerminalHistory history = buffer.getHistory();
        for (int i = 0; i < 100; i++) {
            history.commandStarted(0, 0);
            history.commandCompleted(0);
        }
        assertEquals(8, history.getMarkers().size());
        buffer.scrollDownOneLine(0, 3, TextStyle.NORMAL);
        buffer.clearTranscript();
        assertTrue(history.getMarkers().isEmpty());
    }

    public void testReflowRemapsSameAnchorAtWideCharacterAndPreservesStatus() {
        withTerminalSized(8, 4);
        enterString("12345\033]133;C\007한😀xy\r\n\033]133;D;0\007tail");
        CommandMarker marker = markers().get(0);
        HistoryAnchor identity = marker.anchor;
        assertEquals(5, identity.getColumn());
        resize(4, 4);
        assertSame(identity, markers().get(0).anchor);
        assertEquals(1, identity.getColumn());
        assertEquals("한", mTerminal.getSelectedText(identity.getColumn(), identity.getRow(), identity.getColumn(), identity.getRow()));
        resize(12, 6);
        assertSame(identity, markers().get(0).anchor);
        assertEquals(5, identity.getColumn());
        assertFalse(marker.isRunning());
        assertEquals(Integer.valueOf(0), marker.getExitCode());
    }

    public void testBlankPromptReflowsAndHeightResizeTrimsOldAnchors() {
        TerminalBuffer buffer = new TerminalBuffer(8, 8, 3);
        buffer.getHistory().commandStarted(0, 0);
        HistoryAnchor anchor = buffer.getHistory().getMarkers().get(0).anchor;
        buffer.resize(4, 3, 8, new int[]{0, 2}, TextStyle.NORMAL, false);
        assertNotNull(anchor.getRow());
        for (int i = 0; i < 5; i++) buffer.scrollDownOneLine(0, 3, TextStyle.NORMAL);
        buffer.resize(4, 6, 8, new int[]{0, 0}, TextStyle.NORMAL, false);
        assertNotNull(anchor.getRow());
        // Shrinking again at a bottom cursor consumes the oldest history to fit the capacity.
        buffer.resize(4, 3, 8, new int[]{0, 5}, TextStyle.NORMAL, false);
        if (anchor.getRow() != null) assertTrue(anchor.getRow() >= -buffer.getActiveTranscriptRows());
    }

    public void testAlternateScreenIgnoresBoundariesAndKeepsMainAnchorsThroughResize() {
        withTerminalSized(20, 4);
        enterString("\033]133;C\007start\r\n");
        CommandMarker marker = markers().get(0);
        enterString("\033[?1049h\033]133;A\007\033]133;C\007\033]133;D;9\007");
        assertTrue(mTerminal.isHistoryViewportOwnedByApplication());
        assertEquals(1, markers().size());
        assertTrue(marker.isRunning());
        resize(10, 6);
        enterString("\033[?1049l\033]133;D;0\007");
        assertFalse(mTerminal.isHistoryViewportOwnedByApplication());
        assertNotNull(marker.anchor.getRow());
        assertEquals(Integer.valueOf(0), marker.getExitCode());
        enterString("\033[?1000h");
        assertTrue(mTerminal.isHistoryViewportOwnedByApplication());
        enterString("\033[?1000l\033[2;5r");
        assertTrue(mTerminal.isHistoryViewportOwnedByApplication());
        enterString("\033[r\033[?1h");
        assertTrue(mTerminal.isHistoryViewportOwnedByApplication());
    }

    public void testNarrowReflowEvictsMarkersWhoseCellsNoLongerFitInHistory() {
        TerminalBuffer buffer = new TerminalBuffer(8, 8, 3);
        for (int row = 0; row < 3; row++) buffer.blockSet(0, row, 8, 1, 'x', TextStyle.NORMAL);
        buffer.getHistory().commandStarted(0, 0);
        buffer.getHistory().commandCompleted(0);
        HistoryAnchor anchor = buffer.getHistory().getMarkers().get(0).anchor;
        for (int i = 0; i < 5; i++) {
            buffer.scrollDownOneLine(0, 3, TextStyle.NORMAL);
            buffer.blockSet(0, 2, 8, 1, 'x', TextStyle.NORMAL);
        }
        assertEquals(Integer.valueOf(-5), anchor.getRow());
        buffer.resize(4, 3, 8, new int[]{7, 2}, TextStyle.NORMAL, false);
        assertNull(anchor.getRow());
        assertTrue(buffer.getHistory().getMarkers().isEmpty());
    }

    public void testCheckpointRestoresAnchorAliasesRunningCommandAndPendingPrompt() throws Exception {
        mTerminal = new TerminalEmulator(mOutput, 16, 4, 1, 1, 100, null);
        enterString("\033]133;A\007$ first\r\n\033]133;C\007");
        for (int i = 0; i < 20; i++) enterString("output\r\n");
        CommandMarker before = markers().get(0);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        TerminalCheckpoint.write(mTerminal, bytes);
        TerminalEmulator mirror = new TerminalEmulator(new MockTerminalOutput(), 5, 3, 1, 1, 100, null);
        mirror.restoreState(TerminalCheckpoint.read(new ByteArrayInputStream(bytes.toByteArray())));
        CommandMarker restored = mirror.getHistory().getMarkers().get(0);
        assertEquals(before.startedAt, restored.startedAt);
        assertEquals(before.anchor.getRow(), restored.anchor.getRow());
        append(mirror, "\033]133;D;2\007\033]133;A\007$ next\r\n");
        assertTrue(restored.isFailed());
        mirror.resize(8, 5, 1, 1);
        assertNotNull(restored.anchor.getRow());
        ByteArrayOutputStream second = new ByteArrayOutputStream();
        TerminalCheckpoint.write(mirror, second);
        mirror.restoreState(TerminalCheckpoint.read(new ByteArrayInputStream(second.toByteArray())));
        append(mirror, "\033]133;C\007");
        assertEquals(2, mirror.getHistory().getMarkers().size());
        for (int i = 0; i < 150; i++) append(mirror, "trim\r\n");
        assertTrue(mirror.getHistory().getMarkers().isEmpty());
    }

    private static void append(TerminalEmulator emulator, String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        emulator.append(data, data.length);
    }

    public void testSearchSeparatesCommandOutputAndPromptsAcrossSoftWraps() {
        withTerminalSized(12, 5);
        enterString("\033]133;A\007custom prompt\r\n\033]133;B\007echo argument\r\n\033]133;C\007unique-output-tail\r\n\033]133;D;0\007\033]133;A\007new prompt");
        CommandMarker marker = markers().get(0);
        assertEquals("echo argument", marker.getCommandText());
        assertTrue(marker.matchesCommand("ECHO ARGUMENT"));
        assertFalse(marker.matchesCommand("unique-output"));
        assertTrue(marker.matchesOutput("OUTPUT-TAIL"));
        assertFalse(marker.matchesOutput("argument"));
        assertFalse(marker.matchesOutput("new prompt"));
        assertFalse(marker.matchesCommand("custom prompt"));
        resize(7, 6);
        assertTrue(marker.matchesCommand("echo argument"));
        assertTrue(marker.matchesOutput("output-tail"));
    }

    public void testNativeEchoBoundaryStartsOnlyNonemptyCommandsAndTracksRunningOutput() {
        withTerminalSized(30, 5);
        enterString("\033]133;A\007~ $ \033]133;B;droid=mksh\007\r\n");
        assertTrue(markers().isEmpty());
        enterString("\033]133;D;0\007\033]133;A\007~ $ \033]133;B;droid=mksh\007false\r\n");
        assertEquals(1, markers().size());
        CommandMarker marker = markers().get(0);
        assertEquals("false", marker.getCommandText());
        assertTrue(marker.isRunning());
        enterString("running output\r\n");
        assertTrue(marker.matchesOutput("running output"));
        assertEquals("running output", marker.getOutputText());
        enterString("\033]133;D;1\007\033]133;A\007~ $ ");
        assertTrue(marker.isFailed());
        assertTrue(marker.matchesOutput("running output"));
        assertFalse(marker.matchesOutput("~ $"));
        assertEquals("running output", marker.getOutputText());
        assertEquals(1, markers().size());
    }

    public void testOutputSearchAndBoundariesSurviveCheckpointAndImplicitCompletion() throws Exception {
        withTerminalSized(30, 5);
        enterString("\033]133;A\007$ \033]133;B\007printf result\r\n\033]133;C\007result\r\n\033]133;A\007$ next");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        TerminalCheckpoint.write(mTerminal, bytes);
        mTerminal.restoreState(TerminalCheckpoint.read(new ByteArrayInputStream(bytes.toByteArray())));
        CommandMarker marker = markers().get(0);
        assertFalse(marker.isRunning());
        assertNull(marker.getExitCode());
        assertTrue(marker.matchesOutput("result"));
        assertFalse(marker.matchesOutput("next"));
        assertEquals("printf result", marker.getCommandText());
    }

    public void testNativeContinuationKeepsInputOutOfOutputSearch() {
        withTerminalSized(40, 5);
        enterString("\033]133;A\007~ $ \033]133;B;droid=mksh\007printf '\r\n> \033]133;B;droid=mksh-continuation\007input-value'\r\nactual-output\r\n\033]133;D;0\007");
        CommandMarker marker = markers().get(0);
        assertTrue(marker.matchesCommand("input-value"));
        assertFalse(marker.matchesOutput("input-value"));
        assertTrue(marker.matchesOutput("actual-output"));
        assertEquals("actual-output", marker.getOutputText());
    }

    public void testSshWithoutRemoteHooksRecordsCommandsAndExcludesAuthentication() {
        withTerminalSized(80, 10);
        enterString("\033]133;A\007local $ \033]133;B\007ssh server\r\n\033]133;C\007Password: \r\nWelcome\r\n");
        assertEquals(1, markers().size());
        assertTrue(markers().get(0).isRunning());
        enterString("\033[?2004hremote@server:~$ ");
        CommandMarker ssh = markers().get(0);
        assertFalse(ssh.isRunning());
        assertNull(ssh.getExitCode());
        assertEquals("Password:\nWelcome", ssh.getOutputText());
        enterString("printf value\r\n\033[?2004l\rvalue\r\n\033[?2004hremote@server:~$ ");
        assertEquals(2, markers().size());
        CommandMarker remote = markers().get(1);
        assertEquals("printf value", remote.getCommandText());
        assertEquals("value", remote.getOutputText());
        assertFalse(remote.isRunning());
        assertNull(remote.getExitCode());
        assertTrue(remote.matchesCommand("printf value"));
        assertFalse(remote.matchesCommand("remote@server"));
        assertEquals(1, TerminalSearch.find(mTerminal.getHistory(), "printf value", TerminalSearch.Scope.COMMANDS).size());
        enterString("exit\r\n\033[?2004l\rexit\r\n\033]133;D;0\007\033]133;A\007local $ \033]133;B\007pwd\r\n\033]133;C\007/local\r\n\033]133;D;0\007");
        assertEquals(4, markers().size());
        assertEquals("exit", markers().get(2).getCommandText());
        assertEquals("pwd", markers().get(3).getCommandText());
        assertEquals(Integer.valueOf(0), markers().get(3).getExitCode());
    }

    public void testSuAndNestedSshKeepEachUsersCommandsSeparate() {
        withTerminalSized(80, 12);
        enterString("\033]133;A\007user $ \033]133;B\007su - root\r\n\033]133;C\007\033[?2004hroot@local:~# ");
        enterString("whoami\r\n\033[?2004l\rroot\r\n\033[?2004hroot@local:~# ");
        enterString("ssh remote\r\n\033[?2004l\r\033[?2004huser@remote:~$ ");
        enterString("id\r\n\033[?2004l\ruid=1000\r\n\033[?2004huser@remote:~$ ");
        assertEquals(4, markers().size());
        assertEquals("su - root", markers().get(0).getCommandText());
        assertEquals("whoami", markers().get(1).getCommandText());
        assertEquals("root", markers().get(1).getOutputText());
        assertEquals("ssh remote", markers().get(2).getCommandText());
        assertEquals("id", markers().get(3).getCommandText());
        assertEquals("uid=1000", markers().get(3).getOutputText());
        assertNull(markers().get(3).getExitCode());
    }

    public void testReadlineFallbackNeverDuplicatesExplicitOscBoundaries() {
        withTerminalSized(80, 8);
        enterString("\033]133;A\007\033[?2004hcustom prompt → \033]133;B\007false\r\n\033[?2004l\r\033]133;C\007\033]133;D;1\007\033]133;A\007\033[?2004hcustom prompt → ");
        assertEquals(1, markers().size());
        assertEquals("false", markers().get(0).getCommandText());
        assertTrue(markers().get(0).isFailed());
        assertEquals(Integer.valueOf(1), markers().get(0).getExitCode());
        // A late explicit prompt supersedes a pending fallback candidate as well.
        enterString("\033]133;A\007$ \033]133;B\007echo explicit\r\n\033[?2004l\r\033]133;C\007\033]133;D;0\007");
        assertEquals(2, markers().size());
    }

    public void testReadlineFallbackRejectsPlainOutputBlankInputAndCancelledEditing() {
        withTerminalSized(80, 12);
        enterString("root@host:~# output that looks like a command\r\nPassword: \r\n");
        enterString("\033[?2004hPassword: \r\n\033[?2004l\r");
        enterString("\033[?2004h$ \r\n\033[?2004l\r");
        enterString("\033[?2004h$ cancelled^C\033[?2004l\r\033[?2004h\033[?2004l\r\r\n");
        enterString("\033[?2004h>>> print('REPL input')\r\n\033[?2004l\rREPL output\r\n");
        assertTrue(markers().isEmpty());
        enterString("\033[?2004h$ echo real\r\n\033[?2004l\r");
        assertEquals("echo real", markers().get(0).getCommandText());
    }

    public void testReadlineMultilinePasteAndContinuationWaitForSubmission() {
        withTerminalSized(80, 10);
        enterString("\033[?2004h$ printf 'first\r\nsecond'\r\n");
        assertTrue(markers().isEmpty());
        enterString("\033[?2004l\rfirst\r\nsecond\r\n\033[?2004h$ ");
        assertEquals("printf 'first\nsecond'", markers().get(0).getCommandInputText());
        assertEquals("first\nsecond", markers().get(0).getOutputText());
        enterString("printf 'one\r\n\033[?2004l\r\033[?2004h> ");
        assertTrue(markers().get(1).isRunning());
        enterString("two'\r\n\033[?2004l\rone\r\ntwo\r\n\033[?2004h$ ");
        assertEquals(2, markers().size());
        assertTrue(markers().get(1).matchesCommand("two'"));
        assertFalse(markers().get(1).matchesOutput("two'"));
        assertEquals("one\ntwo", markers().get(1).getOutputText());
    }

    public void testReadlinePendingInputSurvivesChunkingCheckpointAndReflow() throws Exception {
        mTerminal = new TerminalEmulator(mOutput, 30, 8, 1, 1, 100, null);
        enterString("\033[?2004huser@host:~");
        enterString("$ ");
        enterString("printf 한😀wrapped-value");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        TerminalCheckpoint.write(mTerminal, bytes);
        mTerminal.restoreState(TerminalCheckpoint.read(new ByteArrayInputStream(bytes.toByteArray())));
        resize(12, 8);
        enterString("\r\n\033[?2004");
        enterString("l\rresult\r\n\033[?2004huser@host:~$ ");
        assertEquals(1, markers().size());
        assertEquals("printf 한😀wrapped-value", markers().get(0).getCommandInputText());
        assertEquals("result", markers().get(0).getOutputText());
        assertFalse(markers().get(0).isRunning());
    }

    public void testReadlineFallbackIgnoresApplicationOwnedScreensAndEvictedCandidates() {
        mTerminal = new TerminalEmulator(mOutput, 20, 4, 1, 1, 100, null);
        enterString("\033[?2004h$ stale\033[?1049h\033[?2004h$ editor\r\n\033[?2004l\033[?1049l\r\n\033[?2004l");
        assertTrue(markers().isEmpty());
        enterString("\033[?1000h\033[?2004h$ mouse\r\n\033[?2004l\033[?1000l");
        assertTrue(markers().isEmpty());
        enterString("\033[?2004h$ old input\r\n");
        for (int i = 0; i < 150; i++) enterString("scroll\r\n");
        enterString("\033[?2004l");
        assertTrue(markers().isEmpty());
    }

    public void testCommandContentFollowsBoundariesReflowCheckpointAndTrim() throws Exception {
        mTerminal = new TerminalEmulator(mOutput, 18, 6, 1, 1, 100, null);
        enterString("\033]133;A\007custom prompt $ printf result\r\n\033]133;C\007\033[31mfirst line\033[0m\r\n\r\nsecond 😀\r\n\033]133;D;0\007\033]133;A\007$ next prompt");
        CommandMarker marker = markers().get(0);
        String expected = "first line\n\nsecond 😀";
        assertEquals("printf result", marker.getCommandInputText());
        assertEquals(expected, marker.getOutputText());
        resize(7, 6);
        assertEquals("printf result", marker.getCommandInputText());
        assertEquals(expected, marker.getOutputText());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        TerminalCheckpoint.write(mTerminal, bytes);
        mTerminal.restoreState(TerminalCheckpoint.read(new ByteArrayInputStream(bytes.toByteArray())));
        marker = markers().get(0);
        assertEquals(expected, marker.getOutputText());
        assertEquals("printf result", marker.getCommandInputText());
        for (int i = 0; i < 120; i++) enterString("trim\r\n");
        assertTrue(markers().isEmpty());
        assertNull(marker.getCommandInputText());
        assertNull(marker.getOutputText());
    }

    public void testCopyContentIsNotLimitedToTheCommandDisplayPreview() {
        mTerminal = new TerminalEmulator(mOutput, 40, 6, 1, 1, 1000, null);
        StringBuilder input = new StringBuilder(), output = new StringBuilder();
        for (int i = 0; i < 5000; i++) input.append('x');
        for (int i = 0; i < 6500; i++) output.append('y');
        enterString("\033]133;A\007$ \033]133;B\007" + input + "\r\n\033]133;C\007" + output + "\r\n\033]133;D;0\007\033]133;A\007$ next");
        CommandMarker marker = markers().get(0);
        assertTrue(marker.getCommandText().endsWith("…"));
        assertEquals(input.toString(), marker.getCommandInputText());
        assertEquals(output.toString(), marker.getOutputText());
        enterString("\033]133;B\007true\r\n\033]133;C\007\033]133;D;0\007");
        assertEquals("", markers().get(1).getOutputText());
    }
}
