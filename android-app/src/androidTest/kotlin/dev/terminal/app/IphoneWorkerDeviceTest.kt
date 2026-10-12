package dev.terminal.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.native.ApkNativeExecutableLoader
import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android/Bionic execution, not evidence of physical USB or iOS trust. */
@RunWith(AndroidJUnit4::class)
class IphoneWorkerDeviceTest {
    @Test fun nativeWorkerReceivesAndroidFdAndRejectsNonUsbDescriptor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val binary = ApkNativeExecutableLoader(context).resolvePackagedExecutable("iphone-worker").getOrThrow()
        val directory = File(context.cacheDir, "iphone-fdtest-${System.nanoTime()}")
        assertTrue(directory.mkdir())
        android.system.Os.chmod(directory.absolutePath, 448)
        val address = "iphone-fdtest-${java.util.UUID.randomUUID()}"
        val token = java.util.UUID.randomUUID().toString()
        android.net.LocalServerSocket(address).use { server ->
            val child = ProcessBuilder(binary.absolutePath, "info", directory.absolutePath, address, "test-buid")
                .redirectErrorStream(true).start()
            try {
                child.outputStream.use { it.write((token + "\n").toByteArray()) }
                val poll = android.system.StructPollfd().apply {
                    fd = server.fileDescriptor; events = android.system.OsConstants.POLLIN.toShort()
                }
                assertEquals(1, android.system.Os.poll(arrayOf(poll), 5000))
                server.accept().use { socket ->
                    socket.soTimeout = 2000
                    assertEquals(android.os.Process.myUid(), socket.peerCredentials.uid)
                    val received = ByteArray(37).also { java.io.DataInputStream(socket.inputStream).readFully(it) }
                    assertEquals(token + "\n", String(received))
                    android.os.ParcelFileDescriptor.open(File("/dev/null"), android.os.ParcelFileDescriptor.MODE_READ_ONLY).use {
                        socket.setFileDescriptorsForSend(arrayOf(it.fileDescriptor))
                        socket.outputStream.write('U'.code)
                        socket.outputStream.flush()
                        socket.setFileDescriptorsForSend(null)
                    }
                    assertTrue(child.waitFor(10, TimeUnit.SECONDS))
                    val output = child.inputStream.bufferedReader().readText()
                    assertEquals(output, 1, child.exitValue())
                    assertEquals("USB_MUX_INTERFACE_UNAVAILABLE", JSONObject(output).getString("error"))
                }
            } finally { child.destroyForcibly(); directory.deleteRecursively() }
        }
    }

    @Test fun packagedWorkerRunsPrivateMuxAndAtomicPairStoreSelfTest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val binary = ApkNativeExecutableLoader(context).resolvePackagedExecutable("iphone-worker").getOrThrow()
        val directory = File(context.cacheDir, "iphone-selftest-${System.nanoTime()}")
        assertTrue(directory.mkdir())
        android.system.Os.chmod(directory.absolutePath, 448)
        val child = ProcessBuilder(binary.absolutePath, "--self-test", directory.absolutePath)
            .redirectErrorStream(true).start()
        try {
            assertTrue("Native self-test timed out", child.waitFor(30, TimeUnit.SECONDS))
            val output = child.inputStream.bufferedReader().readText()
            assertEquals(output, 0, child.exitValue())
            val reply = JSONObject(output.trim())
            assertTrue(reply.getBoolean("ok"))
            assertFalse(reply.getJSONObject("result").getBoolean("publicMuxSocket"))
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally { child.destroyForcibly(); directory.deleteRecursively() }
    }

    @Test fun packagedWorkerRejectsArbitraryOperationWithoutOpeningUsb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val binary = ApkNativeExecutableLoader(context).resolvePackagedExecutable("iphone-worker").getOrThrow()
        val child = ProcessBuilder(binary.absolutePath, "ReadPairRecord").redirectErrorStream(true).start()
        try {
            assertTrue(child.waitFor(5, TimeUnit.SECONDS))
            assertEquals(1, child.exitValue())
            val reply = JSONObject(child.inputStream.bufferedReader().readText())
            assertEquals("INVALID_OPERATION", reply.getString("error"))
        } finally { child.destroyForcibly() }
    }
}
