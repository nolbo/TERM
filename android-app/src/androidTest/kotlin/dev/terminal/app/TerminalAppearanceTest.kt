package dev.terminal.app

import android.content.Context
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalGlyphRenderOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TerminalAppearanceTest {
    @Test fun resumeKeepsSessionGeometryAndRendererUntilFontSettingsChange() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("resume-test-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE)
        instrumentation.runOnMainSync {
            var resizes = 0
            val backend = RemoteTerminalBackend(write = {}, resize = { _, _ -> resizes++ })
            try {
                preferences.edit().putInt("font-size-sp", 10)
                    .putString("font-family", "SANS_MONOSPACE")
                    .putBoolean("fixed-width", false)
                    .putBoolean("natural-wide-glyphs", false).commit()
                val view = TerminalView(context, null).apply {
                    setTerminalViewClient(TerminalInputCallbacks())
                    TerminalAppearance.apply(preferences, this, 18)
                    layout(0, 0, 800, 600)
                }
                val session = TerminalSession("", "/", emptyArray(), emptyArray(), 1000, TerminalCallbacks {}, backend)
                view.attachSession(session)
                val output = (1..200).joinToString("\r\n") { "line-$it" }.toByteArray()
                session.emulator.append(output, output.size)
                view.onScreenUpdated()
                view.topRow = -30
                val columns = session.emulator.mColumns
                val history = session.emulator.screen.transcriptText
                val rendererField = TerminalView::class.java.getDeclaredField("mRenderer").apply { isAccessible = true }
                val renderer = rendererField.get(view)
                val typefaceField = renderer.javaClass.getDeclaredField("mTypeface").apply { isAccessible = true }
                assertSame("Retired font selection must use Google Sans Code",
                    BundledFonts.googleSansCode(context), typefaceField.get(renderer))
                val optionsField = renderer.javaClass.getDeclaredField("mGlyphRenderOptions").apply { isAccessible = true }
                val fixedWidthField = renderer.javaClass.getDeclaredField("mForceFixedWidth").apply { isAccessible = true }
                assertTrue("Retired preferences must not disable natural wide glyphs",
                    (optionsField.get(renderer) as TerminalGlyphRenderOptions).naturalWideGlyphs())
                assertTrue("Single-cell text must retain grid alignment", fixedWidthField.getBoolean(renderer))
                resizes = 0
                repeat(5) {
                    TerminalAppearance.apply(preferences, view, 18)
                    view.refresh()
                }
                assertEquals(0, resizes)
                assertSame(renderer, rendererField.get(view))
                assertEquals(columns, session.emulator.mColumns)
                assertEquals(history, session.emulator.screen.transcriptText)
                assertEquals(-30, view.topRow)

                preferences.edit().putString("font-family", "MONOSPACE").commit()
                TerminalAppearance.apply(preferences, view, 18)
                assertSame("Retired font selection must not reload fonts", renderer, rendererField.get(view))

                val customFont = File("/system/fonts/Roboto-Regular.ttf")
                assertTrue("The device must provide a test font", customFont.isFile)
                saveCustomTerminalFonts(preferences,
                    listOf(CustomTerminalFont(Uri.fromFile(customFont).toString(), "Test font")))
                TerminalAppearance.apply(preferences, view, 18)
                assertNotSame(renderer, rendererField.get(view))
                val updated = rendererField.get(view)
                assertNotSame("Custom fonts must keep priority over Google Sans Code",
                    BundledFonts.googleSansCode(context), typefaceField.get(updated))
                TerminalAppearance.apply(preferences, view, 18)
                assertSame(updated, rendererField.get(view))
                TerminalAppearance.apply(preferences, view, 22)
                assertTrue(resizes > 0)
                assertTrue(session.emulator.mColumns < columns)
            } finally {
                backend.close()
                preferences.edit().clear().commit()
            }
        }
    }

    @Test fun customPalettePersistsValidRgbColorsAndRejectsInvalidValues() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("palette-test-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE)
        try {
            val ansi = List(16) { index -> "#%06X".format(0x101010 + index) }
            val colors = TerminalAppearance.PaletteColors("#123456", "#ABCDEF", "#00ff00", ansi)
            TerminalAppearance.saveCustomPalette(preferences, colors)
            assertEquals(TerminalAppearance.CUSTOM_PALETTE, preferences.getString(TerminalAppearance.KEY_PALETTE, null))
            val restored = TerminalAppearance.paletteColors(preferences)
            assertEquals(
                TerminalAppearance.PaletteColors("#123456", "#ABCDEF", "#00FF00", ansi),
                restored,
            )
            ansi.forEachIndexed { index, color -> assertEquals(color, restored.asProperties().getProperty("color$index")) }
            assertTrue(TerminalAppearance.isHexColor("#aBc123"))
            assertFalse(TerminalAppearance.isHexColor("#12345"))
            assertFalse(TerminalAppearance.isHexColor("rgb:ff/ff/ff"))
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
