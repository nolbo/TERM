package dev.terminal.app

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle

/** Bundled platform faces; the cached terminal family retains no Activity. */
internal object BundledFonts {
    private var sansTypeface: Typeface? = null

    @Synchronized
    fun googleSans(context: Context): Typeface {
        sansTypeface?.let { return it }
        val family = variableFamily(context, R.font.google_sans_regular, R.font.google_sans_italic, 400..700 step 100)
        val cjk = variableFamily(context, R.font.noto_sans_cjk_kr, null, 100..900 step 100)
        return Typeface.CustomFallbackBuilder(family)
            .addCustomFallback(cjk)
            .setStyle(FontStyle(400, FontStyle.FONT_SLANT_UPRIGHT))
            .setSystemFallback("sans-serif")
            .build().also { sansTypeface = it }
    }

    private fun variableFamily(context: Context, regular: Int, italic: Int?, weights: IntProgression): FontFamily {
        val regularFont = Font.Builder(context.resources, regular).build()
        val italicFont = italic?.let { Font.Builder(context.resources, it).build() }
        val fonts = weights.flatMap { weight ->
            listOfNotNull(regularFont, italicFont).mapIndexed { index, font ->
                Font.Builder(font)
                    .setWeight(weight)
                    .setSlant(if (index == 1) FontStyle.FONT_SLANT_ITALIC else FontStyle.FONT_SLANT_UPRIGHT)
                    .setFontVariationSettings("'wght' $weight")
                    .build()
            }
        }
        return FontFamily.Builder(fonts.first()).apply { fonts.drop(1).forEach(::addFont) }.build()
    }

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
