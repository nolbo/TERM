@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.text.TextRunShaper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.view.TerminalView
import dev.terminal.app.ui.DroidTheme
import dev.terminal.app.ui.GoogleSansCodeFontFamily
import dev.terminal.app.ui.GoogleSansFontFamily
import dev.terminal.app.ui.LocalTerminalMonospaceFontFamily
import dev.terminal.app.ui.SettingsTextRow
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

    private val pretendardFont by lazy {
        Font.Builder(instrumentation.targetContext.resources, R.font.pretendard_variable).build()
    }

    private fun assertPretendardFallback(face: Typeface) {
        fun header(font: Font) = ByteArray(64).also { font.buffer.duplicate().apply { position(0) }.get(it) }
        val text = "한글かなカナ"
        val paint = Paint().apply { typeface = face; textSize = 32f }
        val glyphs = TextRunShaper.shapeTextRun(text, 0, text.length, 0, text.length, 0f, 0f, false, paint)
        assertTrue(glyphs.glyphCount() > 0)
        for (index in 0 until glyphs.glyphCount()) {
            assertNotEquals("Korean and kana glyphs must be present", 0, glyphs.getGlyphId(index))
            val actual = glyphs.getFont(index)
            assertEquals("Must use bundled Pretendard rather than an OEM fallback", pretendardFont.buffer.capacity(), actual.buffer.capacity())
            assertArrayEquals(header(pretendardFont), header(actual))
            assertEquals("Pretendard must retain the requested UI weight", face.weight, actual.style.weight)
        }
    }

    private fun assertUnsupportedHanUsesSystemFallback(face: Typeface) {
        val text = "漢"
        val paint = Paint().apply { typeface = face; textSize = 32f }
        assertTrue("System fallback must retain unsupported Han glyphs", paint.hasGlyph(text))
        val glyphs = TextRunShaper.shapeTextRun(text, 0, text.length, 0, text.length, 0f, 0f, false, paint)
        assertTrue(glyphs.glyphCount() > 0)
        for (index in 0 until glyphs.glyphCount()) {
            assertNotEquals(0, glyphs.getGlyphId(index))
            assertNotEquals("Unsupported Han must reach the system fallback", pretendardFont.buffer.capacity(), glyphs.getFont(index).buffer.capacity())
        }
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
                    if (family === sans) {
                        assertPretendardFallback(face)
                        assertUnsupportedHanUsesSystemFallback(face)
                    }
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
                            typography.displayLarge, typography.displayMedium, typography.displaySmall,
                            typography.headlineLarge, typography.headlineMedium, typography.headlineSmall,
                            typography.titleLarge, typography.titleMedium, typography.titleSmall,
                            typography.bodyLarge, typography.bodyMedium, typography.bodySmall,
                            typography.labelLarge, typography.labelMedium, typography.labelSmall,
                            typography.displayLargeEmphasized, typography.displayMediumEmphasized, typography.displaySmallEmphasized,
                            typography.headlineLargeEmphasized, typography.headlineMediumEmphasized, typography.headlineSmallEmphasized,
                            typography.titleLargeEmphasized, typography.titleMediumEmphasized, typography.titleSmallEmphasized,
                            typography.bodyLargeEmphasized, typography.bodyMediumEmphasized, typography.bodySmallEmphasized,
                            typography.labelLargeEmphasized, typography.labelMediumEmphasized, typography.labelSmallEmphasized,
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
            resolvedFaces.get()!!.dropLast(1).forEach { (_, actual) ->
                assertPretendardFallback(actual)
                assertUnsupportedHanUsesSystemFallback(actual)
            }
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
                assertSame(BundledFonts.googleSansCode(context), typeface())
            }
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test fun settingsRowsAndDialogInputsRetainThemedFontsAndExplicitOverrides() {
        val context = instrumentation.targetContext
        val uiValue = "Google Sans field · 설정 · a0{}W"
        val codeValue = "code@example:1234"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    DroidTheme {
                        // Color-only themes, including terminal dialogs, must inherit the UI family.
                        MaterialTheme(colorScheme = lightColorScheme()) {
                            Column {
                                SettingsTextRow(R.string.profile_name, uiValue, hint = R.string.profile_not_set) {}
                                SettingsTextRow(R.string.workspace_directory, codeValue,
                                    fontFamily = GoogleSansCodeFontFamily) {}
                            }
                        }
                    }
                }
            }
            assertRenderedFamily(uiValue, GoogleSansFontFamily)
            assertRenderedFamily(codeValue, GoogleSansCodeFontFamily)
            clickText(uiValue)
            assertRenderedFamily(uiValue, GoogleSansFontFamily, editable = true)
            assertRenderedFamily(context.getString(R.string.profile_name), GoogleSansFontFamily)
            assertRenderedFamily(context.getString(R.string.profile_not_set), GoogleSansFontFamily)
            assertRenderedFamily(context.getString(R.string.save), GoogleSansFontFamily)
            assertRenderedFamily(context.getString(R.string.cancel), GoogleSansFontFamily)
            clickText(context.getString(R.string.cancel))
            clickText(codeValue)
            assertRenderedFamily(codeValue, GoogleSansCodeFontFamily, editable = true)
            assertRenderedFamily(context.getString(R.string.workspace_directory), GoogleSansFontFamily)
        }
    }

    private fun visitSemantics(visitor: (SemanticsNode) -> Unit) {
        fun visitNode(node: SemanticsNode) {
            visitor(node)
            node.children.forEach(::visitNode)
        }
        fun visitView(view: View) {
            if (view is ViewRootForTest) visitNode(view.semanticsOwner.unmergedRootSemanticsNode)
            else if (view is ViewGroup) for (index in 0 until view.childCount) visitView(view.getChildAt(index))
        }
        // Dialogs have their own Compose root, separate from the Activity's content.
        WindowInspector.getGlobalWindowViews().forEach(::visitView)
    }

    private fun clickText(text: String) {
        instrumentation.runOnMainSync {
            var clicked = false
            visitSemantics { node ->
                if (!clicked && node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true) {
                    var target: SemanticsNode? = node
                    while (target != null && !clicked) {
                        clicked = target.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true
                        target = target.parent
                    }
                }
            }
            assertTrue("Clickable text must exist: $text", clicked)
        }
        instrumentation.waitForIdleSync()
    }

    private fun assertRenderedFamily(text: String, family: FontFamily, editable: Boolean = false) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        var layouts = emptyList<TextLayoutResult>()
        while (layouts.isEmpty() && SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync {
                val results = mutableListOf<TextLayoutResult>()
                visitSemantics { node ->
                    if (editable == (node.config.getOrNull(SemanticsProperties.EditableText) != null)) {
                        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
                    }
                }
                layouts = results.filter { it.layoutInput.text.text == text }
            }
            if (layouts.isEmpty()) SystemClock.sleep(50)
        }
        assertTrue("Rendered ${if (editable) "input" else "text"} must exist: $text", layouts.isNotEmpty())
        layouts.forEach { assertEquals("Rendered font for $text", family, it.layoutInput.style.fontFamily) }
    }
}
