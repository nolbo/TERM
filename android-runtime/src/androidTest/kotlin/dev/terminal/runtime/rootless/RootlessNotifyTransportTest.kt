package dev.terminal.runtime.rootless

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.native.ApkNativeExecutableLoader
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in native transport probe in a standalone APK, separate from user sessions. */
@RunWith(AndroidJUnit4::class)
class RootlessNotifyTransportTest {
    @Test fun transfersFileDescriptorsFromAnAndroidApplicationProcess() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("rootlessNotifyTransport") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.terminal.runtime.test")
        val executable = ApkNativeExecutableLoader(context).resolvePackagedExecutable("rootless-notify-probe").getOrThrow()
        val expected = requireNotNull(arguments.getString("rootlessNotifySha256"))
        require(expected.matches(Regex("[0-9a-f]{64}")))
        val actual = MessageDigest.getInstance("SHA-256").digest(executable.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals("Notification probe digest", expected, actual)
        val policy = File("/proc/self/status").readLines().filter {
            it.startsWith("CapEff:") || it.startsWith("NoNewPrivs:") || it.startsWith("Seccomp:")
        }.joinToString(" ")
        Log.i("RootlessNotifyTransport", policy)
        val temporary = Files.createTempDirectory(context.filesDir.toPath(), "notify-transport-")
        try {
            val process = ProcessBuilder(executable.absolutePath, temporary.toString(), "notify", "1000")
                .directory(context.filesDir).redirectErrorStream(true).start()
            process.outputStream.close()
            try {
                // The fixed probe emits only a benchmark line and bounded diagnostics.
                assertTrue("Notification probe timed out", process.waitFor(20, TimeUnit.SECONDS))
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(output, 0, process.exitValue())
                assertTrue(output, output.contains("PASS unprivileged listener, memory copy, ADDFD_SEND"))
                Log.i("RootlessNotifyTransport", "VERIFIED $actual")
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
                process.inputStream.close()
            }
        } finally {
            deleteRootlessTree(temporary)
        }
    }
}
