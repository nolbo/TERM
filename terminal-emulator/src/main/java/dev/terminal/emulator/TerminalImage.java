package dev.terminal.emulator;

/** Decoded, bounded ARGB pixels. Never contains guest paths or host resources. */
public final class TerminalImage implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    public static final int MAX_DIMENSION = 2048;
    public static final int MAX_PIXELS = 1024 * 1024;
    public final int width, height;
    public final int[] pixels;

    TerminalImage(int width, int height, int[] pixels) {
        checkSize(width, height);
        if (pixels.length != width * height) throw new IllegalArgumentException("EINVAL: pixel count");
        this.width = width;
        this.height = height;
        this.pixels = pixels;
    }

    static void checkSize(int width, int height) {
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION ||
            (long) width * height > MAX_PIXELS) throw new IllegalArgumentException("E2BIG: image dimensions");
    }
}
