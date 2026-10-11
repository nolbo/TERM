package dev.terminal.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.view.TerminalFontFamily
import dev.terminal.view.TerminalView
import java.io.File
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomFontPersistenceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun importedRegularAndItalicFontsSurviveSourceLossAndReload() {
        val regular = sourceFont()
        val italic = File.createTempFile("custom-italic-", ".ttf", context.cacheDir).apply {
            context.resources.openRawResource(R.font.google_sans_italic).use { input ->
                outputStream().use { input.copyTo(it) }
            }
        }
        val fonts = listOf(
            CustomTerminalFont(Uri.fromFile(regular).toString(), "Regular"),
            CustomTerminalFont(Uri.fromFile(italic).toString(), "Italic", CustomTerminalFontStyle.ITALIC),
        )
        val preferencesName = "font-persistence-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        try {
            val store = CustomTerminalFontStore(context)
            fonts.forEach { store.import(Uri.parse(it.uri)) }
            saveCustomTerminalFonts(preferences, fonts)
            val before = TerminalAppearance.fontFamily(preferences, context)
            val regularPixels = pixels(face(before, false))
            val italicPixels = pixels(face(before, true))
            assertFalse(regularPixels.contentEquals(pixels(BundledFonts.googleSansCode(context))))
            assertFalse(italicPixels.contentEquals(pixels(Typeface.create(BundledFonts.googleSansCode(context), Typeface.ITALIC))))
            assertTrue(regular.delete())
            assertTrue(italic.delete())

            val restoredPreferences = context.createPackageContext(context.packageName, 0)
                .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            val after = TerminalAppearance.fontFamily(restoredPreferences, context)
            assertEquals(fonts, customTerminalFonts(restoredPreferences))
            assertArrayEquals(regularPixels, pixels(face(after, false)))
            assertArrayEquals(italicPixels, pixels(face(after, true)))
        } finally {
            fonts.forEach { CustomTerminalFontStore(context).remove(it.uri) }
            regular.delete()
            italic.delete()
            preferences.edit().clear().commit()
        }
    }

    @Test fun legacyUriEntriesAreImportedWithoutChangingOrderOrStyle() {
        val source = sourceFont()
        val uri = Uri.fromFile(source).toString()
        val fonts = listOf(
            CustomTerminalFont(uri, "Legacy italic", CustomTerminalFontStyle.ITALIC),
            CustomTerminalFont(uri, "Legacy regular"),
        )
        val preferences = context.getSharedPreferences("font-migration-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        try {
            saveCustomTerminalFonts(preferences, fonts)
            val before = TerminalAppearance.fontFamily(preferences, context)
            val expected = pixels(before.primaryTypeface)
            assertNotSame(BundledFonts.googleSansCode(context), before.primaryTypeface)
            assertTrue(source.delete())
            val after = TerminalAppearance.fontFamily(preferences, context)
            assertEquals(fonts, customTerminalFonts(preferences))
            assertArrayEquals(expected, pixels(after.primaryTypeface))
        } finally {
            CustomTerminalFontStore(context).remove(uri)
            source.delete()
            preferences.edit().clear().commit()
        }
    }

    @Test fun failedReimportPreservesThePreviouslyStoredFont() {
        val source = sourceFont()
        val uri = Uri.fromFile(source)
        val store = CustomTerminalFontStore(context)
        try {
            val stored = store.import(uri)
            val expected = stored.readBytes()
            source.writeText("not a font")
            assertTrue(runCatching { store.import(uri) }.isFailure)
            assertArrayEquals(expected, stored.readBytes())
            assertTrue(source.delete())
            assertTrue(CustomTerminalFontStore(context).load(uri.toString()).buffer.remaining() > 0)
        } finally {
            store.remove(uri.toString())
            source.delete()
        }
    }

    @Test fun unavailableFontIsRetriedOnTheSameViewWithoutChangingPreferences() {
        val source = File(context.cacheDir, "recover-font-${UUID.randomUUID()}.ttf")
        val font = CustomTerminalFont(Uri.fromFile(source).toString(), "Temporarily unavailable")
        val preferences = context.getSharedPreferences("font-retry-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        try {
            saveCustomTerminalFonts(preferences, listOf(font))
            instrumentation.runOnMainSync {
                val view = TerminalView(context, null)
                val rendererField = TerminalView::class.java.getDeclaredField("mRenderer").apply { isAccessible = true }
                TerminalAppearance.apply(preferences, view)
                val fallbackRenderer = rendererField.get(view)
                val typefaceField = fallbackRenderer.javaClass.getDeclaredField("mTypeface").apply { isAccessible = true }
                assertSame(BundledFonts.googleSansCode(context), typefaceField.get(fallbackRenderer))

                File("/system/fonts/Roboto-Regular.ttf").copyTo(source)
                TerminalAppearance.apply(preferences, view)
                val recoveredRenderer = rendererField.get(view)
                assertNotSame(fallbackRenderer, recoveredRenderer)
                assertNotSame(BundledFonts.googleSansCode(context), typefaceField.get(recoveredRenderer))
                assertEquals(listOf(font), customTerminalFonts(preferences))
                TerminalAppearance.apply(preferences, view)
                assertSame("Successful fonts must still reuse the renderer", recoveredRenderer, rendererField.get(view))
            }
        } finally {
            CustomTerminalFontStore(context).remove(font.uri)
            source.delete()
            preferences.edit().clear().commit()
        }
    }

    private fun sourceFont(): File = File.createTempFile("custom-font-", ".ttf", context.cacheDir).apply {
        File("/system/fonts/Roboto-Regular.ttf").copyTo(this, overwrite = true)
    }

    private fun face(family: TerminalFontFamily, italic: Boolean): Typeface {
        val text = "MW09 abc".toCharArray()
        return family.getTypefaceForText(text, 0, text.size, false, italic)
    }

    private fun pixels(typeface: Typeface): IntArray {
        val bitmap = Bitmap.createBitmap(400, 64, Bitmap.Config.ARGB_8888)
        return try {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; textSize = 32f }
            Canvas(bitmap).drawText("MW09 abc", 8f, 42f, paint)
            IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
        } finally {
            bitmap.recycle()
        }
    }
}
