package dev.terminal.app

import android.content.SharedPreferences

internal class SessionFontSizes(private val preferences: SharedPreferences) {
    fun get(id: String, defaultSize: Int): Int =
        preferences.getInt(id, defaultSize).coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)

    fun scale(id: String, defaultSize: Int, factor: Float, applySize: (Int) -> Unit): Float {
        if (!factor.isFinite() || factor <= 0f) return 1f
        if (factor in 0.9f..1.1f) return factor
        val old = get(id, defaultSize)
        val size = (old + if (factor > 1f) 1 else -1).coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
        if (size != old) {
            preferences.edit().putInt(id, size).apply()
            applySize(size)
        }
        return 1f
    }

    companion object {
        const val MIN_FONT_SIZE_SP = 4
        const val MAX_FONT_SIZE_SP = 72
    }
}
