package dev.terminal.runtime.backup

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** A stopped backend alone cannot prove that a daemon left behind by a shell is gone.
 * Check same-UID processes' bindings, environment, mappings, cwd and open FDs without signalling them.
 * Unreadable live same-UID state fails closed. Android UID isolation is still not a filesystem snapshot.
 */
internal class EnvironmentWriterProbe(private val uid: Int, private val ownPid: Int, private val proc: Path = java.nio.file.Paths.get("/proc")) {
    fun verify(roots: Collection<Path>) {
        val prefixes = roots.flatMap { root ->
            val path = root.toAbsolutePath().normalize().toString()
            val aliases = if (path.startsWith("/data/user/0/")) listOf(path.replaceFirst("/data/user/0/", "/data/data/"))
                else if (path.startsWith("/data/data/")) listOf(path.replaceFirst("/data/data/", "/data/user/0/")) else emptyList()
            listOf(path) + aliases
        }
        fun references(value: String) = prefixes.any { prefix ->
            // Match complete layout prefixes, including the PRoot host:guest bind delimiter.
            Regex(Regex.escape(prefix) + "(?=/|[\\u0000\\s:]|$)").containsMatchIn(value)
        }
        Files.newDirectoryStream(proc).use { processes -> processes.forEach { process ->
            val pid = process.fileName.toString().toIntOrNull() ?: return@forEach
            if (pid == ownPid) return@forEach
            try {
                if ((Files.getAttribute(process, "unix:uid", NOFOLLOW_LINKS) as Int) != uid) return@forEach
                val before = bounded(process.resolve("stat"), 16 * 1024)
                // Zombies have no address space or ability to write.
                if (before.substringAfterLast(") ").startsWith("Z ")) return@forEach
                val texts = listOf(bounded(process.resolve("cmdline"), 1024 * 1024),
                    bounded(process.resolve("environ"), 1024 * 1024), bounded(process.resolve("maps"), 8 * 1024 * 1024))
                check(texts.none(::references)) { "An environment process is still running (PID $pid); stop it before backup" }
                for (name in listOf("cwd", "root", "exe")) {
                    val value = Files.readSymbolicLink(process.resolve(name)).toString().removeSuffix(" (deleted)")
                    check(!references(value)) { "A process still uses this environment (PID $pid)" }
                }
                Files.newDirectoryStream(process.resolve("fd")).use { descriptors -> descriptors.forEach { fd ->
                    val value = try { Files.readSymbolicLink(fd).toString().removeSuffix(" (deleted)") }
                        catch (_: java.nio.file.NoSuchFileException) { return@forEach }
                    check(!references(value)) { "A process has an environment file open (PID $pid)" }
                } }
                val after = bounded(process.resolve("stat"), 16 * 1024)
                check(before.substringAfterLast(") ").split(' ').getOrNull(19) ==
                    after.substringAfterLast(") ").split(' ').getOrNull(19)) { "Process state changed while verifying backup safety" }
            } catch (failure: Exception) {
                if (Files.exists(process, NOFOLLOW_LINKS)) throw IllegalStateException("Cannot verify that environment writers have stopped: ${failure.message}", failure)
            }
        } }
    }
    private fun bounded(path: Path, maximum: Int): String = Files.newInputStream(path).use { input ->
        val buffer = java.io.ByteArrayOutputStream()
        val bytes = ByteArray(8192)
        while (true) {
            val count = input.read(bytes)
            if (count < 0) break
            require(buffer.size() + count <= maximum) { "Process metadata exceeds safety limit" }
            buffer.write(bytes, 0, count)
        }
        buffer.toString(Charsets.UTF_8.name())
    }
}
