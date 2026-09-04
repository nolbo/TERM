package dev.terminal.runtime.ipc;

interface IRuntimeStateListener {
    void onRuntimeStateChanged(String state, String detail);
    void onSessionsChanged(String sessionsJson);
    void onTerminalOutput(String sessionId, in byte[] bytes);
    void onSessionExited(String sessionId, int exitCode);
    void onOperationError(String operation, String message);
}
