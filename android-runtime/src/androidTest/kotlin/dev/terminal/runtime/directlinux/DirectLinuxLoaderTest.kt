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
        require(arguments.getString("directLinuxBenchmark") != "true" ||
            (arguments.getString("directLinuxTrace") != "true" && arguments.getString("directLinuxProfile") != "true" &&
                arguments.getString("directLinuxApplicationProfile") != "true")) {
            "Trace and profile logging must be disabled for performance comparisons"
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.terminal.runtime.test")
        val reportFile = File(context.filesDir, "direct-linux-loader-result.json")
        check(!reportFile.exists() || reportFile.delete()) { "Cannot remove stale result" }
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
        val execProfiles = JSONArray()
        val applicationProfiles = JSONArray()
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
                keys.filter { it.startsWith("PROOT_") || it.startsWith("DIRECT_LINUX_") || it == "LD_PRELOAD" }.toList().forEach { remove(it) }
                if (profile != null) {
                    profile.hostEnvironment.forEach { put(it.substringBefore('='), it.substringAfter('=')) }
                    put("PROOT_LOADER", packaged.resolvePackagedExecutable("proot-loader").getOrThrow().path)
                    put("PROOT_TMP_DIR", temporary.path)
                }
            }
            builder.environment()["DIRECT_LINUX_POLICY"] = "1"
            if (arguments.getString("directLinuxTrace") == "true") builder.environment()["DIRECT_LINUX_TRACE"] = "1"
            if (arguments.getString("directLinuxProfile") == "true" && args.contentEquals(arrayOf("shell-benchmark")))
                builder.environment()["DIRECT_LINUX_EXEC_PROFILE"] = "1"
            if (arguments.getString("directLinuxApplicationProfile") == "true" && args.contentEquals(arrayOf("/applications.py", "profile")))
                builder.environment()["DIRECT_LINUX_EXEC_PROFILE"] = "1"
            val started = System.nanoTime()
            val process = builder.start()
            process.outputStream.close()
            try {
                if (!process.waitFor(20, TimeUnit.SECONDS)) {
                    process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS)
                    val tail = java.io.RandomAccessFile(log, "r").use { file ->
                        file.seek((file.length() - 8192).coerceAtLeast(0))
                        ByteArray((file.length() - file.filePointer).toInt()).also { file.readFully(it) }.decodeToString()
                    }
                    fail("ELF process timeout: $path $tail")
                }
                assertTrue("Unbounded output", log.length() <= 65536)
                val output = log.readText()
                val observedPolicy = output.lineSequence().filter { it.startsWith("POLICY ") }.map { it.removePrefix("POLICY ") }.toList()
                if (observedPolicy.isNotEmpty()) policy = observedPolicy
                val residuals = if (process.exitValue() != expectedCode && useProot) {
                    Files.walk(root.toPath()).use { paths ->
                        paths.filter { it.fileName.toString().startsWith(".l2s.") || it.fileName.toString().startsWith(".proot.") }
                            .limit(12).map { root.toPath().relativize(it).toString() }.toArray().joinToString("\n")
                    }
                } else ""
                assertEquals("${if (useProot) "proot" else "direct-linux"} $path: $output\n$residuals", expectedCode, process.exitValue())
                results.put(JSONObject().put("program", path).put("exit", process.exitValue()).put("engine", if (useProot) "proot" else "direct-linux")
                    .put("arguments", JSONArray(args.toList())).put("observation_ms", (System.nanoTime() - started) / 1e6))
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
                File(root, relative).apply {
                    parentFile!!.mkdirs(); writeBytes(data)
                    assertTrue(setExecutable(entry.optBoolean("executable", true)))
                }
            }
            File(root, "tmp").mkdir()
            File(root, "input").writeText("fixture\n")
            mapOf("absolute-link" to "/input", "relative-link" to "input", "cycle-a" to "cycle-b",
                "cycle-b" to "cycle-a", "outside-link" to "/system/build.prop", "bin/sh" to "dash").forEach { (name, target) ->
                Files.createSymbolicLink(File(root, name).toPath(), File(target).toPath())
            }
            if (manifest.optBoolean("applications")) {
                assertTrue(run("/usr/bin/python3", "/applications.py").contains("PASS Linux applications"))
            }
            run("/bin/true")
            val replaceable = File(root, "replaceable")
            replaceable.writeBytes(File(root, "bin/true").readBytes())
            assertTrue(replaceable.setExecutable(true))
            run("/replaceable")
            // A warm inode must expose new program bytes after an in-place update.
            replaceable.writeBytes(File(root, "bin/printf").readBytes())
            assertTrue(run("/replaceable", "%s\n", "updated").contains("updated\n"))
            // Atomic package replacement must also select the new inode.
            val replacement = File(root, "replacement")
            replacement.writeBytes(File(root, "bin/true").readBytes())
            assertTrue(replacement.setExecutable(true))
            Files.move(replacement.toPath(), replaceable.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            run("/replaceable")
            assertTrue(run("/bin/printf", "%s:%d\n", "linux", "42").contains("linux:42\n"))
            assertTrue(run("/bin/sh", "-c", "read value < /input; cd /tmp; printf '%s:%s\\n' \"\$value\" \"\$PWD\"")
                .contains("fixture:/tmp\n"))
            for (kind in listOf("static", "dynamic")) {
                assertTrue(run("/bin/guest-$kind", "hello guest").contains("PASS Linux ELF auxv/BSS/TLS/heap/path/errno/mask"))
                run("/bin/guest-$kind", "sigbus", expectedCode = 77)
            }
            File(root, "script").apply {
                writeText("#!/bin/sh -e\nprintf 'script:%s:%s\\n' \"\$1\" \"\$DL_TEST\"\n/bin/true\n")
                assertTrue(setExecutable(true))
            }
            File(root, "script-loop").apply { writeText("#!/script-loop\n"); assertTrue(setExecutable(true)) }
            File(root, "no-execute").apply { writeText("#!/bin/sh\n"); assertTrue(setExecutable(false, false)) }
            assertTrue(run("/script", "space argument").contains("script:space argument:"))
            assertTrue(run("/bin/sh", "-c", "DL_TEST=environment /script 'shell argument'").contains("script:shell argument:environment"))
            assertTrue(run("/bin/sh", "-c", "/bin/printf 'pipe\\n' | /bin/sh -c 'read value; printf \"external:%s\\n\" \"\$value\"'; /bin/true; printf 'status:%s\\n' \"\$?\"")
                .contains("external:pipe\nstatus:0\n"))
            assertTrue(run("/bin/sh", "-c", "value=\$(/bin/printf substitution); /bin/true & wait; printf '%s\\n' \"\$value\"")
                .contains("substitution\n"))
            for (kind in listOf("static", "dynamic")) {
                assertTrue(run("/bin/process-$kind", "processes").contains("PASS exec PID/argv/env/cwd/CLOEXEC/signals/mapping/TLS"))
                assertTrue(run("/bin/fds-$kind").contains("PASS close_range bounds/flags/sparse/CLOEXEC/fork/vfork/private FDs"))
                assertTrue(run("/bin/process-$kind", "repeat", "30").contains("PASS repeated exec memory/TLS cleanup"))
                assertTrue(run("/bin/files-$kind").contains("PASS filesystem rename/dirfd/cwd/symlinks/modes/timestamps/unlinked-FDs"))
            }
            val filesScenario = """
                set -e
                mkdir -p /tmp/tools/source/nested
                i=0
                while [ "${'$'}i" -lt 128 ]; do
                    printf 'file:%s\n' "${'$'}i" > "/tmp/tools/source/file-${'$'}i"
                    i=${'$'}((i + 1))
                done
                chmod 640 /tmp/tools/source/file-0
                touch -t 202001020304.05 /tmp/tools/source/file-0
                ln -s ../file-0 /tmp/tools/source/nested/link
                cp -R --reflink=never --preserve=mode,timestamps /tmp/tools/source /tmp/tools/copied
                mv /tmp/tools/copied /tmp/tools/moved
                cat /tmp/tools/moved/nested/link
                rm -r /tmp/tools/source
                printf 'PASS GNU filesystem tools\n'
            """.trimIndent()
            val filesOutput = run("/bin/sh", "-c", filesScenario)
            assertTrue(filesOutput, filesOutput.contains("file:0\nPASS GNU filesystem tools\n"))
            val moved = File(root, "tmp/tools/moved")
            repeat(128) { assertEquals("file:$it\n", File(moved, "file-$it").readText()) }
            assertEquals(416, android.system.Os.stat(File(moved, "file-0").path).st_mode and 511) // 0640
            assertEquals(1577934245L, android.system.Os.stat(File(moved, "file-0").path).st_mtime)
            assertEquals("../file-0", Files.readSymbolicLink(File(moved, "nested/link").toPath()).toString())
            assertFalse(File(root, "tmp/tools/source").exists())
            run("/bin/rm", "-r", "/tmp/tools")
            assertFalse(File(root, "tmp/tools").exists())
            File(root, "not-elf").apply { writeText("not an ELF"); setExecutable(true) }
            assertTrue(run("/not-elf", expectedCode = 126).contains("Direct Linux:"))
            val rejected = manifest.getJSONArray("rejected_elfs")
            for (i in 0 until rejected.length()) {
                assertTrue(run(rejected.getString(i), expectedCode = 126).contains("Direct Linux:"))
            }
            if (arguments.getString("directLinuxBenchmark") == "true") {
                check(dev.terminal.runtime.BuildConfig.ROOTLESS_ACCELERATION) { "Compare with the current accelerated payload" }
                File(root, "usr/local/lib").mkdirs()
                File(root, "filesystem-scenario").writeText(filesScenario + "\n" + """
                    i=0
                    while [ "${'$'}i" -lt 128 ]; do
                        read value < "/tmp/tools/moved/file-${'$'}i"
                        [ "${'$'}value" = "file:${'$'}i" ] || exit 77
                        i=${'$'}((i + 1))
                    done
                    rm -r /tmp/tools
                """.trimIndent() + "\n")
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
                    modes.forEach { useProot ->
                        val output = run("/bin/process-dynamic", "shell-benchmark", useProot = useProot)
                        assertTrue(output, output.contains("PASS shell-exec\n"))
                        val lines = output.lineSequence().filter { it.startsWith("BENCH ") }.toList()
                        assertEquals(output, 1, lines.size)
                        benchmarks.put(JSONObject(lines.single().removePrefix("BENCH "))
                            .put("engine", if (useProot) "proot" else "direct-linux").put("round", round))
                    }
                    modes.forEach { useProot ->
                        val output = run("/bin/process-dynamic", "files-benchmark", "/filesystem-scenario", useProot = useProot)
                        assertTrue(output, output.contains("file:0\nPASS GNU filesystem tools\n"))
                        assertFalse(File(root, "tmp/tools").exists())
                        val lines = output.lineSequence().filter { it.startsWith("BENCH ") }.toList()
                        assertEquals(output, 1, lines.size)
                        benchmarks.put(JSONObject(lines.single().removePrefix("BENCH "))
                            .put("engine", if (useProot) "proot" else "direct-linux").put("round", round))
                    }
                    if (manifest.optBoolean("applications")) modes.forEach { useProot ->
                        if (useProot && arguments.getString("directLinuxSkipApplicationReference") == "true") return@forEach
                        val output = run("/bin/process-dynamic", "apps-benchmark", useProot = useProot)
                        assertTrue(output, output.contains("PASS Linux applications\n"))
                        val lines = output.lineSequence().filter { it.startsWith("BENCH ") }.toList()
                        assertEquals(output, 3, lines.size)
                        lines.forEach { line ->
                            benchmarks.put(JSONObject(line.removePrefix("BENCH "))
                                .put("engine", if (useProot) "proot" else "direct-linux").put("round", round))
                        }
                    }
                }
            }
            if (arguments.getString("directLinuxProfile") == "true") {
                val output = run("/bin/process-dynamic", "shell-benchmark")
                output.lineSequence().filter { it.startsWith("EXEC_PROFILE ") }.forEach {
                    execProfiles.put(JSONObject(it.removePrefix("EXEC_PROFILE ")))
                }
                assertEquals("Outer program + shell + thirty commands", 32, execProfiles.length())
            }
            if (arguments.getString("directLinuxApplicationProfile") == "true") {
                check(manifest.optBoolean("applications"))
                val output = run("/usr/bin/python3", "/applications.py", "profile")
                assertTrue(output, output.contains("PASS Linux applications\n"))
                output.lineSequence().filter { it.startsWith("APPLICATION_PROFILE ") }.forEach {
                    applicationProfiles.put(JSONObject(it.removePrefix("APPLICATION_PROFILE ")))
                }
                assertEquals(14, applicationProfiles.length())
            }
            // MAP_PRIVATE writes and BSS initialization must leave package bytes intact.
            for (i in 0 until files.length()) {
                val entry = files.getJSONObject(i)
                assertEquals(entry.getString("sha256"), digest(File(root, entry.getString("path")).readBytes()))
            }
            val report = JSONObject().put("schema", 1).put("loader_sha256", expected)
                .put("application_reference_skipped", arguments.getString("directLinuxSkipApplicationReference") == "true")
                .put("fixtures", manifest).put("android_api", android.os.Build.VERSION.SDK_INT)
                .put("initial_process_policy", JSONArray(policy))
                .put("results", results).put("stock_policy_verified", false)
                .put("benchmarks", benchmarks).put("exec_profiles", execProfiles)
                .put("application_profiles", applicationProfiles)
                .put("exec_profile_stages", JSONArray(listOf("arguments", "read_validate", "reset", "unmap", "map", "entry", "dispose")))
                .put("exec_profile_map_stages", JSONArray(listOf("reserve", "file_map", "verify", "bss", "final_protect")))
            if (benchmarks.length() > 0) {
                val proot = packaged.resolvePackagedExecutable("proot").getOrThrow()
                report.put("proot_sha256", digest(proot.readBytes()))
                    .put("preload_sha256", digest(File(proot.parentFile, "libproot-faststat.so").readBytes()))
            }
            reportFile.writeText(report.toString(2) + "\n")
        } finally {
            // Files.walk does not follow symlinks by default.
            Files.walk(temporary.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
