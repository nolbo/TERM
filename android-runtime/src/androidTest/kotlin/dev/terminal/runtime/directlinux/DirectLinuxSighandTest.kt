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
class DirectLinuxSighandTest {
    @Test fun sharesDispositionsOnlyWhenRequested() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("directLinuxSighand") == "true")
        // This test-only APK puts the compiled component harness in the probe
        // slot. It does not enable unsupported clone variants in the guest.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.terminal.runtime.test")
        val executable = ApkNativeExecutableLoader(context).resolvePackagedExecutable("direct-linux-probe").getOrThrow()
        val expected = requireNotNull(arguments.getString("directLinuxSighandSha256"))
        require(expected.matches(Regex("[0-9a-f]{64}")))
        val digest = MessageDigest.getInstance("SHA-256").digest(executable.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(expected, digest)
        val report = File(context.filesDir, "direct-linux-sighand-result.json")
        check(!report.exists() || report.delete())
        val log = File.createTempFile("direct-linux-sighand-", ".log", context.cacheDir)
        val started = System.nanoTime()
        val builder = ProcessBuilder(executable.path).redirectErrorStream(true).redirectOutput(log)
        builder.environment().clear()
        val process = builder.start()
        process.outputStream.close()
        try {
            assertTrue("Shared-VM signal-table test timed out", process.waitFor(20, TimeUnit.SECONDS))
            assertTrue(log.length() < 65536)
            val output = log.readText()
            assertEquals(output, 0, process.exitValue())
            assertTrue(output, output.contains("PASS 192 real shared-VM clones: independent/shared sighand, vfork, masks/flags, exec-reset isolation and reclamation"))
            assertTrue(output, output.contains("PASS allocation and clone failure preserve parent dispositions and reclaim private tables"))
            report.writeText(JSONObject().put("component_sha256", digest).put("android_api", android.os.Build.VERSION.SDK_INT)
                .put("output", output).put("observation_ms", (System.nanoTime() - started) / 1e6)
                .put("guest_shared_vm_dispatcher_verified", false).put("stock_policy_verified", false).toString(2) + "\n")
        } finally {
            if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
            process.inputStream.close()
            log.delete()
        }
    }
}
