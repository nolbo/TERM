package dev.terminal.runtime.directlinux

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

@RunWith(AndroidJUnit4::class)
class DirectLinuxLoaderTest {
    @Test fun runsLinuxElfFromDataWithoutPtraceOrKernelExec() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("directLinuxLoader") == "true")
        require(arguments.getString("directLinuxBenchmark") != "true" || arguments.getString("directLinuxTrace") != "true") {
            "Trace logging must be disabled for performance comparisons"
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.terminal.runtime.test")
        val packaged = ApkNativeExecutableLoader(context)
        val executable = packaged.resolvePackagedExecutable("direct-linux-loader").getOrThrow()
        fun digest(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val expected = requireNotNull(arguments.getString("directLinuxLoaderSha256"))
        require(expected.matches(Regex("[0-9a-f]{64}")))
        assertEquals(expected, digest(executable.readBytes()))
        val manifest = JSONObject(context.assets.open("direct-linux-fixtures/manifest.json").bufferedReader().use { it.readText() })
        val temporary = Files.createTempDirectory(context.filesDir.toPath(), "direct-linux-elf-").toFile()
        val root = File(temporary, "root").apply { mkdir() }
        val results = JSONArray()
        var policy: List<String> = emptyList()
        val benchmarks = JSONArray()
        fun run(path: String, vararg args: String, expectedCode: Int = 0, useProot: Boolean = false): String {
            val log = File(temporary, "output")
            val engine = if (useProot) packaged.resolvePackagedExecutable("proot").getOrThrow() else executable
            val profile = if (useProot) RootlessAccelerationProfile(File(engine.parentFile, "libproot-faststat.so")) else null
            val command = if (profile != null) listOf(engine.path, "--link2symlink", "-0", "-r", root.path,
                "-b", "/dev", "-b", "/proc", "-b", profile.binding, "-w", "/", "/bin/env", "-i", "PATH=/bin:/usr/bin") +
                profile.guestEnvironment(null).map { (name, value) -> "$name=$value" } + listOf(path) + args
                else listOf(executable.path, root.path, path) + args
            val builder = ProcessBuilder(command)
                .directory(temporary).redirectErrorStream(true).redirectOutput(log)
            builder.environment().apply {
                keys.filter { it.startsWith("PROOT_") || it == "LD_PRELOAD" }.toList().forEach { remove(it) }
                if (profile != null) {
                    profile.hostEnvironment.forEach { put(it.substringBefore('='), it.substringAfter('=')) }
                    put("PROOT_LOADER", packaged.resolvePackagedExecutable("proot-loader").getOrThrow().path)
                    put("PROOT_TMP_DIR", temporary.path)
                }
            }
            builder.environment()["DIRECT_LINUX_POLICY"] = "1"
            if (arguments.getString("directLinuxTrace") == "true") builder.environment()["DIRECT_LINUX_TRACE"] = "1"
            val process = builder.start()
            process.outputStream.close()
            try {
                assertTrue("ELF process timeout: $path", process.waitFor(20, TimeUnit.SECONDS))
                assertTrue("Unbounded output", log.length() <= 65536)
                val output = log.readText()
                val observedPolicy = output.lineSequence().filter { it.startsWith("POLICY ") }.map { it.removePrefix("POLICY ") }.toList()
                if (observedPolicy.isNotEmpty()) policy = observedPolicy
                assertEquals("$path: $output", expectedCode, process.exitValue())
                results.put(JSONObject().put("program", path).put("exit", process.exitValue()).put("engine", if (useProot) "proot" else "direct-linux"))
                return output
            } finally {
                if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
                process.inputStream.close()
            }
        }
        try {
            val files = manifest.getJSONArray("files")
            for (i in 0 until files.length()) {
                val entry = files.getJSONObject(i)
                val relative = entry.getString("path")
                require(!relative.startsWith('/') && relative.split('/').all { it.isNotEmpty() && it != "." && it != ".." })
                val data = context.assets.open("direct-linux-fixtures/$relative").use { it.readBytes() }
                assertEquals(entry.getLong("size"), data.size.toLong())
                assertEquals(entry.getString("sha256"), digest(data))
                File(root, relative).apply { parentFile!!.mkdirs(); writeBytes(data); assertTrue(setExecutable(true)) }
            }
            File(root, "tmp").mkdir()
            File(root, "input").writeText("fixture\n")
            mapOf("absolute-link" to "/input", "relative-link" to "input", "cycle-a" to "cycle-b",
                "cycle-b" to "cycle-a", "outside-link" to "/system/build.prop", "bin/sh" to "dash").forEach { (name, target) ->
                Files.createSymbolicLink(File(root, name).toPath(), File(target).toPath())
            }
            run("/bin/true")
            assertTrue(run("/bin/printf", "%s:%d\n", "linux", "42").contains("linux:42\n"))
            assertTrue(run("/bin/sh", "-c", "read value < /input; cd /tmp; printf '%s:%s\\n' \"\$value\" \"\$PWD\"")
                .contains("fixture:/tmp\n"))
            for (kind in listOf("static", "dynamic")) {
                assertTrue(run("/bin/guest-$kind", "hello guest").contains("PASS Linux ELF auxv/BSS/TLS/heap/path/errno/mask"))
                run("/bin/guest-$kind", "sigbus", expectedCode = 77)
            }
            File(root, "not-elf").apply { writeText("not an ELF"); setExecutable(true) }
            assertTrue(run("/not-elf", expectedCode = 126).contains("Direct Linux:"))
            val rejected = manifest.getJSONArray("rejected_elfs")
            for (i in 0 until rejected.length()) {
                assertTrue(run(rejected.getString(i), expectedCode = 126).contains("Direct Linux:"))
            }
            if (arguments.getString("directLinuxBenchmark") == "true") {
                check(dev.terminal.runtime.BuildConfig.ROOTLESS_ACCELERATION) { "Compare with the current accelerated payload" }
                File(root, "usr/local/lib").mkdirs()
                repeat(7) { round ->
                    val modes = if (round % 2 == 0) listOf(false, true) else listOf(true, false)
                    modes.forEach { useProot ->
                        val output = run("/bin/guest-dynamic", "benchmark", useProot = useProot)
                        val lines = output.lineSequence().filter { it.startsWith("BENCH ") }.toList()
                        assertEquals(output, 2, lines.size)
                        lines.forEach { line ->
                            val sample = JSONObject(line.removePrefix("BENCH "))
                            if (useProot) assertEquals("Rootless preload path was not used", 5000, sample.getInt("acceleration_hits"))
                            benchmarks.put(sample.put("engine", if (useProot) "proot" else "direct-linux").put("round", round))
                        }
                    }
                }
            }
            val report = JSONObject().put("schema", 1).put("loader_sha256", expected)
                .put("fixtures", manifest).put("android_api", android.os.Build.VERSION.SDK_INT)
                .put("initial_process_policy", JSONArray(policy))
                .put("results", results).put("stock_policy_verified", false)
                .put("benchmarks", benchmarks)
            if (benchmarks.length() > 0) {
                val proot = packaged.resolvePackagedExecutable("proot").getOrThrow()
                report.put("proot_sha256", digest(proot.readBytes()))
                    .put("preload_sha256", digest(File(proot.parentFile, "libproot-faststat.so").readBytes()))
            }
            File(context.filesDir, "direct-linux-loader-result.json").writeText(report.toString(2) + "\n")
        } finally {
            // Files.walk does not follow symlinks by default.
            Files.walk(temporary.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
