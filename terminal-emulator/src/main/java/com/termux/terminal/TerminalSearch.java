package com.termux.terminal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** A disposable view of retained cells. Neither queries nor results enter terminal checkpoints. */
public final class TerminalSearch {
    private TerminalSearch() {}

    public enum Scope { COMMANDS, OUTPUT, ALL }

    /** Screen-relative cells; the end column is exclusive. Valid only for the searched revision. */
    public static final class Match {
        public final int startRow, startColumn, endRow, endColumn;

        Match(int startRow, int startColumn, int endRow, int endColumn) {
            this.startRow = startRow; this.startColumn = startColumn;
            this.endRow = endRow; this.endColumn = endColumn;
        }
    }

    private static final class Range {
        final long start, end;
        Range(long start, long end) { this.start = start; this.end = end; }
    }

    /** Literal, case-insensitive search across soft wraps, never across hard line breaks. */
    public static List<Match> find(TerminalHistory history, String query, Scope scope) {
        if (query == null || query.isEmpty()) return Collections.emptyList();
        TerminalBuffer buffer = history.buffer;
        int[] needle = query.codePoints().map(TerminalSearch::fold).toArray();
        int[] failure = new int[needle.length];
        for (int i = 1, j = 0; i < needle.length; i++) {
            while (j > 0 && needle[i] != needle[j]) j = failure[j - 1];
            if (needle[i] == needle[j]) j++;
            failure[i] = j;
        }
        List<Range> commands = new ArrayList<>(), prompts = new ArrayList<>();
        if (scope != Scope.ALL) {
            for (CommandMarker marker : history.getMarkers()) {
                Integer first = marker.inputStart == null ? null : marker.inputStart.getRow();
                Integer last = marker.inputEnd == null ? null : marker.inputEnd.getRow();
                if (first == null || last == null) continue;
                long start = position(buffer, first, marker.inputStart.getColumn());
                long end = position(buffer, last, marker.inputEnd.getColumn());
                if (marker.inputIncludesPrompt) {
                    long input = afterLegacyPrompt(buffer, start, end);
                    prompts.add(new Range(start, input < 0 ? end : input));
                    if (input < 0) continue;
                    start = input;
                } else {
                    Integer prompt = marker.anchor.getRow();
                    if (prompt != null) prompts.add(new Range(
                        position(buffer, prompt, marker.anchor.getColumn()), start));
                }
                commands.add(new Range(start, end));
            }
            commands.sort(Comparator.comparingLong(range -> range.start));
            prompts.sort(Comparator.comparingLong(range -> range.start));
        }
        List<Match> matches = new ArrayList<>();
        int[] rows = new int[needle.length], columns = new int[needle.length];
        int matched = 0, ring = 0, commandIndex = 0, promptIndex = 0;
        for (int row = -buffer.getActiveTranscriptRows(); row < buffer.mScreenRows; row++) {
            TerminalRow line = buffer.mLines[buffer.externalToInternalRow(row)];
            if (line == null) { matched = 0; continue; }
            int used = line.getSpaceUsed();
            if (!line.mLineWrap) while (used > 0 && line.mText[used - 1] == ' ') used--;
            int column = 0, cellColumn = 0, cellEnd = 0;
            for (int offset = 0; offset < used;) {
                int cp = Character.codePointAt(line.mText, offset, used);
                offset += Character.charCount(cp);
                int width = WcWidth.width(cp);
                if (width > 0) { cellColumn = column; column += width; cellEnd = column; }
                long cell = position(buffer, row, cellColumn);
                while (commandIndex < commands.size() && commands.get(commandIndex).end <= cell) commandIndex++;
                while (promptIndex < prompts.size() && prompts.get(promptIndex).end <= cell) promptIndex++;
                boolean command = contains(commands, commandIndex, cell);
                boolean prompt = contains(prompts, promptIndex, cell);
                boolean eligible = scope == Scope.ALL || (scope == Scope.COMMANDS ? command : !command && !prompt);
                if (!eligible) { matched = 0; continue; }
                rows[ring] = row; columns[ring] = cellColumn;
                ring = (ring + 1) % needle.length;
                int folded = fold(cp);
                while (matched > 0 && folded != needle[matched]) matched = failure[matched - 1];
                if (folded == needle[matched]) matched++;
                if (matched == needle.length) {
                    matches.add(new Match(rows[ring], columns[ring], row, cellEnd));
                    matched = failure[matched - 1];
                }
            }
            if (!line.mLineWrap) matched = 0;
        }
        return Collections.unmodifiableList(matches);
    }

    private static boolean contains(List<Range> ranges, int index, long cell) {
        return index < ranges.size() && ranges.get(index).start <= cell && cell < ranges.get(index).end;
    }

    private static long position(TerminalBuffer buffer, int row, int column) {
        return (long) row * buffer.mColumns + column;
    }

    private static int fold(int codePoint) {
        return Character.toLowerCase(Character.toUpperCase(codePoint));
    }

    // Legacy A/C producers include PS1. Use the same bounded delimiter fallback as command previews.
    private static long afterLegacyPrompt(TerminalBuffer buffer, long start, long end) {
        boolean delimiter = false, whitespace = false;
        int inspected = 0;
        int firstRow = (int) Math.floorDiv(start, buffer.mColumns);
        for (int row = firstRow; position(buffer, row, 0) < end && inspected < 4096; row++) {
            TerminalRow line = buffer.mLines[buffer.externalToInternalRow(row)];
            if (line == null) return -1;
            int column = 0;
            for (int offset = 0; offset < line.getSpaceUsed() && inspected++ < 4096;) {
                int cp = Character.codePointAt(line.mText, offset, line.getSpaceUsed());
                offset += Character.charCount(cp);
                long cell = position(buffer, row, column);
                column += Math.max(0, WcWidth.width(cp));
                if (cell < start) continue;
                if (cell >= end) return whitespace ? cell : -1;
                boolean space = cp == ' ' || cp == '\t' || cp == '\r' || cp == '\n';
                if (whitespace && !space) return cell;
                if (delimiter && space) whitespace = true;
                if (!whitespace) delimiter = cp == '$' || cp == '#' || cp == '❯' || cp == '➜' || cp == '>';
            }
            if (delimiter && !line.mLineWrap) whitespace = true;
        }
        return -1;
    }
}
