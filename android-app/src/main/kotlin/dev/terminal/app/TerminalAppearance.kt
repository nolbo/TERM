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
import java.util.WeakHashMap
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
    private const val KEY_CUSTOM_ANSI_PREFIX = "custom-palette-color-"

    data class PaletteColors(
        val foreground: String,
        val background: String,
        val cursor: String,
        val ansi: List<String>,
    ) {
        init {
            require(ansi.size == ANSI_COLOR_COUNT)
        }

        fun asProperties(): Properties = Properties().apply {
            setProperty("foreground", foreground)
            setProperty("background", background)
            setProperty("cursor", cursor)
            ansi.forEachIndexed { index, color -> setProperty("color$index", color) }
        }
    }

    private var appliedPalette: PaletteColors? = null
    private data class FontSettings(val fallback: String, val custom: List<CustomTerminalFont>)
    // Main-thread-only, per-view cache: it must not retain an Activity after destruction.
    private val appliedFonts = WeakHashMap<com.termux.view.TerminalView, FontSettings>()

    fun paddingDp(preferences: SharedPreferences): Int =
        preferences.getInt(KEY_PADDING, DEFAULT_PADDING_DP).coerceIn(PADDING_RANGE)

    fun apply(
        preferences: SharedPreferences,
        terminalView: com.termux.view.TerminalView,
        fontSizeSp: Int = preferences.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE_SP),
    ): Boolean {
        terminalView.setTextSize(
            fontSizeSpToPx(fontSizeSp) { value ->
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, terminalView.resources.displayMetrics)
            },
        )
        val fonts = FontSettings(builtInSystemFallback(preferences), customTerminalFonts(preferences))
        if (appliedFonts[terminalView] != fonts) {
            terminalView.setTypefaceFamily(fontFamily(preferences, terminalView))
            appliedFonts[terminalView] = fonts
        }
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
            base.ansi.mapIndexed { index, color ->
                preferences.getString("$KEY_CUSTOM_ANSI_PREFIX$index", color).orEmpty().takeIf(::isHexColor) ?: color
            },
        )
    }

    fun saveCustomPalette(preferences: SharedPreferences, colors: PaletteColors) {
        require(isHexColor(colors.foreground) && isHexColor(colors.background) && isHexColor(colors.cursor) && colors.ansi.all(::isHexColor))
        preferences.edit().apply {
            putString(KEY_CUSTOM_FOREGROUND, colors.foreground.uppercase())
            putString(KEY_CUSTOM_BACKGROUND, colors.background.uppercase())
            putString(KEY_CUSTOM_CURSOR, colors.cursor.uppercase())
            colors.ansi.forEachIndexed { index, color -> putString("$KEY_CUSTOM_ANSI_PREFIX$index", color.uppercase()) }
            putString(KEY_PALETTE, CUSTOM_PALETTE)
            apply()
        }
    }

    fun isHexColor(value: String): Boolean = value.matches(Regex("#[0-9a-fA-F]{6}"))

    private fun presetPalette(value: String?): PaletteColors = when (value) {
        "SOLARIZED_DARK" -> PaletteColors("#839496", "#002B36", "#93A1A1", DEFAULT_ANSI_COLORS)
        "AMOLED" -> PaletteColors("#E6E6E6", "#000000", "#FFFFFF", DEFAULT_ANSI_COLORS)
        else -> PaletteColors("#F1F3F5", "#000000", "#FFFFFF", DEFAULT_ANSI_COLORS)
    }

    private const val ANSI_COLOR_COUNT = 16
    private val DEFAULT_ANSI_COLORS = listOf(
        "#000000", "#CD0000", "#00CD00", "#CDCD00", "#6495ED", "#CD00CD", "#00CDCD", "#E5E5E5",
        "#7F7F7F", "#FF0000", "#00FF00", "#FFFF00", "#5C5CFF", "#FF00FF", "#00FFFF", "#FFFFFF",
    )

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
