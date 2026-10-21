package dev.terminal.runtime.native

import java.io.File
import java.util.concurrent.TimeUnit
import android.content.Context
import android.system.Os
import android.system.OsConstants

const val DEFAULT_NATIVE_SHELL = "/system/bin/sh"
data class NativeCompatibility(val backendAvailable: Boolean?, val shellExecutable: Boolean?, val shellPath: String?,
    val backendError: String? = null, val shellError: String? = null,
    val checks: List<NativeDiagnosticCheck> = emptyList())
data class NativeDiagnosticCheck(val name: String, val passed: Boolean, val detail: String)

/** A bounded, noninteractive shell probe creates no PTY, session, backend or persistent files. */
fun inspectNativeCompatibility(context: Context? = null): NativeCompatibility {
    val backend = runCatching {
        Class.forName("dev.terminal.runtime.native.NativeBackend", false, NativeCompatibility::class.java.classLoader)
        System.loadLibrary("terminal-jni")
        true
    }
    val shell = runCatching {
        if (!File(DEFAULT_NATIVE_SHELL).let { it.isFile && it.canExecute() }) false
        else {
            val process = ProcessBuilder(DEFAULT_NATIVE_SHELL, "-c", "exit 0").start()
            try {
                check(process.waitFor(2, TimeUnit.SECONDS)) { "Shell execution check timed out" }
                process.exitValue() == 0
            } finally {
                if (process.isAlive) process.destroyForcibly()
                process.inputStream.close(); process.outputStream.close(); process.errorStream.close()
            }
        }
    }
    val path = runCatching { File(DEFAULT_NATIVE_SHELL).takeIf { it.isFile }?.absolutePath }.getOrNull()
    return NativeCompatibility(if (backend.exceptionOrNull() is LinkageError) false else backend.getOrNull(),
        if (shell.exceptionOrNull() is java.io.IOException) false else shell.getOrNull(), path,
        backend.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message.orEmpty().take(500)}" },
        shell.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message.orEmpty().take(500)}" },
        context?.let { app ->
            runCatching { inspectNativeTools(app) }.getOrElse {
                listOf(NativeDiagnosticCheck("Storage / initialization", false, "${it.javaClass.simpleName}: ${it.message.orEmpty().take(500)}"))
            }
        }.orEmpty())
}

/** Fresh cache-only HOME; never reads user rc/vimrc/history or attaches to sessions. */
private fun inspectNativeTools(context: Context): List<NativeDiagnosticCheck> {
    val checks = mutableListOf<NativeDiagnosticCheck>()
    val sandbox = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "native-diagnostics-").toFile()
    try {
        val tools = runCatching { NativeToolchain(context).prepare() }.getOrElse {
            return listOf(NativeDiagnosticCheck("APK CLI runtime", false, it.message.orEmpty().take(500)))
        }
        val home = File(sandbox, "home").apply { mkdir() }
        val tmp = File(sandbox, "tmp").apply { mkdir() }
        val rc = prepareNativeShellRc(home)
        val environment = nativeEnvironment(home, tmp, DEFAULT_NATIVE_SHELL, rc, emptyMap(), tools, emptyMap())
        fun probe(name: String, vararg args: String) {
            val result = runCatching {
                val output = File(sandbox, "output")
                val process = ProcessBuilder(*args).directory(home).redirectErrorStream(true).redirectOutput(output).apply {
                    environment().clear(); environment().putAll(environment)
                }.start()
                try {
                    check(process.waitFor(5, TimeUnit.SECONDS)) { "Execution timed out" }
                    val detail = output.inputStream().use { input ->
                        val bytes = ByteArray(2048)
                        String(bytes, 0, maxOf(0, input.read(bytes)), Charsets.UTF_8)
                    }.trim()
                    check(process.exitValue() == 0) { "exit=${process.exitValue()}: $detail" }
                    detail
                } finally {
                    if (process.isAlive) process.destroyForcibly().waitFor(1, TimeUnit.SECONDS)
                    process.inputStream.close(); process.errorStream.close(); process.outputStream.close()
                }
            }
            checks += NativeDiagnosticCheck(name, result.isSuccess, result.getOrElse { it.message.orEmpty() }.take(2048))
        }
        checks += NativeDiagnosticCheck("ABI / pages", android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a"),
            "${android.os.Build.SUPPORTED_ABIS.joinToString()} / ${Os.sysconf(OsConstants._SC_PAGESIZE)} bytes; APK LOAD alignment: 16384")
        checks += NativeDiagnosticCheck("HOME / TMPDIR / PATH / ENV", home.canWrite() && tmp.canWrite() && rc.canRead(),
            listOf("HOME", "TMPDIR", "PATH", "ENV").joinToString("\n") { "$it=${environment[it]}" })
        probe("Android / Toybox", DEFAULT_NATIVE_SHELL, "-c",
            "for t in sh toybox ls cat grep sed awk find tar stty; do command -v \"\$t\" || exit 1; done")
        probe("jq", DEFAULT_NATIVE_SHELL, "-c", "printf '%s' '{\"a\":[1,2,3]}' | jq -ce '.a | map(. * 2) == [2,4,6]' ")
        probe("curl / TLS", "${tools.getValue("PATH")}/curl", "--version")
        probe("ssh", "${tools.getValue("PATH")}/ssh", "-V")
        probe("rg", DEFAULT_NATIVE_SHELL, "-c", "printf 'alpha42\\n' | rg '^alpha[0-9]+\$'")
        probe("vim / UTF-8 / syntax / save", "${tools.getValue("PATH")}/vim", "-n", "-es", "-i", "NONE",
            "+set encoding=utf-8", "+syntax on", "+set ft=json", "+call setline(1, '{\"한글\":true}')",
            "+write " + File(sandbox, "vim.json").absolutePath, "+if !exists('g:syntax_on') | cquit | endif", "+qa!")
        probe("shell / pipeline / redirect / exit", DEFAULT_NATIVE_SHELL, "-c",
            "printf 'ok\\n' | cat > \"\$TMPDIR/check\"; grep -q '^ok\$' \"\$TMPDIR/check\" || exit 1; (exit 7); test \$? -eq 7")
        checks += NativeDiagnosticCheck("App storage", context.filesDir.canRead() && context.filesDir.canWrite(),
            "${context.filesDir.absolutePath}; shared storage grant=${android.os.Environment.isExternalStorageManager()}")
    } finally { sandbox.deleteRecursively() }
    return checks
}
