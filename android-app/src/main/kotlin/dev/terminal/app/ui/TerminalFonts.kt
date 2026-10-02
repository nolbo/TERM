package dev.terminal.app.ui

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.terminal.app.R
import dev.terminal.app.BundledFonts

/** DroidTheme supplies the bundled Google Sans Code face to all code-oriented UI. */
internal val LocalTerminalMonospaceFontFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }

internal val GoogleSansFontFamily = FontFamily((400..700 step 100).flatMap { weight ->
    listOf(FontStyle.Normal, FontStyle.Italic).map { GoogleSansUiFont(FontWeight(weight), it) }
})

// Expose individual weights to Compose while retaining the native glyph fallback chain.
private class GoogleSansUiFont(override val weight: FontWeight, override val style: FontStyle) : AndroidFont(
    loadingStrategy = FontLoadingStrategy.Blocking,
    typefaceLoader = GoogleSansTypefaceLoader,
    variationSettings = FontVariation.Settings(),
)

private object GoogleSansTypefaceLoader : AndroidFont.TypefaceLoader {
    override fun loadBlocking(context: Context, font: AndroidFont): Typeface =
        Typeface.create(BundledFonts.googleSans(context), font.weight.weight, font.style == FontStyle.Italic)

    override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface = loadBlocking(context, font)
}

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
