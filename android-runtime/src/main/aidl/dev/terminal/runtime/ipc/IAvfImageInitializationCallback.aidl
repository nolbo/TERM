package dev.terminal.runtime.ipc;

oneway interface IAvfImageInitializationCallback {
    void onProgress(String phase);
    void onComplete(String error);
}
