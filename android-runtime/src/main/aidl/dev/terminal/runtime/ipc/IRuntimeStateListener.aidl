package dev.terminal.runtime.ipc;

interface IRuntimeStateListener {
    void onRuntimeStateChanged(String state, String detail);
    void onSessionsChanged(long revision, String sessionsJson);
    void onTerminalOutput(String sessionId, in byte[] bytes, boolean replay);
    void onSessionExited(String sessionId, int exitCode);
    void onSessionTerminationFailed(String sessionId, String message);
    void onRootlessProgress(String runtimeType, String phase, String detail);
    void onOperationError(String operation, String message);
    void onSessionFocusRequested(String sessionId);
}
