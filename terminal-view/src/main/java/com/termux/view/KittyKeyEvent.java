package com.termux.view;

import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import com.termux.terminal.KeyHandler;
import com.termux.terminal.KittyKeyboard;
import static android.view.KeyEvent.*;

/** Android key/layout fields translated into kitty's platform-independent encoding. */
final class KittyKeyEvent {
    private KittyKeyEvent() {}

    static String encode(KittyKeyboard keyboard, KeyEvent event, boolean ctrl, boolean alt, boolean shift) {
        int code = event.getKeyCode();
        KittyKeyboard.Key key = functional(code);
        int meta = event.getMetaState();
        boolean altGr = (meta & META_ALT_RIGHT_ON) != 0;
        int layoutMeta = meta & ~(META_CTRL_MASK | META_META_MASK | META_ALT_LEFT_ON);
        if (!altGr) layoutMeta &= ~META_ALT_ON;
        if (shift) layoutMeta |= META_SHIFT_ON;
        int text = event.getUnicodeChar(layoutMeta);
        if ((text & KeyCharacterMap.COMBINING_ACCENT) != 0) return null;
        int plain = event.getUnicodeChar(layoutMeta & ~(META_SHIFT_MASK | META_CAPS_LOCK_ON));
        int shifted = event.getUnicodeChar((layoutMeta & ~META_CAPS_LOCK_ON) | META_SHIFT_ON);
        if (key == null) {
            if (plain <= 0 || !Character.isValidCodePoint(plain)) return null;
            key = KittyKeyboard.Key.text(plain);
        }
        int mods = modifiers(event, ctrl, alt, shift);
        int type = event.getAction() == ACTION_UP ? KittyKeyboard.RELEASE : event.getRepeatCount() > 0 ? KittyKeyboard.REPEAT : KittyKeyboard.PRESS;
        String associated = (mods & (KittyKeyboard.ALT | KittyKeyboard.CTRL | KittyKeyboard.SUPER)) == 0 && text >= 32 &&
            Character.isValidCodePoint(text) ? new String(Character.toChars(text)) : "";
        return keyboard.encode(key, mods, type, shifted, baseLayout(code), associated);
    }

    static int modifiers(KeyEvent e, boolean ctrl, boolean alt, boolean shift) {
        int meta = e.getMetaState(), key = e.getKeyCode();
        boolean up = e.getAction() == ACTION_UP;
        if (key == KEYCODE_SHIFT_LEFT || key == KEYCODE_SHIFT_RIGHT) {
            meta &= ~(META_SHIFT_ON | (key == KEYCODE_SHIFT_LEFT ? META_SHIFT_LEFT_ON : META_SHIFT_RIGHT_ON));
            shift = !up || (meta & META_SHIFT_MASK) != 0;
        } else shift |= e.isShiftPressed();
        if (key == KEYCODE_CTRL_LEFT || key == KEYCODE_CTRL_RIGHT) {
            meta &= ~(META_CTRL_ON | (key == KEYCODE_CTRL_LEFT ? META_CTRL_LEFT_ON : META_CTRL_RIGHT_ON));
            ctrl = !up || (meta & META_CTRL_MASK) != 0;
        } else ctrl |= e.isCtrlPressed();
        if (key == KEYCODE_ALT_LEFT || key == KEYCODE_ALT_RIGHT) {
            meta &= ~(META_ALT_ON | (key == KEYCODE_ALT_LEFT ? META_ALT_LEFT_ON : META_ALT_RIGHT_ON));
            alt = !up || (meta & META_ALT_MASK) != 0;
        } else alt |= (meta & META_ALT_RIGHT_ON) == 0 && e.isAltPressed();
        boolean superKey = e.isMetaPressed();
        if (key == KEYCODE_META_LEFT || key == KEYCODE_META_RIGHT) {
            meta &= ~(META_META_ON | (key == KEYCODE_META_LEFT ? META_META_LEFT_ON : META_META_RIGHT_ON));
            superKey = !up || (meta & META_META_MASK) != 0;
        }
        return (shift ? 1 : 0) | (alt ? 2 : 0) | (ctrl ? 4 : 0) | (superKey ? 8 : 0) |
            (e.isCapsLockOn() ? 64 : 0) | (e.isNumLockOn() ? 128 : 0);
    }

    static int legacyModifiers(int mods) {
        return ((mods & KeyHandler.KEYMOD_SHIFT) != 0 ? 1 : 0) | ((mods & KeyHandler.KEYMOD_ALT) != 0 ? 2 : 0) |
            ((mods & KeyHandler.KEYMOD_CTRL) != 0 ? 4 : 0) | ((mods & KeyHandler.KEYMOD_NUM_LOCK) != 0 ? 128 : 0);
    }

    private static final int[][] FUNCTIONAL = {
        {KEYCODE_ESCAPE,27,'u'}, {KEYCODE_ENTER,13,'u'}, {KEYCODE_DPAD_CENTER,13,'u'}, {KEYCODE_TAB,9,'u'}, {KEYCODE_DEL,127,'u'},
        {KEYCODE_INSERT,2,'~'}, {KEYCODE_FORWARD_DEL,3,'~'}, {KEYCODE_DPAD_LEFT,1,'D'}, {KEYCODE_DPAD_RIGHT,1,'C'},
        {KEYCODE_DPAD_UP,1,'A'}, {KEYCODE_DPAD_DOWN,1,'B'}, {KEYCODE_MOVE_HOME,1,'H'}, {KEYCODE_MOVE_END,1,'F'},
        {KEYCODE_PAGE_UP,5,'~'}, {KEYCODE_PAGE_DOWN,6,'~'}, {KEYCODE_F1,1,'P'}, {KEYCODE_F2,1,'Q'}, {KEYCODE_F3,13,'~'},
        {KEYCODE_F4,1,'S'}, {KEYCODE_F5,15,'~'}, {KEYCODE_F6,17,'~'}, {KEYCODE_F7,18,'~'}, {KEYCODE_F8,19,'~'},
        {KEYCODE_F9,20,'~'}, {KEYCODE_F10,21,'~'}, {KEYCODE_F11,23,'~'}, {KEYCODE_F12,24,'~'},
        {KEYCODE_CAPS_LOCK,57358,'u'}, {KEYCODE_SCROLL_LOCK,57359,'u'}, {KEYCODE_NUM_LOCK,57360,'u'},
        {KEYCODE_SYSRQ,57361,'u'}, {KEYCODE_BREAK,57362,'u'}, {KEYCODE_MENU,57363,'u'},
        {KEYCODE_NUMPAD_DOT,57409,'u'}, {KEYCODE_NUMPAD_DIVIDE,57410,'u'}, {KEYCODE_NUMPAD_MULTIPLY,57411,'u'},
        {KEYCODE_NUMPAD_SUBTRACT,57412,'u'}, {KEYCODE_NUMPAD_ADD,57413,'u'}, {KEYCODE_NUMPAD_ENTER,57414,'u'},
        {KEYCODE_NUMPAD_EQUALS,57415,'u'}, {KEYCODE_NUMPAD_COMMA,57416,'u'},
        {KEYCODE_MEDIA_PLAY,57428,'u'}, {KEYCODE_MEDIA_PAUSE,57429,'u'}, {KEYCODE_MEDIA_PLAY_PAUSE,57430,'u'},
        {KEYCODE_MEDIA_STOP,57432,'u'}, {KEYCODE_MEDIA_FAST_FORWARD,57433,'u'}, {KEYCODE_MEDIA_REWIND,57434,'u'},
        {KEYCODE_MEDIA_NEXT,57435,'u'}, {KEYCODE_MEDIA_PREVIOUS,57436,'u'}, {KEYCODE_MEDIA_RECORD,57437,'u'},
        {KEYCODE_VOLUME_DOWN,57438,'u'}, {KEYCODE_VOLUME_UP,57439,'u'}, {KEYCODE_VOLUME_MUTE,57440,'u'},
        {KEYCODE_SHIFT_LEFT,57441,'u'}, {KEYCODE_CTRL_LEFT,57442,'u'}, {KEYCODE_ALT_LEFT,57443,'u'}, {KEYCODE_META_LEFT,57444,'u'},
        {KEYCODE_SHIFT_RIGHT,57447,'u'}, {KEYCODE_CTRL_RIGHT,57448,'u'}, {KEYCODE_ALT_RIGHT,57449,'u'}, {KEYCODE_META_RIGHT,57450,'u'},
    };

    static KittyKeyboard.Key functional(int code) {
        if (code >= KEYCODE_NUMPAD_0 && code <= KEYCODE_NUMPAD_9)
            return new KittyKeyboard.Key(57399 + code - KEYCODE_NUMPAD_0, 'u', true, false);
        for (int[] entry : FUNCTIONAL) if (entry[0] == code)
            return new KittyKeyboard.Key(entry[1], (char) entry[2], true, entry[1] >= 57441 || entry[1] >= 57358 && entry[1] <= 57360);
        return null;
    }

    private static int baseLayout(int code) {
        if (code >= KEYCODE_A && code <= KEYCODE_Z) return 'a' + code - KEYCODE_A;
        if (code >= KEYCODE_0 && code <= KEYCODE_9) return '0' + code - KEYCODE_0;
        switch (code) {
            case KEYCODE_SPACE: return ' '; case KEYCODE_GRAVE: return '`'; case KEYCODE_MINUS: return '-'; case KEYCODE_EQUALS: return '=';
            case KEYCODE_LEFT_BRACKET: return '['; case KEYCODE_RIGHT_BRACKET: return ']'; case KEYCODE_BACKSLASH: return '\\';
            case KEYCODE_SEMICOLON: return ';'; case KEYCODE_APOSTROPHE: return '\'';
            case KEYCODE_COMMA: return ','; case KEYCODE_PERIOD: return '.'; case KEYCODE_SLASH: return '/';
            default: return 0;
        }
    }
}
