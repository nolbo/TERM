package dev.terminal.app

import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.termux.view.TerminalFontFamily
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomFontApplicationTest {
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
