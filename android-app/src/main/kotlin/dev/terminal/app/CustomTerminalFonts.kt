package dev.terminal.app

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal enum class CustomTerminalFontStyle { REGULAR, ITALIC }

/** A user-selected font. Entries are stored in display/priority order per style. */
internal data class CustomTerminalFont(
    val uri: String,
    val name: String,
    val style: CustomTerminalFontStyle = CustomTerminalFontStyle.REGULAR,
)

internal fun customTerminalFonts(preferences: SharedPreferences): List<CustomTerminalFont> =
    parseCustomTerminalFonts(preferences.getString(KEY_CUSTOM_FONTS, null))

internal fun saveCustomTerminalFonts(
    preferences: SharedPreferences,
    fonts: List<CustomTerminalFont>,
) {
    preferences.edit().putString(KEY_CUSTOM_FONTS, serializeCustomTerminalFonts(fonts)).apply()
}

internal fun parseCustomTerminalFonts(serialized: String?): List<CustomTerminalFont> = runCatching {
    val values = JSONArray(serialized ?: "[]")
    buildList<CustomTerminalFont> {
        for (index in 0 until values.length()) {
            val value = values.optJSONObject(index) ?: continue
            val uri = value.optString("uri").trim()
            if (uri.isNotEmpty()) {
                val style = value.optString("style")
                    .let { runCatching { CustomTerminalFontStyle.valueOf(it) }.getOrDefault(CustomTerminalFontStyle.REGULAR) }
                if (none { it.uri == uri && it.style == style }) {
                add(CustomTerminalFont(uri, value.optString("name").ifBlank { uri }, style))
                }
            }
        }
    }
}.getOrDefault(emptyList())

internal fun serializeCustomTerminalFonts(fonts: List<CustomTerminalFont>): String =
    JSONArray().apply {
        fonts.distinctBy { it.uri to it.style }.forEach { font ->
            put(JSONObject().put("uri", font.uri).put("name", font.name).put("style", font.style.name))
        }
    }.toString()

internal fun moveCustomTerminalFont(
    fonts: List<CustomTerminalFont>,
    fromIndex: Int,
    toIndex: Int,
): List<CustomTerminalFont> {
    if (fromIndex !in fonts.indices || toIndex !in fonts.indices || fromIndex == toIndex) return fonts
    return fonts.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
}

internal const val KEY_CUSTOM_FONTS = "custom-fonts"
