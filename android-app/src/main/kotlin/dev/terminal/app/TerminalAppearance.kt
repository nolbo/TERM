package dev.terminal.app

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.net.Uri
import android.util.TypedValue
import com.termux.terminal.TerminalColors
import com.termux.view.TerminalFontFamily
import java.util.Properties
import kotlin.math.roundToInt

/** Applies persisted renderer-only preferences; it never changes the runtime process. */
object TerminalAppearance {
    const val KEY_PADDING = "terminal-padding-dp"
    const val DEFAULT_PADDING_DP = 4
    val PADDING_RANGE = 0..32
    const val DEFAULT_FONT_SIZE_SP = 10

    fun paddingDp(preferences: SharedPreferences): Int =
        preferences.getInt(KEY_PADDING, DEFAULT_PADDING_DP).coerceIn(PADDING_RANGE)

    fun apply(preferences: SharedPreferences, terminalView: com.termux.view.TerminalView) {
        terminalView.setTextSize(
            fontSizeSpToPx(preferences.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE_SP)) { value ->
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, terminalView.resources.displayMetrics)
            },
        )
        terminalView.setTypefaceFamily(fontFamily(preferences, terminalView))
        terminalView.setForceFixedWidth(preferences.getBoolean(KEY_FIXED_WIDTH, true))
        terminalView.setNaturalWideGlyphRendering(preferences.getBoolean(KEY_NATURAL_WIDE_GLYPHS, false))
        TerminalColors.COLOR_SCHEME.updateWith(palette(preferences.getString(KEY_PALETTE, "DROID_DARK")))
        terminalView.setBackgroundColor(backgroundColor(preferences))
    }

    fun backgroundColor(preferences: SharedPreferences): Int =
        Color.parseColor(background(preferences.getString(KEY_PALETTE, "DROID_DARK")))

    private fun palette(value: String?): Properties = Properties().apply {
        when (value) {
            "SOLARIZED_DARK" -> { setProperty("background", "#002b36"); setProperty("foreground", "#839496"); setProperty("cursor", "#93a1a1") }
            "AMOLED" -> { setProperty("background", "#000000"); setProperty("foreground", "#e6e6e6"); setProperty("cursor", "#ffffff") }
            else -> { setProperty("background", "#000000"); setProperty("foreground", "#f1f3f5"); setProperty("cursor", "#ffffff") }
        }
    }

    private fun background(value: String?): String = when (value) {
        "SOLARIZED_DARK" -> "#002b36"
        "AMOLED" -> "#000000"
        else -> "#000000"
    }

    /**
     * Use custom fonts in list order according to their actual cmap coverage. The first usable
     * custom face also supplies the terminal cell metrics, and the selected built-in face is the
     * final fallback. Unavailable or malformed documents are skipped.
     */
    private fun fontFamily(
        preferences: SharedPreferences,
        terminalView: com.termux.view.TerminalView,
    ): TerminalFontFamily {
        val customFaces = customTerminalFonts(preferences).mapNotNull { font ->
            runCatching {
                terminalView.context.contentResolver.openFileDescriptor(Uri.parse(font.uri), "r")?.use { descriptor ->
                    val androidFont = Font.Builder(descriptor).build()
                    val ranges = OpenTypeCmap.codePointRanges(androidFont.buffer, androidFont.ttcIndex)
                    if (ranges.isEmpty()) return@use null
                    val family = FontFamily.Builder(androidFont).build()
                    val typeface = Typeface.CustomFallbackBuilder(family)
                        .setSystemFallback(builtInSystemFallback(preferences))
                        .build()
                    font.style to TerminalFontFamily.FontFace(typeface, ranges)
                }
            }.getOrNull()
        }
        return TerminalFontFamily(
            customFaces.filter { it.first == CustomTerminalFontStyle.REGULAR }.map { it.second },
            customFaces.filter { it.first == CustomTerminalFontStyle.ITALIC }.map { it.second },
            builtInTypeface(preferences),
        )
    }

    private fun builtInTypeface(preferences: SharedPreferences): Typeface =
        if (builtInSystemFallback(preferences) == "sans-serif-monospace") {
            Typeface.create("sans-serif-monospace", Typeface.NORMAL)
        } else {
            Typeface.MONOSPACE
        }

    private fun builtInSystemFallback(preferences: SharedPreferences): String =
        if (preferences.getString(KEY_FONT_FAMILY, "MONOSPACE") == "SANS_MONOSPACE") {
            "sans-serif-monospace"
        } else {
            "monospace"
        }

    private const val KEY_FONT_SIZE = "font-size-sp"
    private const val KEY_FONT_FAMILY = "font-family"
    private const val KEY_PALETTE = "palette"
    private const val KEY_FIXED_WIDTH = "fixed-width"
    private const val KEY_NATURAL_WIDE_GLYPHS = "natural-wide-glyphs"
}

internal fun fontSizeSpToPx(fontSizeSp: Int, convertToPixels: (Float) -> Float): Int =
    convertToPixels(fontSizeSp.toFloat()).roundToInt().coerceAtLeast(1)
