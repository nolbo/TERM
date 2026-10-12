package com.termux.terminal;

import java.util.function.LongSupplier;

/** DEC mode 2026 presentation barrier. Parsing and PTY input never wait on this state. */
public final class SynchronizedOutput {
    public static final long MAX_HOLD_MILLIS = 1000;
    private final LongSupplier clock;
    private long deadline;

    public SynchronizedOutput() { this(() -> System.nanoTime() / 1_000_000); }
    SynchronizedOutput(LongSupplier clock) { this.clock = clock; }

    public void begin() {
        // Repeated BSU must not extend a stalled frame's deadline indefinitely.
        if (deadline == 0) deadline = clock.getAsLong() + MAX_HOLD_MILLIS;
    }
    public void end() { deadline = 0; }
    public long remainingMillis() {
        if (deadline == 0) return 0;
        long remaining = deadline - clock.getAsLong();
        if (remaining <= 0) { deadline = 0; return 0; }
        return remaining;
    }
}
