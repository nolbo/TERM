package com.termux.terminal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Host-only directory capabilities for streaming imports. Every open rejects symbolic links. */
public final class ScopedFileAccess {
    static { System.loadLibrary("termux"); }
    private ScopedFileAccess() {}
    public static int root(String path) throws IOException { return openRoot(path.getBytes(StandardCharsets.UTF_8)); }
    public static int child(int directory, String name, int kind) throws IOException {
        return openChild(directory, name.getBytes(StandardCharsets.UTF_8), kind);
    }
    public static void rename(int source, String oldName, int destination, String newName) throws IOException {
        renameChild(source, oldName.getBytes(StandardCharsets.UTF_8), destination, newName.getBytes(StandardCharsets.UTF_8));
    }
    public static void unlink(int directory, String name, boolean isDirectory) throws IOException {
        unlinkChild(directory, name.getBytes(StandardCharsets.UTF_8), isDirectory);
    }
    private static native int openRoot(byte[] path) throws IOException;
    /** kind 0: read regular file, 1: open directory, 2: create new file, 3: mkdir and open directory. */
    private static native int openChild(int directory, byte[] name, int kind) throws IOException;
    private static native void renameChild(int source, byte[] oldName, int destination, byte[] newName) throws IOException;
    private static native void unlinkChild(int directory, byte[] name, boolean isDirectory) throws IOException;
}
