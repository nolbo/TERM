package com.termux.terminal;

/**
 * Process and transport backend used by {@link TerminalSession}.
 *
 * <p>The terminal emulator deliberately knows nothing about where a shell is running. The default
 * backend is the local Android pty owned by {@link TerminalSession}; implementations of this
 * interface can instead attach the same emulator to a persistent shell in a VM or on another
 * transport.</p>
 */
public interface TerminalSessionBackend {

    interface Callback {
        /**
         * Deliver raw terminal output from a worker thread. The byte array is only valid for the
         * duration of the call.
         */
        void onOutput(byte[] data, int offset, int count);

        /** Restore historical output without sending terminal replies or repeating side effects. */
        default void onReplayOutput(byte[] data, int offset, int count) {
            throw new UnsupportedOperationException("Transcript replay is not supported by this receiver");
        }

        /** Report that the remote process, rather than just its transport, has exited. */
        void onExit(int exitCode);
    }

    /** Start or attach to the backend session. This method must return quickly. */
    void start(int columns, int rows, int cellWidthPixels, int cellHeightPixels, Callback callback);

    /** Update the remote pty dimensions. */
    void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels);

    /** Write raw terminal input. */
    void write(byte[] data, int offset, int count);

    /** Terminate the remote shell. A transport disconnect must not be treated as termination. */
    void finish();

    /** Release local transport resources without implicitly terminating a detached remote shell. */
    void close();

    boolean isRunning();

    /** A display-only process identifier, or {@code 0} if the backend does not expose one. */
    int getPid();

    /** The remote working directory when known, otherwise {@code null}. */
    String getCwd();
}
