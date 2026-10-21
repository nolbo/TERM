package dev.terminal.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.protocol.SessionEvent
import dev.terminal.runtime.native.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeCliDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun bundledCaRootsVerifyPublicHttpsWhenExplicitlyRequested() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("nativePublicHttps") == "true")
        val root = File(context.cacheDir, "native-public-https-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val tools = NativeToolchain(context).prepare()
            val errors = File(root, "errors")
            val process = ProcessBuilder("${tools.getValue("PATH")}/curl", "--fail", "--silent", "--show-error",
                "--max-time", "20", "--max-filesize", "1048576", "https://curl.se/", "--output", File(root, "page").path)
                .redirectError(errors).apply { environment().putAll(tools + mapOf("HOME" to root.path, "TMPDIR" to root.path)) }.start()
            try {
                assertTrue(process.waitFor(25, TimeUnit.SECONDS))
                assertEquals(errors.readText(), 0, process.exitValue())
                assertTrue(File(root, "page").length() > 0)
            } finally { if (process.isAlive) process.destroyForcibly() }
        } finally { root.deleteRecursively() }
    }

    @Test fun diagnosticStorageFailureIsAnErrorResultWithoutTouchingExistingFiles() {
        val file = File(context.cacheDir, "native-diagnostic-blocked-${UUID.randomUUID()}").apply { writeText("preserve") }
        val blocked = object : android.content.ContextWrapper(context) {
            override fun getCacheDir(): File = file
        }
        try {
            val info = inspectNativeCompatibility(blocked)
            assertTrue(info.checks.any { it.name == "Storage / initialization" && !it.passed && it.detail.isNotBlank() })
            assertEquals("preserve", file.readText())
        } finally { file.delete() }
    }

    @Test fun commandAliasesAlwaysResolveToApprovedApkFilesAfterRepair() {
        val root = File(context.cacheDir, "native-alias-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : android.content.ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File = root
        }
        try {
            val tools = NativeToolchain(isolated).prepare()
            val link = File(tools.getValue("PATH"), "jq").toPath()
            val data = File(root, "downloaded-data").apply { writeText("not approved executable code") }
            java.nio.file.Files.delete(link)
            java.nio.file.Files.createSymbolicLink(link, data.toPath())
            NativeToolchain(isolated).prepare()
            assertEquals(ApkNativeExecutableLoader(context).resolvePackagedExecutable("jq").getOrThrow().canonicalFile,
                link.toFile().canonicalFile)
            assertEquals("not approved executable code", data.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun diagnosticsActuallyExecutesEveryToolWithoutUserFiles() {
        val info = inspectNativeCompatibility(context)
        assertEquals(true, info.shellExecutable)
        assertTrue(info.checks.joinToString("\n") { "${it.name}: ${it.detail}" },
            info.checks.isNotEmpty() && info.checks.all { it.passed })
    }

    @Test fun interactiveVimUtf8SaveSyntaxResizeAndNativeLifecycle() = runBlocking {
        val root = File(context.cacheDir, "native-cli-${UUID.randomUUID()}").apply { mkdirs() }
        val tools = NativeToolchain(context).prepare()
        val controller = NativeSessionController(root, tools = tools)
        File(root, "home").mkdirs()
        File(root, "home/.vimrc").writeText("let g:term_user_vimrc = 1\nset tabstop=3\n")
        File(root, "home/.termrc").writeText("export TERM_USER_RC=yes\n")
        val transcript = StringBuffer()
        val reader = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.events.collect { if (it is SessionEvent.Output) transcript.append(String(it.bytes)) }
        }
        suspend fun waitFor(text: String) {
            try {
                withTimeout(20_000) { while (!transcript.contains(text)) delay(30) }
            } catch (failure: TimeoutCancellationException) {
                throw AssertionError("Waiting for $text\n${transcript.toString().takeLast(6000)}", failure)
            }
        }
        try {
            val started = android.os.SystemClock.elapsedRealtime()
            val session = controller.create("/system/bin/sh", "", emptyMap(), 24u, 90u).getOrThrow()
            waitFor("\u001b]133;B;")
            android.util.Log.i("NativeCliValidation", "Native prompt ready in ${android.os.SystemClock.elapsedRealtime() - started} ms (resources prepared)")
            controller.write(session.id, "vim edit.json\n".toByteArray()).getOrThrow()
            waitFor("edit.json")
            controller.write(session.id, "i{\"한글\":42}".toByteArray()).getOrThrow()
            controller.resize(session.id, 37u, 113u).getOrThrow()
            // Escape and Ex commands exercise the same PTY keyboard path as UI input.
            controller.write(session.id, "\u001b:set ft=json\r:syntax on\r:w\r".toByteArray()).getOrThrow()
            withTimeout(10_000) { while (!File(root, "home/edit.json").isFile) delay(30) }
            controller.write(session.id, (":call writefile([&encoding, &filetype, string(exists('g:syntax_on')), " +
                "string(&lines), string(&columns), synIDattr(synID(1, 2, 1), 'name'), string(get(g:, 'term_user_vimrc', 0)), string(&tabstop)], 'vim-check')\r:q!\r").toByteArray()).getOrThrow()
            withTimeout(10_000) { while (!File(root, "home/vim-check").isFile) delay(30) }
            assertEquals("{\"한글\":42}\n", File(root, "home/edit.json").readText())
            val check = File(root, "home/vim-check").readLines()
            assertEquals(listOf("utf-8", "json", "1", "37", "113"), check.take(5))
            assertTrue(check.toString(), check[5].isNotEmpty())
            assertEquals(listOf("1", "3"), check.drop(6))
            withTimeout(10_000) {
                while (transcript.toString().split("\u001b]133;A").size < 3) delay(30)
            }
            assertFalse(transcript.toString().takeLast(3000), transcript.contains("Error detected while processing"))
            controller.detach(session.id).getOrThrow()
            assertEquals(session.id, controller.attach(session.id).getOrThrow().id)
            controller.write(session.id, "printf 'pipeline\\n' | rg '^pipe' > output; (exit 7); printf 'code=%s\\n' \$?; cat output\n".toByteArray()).getOrThrow()
            waitFor("code=7")
            assertEquals("pipeline\n", File(root, "home/output").readText())
            assertTrue(transcript.contains("\u001b]133;D;"))
            File(root, "home/script.sh").writeText("#!/system/bin/sh\n" +
                "test \"\$TERM_USER_RC\" = yes || exit 1\n" +
                "printf 'alpha42\\nbeta\\n' > search\nrg -n 'alpha[0-9]+' search > rg-output\n" +
                "test \"\$(cat rg-output)\" = '1:alpha42' || exit 1\nrg --files | grep -q '^search\$' || exit 1\n" +
                "printf '{bad' | jq . >/dev/null 2>&1; test \$? -ne 0 || exit 1\n" +
                "printf '{\"n\":4}' | jq -r '.n * 2' > \"\$HOME/script-output\"\nexit 23\n")
            controller.write(session.id, "sh script.sh; printf 'script-code=%s\\n' \$?\n".toByteArray()).getOrThrow()
            waitFor("script-code=23")
            assertEquals("8\n", File(root, "home/script-output").readText())
            assertEquals(1, controller.list().getOrThrow().size)
            controller.write(session.id, "fc -ln -5 > history-check; printf '\\nHISTORY_%s\\n' READY\n".toByteArray()).getOrThrow()
            waitFor("HISTORY_READY")
            assertTrue(File(root, "home/history-check").readText().contains("sh script.sh"))
            controller.write(session.id, "false\n".toByteArray()).getOrThrow()
            waitFor("\u001b]133;D;1\u0007")
            controller.write(session.id, "exit 0\n".toByteArray()).getOrThrow()
            withTimeout(10_000) {
                while (controller.list().getOrThrow().single().state != dev.terminal.protocol.SessionStatus.EXITED) delay(30)
            }
            assertEquals(0, controller.list().getOrThrow().single().exitCode)
            // AOSP device mksh compiles persistent history out; retain the
            // configured path for OEM builds that support it without faking it.
            File(root, "home/.sh_history").takeIf { it.isFile }?.let {
                assertTrue(it.readText().contains("sh script.sh"))
            }
            controller.terminate(session.id, 9).getOrThrow()
            assertTrue(controller.list().getOrThrow().isEmpty())
        } finally {
            controller.close(); reader.cancelAndJoin(); root.deleteRecursively()
        }
    }

    /** Optional deterministic host fixture; no public SSH credentials or network dependency. */
    @Test fun tlsVerificationAndSshKeyAuthenticationHostKeysAndForwarding() {
        val arguments = InstrumentationRegistry.getArguments()
        val port = arguments.getString("nativeFixturePort")
        val user = arguments.getString("nativeFixtureUser") ?: "dev"
        org.junit.Assume.assumeNotNull("Pass nativeFixturePort after starting tools/native-cli/fixture.py", port)
        val root = File(context.cacheDir, "native-network-${UUID.randomUUID()}").apply { mkdirs() }
        val tools = NativeToolchain(context).prepare()
        try {
            val env = tools + mapOf("HOME" to root.path, "TMPDIR" to root.path,
                "PATH" to tools.getValue("PATH") + ":/system/bin", "SHELL" to "/system/bin/sh")
            fun run(vararg argv: String): Pair<Int, String> {
                val output = File(root, "command-output")
                val process = ProcessBuilder(*argv).directory(root).redirectErrorStream(true).redirectOutput(output).apply {
                    environment().putAll(env)
                }.start()
                try {
                    check(process.waitFor(20, TimeUnit.SECONDS)) { "Fixture command timed out" }
                    return process.exitValue() to output.readText()
                } finally { if (process.isAlive) process.destroyForcibly() }
            }
            val bin = tools.getValue("PATH")
            val base = "http://127.0.0.1:$port"
            for (name in listOf("ca.crt", "id_ed25519", "known_hosts")) {
                val result = run("$bin/curl", "-fsS", "$base/$name", "-o", File(root, name).path)
                assertEquals(result.second, 0, result.first)
            }
            File(root, "id_ed25519").setReadable(false, false); File(root, "id_ed25519").setReadable(true, true)
            val tlsPort = port!!.toInt() + 1
            assertEquals(0, run("$bin/curl", "--cacert", File(root, "ca.crt").path, "-fsS", "https://localhost:$tlsPort/payload").first)
            assertNotEquals(0, run("$bin/curl", "--cacert", File(root, "ca.crt").path, "-fsS", "https://127.0.0.1:$tlsPort/payload").first)
            assertNotEquals(0, run("$bin/curl", "-fsS", "https://localhost:$tlsPort/payload").first)
            val sshPort = port.toInt() + 2
            val ssh = listOf("$bin/ssh", "-F", "/dev/null", "-p", "$sshPort", "-i", File(root, "id_ed25519").path,
                "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "UserKnownHostsFile=${File(root, "known_hosts")}")
            val accepted = run(*(ssh + listOf("$user@127.0.0.1", "fixture-pass")).toTypedArray())
            assertEquals(accepted.second, 0, accepted.first)
            assertTrue(accepted.second.contains("fixture-pass"))
            val sshHome = File(root, ".ssh").apply { mkdir() }
            File(root, "id_ed25519").copyTo(File(sshHome, "id_ed25519"))
            File(sshHome, "id_ed25519").setReadable(false, false); File(sshHome, "id_ed25519").setReadable(true, true)
            File(root, "known_hosts").copyTo(File(sshHome, "known_hosts"))
            File(sshHome, "config").writeText("Host fixture\n HostName 127.0.0.1\n Port $sshPort\n User $user\n IdentityFile ~/.ssh/id_ed25519\n BatchMode yes\n StrictHostKeyChecking yes\n")
            val configured = run("$bin/ssh", "fixture", "fixture-pass")
            assertEquals(configured.second, 0, configured.first)
            val wrongKey = File(root, "wrong-key").path
            assertEquals(0, run("$bin/ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", wrongKey).first)
            val wrongIdentity = ssh.map { if (it == File(root, "id_ed25519").path) wrongKey else it }
            assertNotEquals(0, run(*(wrongIdentity + listOf("$user@127.0.0.1", "fixture-pass")).toTypedArray()).first)
            val localPort = port.toInt() + 3
            val forward = ProcessBuilder(ssh + listOf("-N", "-o", "ExitOnForwardFailure=yes", "-L",
                "127.0.0.1:$localPort:127.0.0.1:$port", "$user@127.0.0.1")).apply {
                environment().putAll(env)
            }.redirectErrorStream(true).redirectOutput(File(root, "forward-log")).start()
            try {
                val result = run("$bin/curl", "--retry", "10", "--retry-connrefused", "--retry-delay", "1",
                    "--max-time", "5", "-fsS", "http://127.0.0.1:$localPort/payload")
                assertEquals(File(root, "forward-log").readText() + result.second, 0, result.first)
                assertTrue(result.second.contains("fixture-pass"))
            } finally { forward.destroy(); forward.waitFor(5, TimeUnit.SECONDS); if (forward.isAlive) forward.destroyForcibly() }
            assertNotEquals("SSH host-key failure should fail closed", 0,
                run(*(ssh + listOf("-o", "HostKeyAlias=wrong-host", "$user@127.0.0.1", "fixture-pass")).toTypedArray()).first)
            File(root, "known_hosts").writeText("[127.0.0.1]:$sshPort " + File(root, "wrong-key.pub").readText())
            assertNotEquals(0, run(*(ssh + listOf("$user@127.0.0.1", "fixture-pass")).toTypedArray()).first)
        } finally { root.deleteRecursively() }
    }
}
