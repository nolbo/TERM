package dev.terminal.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.net.Uri
import com.termux.view.TerminalFontFamily
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomFontApplicationTest {
    @Test fun palettePreviewUsesRegularFontPriorityAndSkipsUnavailableFonts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("palette-font-test-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE)
        val file = listOf(File("/system/fonts/DroidSansMono.ttf"), File("/system/fonts/Roboto-Regular.ttf"))
            .first(File::isFile)
        val custom = CustomTerminalFont(Uri.fromFile(file).toString(), "Preview mono")
        val missing = CustomTerminalFont(Uri.fromFile(File(context.cacheDir, "missing-${java.util.UUID.randomUUID()}.ttf")).toString(), "Missing")
        try {
            val fallback = BundledFonts.googleSansCode(context)
            assertSame(fallback, palettePreviewTypeface(preferences, context))
            saveCustomTerminalFonts(preferences, listOf(missing))
            assertSame(fallback, palettePreviewTypeface(preferences, context))
            saveCustomTerminalFonts(preferences, listOf(custom.copy(style = CustomTerminalFontStyle.ITALIC)))
            assertSame("A regular sample must not use italic-only custom fonts", fallback,
                palettePreviewTypeface(preferences, context))

            saveCustomTerminalFonts(preferences, listOf(missing, custom,
                CustomTerminalFont("android.resource://${context.packageName}/${R.font.google_sans_code_regular}", "Later font")))
            fun pixels(typeface: Typeface): IntArray {
                val bitmap = Bitmap.createBitmap(400, 64, Bitmap.Config.ARGB_8888)
                return try {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; textSize = 32f }
                    Canvas(bitmap).drawText("$ echo TERM", 8f, 42f, paint)
                    IntArray(bitmap.width * bitmap.height).also {
                        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    }
                } finally { bitmap.recycle() }
            }
            assertArrayEquals("Preview must draw the first usable regular custom font",
                pixels(Typeface.createFromFile(file)), pixels(palettePreviewTypeface(preferences, context)))
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test fun firstCustomFontProvidesAsciiGlyphsAndPrimaryMetrics() {
        val file = listOf(
            File("/system/fonts/Roboto-Regular.ttf"),
            File("/system/fonts/DroidSansMono.ttf"),
            File("/system/fonts/ComingSoon.ttf"),
        ).first(File::isFile)
        val font = Font.Builder(file).build()
        val ranges = OpenTypeCmap.codePointRanges(font.buffer, font.ttcIndex)
        assertTrue("System test font must provide Basic Latin", ranges.covers('A'.code))

        val customTypeface = Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build())
            .setSystemFallback("monospace")
            .build()
        val family = TerminalFontFamily(
            listOf(TerminalFontFamily.FontFace(customTypeface, ranges)),
            Typeface.MONOSPACE,
        )

        assertEquals(customTypeface, family.primaryTypeface)
        assertEquals(
            customTypeface,
            family.getTypefaceForText(charArrayOf('A'), 0, 1, false, false),
        )
    }

    private fun IntArray.covers(codePoint: Int): Boolean {
        for (index in indices step 2) {
            if (codePoint in this[index]..this[index + 1]) return true
        }
        return false
    }
}
