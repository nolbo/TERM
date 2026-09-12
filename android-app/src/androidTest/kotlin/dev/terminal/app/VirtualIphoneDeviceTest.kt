package dev.terminal.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.protocol.SessionEvent
import dev.terminal.runtime.native.ApkNativeExecutableLoader
import dev.terminal.runtime.rootless.RootlessDebianLayout
import dev.terminal.runtime.rootless.RootlessSessionController
import dev.terminal.runtime.usb.UsbBroker
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Full CLI subprocesses, real broker session auth, synthetic iPhone results.
 * This does not emulate physical USB, lockdown TLS, RPPairing or signing.
 */
@RunWith(AndroidJUnit4::class)
class VirtualIphoneDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val loader get() = ApkNativeExecutableLoader(context)
    private data class Output(val code: Int, val text: String)

    private fun matrix(sim: VirtualIphone, directory: File, run: (List<String>) -> Output) {
        fun command(vararg args: String) = run(args.toList())
        command("list").let { assertEquals(it.text, 0, it.code) }
        val check = command("tooling", "check", VirtualIphone.DEVICE)
        assertEquals(check.text, 0, check.code)
        assertEquals("idevice-check", JSONObject(check.text).getString("tool"))
        assertFalse(sim.requests.any { it == "tooling/start" || it == "iphone/pair" })
        for (error in listOf("DEVICE_LOCKED", "TRUST_DENIED", "PAIRING_FAILED")) {
            sim.pairError = error
            val result = command("iphone", "pair", VirtualIphone.DEVICE)
            assertEquals(1, result.code); assertTrue(result.text, result.text.contains(error))
        }
        sim.pairError = null
        assertEquals(0, command("iphone", "pair", VirtualIphone.DEVICE).code)
        assertEquals(0, command("iphone", "info", VirtualIphone.DEVICE).code)
        val destination = File(directory, "download.bin")
        for (fault in listOf("DETACH_READ", "BAD_OFFSET", "OVERSIZE")) {
            sim.fault = fault
            assertEquals(fault, 1, command("iphone", "get", VirtualIphone.DEVICE, "/sample.bin", destination.path).code)
            assertFalse(destination.exists())
            assertTrue(directory.listFiles()!!.none { it.name.startsWith(".terminal-download-") })
        }
        sim.fault = "NONE"
        assertEquals(0, command("iphone", "get", VirtualIphone.DEVICE, "/sample.bin", destination.path).code)
        assertArrayEquals(sim.files["/sample.bin"], destination.readBytes())
        assertEquals(1, command("iphone", "get", VirtualIphone.DEVICE, "/sample.bin", destination.path).code)
        assertArrayEquals(sim.files["/sample.bin"], destination.readBytes())

        val source = File(directory, "source.bin").apply { writeBytes(ByteArray(32768) { it.toByte() }) }
        val sentinel = byteArrayOf(9, 8, 7)
        sim.files["/unrelated.bin"] = sentinel
        sim.fault = "DETACH_WRITE"
        assertEquals(1, command("iphone", "put", VirtualIphone.DEVICE, source.path).code)
        assertEquals(1, sim.uploads)
        assertEquals(16384, sim.files.entries.single { it.key.startsWith("/TerminalImports/") }.value.size)
        assertArrayEquals(sentinel, sim.files["/unrelated.bin"])
        sim.fault = "NONE"
        assertEquals(0, command("iphone", "put", VirtualIphone.DEVICE, source.path).code)
        assertEquals(2, sim.uploads) // Explicit new command only, no hidden retry.
        assertTrue(sim.files.values.any { it.contentEquals(source.readBytes()) })

        assertEquals(0, command("tooling", "start", "isideload", VirtualIphone.DEVICE).code)
        assertEquals(1, command("tooling", "start", "isideload", VirtualIphone.DEVICE).code)
        val waiting = command("tooling", "status", "virtual-operation")
        assertEquals("WAITING_FOR_TRUST", JSONObject(waiting.text).getString("phase"))
        assertEquals(130, command("tooling", "cancel", "virtual-operation").code)
        for ((phase, error) in listOf("WAITING_FOR_TRUST" to "TRUST_DENIED", "INSTALLING" to "USB_DETACHED", "PLACING_PAIRING" to "AFC_PLACEMENT")) {
            sim.toolPhase = phase; sim.toolState = "FAILED"; sim.toolError = error
            val result = command("tooling", "watch", "virtual-operation")
            assertEquals(1, result.code); assertEquals(error, JSONObject(result.text).getString("error"))
        }
        assertFalse(sim.requests.any { it.contains("reset") || it.contains("delete") || it.contains("unpair") })
        assertTrue(sim.errors.toString(), sim.errors.isEmpty())
    }

    private fun native(sim: VirtualIphone, args: List<String>, environment: Map<String, String> = sim.clientEnvironment): Output {
        val child = ProcessBuilder(listOf(loader.resolvePackagedExecutable("terminal-usb").getOrThrow().path) + args)
            .apply { environment().putAll(environment) }.redirectErrorStream(true).start()
        try {
            assertTrue("CLI hung", child.waitFor(10, TimeUnit.SECONDS))
            val text = child.inputStream.bufferedReader().readText()
            assertFalse(text.contains(sim.environment.getValue("TERMINAL_USB_TOKEN")))
            return Output(child.exitValue(), text)
        } finally { child.destroyForcibly() }
    }

    @Test fun nativeCliVirtualIphoneFailureMatrix() {
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "virtual-iphone-").toFile()
        try {
            UsbBroker(context).use { broker -> VirtualIphone(broker).use { sim ->
                matrix(sim, directory) { native(sim, it) }
                // A virtual attachment never becomes a physical-device capability.
                assertEquals(1, native(sim, listOf("tooling", "check", VirtualIphone.DEVICE), sim.environment).code)
                val requests = sim.requests.size
                assertEquals(1, native(sim, listOf("iphone", "pair", VirtualIphone.DEVICE),
                    sim.clientEnvironment + ("TERMINAL_USB_TOKEN" to java.util.UUID.randomUUID().toString())).code)
                assertEquals(requests, sim.requests.size)
                broker.unregister(sim.session)
                val before = sim.uploads
                assertEquals(1, native(sim, listOf("iphone", "put", VirtualIphone.DEVICE, File(directory, "source.bin").path)).code)
                assertEquals(before, sim.uploads)
            } }
        } finally { directory.deleteRecursively() }
    }

    @Test fun terminatingNativeWatchDoesNotCancelVirtualHostTask() {
        UsbBroker(context).use { broker -> VirtualIphone(broker).use { sim ->
            val child = ProcessBuilder(loader.resolvePackagedExecutable("terminal-usb").getOrThrow().path,
                "tooling", "watch", "virtual-operation").apply { environment().putAll(sim.clientEnvironment) }.start()
            try {
                assertTrue(sim.observedStatus.await(5, TimeUnit.SECONDS))
                child.destroyForcibly(); assertTrue(child.waitFor(5, TimeUnit.SECONDS))
                assertEquals("RUNNING", sim.toolState)
                assertTrue(sim.requests.all { it == "tooling/status" })
                assertEquals(0, native(sim, listOf("tooling", "status", "virtual-operation")).code)
            } finally { child.destroyForcibly() }
        } }
    }

    @Test fun rootlessCliVirtualIphoneFailureMatrix() {
        val layout = RootlessDebianLayout(File(context.filesDir, "rootless/debian"))
        assumeTrue("Requires provisioned Rootless Debian; never substitutes host Python", layout.validateInstalledRootfs().isSuccess)
        assumeTrue("Requires apt-installed python3 in the test Debian rootfs", File(layout.rootfs, "usr/bin/python3").exists())
        val directory = Files.createTempDirectory(layout.workspace.toPath(), "virtual-iphone-").toFile()
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        try {
            UsbBroker(context).use { broker -> VirtualIphone(broker).use { sim ->
                val controller = RootlessSessionController(layout, loader.resolvePackagedExecutable("proot").getOrThrow(),
                    loader.resolvePackagedExecutable("proot-loader").getOrThrow(), usbBroker = broker)
                try {
                    matrix(sim, directory) { args -> runBlocking {
                        val events = Channel<SessionEvent>(Channel.UNLIMITED)
                        val collector = launch(start = CoroutineStart.UNDISPATCHED) { controller.events.collect { events.send(it) } }
                        val session = controller.create("/bin/sh", "/root", mapOf("DROID_LOGIN_SHELL" to "false"), 20u, 80u).getOrThrow()
                        try {
                            val output = File(directory, "output")
                            val command = sim.clientEnvironment.entries.joinToString(" ") { "${it.key}=${quote(it.value)}" } +
                                " /usr/local/bin/terminal-usb " + args.joinToString(" ") { quote(it.replace(directory.path, "/workspace/${directory.name}")) } +
                                " > ${quote("/workspace/${directory.name}/output")} 2>&1; exit \$?\n"
                            controller.write(session.id, command.toByteArray()).getOrThrow()
                            val code = withTimeout(20000) {
                                var exited: Int? = null
                                while (exited == null) { val event = events.receive(); if (event is SessionEvent.Exited && event.sessionId == session.id) exited = event.exitCode }
                                exited
                            }
                            Output(code, output.readText()).also {
                                assertFalse(it.text.contains(sim.environment.getValue("TERMINAL_USB_TOKEN")))
                            }
                        } finally { collector.cancel(); controller.terminate(session.id, 9); events.close() }
                    } }
                } finally { controller.close() }
            } }
        } finally { directory.deleteRecursively() }
    }
}
