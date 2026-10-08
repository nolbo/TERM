package com.termux.terminal;

/** Shell boundary metadata only. Command text is deliberately never captured. */
public final class CommandMarker implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    public final HistoryAnchor anchor;
    /** Unix epoch milliseconds. */
    public final long startedAt;
    private boolean running = true;
    private Integer exitCode;

    CommandMarker(HistoryAnchor anchor, long startedAt) {
        this.anchor = anchor;
        this.startedAt = startedAt;
    }

    public boolean isRunning() { return running; }
    public Integer getExitCode() { return exitCode; }
    public boolean isFailed() { return !running && exitCode != null && exitCode != 0; }

    void complete(Integer code) { running = false; exitCode = code; }
}
