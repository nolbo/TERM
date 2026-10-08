package dev.terminal.app

import android.view.KeyEvent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HotkeysTest {
    @Test fun legacyHeightIsIgnoredWithoutLosingCustomKeys() {
        val layout = HotkeyLayout(columns = 4, fontSize = 18,
            keys = listOf(Hotkey("중단", "CTRL+C", 2)), enabled = false)
        for (height in listOf(31, 32, 40, 48, 80, 81)) {
            val legacy = JSONObject(serializeHotkeyLayout(layout)).put("height", height)
            assertEquals(layout, parseHotkeyLayout(legacy.toString()))
        }
        assertFalse(JSONObject(serializeHotkeyLayout(layout)).has("height"))
    }

    @Test fun onlyNavigationAndDeletionChordsRepeat() {
        for (key in listOf("UP", "DOWN", "LEFT", "RIGHT", "BACKSPACE", "DELETE", "PAGEUP", "PAGEDOWN", "CTRL+LEFT")) {
            assertTrue(key, isRepeatableHotkey(key))
        }
        for (key in listOf("ENTER", "ESC", "TAB", "CTRL", "ALT", "SHIFT", "CTRL+C", "HOME", "END", "INVALID")) {
            assertFalse(key, isRepeatableHotkey(key))
        }
    }

    @Test fun defaultsPreserveExistingKeysAndOrder() {
        val layout = parseHotkeyLayout(null)
        assertTrue(layout.enabled)
        assertEquals(14, layout.keys.size)
        assertEquals(listOf((0..6).toList(), (7..13).toList()), hotkeyRows(layout.keys, 7))
        assertTrue(layout.keys.all(::validHotkey))
    }

    @Test fun toolbarVisibilityRoundTripsAndLegacyLayoutsStayEnabled() {
        val disabled = HotkeyLayout(enabled = false)
        val encoded = serializeHotkeyLayout(disabled)
        assertEquals(disabled, parseHotkeyLayout(encoded))
        assertEquals(HotkeyLayout(), parseHotkeyLayout(JSONObject(encoded).apply { remove("enabled") }.toString()))
    }

    @Test fun customizedLayoutRoundTripsIncludingUnicodeNamesAndOrder() {
        val layout = HotkeyLayout(4, 18, listOf(Hotkey("중단", "CTRL+C", 2), Hotkey("이전 탭", "CTRL+SHIFT+TAB", 3)))
        assertEquals(layout, parseHotkeyLayout(serializeHotkeyLayout(layout)))
        assertEquals(layout.keys.reversed(), moveHotkey(layout, 0, 1).keys)
        assertEquals(layout, moveHotkey(layout, -1, 3))
    }

    @Test fun standaloneShiftCanBeSavedAndRestoredAsAModifier() {
        val layout = HotkeyLayout(keys = listOf(Hotkey("Shift", "SHIFT"), Hotkey("Tab", "TAB")))
        assertTrue(validHotkey(layout.keys.first()))
        assertEquals(layout, parseHotkeyLayout(serializeHotkeyLayout(layout)))
        assertNull(parseHotkeyChord("SHIFT"))
    }

    @Test fun invalidStoredSettingsFallBackWithoutPartialMutation() {
        for (value in listOf("{", "{}", "[]", serializeHotkeyLayout(HotkeyLayout(columns = 100)),
            serializeHotkeyLayout(HotkeyLayout(keys = emptyList())),
            serializeHotkeyLayout(HotkeyLayout(keys = List(49) { Hotkey("x", "X") })),
            serializeHotkeyLayout(HotkeyLayout(keys = listOf(Hotkey("bad", "CTRL+UNKNOWN")))))) {
            assertEquals(HotkeyLayout(), parseHotkeyLayout(value))
        }
    }

    @Test fun spanningGridPreservesOrderAndNeverOverflows() {
        val keys = listOf(Hotkey("a", "A", 2), Hotkey("b", "B", 3), Hotkey("c", "C"), Hotkey("d", "D", 4))
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3)), hotkeyRows(keys, 4))
        for (columns in 1..10) {
            val rows = hotkeyRows(keys, columns)
            assertEquals(keys.indices.toList(), rows.flatten())
            assertTrue(rows.all { row -> row.sumOf { keys[it].span.coerceAtMost(columns) } <= columns })
        }
    }

    @Test fun chordsAreCaseInsensitiveAndKeepAllExplicitModifiers() {
        val chord = requireNotNull(parseHotkeyChord(" ctrl + Alt + shift + tab "))
        assertTrue(chord.control && chord.alt && chord.shift)
        assertEquals(KeyEvent.KEYCODE_TAB, chord.keyCode)
        assertNull(chord.codePoint)
        assertEquals('c'.code, parseHotkeyChord("CTRL+C")?.codePoint)
        assertEquals('C'.code, parseHotkeyChord("SHIFT+C")?.codePoint)
        assertEquals('!'.code, parseHotkeyChord("SHIFT+1")?.codePoint)
        assertEquals('+'.code, parseHotkeyChord("ALT+PLUS")?.codePoint)
        assertEquals(KeyEvent.KEYCODE_SPACE, parseHotkeyChord("CTRL+SPACE")?.keyCode)
        assertEquals(KeyEvent.KEYCODE_F12, parseHotkeyChord("CTRL+F12")?.keyCode)
    }

    @Test fun spaceChordsCanBeSavedAndRestoredWithEveryModifierCombination() {
        val shortcuts = listOf("SPACE", "CTRL+SPACE", "ALT+SPACE", "SHIFT+SPACE", "CTRL+ALT+SPACE",
            "CTRL+SHIFT+SPACE", "ALT+SHIFT+SPACE", "CTRL+ALT+SHIFT+SPACE")
        val layout = HotkeyLayout(keys = shortcuts.map { Hotkey(it, it) })
        assertTrue(layout.keys.all(::validHotkey))
        assertEquals(layout, parseHotkeyLayout(serializeHotkeyLayout(layout)))
    }

    @Test fun invalidChordsCannotBeSavedOrSentAsShellText() {
        for (value in listOf("", "CTRL+", "CTRL++C", "CTRL+CTRL+C", "META+C", "CTRL+hello", "echo hi", "F13", "CTRL")) {
            assertNull(value, parseHotkeyChord(value))
        }
        assertTrue(validHotkey(Hotkey("보조 키", "CTRL")))
        assertFalse(validHotkey(Hotkey(" ", "CTRL+C")))
        assertFalse(validHotkey(Hotkey("x".repeat(33), "CTRL+C")))
        assertFalse(validHotkey(Hotkey("a\nb", "CTRL+C")))
        assertFalse(validHotkey(Hotkey("test", "CTRL+C", 0)))
    }
}
