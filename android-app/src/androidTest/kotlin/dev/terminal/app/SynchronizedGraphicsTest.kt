package dev.terminal.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.view.TerminalRenderer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SynchronizedGraphicsTest {
    private fun emulator() = TerminalEmulator(object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }, 30, 16, 10, 20, 100, TerminalCallbacks {})
    private fun append(e: TerminalEmulator, data: String) { val bytes = data.toByteArray(); e.append(bytes, bytes.size) }
    private fun frame(e: TerminalEmulator, r: TerminalRenderer): IntArray {
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        r.render(e, canvas, 0, -1, -1, -1, -1)
        return IntArray(160000).also { bitmap.getPixels(it, 0, 400, 0, 0, 400, 400); bitmap.recycle() }
    }
    private val red = "\u001b_Ga=T,i=1,f=24,s=2,v=1,c=4,r=3,C=1;/wAA/wAA\u001b\\"
    private val delete = "\u001b_Ga=d,d=I,i=1;\u001b\\"

    @Test fun splitDeleteAndReplacementNeverExposeBlankFrameInsideSynchronizedUpdate() {
        val e = emulator(); val r = TerminalRenderer(20, Typeface.MONOSPACE)
        append(e, "\u001b[?25l$red")
        val before = frame(e, r)
        assertTrue(before.count { it == Color.RED } > 100)
        append(e, "\u001b[?2026h$delete")
        assertTrue(e.graphics.placements.isEmpty()) // Deletion is still immediate in protocol state.
        assertArrayEquals(before, frame(e, r))
        append(e, "\u001b_Ga=T,i=1,f=24,s=2,v=1,c=4,r=3,C=1,m=1;AP8A\u001b\\")
        assertArrayEquals(before, frame(e, r))
        append(e, "\u001b_Gm=0;AP8A\u001b\\")
        assertArrayEquals(before, frame(e, r)) // Complete image, but text/UI frame is not committed yet.
        append(e, "\u001b[?2026l")
        val after = frame(e, r)
        assertEquals(0, after.count { it == Color.RED })
        assertTrue(after.count { it == Color.GREEN } > 100)
        append(e, delete)
        assertEquals(0, frame(e, r).count { it == Color.GREEN }) // A real standalone delete is never postponed.
    }

    @Test fun hardwareCanvasReplaysCompletedFrameAcrossRepeatedInvalidations() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val e = emulator(); val r = TerminalRenderer(20, Typeface.MONOSPACE)
            lateinit var view: android.view.View
            var row = 0
            scenario.onActivity { activity ->
                append(e, (1..70).joinToString("\r\n") { "HISTORY-$it" })
                view = object : android.view.View(activity) {
                    override fun onDraw(canvas: Canvas) {
                        canvas.drawColor(Color.BLACK)
                        r.render(e, canvas, row, -1, -1, -1, -1)
                    }
                }
                activity.setContentView(view)
            }
            fun capture(): IntArray {
                instrumentation.waitForIdleSync()
                Thread.sleep(80)
                val done = java.util.concurrent.CountDownLatch(1)
                var result = -1
                lateinit var bitmap: Bitmap
                scenario.onActivity { activity ->
                    bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    val xy = IntArray(2); view.getLocationInWindow(xy)
                    android.view.PixelCopy.request(activity.window,
                        android.graphics.Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height),
                        bitmap, { result = it; done.countDown() }, android.os.Handler(android.os.Looper.getMainLooper()))
                }
                assertTrue(done.await(3, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(android.view.PixelCopy.SUCCESS, result)
                return IntArray(bitmap.width * bitmap.height).also {
                    bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height); bitmap.recycle()
                }
            }
            val initial = capture()
            assertTrue(initial.any { it != Color.BLACK })
            scenario.onActivity { append(e, "\u001b[?2026h"); row = -20; view.invalidate() }
            val history = capture()
            assertTrue(history.any { it != Color.BLACK })
            repeat(3) {
                scenario.onActivity { view.invalidate() }
                assertArrayEquals(history, capture())
            }
            scenario.onActivity { append(e, "\u001b[?2026l"); view.invalidate() }
            assertArrayEquals(history, capture())
        }
    }

    @Test fun stalledFrameExpiresAndSwitchingEmulatorsNeverShowsAnotherSession() {
        val e = emulator(); val r = TerminalRenderer(20, Typeface.MONOSPACE)
        append(e, red); assertTrue(frame(e, r).contains(Color.RED))
        append(e, "\u001b[?2026h$delete")
        assertTrue(frame(e, r).contains(Color.RED))
        Thread.sleep(1050)
        assertFalse(frame(e, r).contains(Color.RED))
        append(e, red); frame(e, r)
        val other = emulator(); append(other, "\u001b[?2026h")
        assertFalse(frame(other, r).contains(Color.RED))
        append(other, "\u001b[?2026l")
        assertFalse(frame(other, r).contains(Color.RED))
    }
}
