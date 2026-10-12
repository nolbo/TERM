package com.termux.terminal;

import java.util.ArrayDeque;

/** One screen's negotiated keyboard mode and platform-independent wire encoding. */
public final class KittyKeyboard {
    public static final int DISAMBIGUATE = 1, EVENTS = 2, ALTERNATES = 4, ALL_KEYS = 8, TEXT = 16;
    public static final int SHIFT = 1, ALT = 2, CTRL = 4, SUPER = 8, HYPER = 16, META = 32, CAPS = 64, NUM = 128;
    public static final int PRESS = 1, REPEAT = 2, RELEASE = 3;
    private static final int MAX_STACK = 32;
    private final ArrayDeque<Integer> stack = new ArrayDeque<>();
    private int flags;

    public int flags() { return flags; }
    public void reset() { flags = 0; stack.clear(); }
    public void set(int value, int mode) {
        value &= 31;
        if (mode == 1) flags = value;
        else if (mode == 2) flags |= value;
        else if (mode == 3) flags &= ~value;
    }
    public void push(int value) {
        if (stack.size() == MAX_STACK - 1) stack.removeFirst();
        stack.addLast(flags); flags = value & 31;
    }
    public void pop(int count) {
        if (count <= 0) return;
        if (count > stack.size()) reset();
        else for (int i = 0; i < count; i++) flags = stack.removeLast();
    }

    /** Functional keys retain their canonical CSI trailer (arrows, F keys, etc.). */
    public static final class Key {
        public final int number;
        public final char trailer;
        public final boolean functional, modifier;
        public Key(int number, char trailer, boolean functional, boolean modifier) {
            this.number = number; this.trailer = trailer; this.functional = functional; this.modifier = modifier;
        }
        public static Key text(int number) { return new Key(number, 'u', false, false); }
    }

    /** null delegates to the existing legacy encoder; an empty string consumes an unreported event. */
    public String encode(Key key, int modifiers, int event, int shifted, int base, String text) {
        if (key == null) return null;
        if (event == RELEASE && (flags & EVENTS) == 0) return "";
        boolean all = (flags & ALL_KEYS) != 0;
        if (key.modifier && !all) return "";
        if (flags == 0) return null;
        modifiers &= 255;
        boolean printable = text != null && !text.isEmpty() && text.codePoints().allMatch(KittyKeyboard::isText);
        // Ordinary text stays UTF-8 until all-key reporting is negotiated.
        if (!all && printable && (modifiers & (ALT | CTRL | SUPER | HYPER | META)) == 0)
            return event == RELEASE ? "" : text;
        if (!all && !key.functional && (modifiers & 63) <= SHIFT && event == RELEASE) return "";
        boolean recoveryKey = key.functional && key.trailer == 'u' && (key.number == 13 || key.number == 9 || key.number == 127);
        if (!all && recoveryKey && (modifiers & 63) == 0) {
            if (event == RELEASE) return "";
            return Character.toString((char) key.number);
        }
        if (!all && (flags & (DISAMBIGUATE | EVENTS)) == 0) return null;
        if (!all && !key.functional && (flags & DISAMBIGUATE) == 0 && event != RELEASE) return null;
        if (!all && !key.functional) modifiers &= 63;
        boolean reportEvent = (flags & EVENTS) != 0 && event != PRESS;
        boolean reportText = all && (flags & TEXT) != 0 && printable && event != RELEASE;
        boolean alternate = (flags & ALTERNATES) != 0 && key.trailer == 'u' && !key.functional;
        boolean shiftedPresent = alternate && (modifiers & SHIFT) != 0 && shifted > 0 && shifted != key.number;
        boolean basePresent = alternate && base > 0 && base != key.number;
        StringBuilder result = new StringBuilder("\033[");
        if (key.number != 1 || key.trailer == 'u' || modifiers != 0 || reportEvent || reportText || shiftedPresent || basePresent)
            result.append(key.number);
        if (shiftedPresent || basePresent) {
            result.append(':');
            if (shiftedPresent) result.append(shifted);
            if (basePresent) result.append(':').append(base);
        }
        if (modifiers != 0 || reportEvent || reportText) {
            result.append(';');
            if (modifiers != 0 || reportEvent) result.append(modifiers + 1);
            if (reportEvent) result.append(':').append(event);
        }
        if (reportText) {
            result.append(';');
            int[] points = text.codePoints().toArray();
            for (int i = 0; i < points.length; i++) {
                if (i != 0) result.append(':');
                result.append(points[i]);
            }
        }
        return result.append(key.trailer).toString();
    }

    public String committedText(String text) {
        if ((flags & (ALL_KEYS | TEXT)) != (ALL_KEYS | TEXT) || text == null || text.isEmpty() ||
                !text.codePoints().allMatch(KittyKeyboard::isText)) return null;
        // IMEs provide committed text, not a physical key identity.
        return encode(Key.text(0), 0, PRESS, 0, 0, text);
    }

    private static boolean isText(int cp) { return cp >= 32 && (cp < 127 || cp >= 160); }
}
