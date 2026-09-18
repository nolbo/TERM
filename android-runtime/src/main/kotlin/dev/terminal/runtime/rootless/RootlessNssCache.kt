package dev.terminal.runtime.rootless

import dev.terminal.runtime.session.SessionCommand
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** One optional cache per controller. The service owns its stdin write end. */
internal class RootlessNssCache(
    private val layout: RootlessDebianLayout,
    private val proot: File,
    private val loader: File,
    directory: File,
) : AutoCloseable {
    private val root = Files.createTempDirectory(directory.toPath(), "nss-").toFile()
    private val run = File(root, "run").apply { mkdirs() }
    private val cache = File(root, "cache").apply { mkdirs() }
    private val config = File(root, "config").apply {
        writeText("threads 3\n" + listOf("passwd", "group").joinToString("") {
            "enable-cache $it yes\npositive-time-to-live $it 2\nnegative-time-to-live $it 1\n" +
                "check-files $it yes\npersistent $it no\nshared $it yes\n"
        })
    }
    val bindings = listOf("${run.path}:/run/nscd", "${cache.path}:/var/cache/nscd")
    private var process: Process? = null
    private var closed = false

    @Synchronized fun ensureStarted(): Boolean {
        check(!closed) { "NSS cache owner closed" }
        if (process?.isAlive == true) return true
        process?.outputStream?.close()
        process = null
        File(run, "socket").delete()
        File(run, "nscd.pid").delete()
        val spec = buildRootlessLaunchSpec(layout, proot, loader, "/bin/sh", "/",
            mapOf(SessionCommand.KEY to SessionCommand.encode(listOf(
                "/usr/sbin/nscd", "-F", "-f", "/usr/local/etc/terminal-nscd.conf"))),
            nssBindings = bindings + "${config.path}:/usr/local/etc/terminal-nscd.conf")
        val child = ProcessBuilder(spec.arguments.toList()).directory(File(spec.hostCwd))
            .redirectOutput(File("/dev/null")).redirectError(File("/dev/null")).apply {
                environment().clear()
                spec.hostEnvironment.forEach { environment()[it.substringBefore('=')] = it.substringAfter('=') }
                environment()["PROOT_OWNER_PIPE"] = "1"
            }.start()
        process = child
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (child.isAlive && System.nanoTime() < deadline) {
                if (File(run, "socket").exists()) return true
                Thread.sleep(20)
            }
        } catch (error: InterruptedException) {
            stopChild(child)
            process = null
            throw error
        }
        stopChild(child)
        process = null
        return false
    }

    private fun stopChild(child: Process) {
        runCatching { child.outputStream.close() }
        if (!child.waitFor(2, TimeUnit.SECONDS)) {
            child.destroyForcibly()
            check(child.waitFor(2, TimeUnit.SECONDS)) { "NSS tracer did not terminate" }
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        process?.let(::stopChild)
        process = null
        deleteRootlessTree(root.toPath())
    }
}
