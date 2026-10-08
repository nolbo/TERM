package com.termux.terminal;

/** Shell boundary metadata only. Command names are read from retained input cells, never saved. */
public final class CommandMarker implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    public final HistoryAnchor anchor;
    /** Unix epoch milliseconds. */
    public final long startedAt;
    final HistoryAnchor inputStart, inputEnd;
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
    /** Executable name only, or null when its input cells/boundaries are unavailable. */
    public String getCommandName() { return anchor.history.commandName(this); }

    void releaseInputAnchors() {
        if (inputStart != null && inputStart != anchor) inputStart.release();
        if (inputEnd != null && inputEnd != anchor && inputEnd != inputStart) inputEnd.release();
    }

    void complete(Integer code) { running = false; exitCode = code; }
}
