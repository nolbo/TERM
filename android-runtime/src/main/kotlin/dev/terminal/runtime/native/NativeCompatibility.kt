package dev.terminal.runtime.native

import java.io.File
import java.util.concurrent.TimeUnit

const val DEFAULT_NATIVE_SHELL = "/system/bin/sh"
data class NativeCompatibility(val backendAvailable: Boolean?, val shellExecutable: Boolean?, val shellPath: String?,
    val backendError: String? = null, val shellError: String? = null)

/** A bounded, noninteractive shell probe creates no PTY, session, backend or persistent files. */
fun inspectNativeCompatibility(): NativeCompatibility {
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
        shell.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message.orEmpty().take(500)}" })
}
