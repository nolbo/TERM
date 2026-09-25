package dev.terminal.runtime.directlinux

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.native.ApkNativeExecutableLoader
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectLinuxExecTransitionTest {
    @Test fun reentersPackagedRuntimeWithInheritedFilter() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("directLinuxReexec") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.terminal.runtime.test")
        val executable = ApkNativeExecutableLoader(context).resolvePackagedExecutable("direct-linux-loader").getOrThrow()
        val expected = requireNotNull(arguments.getString("directLinuxLoaderSha256"))
        val digest = MessageDigest.getInstance("SHA-256").digest(executable.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(expected, digest)
        val report = File(context.filesDir, "direct-linux-reexec-result.json")
        check(!report.exists() || report.delete())
        val log = File.createTempFile("direct-linux-reexec-", ".log", context.cacheDir)
        val started = System.nanoTime()
        val builder = ProcessBuilder(executable.path, "--reexec-probe").redirectErrorStream(true).redirectOutput(log)
        builder.environment().apply { clear(); put("DIRECT_LINUX_POLICY", "1") }
        val process = builder.start()
        process.outputStream.close()
        try {
            assertTrue("Runtime reentry timed out", process.waitFor(20, TimeUnit.SECONDS))
            assertTrue(log.length() < 65536)
            val output = log.readText()
            assertEquals(output, 0, process.exitValue())
            assertTrue(output, output.contains("PASS 200 kernel exec transitions with the same inherited filter and restored dispatcher"))
            report.writeText(JSONObject().put("loader_sha256", digest).put("android_api", android.os.Build.VERSION.SDK_INT)
                .put("output", output).put("observation_ms", (System.nanoTime() - started) / 1e6)
                .put("stock_policy_verified", false).toString(2) + "\n")
        } finally {
            if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
            process.inputStream.close()
            log.delete()
        }
    }
}
