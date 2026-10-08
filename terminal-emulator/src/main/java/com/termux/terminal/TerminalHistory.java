package com.termux.terminal;

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

    TerminalHistory(TerminalBuffer buffer) { this.buffer = buffer; }

    public List<CommandMarker> getMarkers() {
        if (snapshot == null) snapshot = Collections.unmodifiableList(new ArrayList<>(markers.values()));
        return snapshot;
    }

    public long getRevision() { return revision; }
    void changed() { revision++; snapshot = null; }
    void coordinatesChanged() { revision++; }

    private HistoryAnchor at(int row, int column) {
        return new HistoryAnchor(this, buffer.allocateFullLineIfNecessary(buffer.externalToInternalRow(row)), column);
    }

    void promptStarted(int row, int column) {
        commandCompleted(null);
        if (prompt != null) prompt.release();
        if (inputStart != null) inputStart.release();
        prompt = at(row, column);
    }

    void commandInputStarted(int row, int column) {
        if (running != null) return;
        if (inputStart != null) inputStart.release();
        inputStart = at(row, column);
    }

    void commandStarted(int row, int column) {
        if (running != null) return; // Duplicate C frames must not create phantom commands.
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
        if (running == null) return;
        running.complete(code);
        running = null;
        changed();
    }

    void anchorRemoved(HistoryAnchor anchor) {
        CommandMarker removed = markers.remove(anchor);
        if (removed == running) running = null;
        if (removed != null) removed.releaseInputAnchors();
        if (anchor == prompt) prompt = null;
        if (anchor == inputStart) inputStart = null;
        changed();
    }

    void discardInvalidAnchors() {
        for (CommandMarker marker : getMarkers())
            if (marker.anchor.getRow() == null) marker.anchor.release();
        if (prompt != null && prompt.getRow() == null) prompt.release();
        if (inputStart != null && inputStart.getRow() == null) inputStart.release();
    }

    // Legacy A/C producers include PS1 in the range. Standard B frames provide an exact start.
    private static final Pattern PROMPT_END = Pattern.compile("[$#❯➜>]\\s+");
    private static final Pattern ASSIGNMENT = Pattern.compile("[a-zA-Z_][a-zA-Z_0-9]*=.*", Pattern.DOTALL);

    String commandName(CommandMarker marker) {
        if (!markers.containsKey(marker.anchor) || marker.inputStart == null || marker.inputEnd == null) return null;
        Integer start = marker.inputStart.getRow(), end = marker.inputEnd.getRow();
        if (start == null || end == null) return null;
        int startColumn = marker.inputStart.getColumn(), endColumn = marker.inputEnd.getColumn();
        if (endColumn == 0) { end--; endColumn = buffer.mColumns; }
        if (end < start || end == start && endColumn <= startColumn) return null;
        // A label never scans the entire scrollback or retains arguments. Reflow joins wrapped cells.
        boolean truncated = end > start + 63;
        if (truncated) { end = start + 63; endColumn = buffer.mColumns; }
        String input = buffer.getSelectedText(startColumn, start, endColumn - 1, end);
        if (input.length() > 4096) { input = input.substring(0, 4096); truncated = true; }
        if (marker.inputIncludesPrompt) {
            Matcher promptEnd = PROMPT_END.matcher(input);
            if (!promptEnd.find()) return null;
            input = input.substring(promptEnd.end());
        }
        int position = 0;
        while (position < input.length()) {
            while (position < input.length() && Character.isWhitespace(input.charAt(position))) position++;
            StringBuilder word = new StringBuilder();
            char quote = 0;
            while (position < input.length()) {
                char c = input.charAt(position++);
                if (c == '\\' && quote != '\'' && position < input.length()) { word.append(input.charAt(position++)); continue; }
                if (quote != 0) { if (c == quote) quote = 0; else word.append(c); }
                else if (c == '\'' || c == '"') quote = c;
                else if (Character.isWhitespace(c)) break;
                else if (";&|<>()".indexOf(c) >= 0) { if (word.length() == 0) return null; else break; }
                else word.append(c);
            }
            if (word.length() == 0 || quote != 0 || truncated && position == input.length()) return null;
            String name = word.toString();
            if (ASSIGNMENT.matcher(name).matches()) continue;
            int slash = name.lastIndexOf('/');
            if (slash >= 0) name = name.substring(slash + 1);
            if (name.isEmpty()) return null;
            int length = name.codePointCount(0, name.length());
            return length > 80 ? name.substring(0, name.offsetByCodePoints(0, 80)) + "…" : name;
        }
        return null;
    }
}
