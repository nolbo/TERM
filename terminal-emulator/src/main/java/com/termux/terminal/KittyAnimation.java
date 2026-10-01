package com.termux.terminal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Fully composed immutable frames. The caller accounts for every frame in its pixel quota. */
final class KittyAnimation implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    static final int MAX_FRAMES = 128;
    final List<TerminalImage> frames = new ArrayList<>();
    final List<Integer> gaps = new ArrayList<>();
    int current, state = 1;
    long loops = 1, completedLoops, frameStarted;

    KittyAnimation(TerminalImage root) { frames.add(root); gaps.add(0); }
    TerminalImage image() { return frames.get(current); }
    int pixels() { return frames.size() * frames.get(0).pixels.length; }
    int frame(long number) {
        if (number <= 0 || number > frames.size()) throw new IllegalArgumentException("ENOENT: frame");
        return (int) number - 1;
    }

    void control(long selected, long affected, int gap, int newState, long newLoops, long now) {
        int next = selected == 0 ? current : frame(selected);
        int changed = affected == 0 ? -1 : frame(affected);
        if (newState < 0 || newState > 3) throw new IllegalArgumentException("EINVAL: animation state");
        if (gap != 0 && changed >= 0) gaps.set(changed, Math.max(0, gap));
        if (newLoops != 0) loops = newLoops;
        if (newState != 0) {
            if (newState == 1) completedLoops = 0;
            if (state == 1 || newState == 1) frameStarted = now;
            state = newState;
        }
        if (selected != 0) { current = next; frameStarted = now; }
    }

    void addOrEdit(TerminalImage patch, int x, int y, long edit, long base, int background,
                   boolean replace, int gap, long now) {
        TerminalImage root = frames.get(0);
        bounds(x, y, patch.width, patch.height, root);
        int index = edit == 0 ? frames.size() : frame(edit);
        if (index == frames.size() && frames.size() >= MAX_FRAMES)
            throw new IllegalArgumentException("ENOSPC: frames");
        int[] pixels;
        if (edit != 0) pixels = frames.get(index).pixels.clone();
        else if (base != 0) pixels = frames.get(frame(base)).pixels.clone();
        else { pixels = new int[root.pixels.length]; Arrays.fill(pixels, background); }
        composite(patch, pixels, root.width, 0, 0, x, y, patch.width, patch.height, replace);
        TerminalImage result = new TerminalImage(root.width, root.height, pixels);
        if (index == frames.size()) {
            frames.add(result); gaps.add(gap == 0 ? 40 : Math.max(0, gap));
        } else {
            frames.set(index, result);
            if (gap != 0) gaps.set(index, Math.max(0, gap));
        }
    }

    void compose(long source, long target, int sx, int sy, int dx, int dy, int width, int height, boolean replace) {
        int from = frame(source), to = frame(target);
        TerminalImage src = frames.get(from), dst = frames.get(to);
        bounds(sx, sy, width, height, src); bounds(dx, dy, width, height, dst);
        if (from == to && sx < dx + width && dx < sx + width && sy < dy + height && dy < sy + height)
            throw new IllegalArgumentException("EINVAL: overlapping rectangles");
        int[] pixels = dst.pixels.clone();
        composite(src, pixels, dst.width, sx, sy, dx, dy, width, height, replace);
        frames.set(to, new TerminalImage(dst.width, dst.height, pixels));
    }

    void delete(long number, long now) {
        int index = frame(number);
        if (frames.size() == 1) return;
        frames.remove(index); gaps.remove(index);
        if (current > index) current--;
        else if (current >= frames.size()) current = frames.size() - 1;
        frameStarted = now;
    }

    /** Returns the delay until the next visible change, or -1 when no timer is needed. */
    long tick(long now) {
        if (state == 1 || frames.size() < 2) return -1;
        long duration = 0;
        for (int gap : gaps) duration += gap;
        if (duration == 0) return -1; // An all-gapless animation must not spin the UI thread.
        if (frameStarted > now) frameStarted = now;
        // Skip complete cycles arithmetically after a long detach, instead of replaying every frame.
        if (state == 3 && now - frameStarted >= duration) {
            long cycles = (now - frameStarted) / duration;
            if (loops > 1) cycles = Math.min(cycles, Math.max(0, loops - 2 - completedLoops));
            completedLoops += cycles;
            frameStarted += cycles * duration;
        }
        for (int n = 0; n <= frames.size() * 2; n++) {
            int gap = gaps.get(current);
            long elapsed = now - frameStarted;
            if (elapsed < gap) return gap - elapsed;
            if (current + 1 == frames.size()) {
                if (state == 2) return -1;
                if (loops > 1 && completedLoops + 1 >= loops - 1) { state = 1; return -1; }
                completedLoops++; current = 0;
            } else current++;
            frameStarted += gap;
        }
        // A stale mid-cycle start is normalized on the next frame, with bounded work per draw.
        return 1;
    }

    private static void bounds(int x, int y, int width, int height, TerminalImage image) {
        if (x < 0 || y < 0 || width <= 0 || height <= 0 ||
            (long) x + width > image.width || (long) y + height > image.height)
            throw new IllegalArgumentException("EINVAL: rectangle bounds");
    }

    private static void composite(TerminalImage src, int[] dst, int stride,
                                  int sx, int sy, int dx, int dy, int width, int height, boolean replace) {
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int source = src.pixels[(sy + y) * src.width + sx + x];
            int at = (dy + y) * stride + dx + x;
            dst[at] = replace ? source : over(source, dst[at]);
        }
    }

    /** Source-over in straight-alpha sRGB, using long intermediates to avoid overflow. */
    static int over(int src, int dst) {
        int sa = src >>> 24, da = dst >>> 24;
        if (sa == 255 || da == 0) return src;
        if (sa == 0) return dst;
        int alphaNumerator = sa * 255 + da * (255 - sa);
        int result = ((alphaNumerator + 127) / 255) << 24;
        for (int shift = 0; shift <= 16; shift += 8) {
            long channel = ((src >>> shift) & 255) * (long) sa * 255 +
                ((dst >>> shift) & 255) * (long) da * (255 - sa);
            result |= (int) ((channel + alphaNumerator / 2) / alphaNumerator) << shift;
        }
        return result;
    }
}
