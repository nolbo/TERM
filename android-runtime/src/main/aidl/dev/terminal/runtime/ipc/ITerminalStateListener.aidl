package dev.terminal.runtime.ipc;
import android.os.ParcelFileDescriptor;

/** Host-private stream. Checkpoint, resize, output and exit are delivered in one order. */
interface ITerminalStateListener {
    void onCheckpoint(in ParcelFileDescriptor state);
    void onOutput(in byte[] bytes);
    void onResize(int columns, int rows, int cellWidth, int cellHeight);
    void onExited(int code);
    oneway void onError(String message);
}
