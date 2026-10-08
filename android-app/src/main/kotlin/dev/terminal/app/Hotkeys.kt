package dev.terminal.app

import android.content.SharedPreferences
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal const val KEY_HOTKEYS = "hotkey-toolbar-v1"
internal const val MAX_HOTKEYS = 48
internal const val HOTKEY_HEIGHT_DP = 40

internal data class Hotkey(val name: String, val shortcut: String, val span: Int = 1)
internal data class HotkeyLayout(
    val columns: Int = 7,
    val fontSize: Int = 12,
    val keys: List<Hotkey> = defaultHotkeys(),
    val enabled: Boolean = true,
)

internal fun defaultHotkeys() = listOf(
    Hotkey("ESC", "ESC"), Hotkey("/", "/"), Hotkey("−", "-"),
    Hotkey("HOME", "HOME"), Hotkey("END", "END"), Hotkey("PGUP", "PAGEUP"),
    Hotkey("TAB", "TAB"), Hotkey("CTRL", "CTRL"), Hotkey("ALT", "ALT"),
    Hotkey("↑", "UP"), Hotkey("↓", "DOWN"), Hotkey("←", "LEFT"),
    Hotkey("→", "RIGHT"), Hotkey("PGDN", "PAGEDOWN"),
)

internal data class HotkeyChord(val key: String, val control: Boolean, val alt: Boolean, val shift: Boolean) {
    val keyCode: Int? get() = namedHotkeyCodes[key]
    val codePoint: Int? get() {
        if (keyCode != null) return null
        val character = if (key == "PLUS") '+' else key.single()
        return (if (shift) shiftHotkeyCharacter(character) else character).code
    }
}

internal fun isRepeatableHotkey(shortcut: String): Boolean = parseHotkeyChord(shortcut)?.key in
    setOf("UP", "DOWN", "LEFT", "RIGHT", "BACKSPACE", "DELETE", "PAGEUP", "PAGEDOWN")

private fun shiftHotkeyCharacter(value: Char): Char {
    val plain = "1234567890-=[]\\;',./`"
    val shifted = "!@#$%^&*()_+{}|:\"<>?~"
    return plain.indexOf(value).takeIf { it >= 0 }?.let { shifted[it] } ?: value.uppercaseChar()
}

internal val namedHotkeyCodes = mapOf(
    "ESC" to KeyEvent.KEYCODE_ESCAPE, "TAB" to KeyEvent.KEYCODE_TAB,
    "ENTER" to KeyEvent.KEYCODE_ENTER, "BACKSPACE" to KeyEvent.KEYCODE_DEL,
    "SPACE" to KeyEvent.KEYCODE_SPACE,
    "DELETE" to KeyEvent.KEYCODE_FORWARD_DEL, "INSERT" to KeyEvent.KEYCODE_INSERT,
    "HOME" to KeyEvent.KEYCODE_MOVE_HOME, "END" to KeyEvent.KEYCODE_MOVE_END,
    "PAGEUP" to KeyEvent.KEYCODE_PAGE_UP, "PAGEDOWN" to KeyEvent.KEYCODE_PAGE_DOWN,
    "UP" to KeyEvent.KEYCODE_DPAD_UP, "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
    "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT, "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
) + (1..12).associate { "F$it" to KeyEvent.KEYCODE_F1 + it - 1 }

/** One explicit chord, not shell text. CTRL, ALT and SHIFT alone are toolbar latches. */
internal fun parseHotkeyChord(value: String): HotkeyChord? {
    val parts = value.trim().split('+').map { it.trim().uppercase(Locale.ROOT) }
    val modifiers = parts.dropLast(1)
    if (modifiers.any { it !in setOf("CTRL", "ALT", "SHIFT") } || modifiers.distinct().size != modifiers.size) return null
    val token = parts.last()
    val key = if (token.length == 1 && token[0] in '!'..'~') token.lowercase(Locale.ROOT) else token
    if (key !in namedHotkeyCodes && key != "PLUS" && !(key.length == 1 && key[0] in '!'..'~')) return null
    return HotkeyChord(key, "CTRL" in modifiers, "ALT" in modifiers, "SHIFT" in modifiers)
}

internal fun validHotkey(key: Hotkey): Boolean =
    key.name.isNotBlank() && key.name.length <= 32 && key.name.none { it.isISOControl() } &&
        key.span in 1..4 && key.shortcut.length <= 80 &&
        (key.shortcut in setOf("CTRL", "ALT", "SHIFT") || parseHotkeyChord(key.shortcut) != null)

internal fun readHotkeyLayout(preferences: SharedPreferences): HotkeyLayout =
    parseHotkeyLayout(preferences.getString(KEY_HOTKEYS, null))

internal fun parseHotkeyLayout(value: String?): HotkeyLayout = runCatching {
    val json = JSONObject(value ?: return HotkeyLayout())
    require(json.getInt("version") == 1)
    val array = json.getJSONArray("keys")
    require(array.length() in 1..MAX_HOTKEYS)
    val keys = (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        Hotkey(item.getString("name"), item.getString("shortcut"), item.getInt("span")).also { require(validHotkey(it)) }
    }
    HotkeyLayout(json.getInt("columns"), json.getInt("fontSize"), keys,
        if (json.has("enabled")) json.getBoolean("enabled") else true).also {
        require(it.columns in 2..10 && it.fontSize in 10..24)
    }
}.getOrDefault(HotkeyLayout())

internal fun serializeHotkeyLayout(layout: HotkeyLayout): String = JSONObject()
    .put("version", 1).put("columns", layout.columns).put("fontSize", layout.fontSize)
    .put("enabled", layout.enabled)
    .put("keys", JSONArray().apply {
        layout.keys.forEach { put(JSONObject().put("name", it.name).put("shortcut", it.shortcut).put("span", it.span)) }
    }).toString()

/** Ordered grid packing. Narrow screens clamp spans without changing the saved layout. */
internal fun hotkeyRows(keys: List<Hotkey>, columns: Int): List<List<Int>> {
    require(columns >= 1)
    val rows = mutableListOf<List<Int>>()
    var row = mutableListOf<Int>()
    var used = 0
    keys.forEachIndexed { index, key ->
        val span = key.span.coerceIn(1, columns)
        if (used + span > columns) { rows.add(row); row = mutableListOf(); used = 0 }
        row.add(index)
        used += span
    }
    if (row.isNotEmpty()) rows.add(row)
    return rows
}

internal fun moveHotkey(layout: HotkeyLayout, from: Int, to: Int): HotkeyLayout {
    if (from !in layout.keys.indices || to !in layout.keys.indices) return layout
    return layout.copy(keys = layout.keys.toMutableList().apply { add(to, removeAt(from)) })
}
