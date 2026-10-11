package dev.terminal.emulator;

import java.util.Arrays;

/** Two passes bound allocation and expansion work before rasterizing a SIXEL DCS. */
final class SixelDecoder {
    private final String data;
    private int pos, width, height, x, y, color;
    private long work;
    private final int[] palette = new int[256];
    private int[] pixels;
    private int stride;

    private SixelDecoder(String data) { this.data = data; }

    static TerminalImage decode(String data, int background) {
        SixelDecoder d = new SixelDecoder(data);
        d.pass();
        TerminalImage.checkSize(d.width, d.height);
        int w = d.width, h = d.height;
        d.pixels = new int[w * h];
        Arrays.fill(d.pixels, background);
        d.stride = w;
        d.pass();
        return new TerminalImage(w, h, d.pixels);
    }

    private void pass() {
        pos = x = y = color = width = height = 0;
        work = 0;
        Arrays.fill(palette, 0xff000000);
        int[] defaults = {0xff000000, 0xff3333cc, 0xffcc2121, 0xff33cc33, 0xffcc33cc, 0xff33cccc,
            0xffcccc33, 0xff878787, 0xff424242, 0xff5454ff, 0xffff5454, 0xff54ff54,
            0xffff54ff, 0xff54ffff, 0xffffff54, 0xffffffff};
        System.arraycopy(defaults, 0, palette, 0, defaults.length);
        while (pos < data.length()) {
            char c = data.charAt(pos++);
            if (c >= '?' && c <= '~') paint(c - '?', 1);
            else if (c == '!') {
                int count = number(1);
                if (pos >= data.length()) throw invalid();
                int bits = data.charAt(pos++) - '?';
                if (bits < 0 || bits > 63) throw invalid();
                paint(bits, count);
            } else if (c == '$') x = 0;
            else if (c == '-') { x = 0; y += 6; }
            else if (c == '#') {
                color = number(0);
                if (color >= palette.length) throw invalid();
                if (semicolon()) {
                    int mode = number(0);
                    if (!semicolon()) throw invalid();
                    int a = number(0);
                    if (!semicolon()) throw invalid();
                    int b = number(0);
                    if (!semicolon()) throw invalid();
                    int e = number(0);
                    if (mode == 2 && a <= 100 && b <= 100 && e <= 100)
                        palette[color] = 0xff000000 | ((a * 255 + 50) / 100 << 16) |
                            ((b * 255 + 50) / 100 << 8) | (e * 255 + 50) / 100;
                    else if (mode == 1 && a <= 360 && b <= 100 && e <= 100)
                        palette[color] = hls(a, b, e);
                    else throw invalid();
                }
            } else if (c == '"') {
                number(1);
                if (semicolon()) number(1);
                if (semicolon()) width = Math.max(width, number(0));
                if (semicolon()) height = Math.max(height, number(0));
                checkExtent();
            } else if (c > ' ') throw invalid();
        }
    }

    private void paint(int bits, int count) {
        if (count < 1 || count > TerminalImage.MAX_DIMENSION) throw invalid();
        width = Math.max(width, x + count);
        height = Math.max(height, y + 6);
        checkExtent();
        work += (long) count * 6;
        if (work > TerminalImage.MAX_PIXELS * 16L) throw new IllegalArgumentException("E2BIG: sixel work");
        if (pixels != null) {
            for (int bit = 0; bit < 6; bit++) if ((bits & (1 << bit)) != 0)
                Arrays.fill(pixels, (y + bit) * stride + x, (y + bit) * stride + x + count, palette[color]);
        }
        x += count;
    }

    private void checkExtent() {
        if (width > TerminalImage.MAX_DIMENSION || height > TerminalImage.MAX_DIMENSION ||
            (long) width * height > TerminalImage.MAX_PIXELS) throw new IllegalArgumentException("E2BIG: sixel dimensions");
    }

    private int number(int fallback) {
        int start = pos, n = 0;
        while (pos < data.length() && data.charAt(pos) >= '0' && data.charAt(pos) <= '9') {
            n = n * 10 + data.charAt(pos++) - '0';
            if (n > 65535) throw invalid();
        }
        return pos == start ? fallback : n;
    }

    private boolean semicolon() {
        if (pos < data.length() && data.charAt(pos) == ';') { pos++; return true; }
        return false;
    }

    // DEC hue zero is blue (standard HSL hue zero is red).
    private static int hls(int hue, int lightness, int saturation) {
        double h = ((hue + 240) % 360) / 60.0, l = lightness / 100.0, s = saturation / 100.0;
        double c = (1 - Math.abs(2 * l - 1)) * s, v = c * (1 - Math.abs(h % 2 - 1)), m = l - c / 2;
        double r = 0, g = 0, b = 0;
        if (h < 1) { r = c; g = v; } else if (h < 2) { r = v; g = c; }
        else if (h < 3) { g = c; b = v; } else if (h < 4) { g = v; b = c; }
        else if (h < 5) { r = v; b = c; } else { r = c; b = v; }
        return 0xff000000 | ((int) Math.round((r + m) * 255) << 16) |
            ((int) Math.round((g + m) * 255) << 8) | (int) Math.round((b + m) * 255);
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("EINVAL: sixel"); }
}
