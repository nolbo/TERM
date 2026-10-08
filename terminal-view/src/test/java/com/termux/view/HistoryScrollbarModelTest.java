package com.termux.view;

import com.termux.terminal.CommandMarker;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class HistoryScrollbarModelTest {
    private final TerminalEmulator emulator = new TerminalEmulator(new TerminalOutput() {
        public void write(byte[] data, int offset, int count) {}
        public void titleChanged(String oldTitle, String newTitle) {}
        public void onCopyTextToClipboard(String text) {}
        public void onPasteTextFromClipboard() {}
        public void onBell() {}
        public void onColorsChanged() {}
    }, 80, 4, 1, 1, 1000, null);

    private void append(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
    }

    @Test public void dragCoversEntireHistoryAndClampsEdges() {
        assertEquals(-10000, HistoryScrollbarModel.rowAt(0, 10000));
        assertEquals(-5000, HistoryScrollbarModel.rowAt(0.5f, 10000));
        assertEquals(0, HistoryScrollbarModel.rowAt(1, 10000));
        assertEquals(-10000, HistoryScrollbarModel.rowAt(-2, 10000));
        assertEquals(0, HistoryScrollbarModel.rowAt(2, 10000));
        assertEquals(1f, HistoryScrollbarModel.fraction(0, 0), 0);
        assertEquals(32f, HistoryScrollbarModel.thumbHeight(500, 40, 10000, 32), 0);
    }

    @Test public void densityBoundsDrawingAndKeepsMixedStatuses() {
        for (int i = 0; i < 300; i++) append("\033]133;C\007x\r\n\033]133;D;" + (i % 3) + "\007");
        append("\033]133;C\007");
        List<HistoryScrollbarModel.Bucket> buckets = HistoryScrollbarModel.aggregate(emulator.getHistory().getMarkers(),
            emulator.getScreen().getActiveTranscriptRows(), 12);
        assertTrue(buckets.size() <= 12);
        assertEquals(301, buckets.stream().mapToInt(bucket -> bucket.count).sum());
        assertTrue(buckets.stream().anyMatch(bucket -> bucket.running));
        assertTrue(buckets.stream().anyMatch(bucket -> bucket.failed));
        assertTrue(buckets.stream().anyMatch(bucket -> bucket.count > 1));
        for (HistoryScrollbarModel.Bucket bucket : buckets) {
            assertNotNull(bucket.marker);
            if (bucket.running) assertTrue(bucket.runningMarker.isRunning());
            if (bucket.failed) assertTrue(bucket.failedMarker.isFailed());
            int index = Math.round(HistoryScrollbarModel.fraction(bucket.marker.anchor.getRow(),
                emulator.getScreen().getActiveTranscriptRows()) * 11);
            assertEquals(bucket.index, index);
        }
    }

    @Test public void aggregateTapTargetIsCommandNearestDisplayedGlyphAndSurvivesTrim() {
        for (int i = 0; i < 1200; i++) append("\033]133;C\007x\r\n\033]133;D;0\007");
        int rows = emulator.getScreen().getActiveTranscriptRows();
        List<CommandMarker> markers = emulator.getHistory().getMarkers();
        List<HistoryScrollbarModel.Bucket> buckets = HistoryScrollbarModel.aggregate(markers, rows, 5);
        for (HistoryScrollbarModel.Bucket bucket : buckets) {
            int center = HistoryScrollbarModel.rowAt(bucket.index / 4f, rows);
            assertSame(HistoryScrollbarModel.nearest(markers, center, rows), bucket.marker);
            assertTrue(bucket.marker.anchor.getRow() >= -rows);
        }
        HistoryScrollbarModel.Bucket single = HistoryScrollbarModel.aggregate(markers, rows, 1).get(0);
        assertSame(HistoryScrollbarModel.nearest(markers, HistoryScrollbarModel.rowAt(0.5f, rows), rows), single.marker);
    }

    @Test public void navigationDisambiguatesSameRowAndWeakSnapHasSmallRadius() {
        append("\033]133;C\007\033]133;D;0\007\033]133;C\007\033]133;D;1\007");
        append("\r\n\033]133;C\007\033]133;D;0\007");
        for (int i = 0; i < 40; i++) append("output\r\n");
        List<CommandMarker> markers = emulator.getHistory().getMarkers();
        int row = markers.get(0).anchor.getRow();
        assertSame(markers.get(1), HistoryScrollbarModel.adjacent(markers, row, true, markers.get(0)));
        assertSame(markers.get(0), HistoryScrollbarModel.adjacent(markers, row, false, markers.get(1)));
        assertSame(markers.get(2), HistoryScrollbarModel.adjacent(markers, row, true, markers.get(1)));
        assertSame(markers.get(0), HistoryScrollbarModel.nearest(markers, row - 2, 2));
        assertNull(HistoryScrollbarModel.nearest(markers, row - 3, 2));
        assertSame(markers.get(2), HistoryScrollbarModel.adjacent(markers, 0, false, null));
    }

    @Test public void nextCommandInLiveScreenDoesNotJumpToSameMarkerRepeatedly() {
        append("\033]133;C\007\033]133;D;0\007\r\n\033]133;C\007\033]133;D;0\007");
        List<CommandMarker> markers = emulator.getHistory().getMarkers();
        assertNull(HistoryScrollbarModel.adjacent(markers, 0, true, markers.get(1)));
    }
}
