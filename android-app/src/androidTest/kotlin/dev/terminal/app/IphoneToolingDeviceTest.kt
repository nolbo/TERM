package dev.terminal.app

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.native.ApkNativeExecutableLoader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IphoneToolingDeviceTest {
    @Test fun genericHostInputScreenIsNonExportedAndStaysSecureAfterRecreation() {
        androidx.test.core.app.ActivityScenario.launch<HostToolInputsActivity>(android.content.Intent(context, HostToolInputsActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertEquals(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, activity.window.decorView.importantForAutofill)
            }
            scenario.recreate()
            scenario.onActivity { assertTrue(it.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0) }
        }
        val info = context.packageManager.getActivityInfo(android.content.ComponentName(context, HostToolInputsActivity::class.java), 0)
        assertFalse(info.exported)
    }
    @Test fun nativeCliLifecycleCommandsMatchRootlessScopedRequests() {
        val cases = listOf(
            listOf("start", "isideload", "phone") to mapOf("tool" to "isideload", "device" to "phone"),
            listOf("status", "operation") to mapOf("operation" to "operation"),
            listOf("watch", "operation") to mapOf("operation" to "operation"),
            listOf("select", "operation", "A,B") to mapOf("operation" to "operation", "choices" to "A,B"),
            listOf("cancel", "operation") to mapOf("operation" to "operation"),
        )
        for ((arguments, fields) in cases) {
            val address = "tool-life-test-${UUID.randomUUID()}"; val token = UUID.randomUUID().toString()
            LocalServerSocket(address).use { server ->
                val child = ProcessBuilder(listOf(binary("terminal-usb"), "tooling") + arguments).apply {
                    environment().clear(); environment()["TERMINAL_USB_SOCKET"] = address; environment()["TERMINAL_USB_TOKEN"] = token
                    redirectErrorStream(true)
                }.start()
                try {
                    ready(server)
                    server.accept().use { socket ->
                        socket.soTimeout = 2000; val input = DataInputStream(socket.inputStream)
                        val size = input.readInt(); assertTrue(size in 1..49152)
                        val request = JSONObject(String(ByteArray(size).also(input::readFully)))
                        assertEquals(if (arguments[0] == "watch") "status" else arguments[0], request.getString("action"))
                        assertEquals(setOf("version", "token", "op", "action") + fields.keys, request.keys().asSequence().toSet())
                        fields.forEach { (key, value) -> assertEquals(value, request.getString(key)) }
                        val reply = "{\"ok\":true,\"result\":{\"state\":\"COMPLETED\"}}".toByteArray()
                        DataOutputStream(socket.outputStream).apply { writeInt(reply.size); write(reply); flush() }
                        assertTrue(child.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, child.exitValue())
                        assertFalse(child.inputStream.bufferedReader().readText().contains(token))
                    }
                } finally { child.destroyForcibly() }
            }
        }
    }
    @Test fun packagedIsideloadRunsPrivateStoragePairingSelfTestWithoutNetworkOrUsb() {
        val directory = java.io.File(context.noBackupFilesDir, "tool-self-test-${UUID.randomUUID()}").apply { mkdirs() }
        android.system.Os.chmod(directory.absolutePath, 448)
        val child = ProcessBuilder(binary("isideload"), "--self-test", directory.canonicalPath).apply {
            environment().clear(); redirectErrorStream(true)
        }.start()
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS))
            val output = child.inputStream.bufferedReader().readText()
            assertEquals(output, 0, child.exitValue())
            assertTrue(JSONObject(output).getBoolean("selfTest"))
            assertFalse(JSONObject(output).getBoolean("rawUsbFd"))
        } finally { child.destroyForcibly(); directory.deleteRecursively() }
    }
    @Test fun packagedIsideloadRejectsArbitraryOperationBeforeAuthentication() {
        val child = ProcessBuilder(binary("isideload"), "ReadPairRecord").apply { environment().clear(); redirectErrorStream(true) }.start()
        try {
            assertTrue(child.waitFor(5, TimeUnit.SECONDS))
            assertEquals("FAILED", JSONObject(child.inputStream.bufferedReader().readText()).getString("phase"))
        } finally { child.destroyForcibly() }
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun binary(name: String) = ApkNativeExecutableLoader(context).resolvePackagedExecutable(name).getOrThrow().absolutePath
    private fun ready(server: LocalServerSocket) {
        val poll = android.system.StructPollfd().apply { fd = server.fileDescriptor; events = android.system.OsConstants.POLLIN.toShort() }
        assertEquals(1, android.system.Os.poll(arrayOf(poll), 5000))
    }
    private fun send(socket: LocalSocket, fd: java.io.FileDescriptor) {
        socket.setFileDescriptorsForSend(arrayOf(fd))
        try { socket.outputStream.write('M'.code); socket.outputStream.flush() }
        finally { socket.setFileDescriptorsForSend(null) }
    }

    @Test fun nativeCliUsesSameBoundedJsonFramesAsRootlessClient() {
        val address = "tool-cli-test-${UUID.randomUUID()}"
        val token = UUID.randomUUID().toString()
        LocalServerSocket(address).use { server ->
            val child = ProcessBuilder(binary("terminal-usb"), "tooling", "list").apply {
                environment().clear(); environment()["TERMINAL_USB_SOCKET"] = address; environment()["TERMINAL_USB_TOKEN"] = token
                redirectErrorStream(true)
            }.start()
            try {
                ready(server)
                server.accept().use { socket ->
                    socket.soTimeout = 2000
                    val input = DataInputStream(socket.inputStream)
                    val length = input.readInt(); assertTrue(length in 1..49152)
                    val request = JSONObject(String(ByteArray(length).also(input::readFully)))
                    assertEquals(1, request.getInt("version")); assertEquals(token, request.getString("token"))
                    assertEquals("tooling", request.getString("op")); assertEquals("list", request.getString("action"))
                    assertEquals(setOf("version", "token", "op", "action"), request.keys().asSequence().toSet())
                    val reply = "{\"ok\":true,\"result\":{\"tools\":[\"idevice-check\"],\"publicMuxSocket\":false}}".toByteArray()
                    DataOutputStream(socket.outputStream).apply { writeInt(reply.size); write(reply); flush() }
                    assertTrue(child.waitFor(5, TimeUnit.SECONDS))
                    val output = child.inputStream.bufferedReader().readText()
                    assertEquals(output, 0, child.exitValue()); assertFalse(output.contains(token))
                    assertFalse(JSONObject(output).getBoolean("publicMuxSocket"))
                }
            } finally { child.destroyForcibly() }
        }
    }

    @Test fun trustedProbeObtainsFreshPrivateMuxStreamsWithoutUsbOrPublicListener() {
        val address = "tool-probe-test-${UUID.randomUUID()}"
        val factoryAddress = "tool-factory-test-${UUID.randomUUID()}"
        val token = UUID.randomUUID().toString()
        LocalServerSocket(address).use { host -> LocalServerSocket(factoryAddress).use { factory ->
            val child = ProcessBuilder(binary("iphone-tool-probe"), "probe", address).redirectErrorStream(true).start()
            try {
                child.outputStream.use { it.write((token + "\n").toByteArray()) }
                ready(host)
                host.accept().use { control ->
                    control.soTimeout = 3000
                    val received = ByteArray(37); DataInputStream(control.inputStream).readFully(received)
                    assertTrue(java.security.MessageDigest.isEqual((token + "\n").toByteArray(), received))
                    assertEquals(android.os.Process.myUid(), control.peerCredentials.uid)
                    LocalSocket().use { connector ->
                        connector.connect(LocalSocketAddress(factoryAddress))
                        factory.accept().use { requests ->
                            requests.soTimeout = 3000; send(control, connector.fileDescriptor)
                            val streams = ArrayList<LocalSocket>()
                            try {
                                repeat(2) { index ->
                                    assertEquals('M'.code, requests.inputStream.read())
                                    LocalServerSocket("tool-stream-$index-${UUID.randomUUID()}").use { service ->
                                        LocalSocket().use { endpoint ->
                                            endpoint.connect(service.localSocketAddress)
                                            streams.add(service.accept().apply { soTimeout = 3000 })
                                            send(requests, endpoint.fileDescriptor)
                                        }
                                    }
                                }
                                // No device identities or pair records. Only an empty list and synthetic BUID.
                                streams.forEachIndexed { index, stream ->
                                    val input = DataInputStream(stream.inputStream)
                                    val header = ByteArray(16).also(input::readFully)
                                    val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                                    val size = buffer.getInt(0); assertTrue(size in 17..65536)
                                    val body = ByteArray(size - 16).also(input::readFully)
                                    assertTrue(String(body).contains(if (index == 0) "ListDevices" else "ReadBUID"))
                                    val dictionary = if (index == 0) "<key>DeviceList</key><array/>" else "<key>BUID</key><string>synthetic</string>"
                                    val reply = "<?xml version=\"1.0\"?><plist version=\"1.0\"><dict>$dictionary</dict></plist>".toByteArray()
                                    buffer.putInt(0, reply.size + 16)
                                    stream.outputStream.apply { write(header); write(reply); flush() }
                                }
                                assertTrue(child.waitFor(5, TimeUnit.SECONDS))
                                val output = child.inputStream.bufferedReader().readText()
                                assertEquals(output, 0, child.exitValue())
                                val result = JSONObject(output); assertTrue(result.getBoolean("privateConnector")); assertFalse(result.getBoolean("publicMuxSocket"))
                            } finally { streams.forEach { it.close() } }
                        }
                    }
                }
            } finally { child.destroyForcibly() }
        } }
    }
    @Test fun nativeCliFailureStopsShellChainingWithoutReplayingRequests() {
        val address = "tool-failure-test-${UUID.randomUUID()}"
        LocalServerSocket(address).use { server ->
            val child = ProcessBuilder(binary("terminal-usb"), "tooling", "watch", "operation").apply {
                environment()["TERMINAL_USB_SOCKET"] = address
                environment()["TERMINAL_USB_TOKEN"] = UUID.randomUUID().toString()
            }.redirectErrorStream(true).start()
            try {
                ready(server)
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val input = DataInputStream(socket.inputStream)
                    val size = input.readInt(); assertTrue(size in 1..49152)
                    val request = JSONObject(String(ByteArray(size).also(input::readFully)))
                    assertEquals("status", request.getString("action"))
                    val reply = "{\"ok\":true,\"result\":{\"state\":\"FAILED\",\"error\":\"USB_DETACHED\"}}".toByteArray()
                    DataOutputStream(socket.outputStream).apply { writeInt(reply.size); write(reply); flush() }
                    assertTrue(child.waitFor(5, TimeUnit.SECONDS))
                    assertEquals(1, child.exitValue())
                    assertEquals("FAILED", JSONObject(child.inputStream.bufferedReader().readText()).getString("state"))
                    assertEquals(-1, input.read())
                }
            } finally { child.destroyForcibly() }
        }
    }

    @Test fun trustedProbeRejectsArbitraryOperationWithoutUsb() {
        val child = ProcessBuilder(binary("iphone-tool-probe"), "ReadPairRecord").redirectErrorStream(true).start()
        try {
            assertTrue(child.waitFor(5, TimeUnit.SECONDS)); assertEquals(1, child.exitValue())
            assertEquals("WORKER_PROTOCOL", JSONObject(child.inputStream.bufferedReader().readText()).getString("error"))
        } finally { child.destroyForcibly() }
    }
}
