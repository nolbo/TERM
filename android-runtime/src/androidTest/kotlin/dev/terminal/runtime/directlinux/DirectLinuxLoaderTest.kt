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
        val baselineDigest = arguments.getString("directLinuxBaselineSha256")
        require((arguments.getString("directLinuxBenchmark") != "true" && baselineDigest == null) ||
            (arguments.getString("directLinuxTrace") != "true" && arguments.getString("directLinuxProfile") != "true" &&
                arguments.getString("directLinuxApplicationProfile") != "true" && arguments.getString("directLinuxStartupProfile") != "true")) {
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
        // A comparison-only APK puts the old loader in the existing test probe
        // slot. Both binaries still pass through the central APK resolver.
        val baseline = baselineDigest?.let {
            require(it.matches(Regex("[0-9a-f]{64}")))
            packaged.resolvePackagedExecutable("direct-linux-probe").getOrThrow().also { file ->
                assertEquals(it, digest(file.readBytes()))
            }
        }
        val manifest = JSONObject(context.assets.open("direct-linux-fixtures/manifest.json").bufferedReader().use { it.readText() })
        val temporary = Files.createTempDirectory(context.filesDir.toPath(), "direct-linux-elf-").toFile()
        val root = File(temporary, "root").apply { mkdir() }
        val results = JSONArray()
        var policy: List<String> = emptyList()
        val benchmarks = JSONArray()
        val execProfiles = JSONArray()
        val applicationProfiles = JSONArray()
        val startupProfiles = JSONArray()
        val ptyResults = JSONArray()
        val networkResults = JSONArray()
        val signalResults = JSONArray()
        val threadResults = JSONArray()
        fun launch(path: String, args: Array<out String>, useProot: Boolean, terminal: Boolean = false, useBaseline: Boolean = false): ProcessBuilder {
            check(!useProot || !useBaseline)
            val engine = if (useProot) packaged.resolvePackagedExecutable("proot").getOrThrow()
                else if (useBaseline) requireNotNull(baseline) else executable
            val profile = if (useProot) RootlessAccelerationProfile(File(engine.parentFile, "libproot-faststat.so")) else null
            val command = if (profile != null) listOf(engine.path, "--link2symlink", "-0", "-r", root.path,
                "-b", "/dev", "-b", "/proc", "-b", profile.binding, "-w", "/", "/bin/env", "-i", "PATH=/bin:/usr/bin") +
                (if (terminal) listOf("TERM=xterm-256color") else emptyList()) +
                profile.guestEnvironment(null).map { (name, value) -> "$name=$value" } + listOf(path) + args
                else listOf(engine.path, root.path, path) + args
            val builder = ProcessBuilder(command)
                .directory(temporary)
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
            if (arguments.getString("directLinuxStartupProfile") == "true" &&
                (path == "/bin/startup-dynamic" || args.contentEquals(arrayOf("shell-benchmark"))))
                builder.environment()["DIRECT_LINUX_STARTUP_PROFILE"] = "1"
            return builder
        }
        fun run(path: String, vararg args: String, expectedCode: Int = 0, useProot: Boolean = false, useBaseline: Boolean = false): String {
            val log = File(temporary, "output")
            val builder = launch(path, args, useProot, useBaseline = useBaseline).redirectErrorStream(true).redirectOutput(log)
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
                assertEquals("${if (useProot) "proot" else if (useBaseline) "baseline" else "direct-linux"} $path: $output\n$residuals", expectedCode, process.exitValue())
                results.put(JSONObject().put("program", path).put("exit", process.exitValue()).put("engine", if (useProot) "proot" else if (useBaseline) "baseline" else "direct-linux")
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
                assertTrue(run("/bin/process-$kind", "inheritance").contains("PASS fork inheritance, partial madvise errors and subsequent exec"))
                assertTrue(run("/bin/files-$kind").contains("PASS filesystem rename/dirfd/cwd/symlinks/modes/timestamps/unlinked-FDs"))
                assertTrue(run("/bin/tty-$kind", "no-terminal").contains("PASS no controlling terminal"))
            }
            if (arguments.getString("directLinuxThreads") == "true") {
                check(manifest.optBoolean("applications"))
                android.system.Os.mkfifo(File(root, "tmp/thread-fifo").path, 384)
                for (reference in listOf(false, true)) {
                    val checks = JSONArray()
                    val limitations = JSONArray()
                    for (kind in listOf("static", "dynamic")) {
                        for (scenario in listOf("exec-leader", "exec-worker", "exec-exited-leader")) {
                            val output = run("/bin/concurrency-$kind", scenario, useProot = reference)
                            val case = "${if (reference) "proot" else "direct-linux"}/$kind/$scenario"
                            if (reference && scenario != "exec-leader" &&
                                output.contains("proot info: vpid 1: terminated with signal 11")) {
                                assertFalse(case, output.contains("PASS concurrent exec"))
                                limitations.put("$kind $scenario: reference tracee terminated with SIGSEGV (tracer exit status 0)")
                                continue
                            }
                            assertTrue("$case: $output", output.contains("PASS concurrent exec"))
                            assertFalse("$case: $output", output.contains("FAIL "))
                            output.lineSequence().filter { it.startsWith("PASS ") }.forEach { checks.put("$kind $scenario: $it") }
                        }
                        for (scenario in listOf("common", "main-exit")) {
                            val output = run("/bin/threads-$kind", scenario, useProot = reference)
                            assertTrue(output, output.contains("PASS "))
                            output.lineSequence().filter { it.startsWith("PASS ") }.forEach { checks.put("$kind: $it") }
                        }
                        val unix = run("/bin/threads-$kind", "main-exit-unix", expectedCode = if (reference) 78 else 0, useProot = reference)
                        if (reference) {
                            assertTrue(unix, unix.contains("LIMIT Unix getsockname did not return the guest pathname after leader exit"))
                            limitations.put("$kind: Unix getsockname returned a different pathname after leader exit")
                        } else assertTrue(unix, unix.contains("PASS Unix pathname operations after leader exit"))
                    }
                    val python = run("/usr/bin/python3", "/threads.py", useProot = reference)
                    assertTrue(python, python.contains("PASS Python ThreadPoolExecutor"))
                    checks.put("PASS Python ThreadPoolExecutor, thread-local state and concurrent file work")
                    threadResults.put(JSONObject().put("engine", if (reference) "proot" else "direct-linux")
                        .put("checks", checks).put("observed_limitations", limitations)
                        .put("unix_names_after_leader_exit_verified", !reference))
                }
            }
            if (arguments.getString("directLinuxSignalMasks") == "true") {
                android.system.Os.mkfifo(File(root, "tmp/blocking-fifo").path, 384)
                android.system.Os.mkfifo(File(root, "tmp/blocking-inner").path, 384)
                for (reference in listOf(false, true)) {
                    val checks = JSONArray()
                    val limitations = JSONArray()
                    for (kind in listOf("static", "dynamic")) {
                        for (scenario in listOf("signals", "cancel")) {
                            val selected = if (reference && scenario == "signals") "signals-reference" else scenario
                            val output = run("/bin/blocking-$kind", selected, useProot = reference)
                            assertTrue(output, output.contains("PASS "))
                            assertFalse(output, output.contains("FAIL "))
                            output.lineSequence().filter { it.startsWith("PASS ") }.forEach { checks.put("$kind: $it") }
                            output.lineSequence().filter { it.startsWith("LIMIT ") || it.startsWith("SKIP ") }
                                .forEach { limitations.put("$kind: $it") }
                        }
                        for (scenario in listOf("actions", "waits")) {
                            val output = run("/bin/signals-$kind", scenario, useProot = reference)
                            assertTrue(output, output.contains("PASS "))
                            output.lineSequence().filter { it.startsWith("PASS ") || it.startsWith("SKIP ") }
                                .forEach { checks.put("$kind: $it") }
                        }
                    }
                    signalResults.put(JSONObject().put("engine", if (reference) "proot" else "direct-linux")
                        .put("checks", checks).put("observed_limitations", limitations))
                }
            }
            if (arguments.getString("directLinuxPty") == "true") {
                for (reference in listOf(false, true)) {
                    val name = if (reference) "proot" else "direct-linux"
                    val result = try {
                        DirectLinuxPtyScenario.verify { path, args -> launch(path, args, reference, terminal = true) }
                    } catch (failure: Exception) { throw IllegalStateException("$name PTY: ${failure.message}", failure) }
                    ptyResults.put(result.put("engine", name))
                }
            }
            if (arguments.getString("directLinuxNetwork") == "true") {
                check(manifest.optBoolean("applications"))
                for (reference in listOf(false, true)) {
                    val checks = JSONArray()
                    val limitations = JSONArray()
                    for (kind in listOf("static", "dynamic")) {
                        val output = run("/bin/network-$kind", if (reference) "common" else "direct-policy", useProot = reference)
                        assertTrue(output, output.contains("PASS Linux network syscalls\n"))
                        output.lineSequence().filter { it.startsWith("PASS ") }.forEach { checks.put("$kind: $it") }
                        val unix = run("/bin/unix-$kind", if (reference) "common" else "direct", useProot = reference)
                        assertTrue(unix, unix.contains("PASS common Unix socket calls\n"))
                        unix.lineSequence().filter { it.startsWith("PASS ") }.forEach { checks.put("$kind: $it") }
                        if (reference) {
                            val failure = run("/bin/unix-$kind", "datagrams", expectedCode = 1, useProot = true)
                            assertTrue(failure, failure.contains("UNIX DATAGRAM sendto result=-1 errno=2\n"))
                            limitations.put("$kind: named Unix datagram sendto failed with ENOENT")
                        }
                        val longName = run("/bin/unix-$kind", "long-name", expectedCode = if (reference) 0 else 78, useProot = reference)
                        if (reference) assertTrue(longName, longName.contains("PASS Unix 100-byte pathname bind\n"))
                        else {
                            assertTrue(longName, longName.contains("UNIX LIMIT 100-byte pathname bind errno=36\n"))
                            limitations.put("$kind: 100-byte Unix pathname bind failed with ENAMETOOLONG")
                        }
                    }
                    val output = run("/usr/bin/python3", "/network.py", useProot = reference)
                    assertTrue(output, output.contains("PASS Linux network applications\n"))
                    output.lineSequence().filter { it.startsWith("PASS ") }.forEach { checks.put(it) }
                    networkResults.put(JSONObject().put("engine", if (reference) "proot" else "direct-linux").put("checks", checks)
                        .put("local_glibc_dns_verified", true).put("external_dns_verified", false)
                        .put("pathname_unix_stream_verified", true).put("pathname_unix_datagram_verified", !reference)
                        .put("unix_100_byte_name_verified", reference).put("observed_limitations", limitations)
                        .put("named_unix_sockets_verified", false))
                }
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
            if (baseline != null) {
                check(dev.terminal.runtime.BuildConfig.ROOTLESS_ACCELERATION)
                File(root, "usr/local/lib").mkdirs()
                val orders = listOf(
                    listOf("baseline", "direct-linux", "proot"), listOf("baseline", "proot", "direct-linux"),
                    listOf("direct-linux", "baseline", "proot"), listOf("direct-linux", "proot", "baseline"),
                    listOf("proot", "baseline", "direct-linux"), listOf("proot", "direct-linux", "baseline"),
                )
                repeat(18) { round ->
                    orders[round % orders.size].forEach { engine ->
                        val output = run("/bin/process-dynamic", "shell-benchmark",
                            useProot = engine == "proot", useBaseline = engine == "baseline")
                        assertTrue(output, output.contains("PASS shell-exec\n"))
                        val lines = output.lineSequence().filter { it.startsWith("BENCH ") }.toList()
                        assertEquals(output, 1, lines.size)
                        benchmarks.put(JSONObject(lines.single().removePrefix("BENCH "))
                            .put("engine", engine).put("round", round).put("comparison", "same-apk"))
                    }
                }
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
                    for (scenario in listOf("shell-benchmark", "arguments-benchmark")) {
                        modes.forEach { useProot ->
                            val output = run("/bin/process-dynamic", scenario, useProot = useProot)
                            assertTrue(output, output.contains("PASS shell-exec\n"))
                            val lines = output.lineSequence().filter { it.startsWith("BENCH ") }.toList()
                            assertEquals(output, 1, lines.size)
                            benchmarks.put(JSONObject(lines.single().removePrefix("BENCH "))
                                .put("engine", if (useProot) "proot" else "direct-linux").put("round", round))
                        }
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
            if (arguments.getString("directLinuxStartupProfile") == "true") {
                for (scenario in listOf("fork-exit", "exec-static", "exec-dynamic", "shell-benchmark")) {
                    val output = if (scenario == "shell-benchmark") run("/bin/process-dynamic", scenario)
                        else run("/bin/startup-dynamic", scenario)
                    output.lineSequence().filter { it.startsWith("STARTUP_HOST ") }.forEach {
                        startupProfiles.put(JSONObject(it.removePrefix("STARTUP_HOST ")).put("kind", "host").put("case", scenario))
                    }
                    val samples = output.lineSequence().filter { it.startsWith("STARTUP_SAMPLE ") }.toList()
                    assertEquals(if (scenario == "shell-benchmark") 0 else 30, samples.size)
                    val runtime = output.lineSequence().filter { it.startsWith("STARTUP_RUNTIME ") }.toList()
                    assertEquals(32.takeIf { scenario == "shell-benchmark" } ?: 31, runtime.size)
                    samples.forEach { startupProfiles.put(JSONObject(it.removePrefix("STARTUP_SAMPLE ")).put("kind", "sample")) }
                    runtime.forEach {
                        val entry = JSONObject(it.removePrefix("STARTUP_RUNTIME "))
                        assertEquals(0, entry.getInt("truncated"))
                        startupProfiles.put(entry.put("kind", "runtime").put("case", scenario))
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
                .put("baseline_sha256", baselineDigest ?: JSONObject.NULL)
                .put("application_reference_skipped", arguments.getString("directLinuxSkipApplicationReference") == "true")
                .put("fixtures", manifest).put("android_api", android.os.Build.VERSION.SDK_INT)
                .put("initial_process_policy", JSONArray(policy))
                .put("results", results).put("stock_policy_verified", false)
                .put("benchmarks", benchmarks).put("exec_profiles", execProfiles)
                .put("application_profiles", applicationProfiles)
                .put("startup_profiles", startupProfiles)
                .put("pty_results", ptyResults)
                .put("network_results", networkResults)
                .put("signal_results", signalResults)
                .put("thread_results", threadResults)
                .put("exec_profile_stages", JSONArray(listOf("arguments", "read_validate", "reset", "unmap", "map", "entry", "dispose")))
                .put("exec_profile_map_stages", JSONArray(listOf("reserve", "file_map", "verify", "bss", "final_protect")))
            if (benchmarks.length() > 0 || ptyResults.length() > 0 || networkResults.length() > 0 || signalResults.length() > 0 || threadResults.length() > 0) {
                val proot = packaged.resolvePackagedExecutable("proot").getOrThrow()
                report.put("proot_sha256", digest(proot.readBytes()))
                    .put("proot_loader_sha256", digest(packaged.resolvePackagedExecutable("proot-loader").getOrThrow().readBytes()))
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
