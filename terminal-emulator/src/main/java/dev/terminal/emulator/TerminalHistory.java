package dev.terminal.emulator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Buffer-owned shell integration state; travels with the authoritative terminal checkpoint. */
public final class TerminalHistory implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    final TerminalBuffer buffer;
    private final LinkedHashMap<HistoryAnchor, CommandMarker> markers = new LinkedHashMap<>();
    private HistoryAnchor prompt;
    private HistoryAnchor inputStart;
    private CommandMarker running;
    private transient List<CommandMarker> snapshot;
    private long revision;
    private boolean echoedInput;
    private HistoryAnchor lineEditingStart;
    private HistoryAnchor lineEditingEnd;
    private boolean inferredRunning;

    TerminalHistory(TerminalBuffer buffer) { this.buffer = buffer; }

    public List<CommandMarker> getMarkers() {
        if (snapshot == null) snapshot = Collections.unmodifiableList(new ArrayList<>(markers.values()));
        return snapshot;
    }

    public long getRevision() { return revision; }
    void changed() { revision++; snapshot = null; }
    void coordinatesChanged() { revision++; }
    void textChanged() { revision++; }

    private HistoryAnchor at(int row, int column) {
        return new HistoryAnchor(this, buffer.allocateFullLineIfNecessary(buffer.externalToInternalRow(row)), column);
    }

    void promptStarted(int row, int column) {
        cancelLineEditing();
        commandCompleted(null, row, column);
        if (prompt != null) prompt.release();
        if (inputStart != null) inputStart.release();
        prompt = at(row, column);
        echoedInput = false;
    }

    void commandInputStarted(int row, int column) {
        if (running != null) return;
        cancelLineEditing();
        if (inputStart != null) inputStart.release();
        inputStart = at(row, column);
    }

    // The app-owned mksh prompt explicitly opts into echoed-input boundaries. Standard
    // OSC 133 producers continue to use C; ordinary output/newlines never create markers.
    void echoedInputStarted(int row, int column) {
        commandInputStarted(row, column);
        echoedInput = true;
    }

    void echoedContinuationStarted() {
        if (running != null) echoedInput = true;
    }

    void echoedInputSubmitted(int row, int column) {
        if (!echoedInput) return;
        if (running != null) {
            running.inputEnd.release();
            running.inputEnd = at(row, column);
            echoedInput = false;
            changed();
            return;
        }
        if (inputStart == null) return;
        HistoryAnchor end = at(row, column);
        String input = readRange(inputStart, end, 4096);
        end.release();
        if (input != null && !input.trim().isEmpty()) commandStarted(row, column);
        echoedInput = false;
    }

    // Readline enables bracketed paste before PS1 and disables it after echoed Enter.
    // This survives su/ssh without installing hooks in another user's or server's shell.
    // Require both that editing boundary and a visible shell prompt; plain output is inert.
    void lineEditingStarted(int row, int column) {
        if (lineEditingStart != null) return;
        if (prompt == null && inputStart == null) lineEditingStart = at(row, column);
    }

    void lineEditingNewline(int row, int column) {
        if (lineEditingStart == null) return;
        if (lineEditingEnd != null) lineEditingEnd.release();
        lineEditingEnd = at(row, column);
    }

    void lineEditingPromptVisible(int row, int column) {
        String text = lineEditingStart == null ? null : readRange(lineEditingStart, row, column, 4096);
        if (text == null || isContinuationPrompt(text)) return;
        // Selected text trims trailing blank cells, including the space after PS1's delimiter.
        if (LINE_EDITING_PROMPT_END.matcher(text + " ").find()) {
            Integer start = lineEditingStart.getRow();
            if (start != null) commandCompleted(null, start, lineEditingStart.getColumn());
        }
    }

    void lineEditingFinished(int row, int column) {
        if (lineEditingStart == null) return;
        String text = readRange(lineEditingStart, lineEditingEnd, 4096);
        Integer end = lineEditingEnd == null ? null : lineEditingEnd.getRow();
        // Mode resets without an echoed newline include cancelled input and password prompts.
        if (text != null && end != null && end == row && lineEditingEnd.getColumn() == column) {
            if (isContinuationPrompt(text)) {
                if (running != null && inferredRunning) {
                    running.inputEnd.release();
                    running.inputEnd = at(row, column);
                    changed();
                }
            } else {
                Matcher delimiter = LINE_EDITING_PROMPT_END.matcher(text);
                Integer start = lineEditingStart.getRow();
                if (start != null && delimiter.find()) {
                    commandCompleted(null, start, lineEditingStart.getColumn());
                    if (!text.substring(delimiter.end()).trim().isEmpty()) {
                        int startColumn = lineEditingStart.getColumn();
                        promptStarted(start, startColumn);
                        commandStarted(row, column);
                        inferredRunning = true;
                    }
                }
            }
        }
        cancelLineEditing();
    }

    private static boolean isContinuationPrompt(String text) { return text.equals(">") || text.startsWith("> "); }

    void cancelLineEditing() {
        if (lineEditingStart != null) lineEditingStart.release();
        if (lineEditingEnd != null) lineEditingEnd.release();
    }

    void commandStarted(int row, int column) {
        if (running != null) return; // Duplicate C frames must not create phantom commands.
        cancelLineEditing();
        inferredRunning = false;
        HistoryAnchor anchor = prompt;
        prompt = null;
        boolean includesPrompt = inputStart == null && anchor != null;
        if (anchor == null || anchor.getRow() == null) anchor = inputStart != null ? inputStart : at(row, column);
        running = new CommandMarker(anchor, System.currentTimeMillis(), inputStart != null ? inputStart : anchor,
            at(row, column), includesPrompt);
        inputStart = null;
        markers.put(anchor, running);
        // Bound metadata even if a program emits thousands of commands on one physical line.
        while (markers.size() > buffer.mTotalRows) markers.keySet().iterator().next().release();
        changed();
    }

    void commandCompleted(Integer code) {
        commandCompleted(code, -1, -1);
    }

    void commandCompleted(Integer code, int row, int column) {
        if (running == null) return;
        if (row >= 0) running.outputEnd = at(row, column);
        running.complete(code);
        running = null;
        inferredRunning = false;
        changed();
    }

    void anchorRemoved(HistoryAnchor anchor) {
        CommandMarker removed = markers.remove(anchor);
        if (removed == running) running = null;
        if (removed != null) removed.releaseInputAnchors();
        if (anchor == prompt) prompt = null;
        if (anchor == inputStart) inputStart = null;
        if (anchor == lineEditingStart) lineEditingStart = null;
        if (anchor == lineEditingEnd) lineEditingEnd = null;
        changed();
    }

    void discardInvalidAnchors() {
        for (CommandMarker marker : getMarkers())
            if (marker.anchor.getRow() == null) marker.anchor.release();
        if (prompt != null && prompt.getRow() == null) prompt.release();
        if (inputStart != null && inputStart.getRow() == null) inputStart.release();
        if (lineEditingStart != null && lineEditingStart.getRow() == null) lineEditingStart.release();
        if (lineEditingEnd != null && lineEditingEnd.getRow() == null) lineEditingEnd.release();
    }

    // Legacy A/C producers include PS1 in the range. Standard B frames provide an exact start.
    private static final Pattern PROMPT_END = Pattern.compile("[$#❯➜>]\\s+");
    // A bare '>' is PS2, and '>>>' belongs to Readline REPLs rather than a Bash PS1.
    private static final Pattern LINE_EDITING_PROMPT_END = Pattern.compile("[$#❯➜]\\s+");

    private String readRange(HistoryAnchor first, HistoryAnchor last, int limit) {
        if (first == null || last == null) return null;
        Integer end = last.getRow();
        return end == null ? null : readRange(first, end, last.getColumn(), limit);
    }

    private String readRange(HistoryAnchor first, int end, int endColumn, int limit) {
        Integer start = first == null ? null : first.getRow();
        if (start == null) return null;
        if (endColumn == 0) { end--; endColumn = buffer.mColumns; }
        if (end < start || end == start && endColumn <= first.getColumn()) return "";
        StringBuilder result = new StringBuilder();
        for (int row = start; row <= end && result.length() <= limit; row++) {
            result.append(buffer.getSelectedText(row == start ? first.getColumn() : 0, row,
                row == end ? endColumn - 1 : buffer.mColumns - 1, row));
            if (row < end && !buffer.getLineWrap(row)) result.append('\n');
        }
        return result.length() > limit ? result.substring(0, limit) + "…" : result.toString();
    }

    String commandText(CommandMarker marker) {
        return commandText(marker, 4096);
    }

    String commandInputText(CommandMarker marker) {
        return commandText(marker, Integer.MAX_VALUE);
    }

    private String commandText(CommandMarker marker, int limit) {
        if (!markers.containsKey(marker.anchor) || marker.inputStart == null || marker.inputEnd == null) return null;
        String input = readRange(marker.inputStart, marker.inputEnd, limit);
        if (input == null) return null;
        if (marker.inputIncludesPrompt) {
            Matcher promptEnd = PROMPT_END.matcher(input);
            if (!promptEnd.find()) return null;
            input = input.substring(promptEnd.end());
        }
        return input.trim().isEmpty() ? null : input.trim();
    }

    String outputText(CommandMarker marker) {
        if (!markers.containsKey(marker.anchor) || marker.inputEnd == null) return null;
        if (!marker.isRunning()) return readRange(marker.inputEnd, marker.outputEnd, Integer.MAX_VALUE);
        String output = readRange(marker.inputEnd, buffer.mScreenRows - 1, buffer.mColumns, Integer.MAX_VALUE);
        if (output == null) return null;
        // The running range includes unused screen rows; do not display them as output.
        int end = output.length();
        while (end > 0 && output.charAt(end - 1) == '\n') end--;
        return output.substring(0, end);
    }

    /** Case-insensitive literal search streams retained cells, including soft-wrap boundaries. */
    boolean matches(CommandMarker marker, String query, boolean output) {
        if (!markers.containsKey(marker.anchor) || query == null || query.isEmpty()) return false;
        HistoryAnchor first = output ? marker.inputEnd : marker.inputStart;
        HistoryAnchor last = output ? marker.outputEnd : marker.inputEnd;
        Integer start = first == null ? null : first.getRow();
        Integer end = last == null ? null : last.getRow();
        if (output && marker.isRunning()) end = buffer.mScreenRows - 1;
        if (start == null || end == null) return false;
        int endColumn = output && marker.isRunning() ? buffer.mColumns : last.getColumn();
        if (endColumn == 0) { end--; endColumn = buffer.mColumns; }
        String needle = query.toLowerCase(java.util.Locale.ROOT), tail = "";
        boolean skipPrompt = !output && marker.inputIncludesPrompt;
        String promptTail = "";
        for (int row = start; row <= end; row++) {
            int column = row == start ? first.getColumn() : 0;
            int right = row == end ? endColumn - 1 : buffer.mColumns - 1;
            if (right < column) continue;
            String cells = buffer.getSelectedText(column, row, right, row);
            if (!buffer.getLineWrap(row)) cells += '\n';
            if (skipPrompt) {
                cells = promptTail + cells;
                Matcher delimiter = PROMPT_END.matcher(cells);
                if (!delimiter.find()) {
                    promptTail = cells.substring(Math.max(0, cells.length() - 256));
                    continue;
                }
                cells = cells.substring(delimiter.end());
                skipPrompt = false;
            }
            String part = (tail + cells).toLowerCase(java.util.Locale.ROOT);
            if (part.contains(needle)) return true;
            tail = part.substring(Math.max(0, part.length() - needle.length() + 1));
        }
        return false;
    }

}
