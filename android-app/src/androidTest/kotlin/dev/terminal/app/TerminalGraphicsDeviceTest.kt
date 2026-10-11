package dev.terminal.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.emulator.TerminalEmulator
import dev.terminal.emulator.TerminalOutput
import dev.terminal.view.TerminalRenderer
import dev.terminal.protocol.SessionEvent
import dev.terminal.runtime.native.ApkNativeExecutableLoader
import dev.terminal.runtime.rootless.RootlessDebianLayout
import dev.terminal.runtime.rootless.RootlessSessionController
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalGraphicsDeviceTest {
    private class Output : TerminalOutput() {
        val replies = ByteArrayOutputStream()
        override fun write(data: ByteArray, offset: Int, count: Int) { replies.write(data, offset, count) }
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    private fun emulator(output: Output) = TerminalEmulator(output, 40, 20, 12, 24, 100, null)
    private fun append(terminal: TerminalEmulator, data: String) {
        val bytes = data.toByteArray()
        terminal.append(bytes, bytes.size)
    }

    @Test fun pngDecodesAndBothProtocolsRenderOnAndroidCanvas() {
        val source = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        source.setPixel(0, 0, Color.RED)
        source.setPixel(1, 0, Color.GREEN)
        val encoded = ByteArrayOutputStream()
        source.compress(Bitmap.CompressFormat.PNG, 100, encoded)
        source.recycle()
        val output = Output()
        val terminal = emulator(output)
        append(terminal, "\u001b_Ga=T,i=1,f=100,c=8,r=3,C=1;${Base64.getEncoder().encodeToString(encoded.toByteArray())}\u001b\\")
        assertEquals("\u001b_Gi=1;OK\u001b\\", output.replies.toString("UTF-8"))
        assertArrayEquals(intArrayOf(Color.RED, Color.GREEN), terminal.graphics.placements[0].image.pixels)
        append(terminal, "\u001b[6;1H\u001bP0;1q#1;2;0;0;100#1!60~\u001b\\")
        val canvasBitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        canvasBitmap.eraseColor(Color.BLACK)
        TerminalRenderer(20, Typeface.MONOSPACE).render(terminal, Canvas(canvasBitmap), 0, -1, -1, -1, -1)
        val pixels = IntArray(600 * 600)
        canvasBitmap.getPixels(pixels, 0, 600, 0, 0, 600, 600)
        assertTrue("kitty red pixels", pixels.count { it == Color.RED } > 100)
        assertTrue("kitty green pixels", pixels.count { it == Color.GREEN } > 100)
        assertTrue("sixel blue pixels", pixels.count { it == Color.BLUE } > 100)
        canvasBitmap.recycle()
    }

    /** Uses a separate test PTY; never attaches to, writes to, or terminates user sessions. */
    @Test fun rootlessPtyGraphicsReplayAndQueryRoundTrip() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val layout = RootlessDebianLayout(File(context.filesDir, "rootless/debian"))
        assumeTrue("Requires provisioned Rootless Debian; does not install a rootfs", layout.validateInstalledRootfs().isSuccess)
        val loader = ApkNativeExecutableLoader(context)
        val controller = RootlessSessionController(layout, loader.resolvePackagedExecutable("proot").getOrThrow(),
            loader.resolvePackagedExecutable("proot-loader").getOrThrow())
        val events = Channel<SessionEvent>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { controller.events.collect { events.send(it) } }
        try {
            val session = controller.create("/bin/sh", "/tmp", mapOf("PS1" to ""), 20u, 40u).getOrThrow()
            val output = Output()
            val terminal = emulator(output)
            // Disable echo, then query. dd reads exactly the terminal's response from the PTY.
            val expectedReply = "\u001b_Gi=7;OK\u001b\\"
            val script = "stty -echo -icanon min 1 time 0; printf '\\033_Ga=q,i=7,f=24,s=1,v=1;/wAA\\033\\\\'; " +
                "answer=\$(dd bs=1 count=${expectedReply.toByteArray().size} 2>/dev/null); " +
                "case \"\$answer\" in *';OK'*) printf 'QUERY_PASS\\n';; *) printf 'QUERY_FAIL\\n';; esac; " +
                "printf '\\033_Ga=T,i=8,f=24,s=1,v=1,c=4,r=2,C=1;/wAA\\033\\\\'; " +
                "printf '\\033[5;1H\\033P0;1q#1;2;0;100;0#1!24~'; " +
                "yes '\$' | head -n 20000; printf '\\033\\\\'; printf '\\nGRAPHICS_DONE\\n'\n"
            controller.write(session.id, script.toByteArray()).getOrThrow()
            withTimeout(20_000) {
                while (true) {
                    val event = events.receive()
                    if (event is SessionEvent.Output && event.sessionId == session.id) {
                        terminal.append(event.bytes, event.bytes.size)
                        if (output.replies.size() > 0) {
                            controller.write(session.id, output.replies.toByteArray()).getOrThrow()
                            output.replies.reset()
                        }
                        if (terminal.screen.transcriptText.contains("GRAPHICS_DONE") && terminal.graphics.placements.size == 2) break
                    }
                }
            }
            assertTrue(terminal.screen.transcriptText.lineSequence().any { it.trim() == "QUERY_PASS" })
            assertEquals(Color.RED, terminal.graphics.placements.first { it.imageId == 8L }.image.pixels[0])
            assertEquals(Color.GREEN, terminal.graphics.placements.first { it.imageId != 8L }.image.pixels[0])
            controller.detach(session.id).getOrThrow()
            val restoredOutput = Output()
            val restored = emulator(restoredOutput)
            controller.attach(session.id).getOrThrow()
            var replayFrames = 0
            withTimeout(10_000) {
                while (true) {
                    val event = events.receive()
                    if (event is SessionEvent.Output && event.sessionId == session.id && event.replay) {
                        replayFrames++
                        assertTrue(event.bytes.size <= 32 * 1024)
                        restored.appendReplay(event.bytes, event.bytes.size)
                        if (restored.screen.transcriptText.contains("GRAPHICS_DONE") && restored.graphics.placements.size == 2) break
                    }
                }
            }
            assertTrue("Image-sized replay must be split into multiple Binder frames", replayFrames >= 2)
            assertEquals(2, restored.graphics.placements.size)
            assertEquals(0, restoredOutput.replies.size())
            assertTrue(controller.list().getOrThrow().any { it.id == session.id })
        } finally {
            collector.cancelAndJoin()
            controller.close()
            events.close()
        }
    }
}
