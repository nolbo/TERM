package dev.terminal.app

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle

/** Bundled platform faces; the cached terminal family retains no Activity. */
internal object BundledFonts {
    fun googleSans(context: Context): Typeface = context.resources.getFont(R.font.google_sans)

    private var codeTypeface: Typeface? = null

    @Synchronized
    fun googleSansCode(context: Context): Typeface {
        codeTypeface?.let { return it }
        val fonts = (300..800 step 100).flatMap { weight ->
            listOf(false, true).map { italic ->
                Font.Builder(context.resources,
                    if (italic) R.font.google_sans_code_italic else R.font.google_sans_code_regular)
                    .setWeight(weight)
                    .setSlant(if (italic) FontStyle.FONT_SLANT_ITALIC else FontStyle.FONT_SLANT_UPRIGHT)
                    .setFontVariationSettings("'wght' $weight")
                    .build()
            }
        }
        val family = FontFamily.Builder(fonts.first()).apply { fonts.drop(1).forEach(::addFont) }.build()
        return Typeface.CustomFallbackBuilder(family)
            .setStyle(FontStyle(400, FontStyle.FONT_SLANT_UPRIGHT))
            .setSystemFallback("monospace")
            .build().also { codeTypeface = it }
    }
}
