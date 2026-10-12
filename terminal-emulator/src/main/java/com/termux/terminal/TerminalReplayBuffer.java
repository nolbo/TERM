package com.termux.terminal;

import java.util.BitSet;

/**
 * Bounded output replay. Retention starts at a control-sequence/UTF-8 boundary,
 * and never in the middle of a kitty chunked transfer. This is a transcript,
 * not an unbounded image archive or a promise of restoration after PTY death.
 */
public final class TerminalReplayBuffer {
    private final int limit;
    private byte[] bytes;
    private BitSet boundaries = new BitSet();
    private int head, size, state, utf8Remaining;
    private int stringKind;
    private boolean kittyChunks, headerDone;
    private final StringBuilder header = new StringBuilder();
    private static final int GROUND = 0, ESCAPE = 1, CSI = 2, STRING = 3, STRING_ESCAPE = 4;

    public TerminalReplayBuffer(int limit) {
        if (limit < 16) throw new IllegalArgumentException("replay limit");
        this.limit = limit;
        bytes = new byte[Math.min(8192, limit)];
    }

    public synchronized void append(byte[] data, int count) {
        if (count < 0 || count > data.length) throw new IllegalArgumentException("count");
        for (int i = 0; i < count; i++) {
            if (size == bytes.length && bytes.length < limit) grow();
            int index = (head + size) % bytes.length;
            bytes[index] = data[i];
            boundaries.set(index, state == GROUND && utf8Remaining == 0 && !kittyChunks);
            if (size == bytes.length) head = (head + 1) % bytes.length;
            else size++;
            consume(data[i] & 255);
        }
    }

    public synchronized byte[] snapshot() {
        int start = 0;
        while (start < size && !boundaries.get((head + start) % bytes.length)) start++;
        if (start == size) {
            // The entire retained range is an incomplete oversized string. Seed a
            // harmless APC consumer so its live tail is also drained through ST.
            if (state == STRING || state == STRING_ESCAPE) {
                // OSC also accepts BEL. Preserve that terminator and force an
                // unknown command so the retained tail cannot become a new OSC action.
                if (stringKind == ']') return state == STRING_ESCAPE
                    ? new byte[]{27, ']', '9', '9', '9', ';', 27} : new byte[]{27, ']', '9', '9', '9', ';'};
                return state == STRING_ESCAPE ? new byte[]{27, '_', 27} : new byte[]{27, '_'};
            }
            if (state == ESCAPE) return new byte[]{27};
            if (state == CSI) return new byte[]{27, '['};
            return new byte[0];
        }
        byte[] result = new byte[size - start];
        for (int i = 0; i < result.length; i++) result[i] = bytes[(head + start + i) % bytes.length];
        return result;
    }

    private void grow() {
        byte[] expanded = new byte[Math.min(limit, bytes.length * 2)];
        BitSet flags = new BitSet();
        for (int i = 0; i < size; i++) {
            int old = (head + i) % bytes.length;
            expanded[i] = bytes[old];
            flags.set(i, boundaries.get(old));
        }
        bytes = expanded; boundaries = flags; head = 0;
    }

    private void consume(int b) {
        if (utf8Remaining > 0 && (b & 0xc0) == 0x80 && state == GROUND) { utf8Remaining--; return; }
        utf8Remaining = 0;
        if (b == 24 || b == 26) { state = GROUND; kittyChunks = false; return; }
        if (state == STRING || state == STRING_ESCAPE) {
            if ((state == STRING_ESCAPE && b == '\\') || (stringKind == ']' && b == 7)) {
                if (stringKind == '_' && header.length() > 0 && header.charAt(0) == 'G')
                    kittyChunks = ("," + header.substring(1) + ",").contains(",m=1,");
                state = GROUND;
            } else {
                state = b == 27 ? STRING_ESCAPE : STRING;
                if (!headerDone) {
                    if (b == ';' || header.length() >= 1024) headerDone = true;
                    else header.append((char) b);
                }
            }
        } else if (b == 27) state = ESCAPE;
        else if (state == ESCAPE) {
            if (b == '[') state = CSI;
            else if (b == 'P' || b == '_' || b == ']' || b == '^') {
                stringKind = b; state = STRING; header.setLength(0); headerDone = false;
            } else if (b >= 0x30 && b <= 0x7e) state = GROUND;
        } else if (state == CSI) {
            if (b >= 0x40 && b <= 0x7e) state = GROUND;
        } else if ((b & 0xe0) == 0xc0) utf8Remaining = 1;
        else if ((b & 0xf0) == 0xe0) utf8Remaining = 2;
        else if ((b & 0xf8) == 0xf0) utf8Remaining = 3;
    }
}
