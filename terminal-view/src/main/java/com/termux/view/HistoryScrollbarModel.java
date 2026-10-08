package com.termux.view;

import com.termux.terminal.CommandMarker;
import java.util.ArrayList;
import java.util.List;

/** History coordinates and bounded density buckets, shared by drawing, hit testing and tests. */
public final class HistoryScrollbarModel {
    private HistoryScrollbarModel() {}

    public static float fraction(int row, int historyRows) {
        return historyRows == 0 ? 1 : Math.max(0, Math.min(1, 1 + (float) row / historyRows));
    }

    public static int rowAt(float fraction, int historyRows) {
        return Math.round((Math.max(0, Math.min(1, fraction)) - 1) * historyRows);
    }

    public static float thumbHeight(float trackHeight, int screenRows, int historyRows, float minimum) {
        return Math.min(trackHeight, Math.max(minimum, trackHeight * screenRows / (screenRows + (float) historyRows)));
    }

    public static final class Bucket {
        public final int index;
        public int count;
        public boolean running, failed;
        Bucket(int index) { this.index = index; }
    }

    /** At most one glyph per bucket, even for commands sharing the same history cell. */
    public static List<Bucket> aggregate(List<CommandMarker> markers, int historyRows, int bucketCount) {
        bucketCount = Math.max(1, bucketCount);
        Bucket[] bins = new Bucket[bucketCount];
        for (CommandMarker marker : markers) {
            Integer row = marker.anchor.getRow();
            if (row == null) continue;
            int index = Math.min(bucketCount - 1, Math.round(fraction(row, historyRows) * (bucketCount - 1)));
            Bucket bucket = bins[index];
            if (bucket == null) bins[index] = bucket = new Bucket(index);
            bucket.count++;
            bucket.running |= marker.isRunning();
            bucket.failed |= marker.isFailed();
        }
        ArrayList<Bucket> result = new ArrayList<>();
        for (Bucket bin : bins) if (bin != null) result.add(bin);
        return result;
    }

    public static CommandMarker nearest(List<CommandMarker> markers, int row, int maximumDistance) {
        CommandMarker best = null;
        long distance = (long) maximumDistance + 1;
        for (CommandMarker marker : markers) {
            Integer position = marker.anchor.getRow();
            if (position == null) continue;
            long delta = Math.abs((long) position - row);
            if (delta < distance) { best = marker; distance = delta; }
        }
        return best;
    }

    /** Selected identity disambiguates multiple commands at exactly the same row. */
    public static CommandMarker adjacent(List<CommandMarker> markers, int row, boolean next, CommandMarker selected) {
        int selectedIndex = markers.indexOf(selected);
        if (selectedIndex >= 0) {
            Integer selectedRow = selected.anchor.getRow();
            if (selectedRow != null && Math.min(0, selectedRow) == row) row = selectedRow;
            else selectedIndex = -1;
        }
        int bestIndex = -1;
        int bestRow = next ? Integer.MAX_VALUE : Integer.MIN_VALUE;
        for (int i = 0; i < markers.size(); i++) {
            Integer position = markers.get(i).anchor.getRow();
            if (position == null) continue;
            boolean sameRow = position == row && selectedIndex >= 0 && (next ? i > selectedIndex : i < selectedIndex);
            if (!(sameRow || (next ? position > row : position < row))) continue;
            if (bestIndex == -1 || (next ? position < bestRow : position > bestRow)
                || position == bestRow && (next ? i < bestIndex : i > bestIndex)) {
                bestIndex = i;
                bestRow = position;
            }
        }
        return bestIndex < 0 ? null : markers.get(bestIndex);
    }
}
