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
    const val KEY_PALETTE = "palette"
    const val CUSTOM_PALETTE = "CUSTOM"
    private const val KEY_CUSTOM_FOREGROUND = "custom-palette-foreground"
    private const val KEY_CUSTOM_BACKGROUND = "custom-palette-background"
    private const val KEY_CUSTOM_CURSOR = "custom-palette-cursor"

    data class PaletteColors(val foreground: String, val background: String, val cursor: String) {
        fun asProperties(): Properties = Properties().apply {
            setProperty("foreground", foreground)
            setProperty("background", background)
            setProperty("cursor", cursor)
        }
    }

    private var appliedPalette: PaletteColors? = null

    fun paddingDp(preferences: SharedPreferences): Int =
        preferences.getInt(KEY_PADDING, DEFAULT_PADDING_DP).coerceIn(PADDING_RANGE)

    fun apply(preferences: SharedPreferences, terminalView: com.termux.view.TerminalView): Boolean {
        terminalView.setTextSize(
            fontSizeSpToPx(preferences.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE_SP)) { value ->
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, terminalView.resources.displayMetrics)
            },
        )
        terminalView.setTypefaceFamily(fontFamily(preferences, terminalView))
        terminalView.setForceFixedWidth(preferences.getBoolean(KEY_FIXED_WIDTH, true))
        terminalView.setNaturalWideGlyphRendering(preferences.getBoolean(KEY_NATURAL_WIDE_GLYPHS, false))
        val colors = paletteColors(preferences)
        TerminalColors.COLOR_SCHEME.updateWith(colors.asProperties())
        val paletteChanged = colors != appliedPalette
        if (paletteChanged) {
            terminalView.resetColorsToDefault()
            appliedPalette = colors
        }
        terminalView.setBackgroundColor(backgroundColor(preferences))
        return paletteChanged
    }

    fun backgroundColor(preferences: SharedPreferences): Int =
        Color.parseColor(paletteColors(preferences).background)

    fun paletteColors(preferences: SharedPreferences): PaletteColors {
        val base = presetPalette(preferences.getString(KEY_PALETTE, "DROID_DARK"))
        if (preferences.getString(KEY_PALETTE, "DROID_DARK") != CUSTOM_PALETTE) return base
        return PaletteColors(
            preferences.getString(KEY_CUSTOM_FOREGROUND, base.foreground).orEmpty().takeIf(::isHexColor) ?: base.foreground,
            preferences.getString(KEY_CUSTOM_BACKGROUND, base.background).orEmpty().takeIf(::isHexColor) ?: base.background,
            preferences.getString(KEY_CUSTOM_CURSOR, base.cursor).orEmpty().takeIf(::isHexColor) ?: base.cursor,
        )
    }

    fun saveCustomPalette(preferences: SharedPreferences, colors: PaletteColors) {
        require(isHexColor(colors.foreground) && isHexColor(colors.background) && isHexColor(colors.cursor))
        preferences.edit()
            .putString(KEY_CUSTOM_FOREGROUND, colors.foreground.uppercase())
            .putString(KEY_CUSTOM_BACKGROUND, colors.background.uppercase())
            .putString(KEY_CUSTOM_CURSOR, colors.cursor.uppercase())
            .putString(KEY_PALETTE, CUSTOM_PALETTE)
            .apply()
    }

    fun isHexColor(value: String): Boolean = value.matches(Regex("#[0-9a-fA-F]{6}"))

    private fun presetPalette(value: String?): PaletteColors = when (value) {
        "SOLARIZED_DARK" -> PaletteColors("#839496", "#002B36", "#93A1A1")
        "AMOLED" -> PaletteColors("#E6E6E6", "#000000", "#FFFFFF")
        else -> PaletteColors("#F1F3F5", "#000000", "#FFFFFF")
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
    private const val KEY_FIXED_WIDTH = "fixed-width"
    private const val KEY_NATURAL_WIDE_GLYPHS = "natural-wide-glyphs"
}

internal fun fontSizeSpToPx(fontSizeSp: Int, convertToPixels: (Float) -> Float): Int =
    convertToPixels(fontSizeSp.toFloat()).roundToInt().coerceAtLeast(1)
