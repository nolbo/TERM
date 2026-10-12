package dev.terminal.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.GraphicsFileReader
import com.termux.terminal.KittyPlaceholders
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.view.TerminalRenderer
import dev.terminal.protocol.SessionController
import dev.terminal.protocol.SessionEvent
import dev.terminal.runtime.native.ApkNativeExecutableLoader
import dev.terminal.runtime.native.NativeSessionController
import dev.terminal.runtime.rootless.RootlessDebianLayout
import dev.terminal.runtime.rootless.RootlessSessionController
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
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
class KittyProtocolDeviceTest {
    private class Output : TerminalOutput() {
        val replies = ByteArrayOutputStream()
        override fun write(data: ByteArray, offset: Int, count: Int) { replies.write(data, offset, count) }
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }
    private fun terminal(output: Output = Output()) = TerminalEmulator(output, 40, 20, 12, 24, 100, null)
    private fun append(terminal: TerminalEmulator, text: String) { val bytes = text.toByteArray(); terminal.append(bytes, bytes.size) }
    private fun command(header: String, data: String = "") = "\u001b_G$header;$data\u001b\\"
    private fun pixels(terminal: TerminalEmulator, renderer: TerminalRenderer = TerminalRenderer(20, Typeface.MONOSPACE)): IntArray {
        val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        renderer.render(terminal, Canvas(bitmap), 0, -1, -1, -1, -1)
        val pixels = IntArray(600 * 600)
        bitmap.getPixels(pixels, 0, 600, 0, 0, 600, 600)
        bitmap.recycle()
        return pixels
    }

    @Test fun graphicsLayersRespectTextAndExplicitBackgrounds() {
        fun draw(z: Int): IntArray {
            val terminal = terminal()
            append(terminal, "\u001b[?25l\u001b[38;2;255;255;255m\u001b[48;2;0;0;0mW \u001b[H")
            append(terminal, command("a=T,i=1,f=24,s=1,v=1,c=2,r=1,C=1,z=$z", "/wAA"))
            return pixels(terminal)
        }
        val belowBackground = draw(Int.MIN_VALUE)
        val belowText = draw(-1)
        val aboveText = draw(1)
        assertEquals(0, belowBackground.count { it == Color.RED })
        assertTrue(belowText.count { it == Color.RED } > 100)
        assertTrue(belowText.count { it == Color.WHITE } > aboveText.count { it == Color.WHITE })
    }

    @Test fun croppedAndPlaceholderImagesRenderWithoutMetadataGlyphs() {
        val terminal = terminal()
        append(terminal, "\u001b[?25l")
        append(terminal, command("a=T,i=1,f=24,s=2,v=1,x=1,w=1,c=2,r=2,C=1", "/wAAAP8A"))
        var pixels = pixels(terminal)
        assertEquals(0, pixels.count { it == Color.RED }); assertTrue(pixels.count { it == Color.GREEN } > 100)
        append(terminal, "\u001b[2J\u001b[H")
        append(terminal, command("a=T,i=42,p=7,f=24,s=1,v=1,U=1,c=2,r=2,q=2", "/wAA"))
        val cell = String(Character.toChars(KittyPlaceholders.CODE_POINT))
        append(terminal, "\u001b[38;5;42m\u001b[58:2::0:0:7m" + cell + "\u0305" + cell + "\r\n" + cell + "\u030d" + cell)
        pixels = pixels(terminal)
        assertTrue(pixels.count { it == Color.RED } > 100)
        assertEquals(4, terminal.graphics.getRenderPlacements(terminal.screen, 0, 20, 40, System.nanoTime() / 1_000_000).size)
    }

    @Test fun animationChangesCanvasAndInvalidatesBitmapCache() {
        val terminal = terminal()
        val renderer = TerminalRenderer(20, Typeface.MONOSPACE)
        append(terminal, command("a=T,i=1,f=24,s=1,v=1,c=2,r=2,C=1", "/wAA"))
        append(terminal, command("a=f,i=1,f=24,s=1,v=1,z=100", "AP8A"))
        assertTrue(pixels(terminal, renderer).count { it == Color.RED } > 100)
        append(terminal, command("a=a,i=1,r=1,z=30,s=3,v=2"))
        Thread.sleep(60)
        val animated = pixels(terminal, renderer)
        assertTrue(animated.count { it == Color.GREEN } > 100)
        assertEquals(0, animated.count { it == Color.RED })
        append(terminal, command("a=a,i=1,s=1,c=1"))
        assertTrue(pixels(terminal, renderer).count { it == Color.RED } > 100)
    }

    @Test fun nativeReaderPinsDirectoriesAndRejectsSymlinksAndSpecialFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "kitty-reader-${UUID.randomUUID()}").apply { mkdirs() }.canonicalFile
        try {
            File(root, "pixel").writeBytes(byteArrayOf(9, -1, 0, 0, 8))
            assertArrayEquals(byteArrayOf(-1, 0, 0), GraphicsFileReader.read(root.path, "pixel", 1, 3, false))
            Files.createSymbolicLink(File(root, "escape").toPath(), File(root.parentFile, "outside").toPath())
            for (name in listOf("../outside", "escape", "/pixel")) {
                try { GraphicsFileReader.read(root.path, name, 0, 0, false); fail(name) } catch (_: IOException) { }
            }
            try { GraphicsFileReader.read("/dev", "zero", 0, 3, false); fail("device file") } catch (_: IOException) { }
            GraphicsFileReader.read(root.path, "pixel", 1, 3, true)
            assertFalse(File(root, "pixel").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun nativeFileTransferDeletesTemporaryDataAndReplaysBytes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "kitty-native-${UUID.randomUUID()}").apply { mkdirs() }
        val temporary = File(root, "tmp").apply { mkdirs() }
        val source = File(temporary, "tty-graphics-protocol-red").apply { writeBytes(byteArrayOf(-1, 0, 0)) }
        val controller = NativeSessionController(root)
        try {
            val script = shellGraphic("a=T,i=51,t=t,f=24,s=1,v=1,C=1,q=2", source.absolutePath)
            checkMedia(controller, "/system/bin/sh", script, mapOf(51L to Color.RED))
            assertFalse(source.exists())
        } finally { controller.close(); root.deleteRecursively() }
    }

    @Test fun rootlessFileAndSharedMemoryTransfersAreResolvedBeforeReplay() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val layout = RootlessDebianLayout(File(context.filesDir, "rootless/debian"))
        assumeTrue("Requires an already installed Debian rootfs", layout.validateInstalledRootfs().isSuccess)
        val token = UUID.randomUUID().toString()
        val source = File(layout.rootfs, "tmp/tty-graphics-protocol-$token").apply { writeBytes(byteArrayOf(-1, 0, 0)) }
        val shm = File(layout.tmp, "graphics-shm").apply { mkdirs() }
        val shared = File(shm, "kitty-$token")
        val loader = ApkNativeExecutableLoader(context)
        val controller = RootlessSessionController(layout, loader.resolvePackagedExecutable("proot").getOrThrow(),
            loader.resolvePackagedExecutable("proot-loader").getOrThrow())
        try {
            val script = "printf '\\000\\000\\377' > /dev/shm/${shared.name}; " + shellGraphic("a=T,i=52,t=t,f=24,s=1,v=1,C=1,q=2", "/tmp/${source.name}") +
                shellGraphic("a=T,i=53,t=s,f=24,s=1,v=1,C=1,q=2", "/${shared.name}")
            checkMedia(controller, "/bin/sh", script, mapOf(52L to Color.RED, 53L to Color.BLUE))
            assertFalse(source.exists()); assertFalse(shared.exists())
        } finally { controller.close(); source.delete(); shared.delete() }
    }

    private fun shellGraphic(header: String, name: String): String {
        val encoded = Base64.getEncoder().encodeToString(name.toByteArray())
        return "printf '\\033_G$header;$encoded\\033\\\\'; "
    }

    private suspend fun CoroutineScope.checkMedia(controller: SessionController, shell: String, script: String, expected: Map<Long, Int>) {
        val events = Channel<SessionEvent>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { controller.events.collect { events.send(it) } }
        try {
            val session = controller.create(shell, "/", mapOf("PS1" to "", "DROID_LOGIN_SHELL" to "false"), 20u, 40u).getOrThrow()
            controller.write(session.id, ("stty -echo; " + script + "printf '\\nMEDIA_DONE\\n'\n").toByteArray()).getOrThrow()
            val output = Output(); val terminal = terminal(output)
            withTimeout(20_000) {
                while (true) {
                    val event = events.receive()
                    if (event is SessionEvent.Output && event.sessionId == session.id) {
                        assertTrue(event.bytes.size <= 32 * 1024)
                        terminal.append(event.bytes, event.bytes.size)
                        if (output.replies.size() > 0) {
                            controller.write(session.id, output.replies.toByteArray()).getOrThrow(); output.replies.reset()
                        }
                        if (expected.all { (id, color) -> terminal.graphics.placements.any { it.imageId == id && it.image.pixels[0] == color } } &&
                            terminal.screen.transcriptText.contains("MEDIA_DONE")) break
                    }
                }
            }
            controller.detach(session.id).getOrThrow()
            val restoredOutput = Output(); val restored = terminal(restoredOutput)
            controller.attach(session.id).getOrThrow()
            withTimeout(10_000) {
                while (restored.graphics.placements.size < expected.size) {
                    val event = events.receive()
                    if (event is SessionEvent.Output && event.sessionId == session.id && event.replay) {
                        assertTrue(event.bytes.size <= 32 * 1024); restored.appendReplay(event.bytes, event.bytes.size)
                    }
                }
            }
            expected.forEach { (id, color) -> assertEquals(color, restored.graphics.placements.first { it.imageId == id }.image.pixels[0]) }
            assertEquals(0, restoredOutput.replies.size())
        } finally { collector.cancelAndJoin(); events.close() }
    }
}
