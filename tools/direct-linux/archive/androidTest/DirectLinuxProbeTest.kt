package dev.terminal.runtime.directlinux

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.native.ApkNativeExecutableLoader
import dev.terminal.runtime.rootless.RootlessAccelerationProfile
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in, standalone test APK only; never modifies a user's rootfs. */
@RunWith(AndroidJUnit4::class)
class DirectLinuxProbeTest {
    @Test fun untracedDispatcherFromApplicationProcess() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("directLinuxProbe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.terminal.runtime.test")
        val loader = ApkNativeExecutableLoader(context)
        val probe = loader.resolvePackagedExecutable("direct-linux-probe").getOrThrow()
        fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        val expected = requireNotNull(arguments.getString("directLinuxProbeSha256"))
        require(expected.matches(Regex("[0-9a-f]{64}")))
        assertEquals("Packaged probe digest", expected, digest(probe))
        val proot = loader.resolvePackagedExecutable("proot").getOrThrow()
        val prootLoader = loader.resolvePackagedExecutable("proot-loader").getOrThrow()
        val acceleration = if (dev.terminal.runtime.BuildConfig.ROOTLESS_ACCELERATION)
            RootlessAccelerationProfile(File(proot.parentFile, "libproot-faststat.so")).hostEnvironment
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            else emptyMap()
        val temporary = Files.createTempDirectory(context.filesDir.toPath(), "direct-linux-").toFile()
        val results = JSONArray()
        val policy = JSONArray()
        fun run(command: List<String>, environment: Map<String, String> = emptyMap()): String {
            val builder = ProcessBuilder(command).directory(temporary).redirectErrorStream(true)
            builder.environment().apply {
                keys.filter { it.startsWith("PROOT_") || it == "LD_PRELOAD" }.toList().forEach { remove(it) }
                putAll(environment)
            }
            // File output avoids pipe deadlock and is deleted with this fixture.
            val log = File(temporary, "process.log")
            val process = builder.redirectOutput(log).start()
            process.outputStream.close()
            try {
                assertTrue("Probe timeout", process.waitFor(30, TimeUnit.SECONDS))
                assertTrue("Unexpected output size", log.length() < 64 * 1024)
                val output = log.readText()
                assertEquals(output, 0, process.exitValue())
                return output
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
                process.inputStream.close()
            }
        }
        try {
            File(temporary, "input").writeText("direct-linux-fixture\n")
            Files.createSymbolicLink(File(temporary, "link").toPath(), File("input").toPath())
            val checked = run(listOf(probe.path, temporary.path, "check", "1"))
            assertTrue(checked, checked.contains("PASS direct-linux policy, W^X, trap, EFAULT, fork, threads, inherited denial"))
            checked.lineSequence().filter { it.startsWith("POLICY ") }.forEach { policy.put(it.removePrefix("POLICY ")) }
            // Same APK binary, same file, warm cache, alternating order, preparation
            // excluded. Raw SVC deliberately does not use glibc preload acceleration.
            repeat(7) { round ->
                val modes = if (round % 2 == 0) listOf("direct", "trap", "guest") else listOf("guest", "trap", "direct")
                modes.forEach { mode ->
                    val output = if (mode == "guest") {
                        run(listOf(proot.path, "--link2symlink", "-0", "-r", "/", "-b", "${temporary.path}:/probe",
                            "-w", "/probe", probe.path, "/probe", mode, "5000"),
                            acceleration + mapOf("PROOT_LOADER" to prootLoader.path, "PROOT_TMP_DIR" to temporary.path))
                    } else run(listOf(probe.path, temporary.path, mode, "5000"))
                    val line = output.lineSequence().single { it.startsWith("RESULT ") }
                    results.put(JSONObject(line.removePrefix("RESULT ")).put("round", round))
                }
            }
            val report = JSONObject().put("schema", 1)
                .put("probe_sha256", expected).put("proot_sha256", digest(proot))
                .put("loader_sha256", digest(prootLoader))
                .put("proot_engine_acceleration", dev.terminal.runtime.BuildConfig.ROOTLESS_ACCELERATION)
                .put("android_api", android.os.Build.VERSION.SDK_INT)
                .put("page_size", android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE))
                .put("initial_process_policy", policy).put("checks_passed", true)
                .put("scope", "Single warm file, raw open/read/fcntl/close; no general rootfs, ELF loader, or end-to-end performance claim")
                .put("samples", results)
            File(context.filesDir, "direct-linux-probe-result.json").writeText(report.toString(2) + "\n")
            Log.i("DirectLinuxProbe", "PASS policy and transport checks; 21 samples written to direct-linux-probe-result.json")
        } finally {
            // No recursion through links and no existing user data in this directory.
            temporary.listFiles().orEmpty().forEach { Files.deleteIfExists(it.toPath()) }
            Files.deleteIfExists(temporary.toPath())
        }
    }
}
