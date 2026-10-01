package dev.terminal.runtime.session

import java.util.Properties

/** Terminal protocol color defaults shared by the PTY owner and its renderers. */
object TerminalPalette {
    private val ansi = listOf(
        "#000000", "#CD0000", "#00CD00", "#CDCD00", "#6495ED", "#CD00CD", "#00CDCD", "#E5E5E5",
        "#7F7F7F", "#FF0000", "#00FF00", "#FFFF00", "#5C5CFF", "#FF00FF", "#00FFFF", "#FFFFFF",
    )
    fun resolve(read: (String, String) -> String, profile: List<String>? = null): List<String> {
        if (profile != null) return profile
        val selected = read("palette", "DROID_DARK")
        val base = when (selected) {
            "SOLARIZED_DARK" -> listOf("#839496", "#002B36", "#93A1A1")
            "AMOLED" -> listOf("#E6E6E6", "#000000", "#FFFFFF")
            else -> listOf("#F1F3F5", "#000000", "#FFFFFF")
        } + ansi
        if (selected != "CUSTOM") return base
        val keys = listOf("foreground", "background", "cursor") + (0..15).map { "color-$it" }
        return base.mapIndexed { index, fallback ->
            read("custom-palette-${keys[index]}", fallback).takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) } ?: fallback
        }
    }
    fun asProperties(colors: List<String>) = Properties().apply {
        require(colors.size == 19)
        listOf("foreground", "background", "cursor").forEachIndexed { index, key -> setProperty(key, colors[index]) }
        colors.drop(3).forEachIndexed { index, color -> setProperty("color$index", color) }
    }
}
