@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.view.TerminalView
import dev.terminal.app.ui.DroidTheme
import dev.terminal.app.ui.LocalTerminalMonospaceFontFamily
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class DefaultFontsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun glyphPixels(face: Typeface): IntArray {
        val bitmap = Bitmap.createBitmap(600, 140, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = 100f }
        Canvas(bitmap).drawText("a0{}W", 0f, 110f, paint)
        return IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            bitmap.recycle()
        }
    }

    private fun assertSameFace(expected: Typeface, actual: Typeface) {
        // Variation instances may retain the source file's weight metadata; compare actual glyphs.
        assertArrayEquals(glyphPixels(expected), glyphPixels(actual))
    }

    @Test fun bundledFamiliesSupportRealStylesFixedWidthAndKoreanFallback() {
        val context = instrumentation.targetContext
        val sans = BundledFonts.googleSans(context)
        val code = BundledFonts.googleSansCode(context)
        for ((family, weights) in listOf(sans to (400..700 step 100), code to (300..800 step 100))) {
            for (weight in weights) {
                for (italic in listOf(false, true)) {
                    val face = Typeface.create(family, weight, italic)
                    assertEquals(weight, face.weight)
                    assertEquals(italic, face.isItalic)
                    val paint = Paint().apply { typeface = face; textSize = 32f }
                    assertTrue("Korean fallback missing", paint.hasGlyph("한"))
                    if (family === code) {
                        val width = paint.measureText("i")
                        for (glyph in listOf("W", "0", "@", " ")) {
                            assertEquals("Code font must retain equal cell advances", width, paint.measureText(glyph), 0.01f)
                        }
                    }
                }
            }
        }
        // Ensure the bundled faces are not silently replaced with platform defaults.
        assertFalse(glyphPixels(sans).contentEquals(glyphPixels(Typeface.DEFAULT)))
        assertFalse(glyphPixels(code).contentEquals(glyphPixels(Typeface.MONOSPACE)))
    }

    @Test fun composeViewAndTerminalDefaultsUseBundledFamilies() {
        val context = instrumentation.targetContext
        val resolvedFaces = AtomicReference<List<Pair<Typeface, Typeface>>?>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DroidTheme {
                        val sans = BundledFonts.googleSans(activity)
                        val resolver = LocalFontFamilyResolver.current
                        val typography = MaterialTheme.typography
                        val styles = listOf(
                            typography.displayLarge, typography.headlineMedium,
                            typography.titleLarge, typography.bodyLarge, typography.labelSmall,
                            typography.displayLargeEmphasized, typography.headlineMediumEmphasized,
                            typography.titleLargeEmphasized, typography.bodyLargeEmphasized,
                            typography.labelSmallEmphasized,
                        )
                        val faces = styles.map { style ->
                            val weight = style.fontWeight ?: FontWeight.Normal
                            val italic = style.fontStyle == FontStyle.Italic
                            Typeface.create(sans, weight.weight, italic) to
                                (resolver.resolve(style.fontFamily, weight, style.fontStyle ?: FontStyle.Normal).value as Typeface)
                        } + (BundledFonts.googleSansCode(activity) to
                            (resolver.resolve(LocalTerminalMonospaceFontFamily.current).value as Typeface))
                        Text("Google Sans · 설정 · 한글")
                        SideEffect { resolvedFaces.set(faces) }
                    }
                }
            }
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (resolvedFaces.get() == null && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(50)
            }
            assertNotNull("Theme fixture must compose", resolvedFaces.get())
            resolvedFaces.get()!!.forEach { (expected, actual) -> assertSameFace(expected, actual) }
        }
        val preferences = context.getSharedPreferences("default-font-test-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE)
        try {
            instrumentation.runOnMainSync {
                val themed = ContextThemeWrapper(context, R.style.Theme_DroidTerminal)
                assertSameFace(BundledFonts.googleSans(context), TextView(themed).typeface)
                val view = TerminalView(context, null).apply { setTerminalViewClient(TerminalInputCallbacks()) }
                val rendererField = TerminalView::class.java.getDeclaredField("mRenderer").apply { isAccessible = true }
                fun typeface(): Typeface {
                    val renderer = rendererField.get(view)
                    return renderer.javaClass.getDeclaredField("mTypeface").apply { isAccessible = true }.get(renderer) as Typeface
                }
                TerminalAppearance.apply(preferences, view)
                assertSame(BundledFonts.googleSansCode(context), typeface())
                preferences.edit().putString("font-family", "MONOSPACE").commit()
                TerminalAppearance.apply(preferences, view)
                assertSame(BundledFonts.googleSansCode(context), typeface())
                preferences.edit().putString("font-family", "SANS_MONOSPACE").commit()
                TerminalAppearance.apply(preferences, view)
                assertEquals(Typeface.create("sans-serif-monospace", Typeface.NORMAL), typeface())
            }
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
