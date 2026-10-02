package dev.terminal.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.terminal.app.R

/** DroidTheme supplies the bundled Google Sans Code face to all code-oriented UI. */
internal val LocalTerminalMonospaceFontFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }

internal val GoogleSansFontFamily = variableFontFamily(
    R.font.google_sans_regular, R.font.google_sans_italic, 400..700 step 100,
)

internal val GoogleSansCodeFontFamily = variableFontFamily(
    R.font.google_sans_code_regular, R.font.google_sans_code_italic, 300..800 step 100,
)

// Register each weight explicitly: a native Typeface wrapper only synthesizes normal/bold.
private fun variableFontFamily(regular: Int, italic: Int, weights: IntProgression): FontFamily =
    FontFamily(weights.flatMap { weight ->
        listOf(FontStyle.Normal, FontStyle.Italic).map { style ->
            Font(
                resId = if (style == FontStyle.Italic) italic else regular,
                weight = FontWeight(weight),
                style = style,
                variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
            )
        }
    })
