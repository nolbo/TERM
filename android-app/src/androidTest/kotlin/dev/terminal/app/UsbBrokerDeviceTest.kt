package dev.terminal.app

import android.net.LocalSocket
import android.net.LocalSocketAddress
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.usb.UsbBroker
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import dev.terminal.runtime.rootless.RootlessDebianLayout
import dev.terminal.runtime.rootless.RootlessSessionController
import dev.terminal.runtime.native.ApkNativeExecutableLoader
import dev.terminal.protocol.SessionEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** Hardware-independent tests; these do not claim that a physical USB transfer passed. */
@RunWith(AndroidJUnit4::class)
class UsbBrokerDeviceTest {
    @Test fun rootlessReceivesClientAndCredentialsWithoutChangingItsFilesystem(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val layout = RootlessDebianLayout(File(context.filesDir, "rootless/debian"))
        assumeTrue("Requires a provisioned Debian rootfs", layout.validateInstalledRootfs().isSuccess)
        val loader = ApkNativeExecutableLoader(context)
        val broker = UsbBroker(context)
        val controller = RootlessSessionController(layout, loader.resolvePackagedExecutable("proot").getOrThrow(),
            loader.resolvePackagedExecutable("proot-loader").getOrThrow(), usbBroker = broker)
        val events = Channel<SessionEvent>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { controller.events.collect { events.send(it) } }
        try {
            val script = "test -x /usr/local/bin/terminal-usb && test -n \"\$TERMINAL_USB_SOCKET\" && " +
                "test -n \"\$TERMINAL_USB_TOKEN\" && printf 'usb-bind-ok\\n'"
            val session = controller.create("/bin/sh", "/root", mapOf("DROID_LOGIN_SHELL" to "false"), 20u, 80u).getOrThrow()
            controller.write(session.id, (script + "; exit \$?\n").toByteArray()).getOrThrow()
            val output = StringBuilder()
            withTimeout(20_000) {
                while (true) {
                    when (val event = events.receive()) {
                        is SessionEvent.Output -> output.append(String(event.bytes))
                        is SessionEvent.Exited -> { assertEquals(0, event.exitCode); break }
                    }
                }
            }
            assertTrue(output.toString(), output.contains("usb-bind-ok"))
            controller.terminate(session.id, 9).getOrThrow()
        } finally { collector.cancel(); controller.close(); broker.close() }
    }

    @Test fun realSocketRejectsWrongAndExpiredSessionsAndOversizedFrames() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val broker = UsbBroker(context)
        try {
            val first = broker.register("usb-test-session", "Debian")
            val second = broker.register("usb-test-sibling", "Ubuntu")
            fun connect() = LocalSocket().apply {
                connect(LocalSocketAddress(first.getValue("TERMINAL_USB_SOCKET"), LocalSocketAddress.Namespace.ABSTRACT))
                soTimeout = 3000
            }
            fun call(token: String, raw: String? = null): JSONObject = connect().use { socket ->
                val request = raw ?: JSONObject().put("version", 1).put("token", token).put("op", "list").toString()
                val bytes = request.toByteArray()
                DataOutputStream(socket.outputStream).apply { writeInt(bytes.size); write(bytes); flush() }
                val input = DataInputStream(socket.inputStream)
                val result = ByteArray(input.readInt()).also(input::readFully)
                JSONObject(String(result))
            }
            assertTrue(call(first.getValue("TERMINAL_USB_TOKEN")).getBoolean("ok"))
            val toolingRequest = JSONObject().put("version", 1).put("token", first.getValue("TERMINAL_USB_TOKEN"))
                .put("op", "tooling").put("action", "list")
            val tools = call("", toolingRequest.toString()).getJSONObject("result")
            assertEquals("list", tools.getString("requestAction"))
            assertEquals(3, tools.getInt("serviceRevision"))
            assertTrue(tools.getString("requestId").matches(Regex("[0-9a-f-]{36}")))
            for (name in listOf("iphone-worker", "iphone-tool-probe"))
                assertTrue(tools.getJSONObject("workerSha256").getString(name).matches(Regex("[0-9a-f]{64}")))
            assertFalse(tools.has("operation"))
            assertFalse(tools.toString().contains(first.getValue("TERMINAL_USB_TOKEN")))
            assertFalse(call("wrong-token").getBoolean("ok"))
            assertFalse(call("", "{\"nested\":{}}").getBoolean("ok"))
            val serial = JSONObject().put("version", 1).put("token", first.getValue("TERMINAL_USB_TOKEN"))
                .put("op", "serial-open").put("device", "unavailable").put("driver", "ftdi").put("port", 0)
            serial.put("force", true)
            assertEquals("INVALID_SERIAL_REQUEST", call("", serial.toString()).getString("error"))
            serial.remove("force"); serial.put("token", "forged")
            assertEquals("SESSION_EXPIRED", call("", serial.toString()).getString("error"))
            serial.put("token", first.getValue("TERMINAL_USB_TOKEN")).put("port", "0")
            assertEquals("INVALID_SERIAL_INTEGER", call("", serial.toString()).getString("error"))
            val files = JSONObject().put("version", 1).put("token", "forged")
                .put("op", "files-open").put("root", "opaque-root").put("mode", "read")
            assertEquals("SESSION_EXPIRED", call("", files.toString()).getString("error"))
            files.put("token", first.getValue("TERMINAL_USB_TOKEN")).put("uri", "content://private")
            assertEquals("INVALID_FILE_REQUEST", call("", files.toString()).getString("error"))
            files.remove("uri")
            assertEquals("STORAGE_UNAVAILABLE", call("", files.toString()).getString("error"))
            assertTrue(broker.addStorageTree("content://untrusted.provider/tree/private", 3).isNotEmpty())
            val config = JSONObject().put("version", 1).put("token", first.getValue("TERMINAL_USB_TOKEN"))
                .put("op", "configuration").put("device", "unavailable").put("configuration", 0)
            assertEquals("INVALID_USB_CONFIGURATION", call("", config.toString()).getString("error"))
            val diagnostics = JSONObject().put("version", 1).put("token", first.getValue("TERMINAL_USB_TOKEN")).put("op", "diagnostics")
            val snapshot = call("", diagnostics.toString()).getJSONObject("result")
            assertEquals(0, snapshot.getInt("rawConnections"))
            assertEquals(0, snapshot.getInt("fileConnections"))
            assertFalse(snapshot.toString().contains(first.getValue("TERMINAL_USB_TOKEN")))
            val iphone = JSONObject().put("version", 1).put("token", first.getValue("TERMINAL_USB_TOKEN"))
                .put("op", "iphone").put("device", "unavailable").put("action", "ReadPairRecord")
            assertEquals("UNSUPPORTED_IPHONE_OPERATION", call("", iphone.toString()).getString("error"))
            iphone.put("action", "info").put("port", 62078)
            assertEquals("INVALID_IPHONE_REQUEST", call("", iphone.toString()).getString("error"))
            iphone.remove("port")
            iphone.put("token", "forged")
            assertEquals("SESSION_EXPIRED", call("", iphone.toString()).getString("error"))
            assertEquals("REQUEST_EXPIRED", broker.approve("unknown-request"))
            assertEquals(0, JSONObject(broker.state()).getJSONArray("grants").length())
            broker.unregister("usb-test-session")
            assertFalse(call(first.getValue("TERMINAL_USB_TOKEN")).getBoolean("ok"))
            assertTrue(call(second.getValue("TERMINAL_USB_TOKEN")).getBoolean("ok"))
            connect().use { socket ->
                DataOutputStream(socket.outputStream).apply { writeInt(49 * 1024); flush() }
                assertEquals(-1, socket.inputStream.read())
            }
            // A rejected connection must not exhaust the worker pool or break a sibling.
            assertTrue(call(second.getValue("TERMINAL_USB_TOKEN")).getBoolean("ok"))
        } finally { broker.close() }
    }

    @Test fun usbSettingsCanRecreateWithoutOwningTheRuntime() {
        ActivityScenario.launch(UsbDevicesActivity::class.java).use { scenario ->
            scenario.recreate()
            scenario.onActivity { assertFalse(it.isFinishing) }
        }
    }
}
