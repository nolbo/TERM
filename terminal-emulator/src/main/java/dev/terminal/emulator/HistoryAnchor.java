package dev.terminal.emulator;

/** A cell attached to retained history, independent of screen row numbers and ring-buffer reuse. */
public final class HistoryAnchor implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    final TerminalHistory history;
    TerminalRow line;
    int column;

    HistoryAnchor(TerminalHistory history, TerminalRow line, int column) {
        this.history = history;
        moveTo(line, column);
    }

    /** Current screen-relative row, or null after the underlying history has been discarded. */
    public Integer getRow() { return history.buffer.resolveHistoryAnchor(this); }
    public int getColumn() { return column; }

    void moveTo(TerminalRow destination, int column) {
        line = destination;
        this.column = column;
        if (destination.mHistoryAnchors == null) destination.mHistoryAnchors = new java.util.ArrayList<>();
        destination.mHistoryAnchors.add(this);
    }

    void release() {
        if (line == null) return;
        if (line.mHistoryAnchors != null) {
            line.mHistoryAnchors.remove(this);
            if (line.mHistoryAnchors.isEmpty()) line.mHistoryAnchors = null;
        }
        line = null;
        history.anchorRemoved(this);
    }
}
