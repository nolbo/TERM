package dev.terminal.app

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import com.termux.terminal.TerminalColors
import java.util.Properties
import kotlin.math.roundToInt

/** Applies persisted renderer-only preferences; it never changes the runtime process. */
object TerminalAppearance {
    fun apply(preferences: SharedPreferences, terminalView: com.termux.view.TerminalView) {
        terminalView.setTextSize(
            fontSizeSpToPx(preferences.getInt(KEY_FONT_SIZE, 16)) { value ->
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, terminalView.resources.displayMetrics)
            },
        )
        terminalView.setTypeface(
            if (preferences.getString(KEY_FONT_FAMILY, "MONOSPACE") == "SANS_MONOSPACE")
                Typeface.create("sans-serif-monospace", Typeface.NORMAL)
            else Typeface.MONOSPACE,
        )
        terminalView.setForceFixedWidth(preferences.getBoolean(KEY_FIXED_WIDTH, true))
        TerminalColors.COLOR_SCHEME.updateWith(palette(preferences.getString(KEY_PALETTE, "DROID_DARK")))
        terminalView.setBackgroundColor(Color.parseColor(background(preferences.getString(KEY_PALETTE, "DROID_DARK"))))
    }

    private fun palette(value: String?): Properties = Properties().apply {
        when (value) {
            "SOLARIZED_DARK" -> { setProperty("background", "#002b36"); setProperty("foreground", "#839496"); setProperty("cursor", "#93a1a1") }
            "AMOLED" -> { setProperty("background", "#000000"); setProperty("foreground", "#e6e6e6"); setProperty("cursor", "#ffffff") }
            else -> { setProperty("background", "#000000"); setProperty("foreground", "#f1f3f5"); setProperty("cursor", "#6ef2ad") }
        }
    }

    private fun background(value: String?): String = when (value) {
        "SOLARIZED_DARK" -> "#002b36"
        "AMOLED" -> "#000000"
        else -> "#000000"
    }

    private const val KEY_FONT_SIZE = "font-size-sp"
    private const val KEY_FONT_FAMILY = "font-family"
    private const val KEY_PALETTE = "palette"
    private const val KEY_FIXED_WIDTH = "fixed-width"
}

internal fun fontSizeSpToPx(fontSizeSp: Int, convertToPixels: (Float) -> Float): Int =
    convertToPixels(fontSizeSp.toFloat()).roundToInt().coerceAtLeast(1)
