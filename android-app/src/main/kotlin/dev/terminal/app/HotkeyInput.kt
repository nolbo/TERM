package dev.terminal.app

import android.os.SystemClock
import android.view.KeyEvent
import dev.terminal.view.TerminalView

internal fun dispatchHotkeyShortcut(view: TerminalView, callbacks: TerminalInputCallbacks, value: String) {
    var chord = parseHotkeyChord(value) ?: return
    // A complete chord replaces latched modifiers; plain keys retain latch behavior.
    if (chord.control || chord.alt || chord.shift) {
        callbacks.controlPressed = false
        callbacks.altPressed = false
        callbacks.shiftPressed = false
    } else if (callbacks.shiftPressed) {
        chord = chord.copy(shift = true)
    }
    val keyCode = chord.keyCode
    if (keyCode != null) {
        val meta = (if (chord.control) KeyEvent.META_CTRL_ON else 0) or
            (if (chord.alt) KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON else 0) or
            (if (chord.shift) KeyEvent.META_SHIFT_ON else 0)
        val now = SystemClock.uptimeMillis()
        view.onKeyDown(keyCode, KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        view.onKeyUp(keyCode, KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
        callbacks.consumeLatchedModifiers()
    } else {
        chord.codePoint?.let { view.inputCodePoint(it, chord.control, chord.alt) }
    }
}
