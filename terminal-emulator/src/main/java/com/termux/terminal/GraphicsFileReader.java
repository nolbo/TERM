package com.termux.terminal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Host-selected directory capability. JNI never follows a symlink or returns an open descriptor. */
public final class GraphicsFileReader {
    static { System.loadLibrary("termux"); }
    private GraphicsFileReader() {}

    public static byte[] read(String root, String relativePath, long offset, long size, boolean unlink) throws IOException {
        if (root.indexOf('\0') >= 0 || relativePath.indexOf('\0') >= 0 || offset < 0 || size < 0)
            throw new IOException("EINVAL: media path");
        return readBelow(root.getBytes(StandardCharsets.UTF_8), relativePath.getBytes(StandardCharsets.UTF_8),
            offset, size, unlink, TerminalGraphics.MAX_TRANSFER_BYTES);
    }

    private static native byte[] readBelow(byte[] root, byte[] relativePath, long offset, long size,
                                           boolean unlink, int maximum) throws IOException;
}
