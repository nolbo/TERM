package dev.terminal.app

import android.content.SharedPreferences
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

internal const val KEY_HOTKEYS = "hotkey-toolbar-v1"
internal const val MAX_HOTKEYS = 48
internal const val HOTKEY_HEIGHT_DP = 40
internal const val HOTKEY_GAP_DP = 4
internal const val MAX_HOTKEY_ROWS = 128

/** Grid cells use the saved canvas columns and 44 dp row pitch; older fractional layouts remain readable. */
internal data class HotkeyBounds(val x: Float, val y: Float, val width: Float = 1f, val height: Float = 1f) {
    val right get() = x + width
    val bottom get() = y + height
    fun overlaps(other: HotkeyBounds): Boolean =
        x < other.right - 0.0001f && right > other.x + 0.0001f &&
            y < other.bottom - 0.0001f && bottom > other.y + 0.0001f
    fun fits(columns: Int): Boolean = listOf(x, y, width, height).all { it.isFinite() } &&
        x >= 0 && y >= 0 && width >= 1 && height >= 1 && right <= columns && bottom <= MAX_HOTKEY_ROWS
}

internal data class Hotkey(val name: String, val shortcut: String, val span: Int = 1, val bounds: HotkeyBounds? = null)
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
        key.span in 1..4 && (key.bounds?.fits(10) != false) && key.shortcut.length <= 80 &&
        (key.shortcut in setOf("CTRL", "ALT", "SHIFT") || parseHotkeyChord(key.shortcut) != null)

internal fun readHotkeyLayout(preferences: SharedPreferences): HotkeyLayout =
    parseHotkeyLayout(preferences.getString(KEY_HOTKEYS, null))

internal fun parseHotkeyLayout(value: String?): HotkeyLayout = runCatching {
    val json = JSONObject(value ?: return HotkeyLayout())
    val version = json.getInt("version")
    require(version in 1..2)
    val array = json.getJSONArray("keys")
    require(array.length() in 1..MAX_HOTKEYS)
    val keys = (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        val bounds = if (version == 2 && item.has("bounds")) item.getJSONObject("bounds").let {
            HotkeyBounds(it.getDouble("x").toFloat(), it.getDouble("y").toFloat(),
                it.getDouble("width").toFloat(), it.getDouble("height").toFloat())
        } else null
        Hotkey(item.getString("name"), item.getString("shortcut"), item.getInt("span"), bounds).also { require(validHotkey(it)) }
    }
    HotkeyLayout(json.getInt("columns"), json.getInt("fontSize"), keys,
        if (json.has("enabled")) json.getBoolean("enabled") else true).also {
        require(it.columns in 2..10 && it.fontSize in 10..24)
        val bounds = hotkeyBounds(it)
        require(bounds.all { rect -> rect.fits(it.columns) })
        require(bounds.indices.none { index -> bounds.take(index).any { other -> bounds[index].overlaps(other) } })
    }
}.getOrDefault(HotkeyLayout())

internal fun serializeHotkeyLayout(layout: HotkeyLayout): String = JSONObject()
    .put("version", 2).put("columns", layout.columns).put("fontSize", layout.fontSize)
    .put("enabled", layout.enabled)
    .put("keys", JSONArray().apply {
        layout.keys.forEach { key ->
            put(JSONObject().put("name", key.name).put("shortcut", key.shortcut).put("span", key.span).apply {
                key.bounds?.let { put("bounds", JSONObject().put("x", it.x).put("y", it.y).put("width", it.width).put("height", it.height)) }
            })
        }
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

/** Legacy keys retain their original packed positions until the layout is first edited. */
internal fun hotkeyBounds(layout: HotkeyLayout): List<HotkeyBounds> {
    val packed = mutableMapOf<Int, HotkeyBounds>()
    hotkeyRows(layout.keys, layout.columns).forEachIndexed { row, indices ->
        var column = 0
        indices.forEach { index ->
            val width = layout.keys[index].span.coerceAtMost(layout.columns)
            packed[index] = HotkeyBounds(column.toFloat(), row.toFloat(), width.toFloat())
            column += width
        }
    }
    return layout.keys.mapIndexed { index, key -> key.bounds ?: packed.getValue(index) }
}

internal fun positionedHotkeys(layout: HotkeyLayout): List<Hotkey> = hotkeyBounds(layout).mapIndexed { index, bounds ->
    layout.keys[index].copy(bounds = bounds)
}

internal fun canPlaceHotkey(layout: HotkeyLayout, index: Int, bounds: HotkeyBounds): Boolean =
    index in layout.keys.indices && bounds.fits(layout.columns) &&
        hotkeyBounds(layout).withIndex().none { (other, rect) -> other != index && bounds.overlaps(rect) }

internal fun placeHotkey(layout: HotkeyLayout, index: Int, bounds: HotkeyBounds): HotkeyLayout =
    if (!canPlaceHotkey(layout, index, bounds)) layout else layout.copy(keys = positionedHotkeys(layout).toMutableList().apply {
        set(index, get(index).copy(bounds = bounds))
    })

internal fun addHotkey(layout: HotkeyLayout, key: Hotkey): HotkeyLayout {
    if (layout.keys.size >= MAX_HOTKEYS || !validHotkey(key)) return layout
    val occupied = hotkeyBounds(layout)
    val width = key.span.coerceAtMost(layout.columns).toFloat()
    for (row in 0 until MAX_HOTKEY_ROWS) for (column in 0..layout.columns - width.toInt()) {
        val bounds = HotkeyBounds(column.toFloat(), row.toFloat(), width)
        if (occupied.none(bounds::overlaps)) return layout.copy(keys = positionedHotkeys(layout) + key.copy(bounds = bounds))
    }
    return layout
}

internal fun removeHotkey(layout: HotkeyLayout, index: Int): HotkeyLayout =
    if (layout.keys.size <= 1 || index !in layout.keys.indices) layout
    else layout.copy(keys = positionedHotkeys(layout).filterIndexed { position, _ -> position != index })

internal fun resizeHotkeyCanvas(layout: HotkeyLayout, columns: Int): HotkeyLayout =
    if (columns !in 2..10 || columns == layout.columns || hotkeyBounds(layout).any { it.right > columns }) layout
    else layout.copy(columns = columns, keys = positionedHotkeys(layout))

internal enum class HotkeyCorner(val left: Boolean, val top: Boolean) {
    TopLeft(true, true), TopRight(false, true), BottomLeft(true, false), BottomRight(false, false),
}

internal fun transformHotkeyBounds(bounds: HotkeyBounds, dx: Float, dy: Float, columns: Int,
    corner: HotkeyCorner? = null): HotkeyBounds {
    if (!dx.isFinite() || !dy.isFinite()) return bounds
    fun snap(value: Float) = value.roundToInt().toFloat()
    val fixedLeft = snap(bounds.x)
    val fixedTop = snap(bounds.y)
    val fixedRight = snap(bounds.right)
    val fixedBottom = snap(bounds.bottom)
    if (corner == null) return HotkeyBounds(
        snap(bounds.x + dx).coerceIn(0f, columns - (fixedRight - fixedLeft)),
        snap(bounds.y + dy).coerceIn(0f, MAX_HOTKEY_ROWS - (fixedBottom - fixedTop)),
        fixedRight - fixedLeft, fixedBottom - fixedTop)
    val left = if (corner.left) snap(bounds.x + dx).coerceIn(0f, fixedRight - 1f) else fixedLeft
    val right = if (!corner.left) snap(bounds.right + dx).coerceIn(fixedLeft + 1f, columns.toFloat()) else fixedRight
    val top = if (corner.top) snap(bounds.y + dy).coerceIn(0f, fixedBottom - 1f) else fixedTop
    val bottom = if (!corner.top) snap(bounds.bottom + dy).coerceIn(fixedTop + 1f, MAX_HOTKEY_ROWS.toFloat()) else fixedBottom
    return HotkeyBounds(left, top, right - left, bottom - top)
}
