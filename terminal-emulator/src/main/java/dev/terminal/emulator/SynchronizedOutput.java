package dev.terminal.emulator;

import java.util.function.LongSupplier;

/** DEC mode 2026 presentation barrier. Parsing and PTY input never wait on this state. */
public final class SynchronizedOutput implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    public static final long MAX_HOLD_MILLIS = 1000;
    private transient LongSupplier clock;
    private long deadline;

    public SynchronizedOutput() { this(() -> System.nanoTime() / 1_000_000); }
    SynchronizedOutput(LongSupplier clock) { this.clock = clock; }

    private void readObject(java.io.ObjectInputStream in) throws java.io.IOException, ClassNotFoundException {
        in.defaultReadObject();
        clock = () -> System.nanoTime() / 1_000_000;
    }

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
