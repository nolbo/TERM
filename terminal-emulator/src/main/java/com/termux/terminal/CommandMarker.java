package com.termux.terminal;

/** Shell boundary metadata only. Command text is read from retained cells, never saved. */
public final class CommandMarker implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    public final HistoryAnchor anchor;
    /** Unix epoch milliseconds. */
    public final long startedAt;
    final HistoryAnchor inputStart;
    HistoryAnchor inputEnd;
    HistoryAnchor outputEnd;
    final boolean inputIncludesPrompt;
    private boolean running = true;
    private Integer exitCode;

    CommandMarker(HistoryAnchor anchor, long startedAt, HistoryAnchor inputStart, HistoryAnchor inputEnd, boolean inputIncludesPrompt) {
        this.anchor = anchor;
        this.startedAt = startedAt;
        this.inputStart = inputStart;
        this.inputEnd = inputEnd;
        this.inputIncludesPrompt = inputIncludesPrompt;
    }

    public boolean isRunning() { return running; }
    public Integer getExitCode() { return exitCode; }
    public boolean isFailed() { return !running && exitCode != null && exitCode != 0; }
    /** A bounded display preview, or null when the input is no longer retained. */
    public String getCommandText() { return anchor.history.commandText(this); }
    public boolean matchesCommand(String query) { return anchor.history.matches(this, query, false); }
    public boolean matchesOutput(String query) { return anchor.history.matches(this, query, true); }

    void releaseInputAnchors() {
        if (inputStart != null && inputStart != anchor) inputStart.release();
        if (inputEnd != null && inputEnd != anchor && inputEnd != inputStart) inputEnd.release();
        if (outputEnd != null && outputEnd != anchor && outputEnd != inputStart && outputEnd != inputEnd) outputEnd.release();
    }

    void complete(Integer code) { running = false; exitCode = code; }
}
