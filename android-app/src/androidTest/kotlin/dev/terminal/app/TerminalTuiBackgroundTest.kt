package dev.terminal.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.graphics.toArgb
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.view.TerminalRenderer
import dev.terminal.view.TerminalView
import dev.terminal.emulator.TerminalEmulator
import dev.terminal.emulator.TerminalCheckpoint
import dev.terminal.emulator.TerminalOutput
import dev.terminal.emulator.TerminalSession
import dev.terminal.emulator.TextStyle
import dev.terminal.app.ui.TerminalChromeColors
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class TerminalTuiBackgroundTest {
    private fun emulator() = TerminalEmulator(object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }, 80, 24, 10, 20, 1000, TerminalCallbacks {})

    private fun TerminalEmulator.output(text: String) {
        val bytes = text.toByteArray()
        append(bytes, bytes.size)
    }

    @Test fun optInOnlyFollowsAlternateScreenAndRestoresPaletteOnExit() {
        val emulator = emulator()
        val original = emulator.mColors.mCurrentColors.clone()
        emulator.output("\u001b[48;2;18;52;86m\u001b[2J")
        assertEquals(original[TextStyle.COLOR_INDEX_BACKGROUND], terminalChromeBackground(emulator, true))
        emulator.output("\u001b[?1049h\u001b[48;2;18;52;86m\u001b[2J")
        assertEquals(original[TextStyle.COLOR_INDEX_BACKGROUND], terminalChromeBackground(emulator, false))
        assertEquals(0xFF123456.toInt(), terminalChromeBackground(emulator, true))
        assertArrayEquals(original, emulator.mColors.mCurrentColors)
        emulator.output("\u001b[?1049l")
        assertEquals(original[TextStyle.COLOR_INDEX_BACKGROUND], terminalChromeBackground(emulator, true))
    }

    @Test fun declaredBackgroundIgnoresStatusLinesAndUpdatesWithTheme() {
        val emulator = emulator()
        emulator.output("\u001b[?1049h\u001b[48;2;18;52;86m\u001b[2J" +
            "\u001b[H\u001b[41m\u001b[2K\u001b[24;1H\u001b[42m\u001b[2K")
        assertEquals(0xFF123456.toInt(), terminalChromeBackground(emulator, true))
        emulator.output("\u001b[48;2;240;224;208m\u001b[2J")
        assertEquals(0xFFF0E0D0.toInt(), terminalChromeBackground(emulator, true))
    }

    private fun TerminalEmulator.fillRows(first: Int, last: Int, sgr: String) {
        output("\u001b[${sgr}m")
        for (row in first..last) output("\u001b[$row;1H\u001b[2K")
    }

    @Test fun largeFullWidthDiffKeepsDefaultBackgroundWithoutEraseHint() {
        val emulator = emulator()
        val fallback = emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]
        emulator.output("\u001b[?1049h")
        emulator.fillRows(1, 22, "48;2;33;58;43")
        assertEquals(fallback, terminalChromeBackground(emulator, true))
    }

    @Test fun wholeDisplayRepaintUpdatesBackgroundWithoutAnotherErase() {
        val emulator = emulator()
        emulator.output("\u001b[?1049h\u001b[48;2;18;52;86m\u001b[2J")
        emulator.fillRows(1, 24, "48;2;33;58;43")
        assertEquals(0xFF213A2B.toInt(), terminalChromeBackground(emulator, true))
        emulator.output("\u001b[0m\u001b[H\u001b[J")
        emulator.fillRows(1, 24, "48;2;33;58;43")
        assertEquals(0xFF213A2B.toInt(), terminalChromeBackground(emulator, true))
    }

    @Test fun oscDeclarationWinsEvenWhenEqualToDefaultsAndQueryDoesNotDeclare() {
        val emulator = emulator()
        emulator.output("\u001b[?1049h\u001b[48;2;18;52;86m\u001b[2J\u001b]11;?\u0007")
        assertEquals(0xFF123456.toInt(), terminalChromeBackground(emulator, true))
        // Black already is the default palette background; provenance still matters.
        emulator.output("\u001b]11;#000000\u0007")
        emulator.fillRows(1, 24, "48;2;33;58;43")
        assertEquals(0xFF000000.toInt(), terminalChromeBackground(emulator, true))
        emulator.output("\u001b]11;#ABCDEF\u0007")
        assertEquals(0xFFABCDEF.toInt(), terminalChromeBackground(emulator, true))
        emulator.output("\u001b[?5h")
        assertEquals(emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND],
            terminalChromeBackground(emulator, true))
        emulator.output("\u001b[?5l\u001b]111\u0007")
        assertEquals(0xFF213A2B.toInt(), terminalChromeBackground(emulator, true))
    }

    @Test fun cellPaintedThemeRequiresAgreementAcrossRowsAndIncludesScreenEdges() {
        val emulator = emulator()
        emulator.output("\u001b[?1049h")
        emulator.fillRows(1, 24, "48;2;18;52;86")
        emulator.fillRows(1, 1, "41")
        emulator.fillRows(24, 24, "42")
        assertEquals(0xFF123456.toInt(), terminalChromeBackground(emulator, true))
        // A default-colored gutter must be observed even on screens wider than the sample grid.
        emulator.output("\u001b[12;1H\u001b[0m ")
        assertEquals(emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND],
            terminalChromeBackground(emulator, true))
    }

    @Test fun localizedMajorityWithoutBackgroundDeclarationUsesPalette() {
        val emulator = emulator()
        emulator.output("\u001b[?1049h")
        emulator.fillRows(1, 24, "48;2;18;52;86")
        emulator.fillRows(3, 22, "48;2;33;58;43")
        assertEquals(emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND],
            terminalChromeBackground(emulator, true))
    }

    @Test fun backgroundEvidenceSurvivesCheckpointAndResetsForNextApplication() {
        val emulator = emulator()
        emulator.output("\u001b[?1049h\u001b[48;2;18;52;86m\u001b[2J")
        emulator.fillRows(2, 23, "42")
        val bytes = ByteArrayOutputStream().also { TerminalCheckpoint.write(emulator, it) }.toByteArray()
        val mirror = this.emulator()
        mirror.restoreState(TerminalCheckpoint.read(ByteArrayInputStream(bytes)))
        assertEquals(0xFF123456.toInt(), terminalChromeBackground(mirror, true))
        mirror.fillRows(1, 24, "48;2;240;224;208")
        assertEquals(0xFFF0E0D0.toInt(), terminalChromeBackground(mirror, true))
        mirror.output("\u001b[?1049l\u001b[0m\u001b[?1049h")
        assertEquals(mirror.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND],
            terminalChromeBackground(mirror, true))
        // Another session has independent whole-display background evidence.
        val second = this.emulator()
        second.output("\u001b[?1049h\u001b[42m\u001b[2J")
        assertEquals(second.mColors.mCurrentColors[2], terminalChromeBackground(second, true))
        assertEquals(0xFF123456.toInt(), terminalChromeBackground(emulator, true))
    }

    @Test fun chromeAndViewBackgroundStayTogetherAndWaitForSynchronizedOutput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val backend = RemoteTerminalBackend(write = {}, resize = { _, _ -> })
            val session = TerminalSession("", "/", emptyArray(), emptyArray(), 1000,
                TerminalCallbacks {}, backend)
            val view = TerminalView(instrumentation.targetContext, null).apply {
                setTerminalViewClient(TerminalInputCallbacks())
                setTextSize(20)
                layout(0, 0, 1500, 1000)
            }
            val bitmap = Bitmap.createBitmap(1500, 1000, Bitmap.Config.ARGB_8888)
            var background = 0
            var chrome: TerminalChromeColors? = null
            var followTuiBackground = true
            val transition = TerminalPaletteTransition(followTuiBackground = { followTuiBackground }) {
                background = it.background.toArgb()
                chrome = it
            }
            fun assertBackground(expected: Int) {
                assertEquals(expected, background)
                assertEquals(background, (view.background as ColorDrawable).color)
                view.draw(Canvas(bitmap))
                assertEquals(background, bitmap.getPixel(1499, 999))
            }
            try {
                view.attachSession(session)
                session.updateSize(80, 24, 10, 20)
                val emulator = requireNotNull(session.emulator)
                emulator.output("\u001b[?1049h\u001b[48;2;18;52;86m\u001b[2J")
                transition.show(view, animate = false)
                emulator.fillRows(1, 23, "42")
                transition.update(view)
                assertBackground(0xFF123456.toInt())
                val originalAccent = requireNotNull(chrome).accent
                assertNotNull(requireNotNull(chrome).colorScheme)
                emulator.output("\u001b[?2026h")
                emulator.fillRows(1, 24, "48;2;171;205;239")
                transition.update(view)
                assertBackground(0xFF123456.toInt())
                assertEquals(originalAccent, requireNotNull(chrome).accent)
                emulator.output("\u001b[?2026l")
                transition.update(view)
                assertBackground(0xFFABCDEF.toInt())
                assertNotEquals(originalAccent, requireNotNull(chrome).accent)
                assertEquals(background, requireNotNull(requireNotNull(chrome).colorScheme).surface.toArgb())
                emulator.fillRows(1, 24, "0")
                transition.update(view)
                val fallback = emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]
                assertBackground(fallback)
                emulator.fillRows(1, 24, "48;2;171;205;239")
                transition.update(view)
                assertBackground(0xFFABCDEF.toInt())
                followTuiBackground = false
                transition.update(view)
                assertBackground(fallback)
                assertNull(requireNotNull(chrome).colorScheme)
                followTuiBackground = true
                transition.update(view)
                assertNotNull(requireNotNull(chrome).colorScheme)
                emulator.output("\u001b[?1049l")
                transition.update(view)
                assertBackground(fallback)
                assertNull(requireNotNull(chrome).colorScheme)
            } finally {
                transition.cancel()
                bitmap.recycle()
                backend.close()
            }
        }
    }

    @Test fun indexedOscAndInverseColorsMatchRenderer() {
        val emulator = emulator()
        emulator.output("\u001b[?1049h\u001b[44m\u001b[2J")
        assertEquals(emulator.mColors.mCurrentColors[4], terminalChromeBackground(emulator, true))
        emulator.output("\u001b[48;5;123m\u001b[2J")
        assertEquals(emulator.mColors.mCurrentColors[123], terminalChromeBackground(emulator, true))
        emulator.output("\u001b]4;123;#123456\u0007")
        assertEquals(0xFF123456.toInt(), terminalChromeBackground(emulator, true))
        emulator.output("\u001b[0;31;7;1m\u001b[2J")
        assertEquals(emulator.mColors.mCurrentColors[9], terminalChromeBackground(emulator, true))
        emulator.output("\u001b[?5h")
        assertEquals(emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND], terminalChromeBackground(emulator, true))
        emulator.output("\u001b[?5l\u001b[0m\u001b]11;#ABCDEF\u0007\u001b[2J")
        assertEquals(0xFFABCDEF.toInt(), terminalChromeBackground(emulator, true))
    }

    @Test fun canvasFillFollowsTuiWithoutRecoloringCellsAndRestoresWhenDisabled() {
        val emulator = emulator()
        val palette = emulator.mColors.mCurrentColors.clone()
        val renderer = TerminalRenderer(20, Typeface.MONOSPACE)
        val spacing = TerminalRenderer::class.java.getDeclaredField("mFontLineSpacing")
            .apply { isAccessible = true }.getInt(renderer)
        val top = TerminalRenderer::class.java.getDeclaredField("mFontLineSpacingAndAscent")
            .apply { isAccessible = true }.getInt(renderer)
        fun render(): Bitmap = Bitmap.createBitmap(1500, 1000, Bitmap.Config.ARGB_8888).also {
            renderer.render(emulator, Canvas(it), 0, -1, -1, -1, -1)
        }
        emulator.output("\u001b[?1049h\u001b[?25l\u001b[48;2;18;52;86m\u001b[2J" +
            "\u001b[2;1H\u001b[0m\u001b[2K")
        val tuiColor = terminalChromeBackground(emulator, true)
        renderer.setCanvasBackgroundOverride(tuiColor)
        render().also {
            assertEquals(tuiColor, it.getPixel(0, 0))
            assertEquals(tuiColor, it.getPixel(1499, 100))
            assertEquals(tuiColor, it.getPixel(0, 999))
            assertEquals(tuiColor, it.getPixel(2, top + spacing / 2))
            assertEquals(palette[TextStyle.COLOR_INDEX_BACKGROUND], it.getPixel(2, top + spacing + spacing / 2))
            it.recycle()
        }
        emulator.fillRows(2, 23, "42")
        renderer.setCanvasBackgroundOverride(terminalChromeBackground(emulator, true))
        render().also {
            assertEquals(tuiColor, it.getPixel(1499, 100))
            assertEquals(tuiColor, it.getPixel(0, 999))
            assertEquals(palette[2], it.getPixel(2, top + spacing + spacing / 2))
            it.recycle()
        }
        // Reverse video must not repaint the canvas fill after it has been overridden.
        emulator.output("\u001b[?5h")
        renderer.setCanvasBackgroundOverride(terminalChromeBackground(emulator, true))
        render().also {
            assertEquals(palette[TextStyle.COLOR_INDEX_FOREGROUND], it.getPixel(1499, 100))
            it.recycle()
        }
        emulator.output("\u001b[?5l")
        renderer.setCanvasBackgroundOverride(null)
        render().also {
            assertEquals(palette[TextStyle.COLOR_INDEX_BACKGROUND], it.getPixel(1499, 100))
            assertEquals(tuiColor, it.getPixel(2, top + spacing / 2))
            it.recycle()
        }
        assertArrayEquals(palette, emulator.mColors.mCurrentColors)
    }
}
