package dev.terminal.emulator;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import android.os.ParcelFileDescriptor;

/**
 * A small, process-only facade over the local PTY implementation.
 *
 * This deliberately contains no terminal rendering state. It lets a service own a
 * real PTY while a separately-lived UI renders the byte stream.
 */
public final class PtyProcess implements AutoCloseable {
    private final int fileDescriptor;
    private final int processId;
    private final ParcelFileDescriptor parcelFileDescriptor;
    private final InputStream input;
    private final OutputStream output;
    private volatile boolean closed;
    private volatile boolean exited;

    private PtyProcess(int fileDescriptor, int processId) {
        this.fileDescriptor = fileDescriptor;
        this.processId = processId;
        parcelFileDescriptor = ParcelFileDescriptor.adoptFd(fileDescriptor);
        FileDescriptor descriptor = parcelFileDescriptor.getFileDescriptor();
        input = new FileInputStream(descriptor);
        output = new FileOutputStream(descriptor);
    }

    public static PtyProcess start(String command, String cwd, String[] args, String[] environment,
                                   int rows, int columns, int cellWidth, int cellHeight) {
        int[] processId = new int[1];
        int descriptor = JNI.createSubprocess(command, cwd, args, environment, processId,
                rows, columns, cellWidth, cellHeight);
        return new PtyProcess(descriptor, processId[0]);
    }

    public InputStream getInputStream() { return input; }
    public OutputStream getOutputStream() { return output; }
    public int getProcessId() { return processId; }

    public void resize(int rows, int columns, int cellWidth, int cellHeight) {
        if (!closed) JNI.setPtyWindowSize(fileDescriptor, rows, columns, cellWidth, cellHeight);
    }

    public int waitFor() {
        int result = JNI.waitFor(processId);
        exited = true;
        return result;
    }

    public void terminate(int signal) {
        // PIDs can be reused after waitpid(). Never signal a different process
        // when the user closes a tab whose shell has already exited.
        if (!closed && !exited) android.os.Process.sendSignal(processId, signal);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            parcelFileDescriptor.close();
        } catch (java.io.IOException ignored) {
            // The descriptor may already have been closed by the reader at process exit.
        }
    }
}
