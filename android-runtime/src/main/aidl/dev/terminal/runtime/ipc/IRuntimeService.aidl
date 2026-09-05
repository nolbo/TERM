package dev.terminal.runtime.ipc;

import dev.terminal.runtime.ipc.IRuntimeStateListener;

interface IRuntimeService {
    String getState();
    String getDetail();
    String getImageStatus();
    String getRuntimeStatusJson();
    String getSessionsJson();
    String getForwardedPortsJson();
    String getWakePolicy();
    String getCapabilityGrant(String capability);
    void prepare();
    void startRuntime();
    void stopRuntime(int mode);
    void createSession(String runtimeType, String shell, String cwd, int rows, int cols);
    void attachSession(String sessionId);
    void detachSession(String sessionId);
    void writeSessionInput(String sessionId, in byte[] bytes);
    void resizeSession(String sessionId, int rows, int cols);
    void terminateSession(String sessionId);
    void setWakePolicy(String policy);
    void setAvfSettings(int memoryMib, int cpuCount, boolean protectedVm, boolean enableNetwork);
    void setNativeSessionSettings(String terminalType, boolean loginShell);
    void prepareRootless();
    void setCapabilityGrant(String capability, String grant);
    void registerListener(IRuntimeStateListener listener);
    void unregisterListener(IRuntimeStateListener listener);
}
