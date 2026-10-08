package com.termux.terminal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/** Buffer-owned shell integration state; travels with the authoritative terminal checkpoint. */
public final class TerminalHistory implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    final TerminalBuffer buffer;
    private final LinkedHashMap<HistoryAnchor, CommandMarker> markers = new LinkedHashMap<>();
    private HistoryAnchor prompt;
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
        prompt = at(row, column);
    }

    void commandStarted(int row, int column) {
        if (running != null) return; // Duplicate C frames must not create phantom commands.
        HistoryAnchor anchor = prompt;
        prompt = null;
        if (anchor == null || anchor.getRow() == null) anchor = at(row, column);
        running = new CommandMarker(anchor, System.currentTimeMillis());
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
        if (anchor == prompt) prompt = null;
        changed();
    }

    void discardInvalidAnchors() {
        for (CommandMarker marker : getMarkers())
            if (marker.anchor.getRow() == null) marker.anchor.release();
        if (prompt != null && prompt.getRow() == null) prompt.release();
    }
}
