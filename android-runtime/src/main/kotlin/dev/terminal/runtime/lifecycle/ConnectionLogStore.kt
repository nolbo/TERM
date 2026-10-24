package dev.terminal.runtime.lifecycle

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.Properties

data class ConnectionLogSnapshot(val enabled: Boolean, val entries: List<String>)

/** Shared by the UI, workers and :vmhost. Access never starts or binds a runtime. */
class ConnectionLogStore(private val root: File, private val uptimeNanos: () -> Long) {
    private data class Configuration(val enabled: Boolean = true, val clearedAt: Long = 0)
    private val events get() = File(root, EVENTS)
    private val settings get() = File(root, "connection-events.settings")

    fun snapshot(): ConnectionLogSnapshot = locked {
        ConnectionLogSnapshot(configuration().enabled, entries())
    }

    fun setEnabled(enabled: Boolean) = locked {
        // Enabling must not backfill events queued while recording was disabled.
        writeConfiguration(configuration().copy(enabled = enabled, clearedAt = uptimeNanos()))
    }

    fun clear() = locked {
        // All Android processes share this boot's elapsed realtime clock. A queued
        // event submitted before this barrier must not reappear after deletion.
        writeConfiguration(configuration().copy(clearedAt = uptimeNanos()))
        Files.deleteIfExists(events.toPath())
    }

    internal fun append(line: String, createdAt: Long): Boolean = locked {
        require(line.length <= MAX_LINE_CHARS && '\n' !in line && '\r' !in line)
        var state = configuration()
        if (state.clearedAt > uptimeNanos()) {
            // The device rebooted. No queued event can survive that process death.
            state = state.copy(clearedAt = 0)
            writeConfiguration(state)
        }
        if (!state.enabled || createdAt <= state.clearedAt) return@locked false
        val lines = try { entries().toMutableList() } catch (_: OversizedLog) { mutableListOf() }
        lines += line
        while (lines.size > MAX_RECORDS) lines.removeAt(0)
        var bytes = lines.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 }
        while (bytes > MAX_BYTES && lines.isNotEmpty()) {
            bytes -= lines.removeAt(0).toByteArray(Charsets.UTF_8).size + 1
        }
        writeAtomic(events, lines.joinToString("\n", postfix = "\n"))
        true
    }

    private fun configuration(): Configuration {
        val text = readBounded(settings, 256) ?: return Configuration()
        val properties = Properties().apply { text.reader().use { load(it) } }
        require(properties.stringPropertyNames() == setOf("version", "enabled", "clearedAt"))
        require(properties.getProperty("version") == "1")
        val enabled = when (properties.getProperty("enabled")) {
            "true" -> true
            "false" -> false
            else -> throw IOException("Invalid diagnostic recording configuration")
        }
        val cleared = properties.getProperty("clearedAt").toLong()
        require(cleared >= 0)
        return Configuration(enabled, cleared)
    }

    private fun writeConfiguration(state: Configuration) = writeAtomic(settings,
        "version=1\nenabled=${state.enabled}\nclearedAt=${state.clearedAt}\n")

    private fun entries(): List<String> = readBounded(events, MAX_BYTES)?.lineSequence()
        ?.filter { it.isNotBlank() && it.length <= MAX_LINE_CHARS }?.toList()?.takeLast(MAX_RECORDS).orEmpty()

    private fun readBounded(file: File, maximum: Int): String? {
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return null
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) { "Invalid diagnostic file" }
        return Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { input ->
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size, maximum + 1 - bytes.size()))
                if (count < 0) break
                bytes.write(buffer, 0, count)
                if (bytes.size() > maximum) throw OversizedLog()
            }
            bytes.toByteArray().toString(Charsets.UTF_8)
        }
    }

    private fun writeAtomic(file: File, text: String) {
        // One staging file per destination bounds crash leftovers as well as the log.
        // Unlink before CREATE_NEW: never follow a stale symlink or hardlinked inode.
        val temporary = File(root, ".${file.name}.tmp").toPath()
        Files.deleteIfExists(temporary)
        try {
            FileChannel.open(temporary, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                val bytes = java.nio.ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) channel.write(bytes)
                if (file == settings) channel.force(true)
            }
            Files.move(temporary, file.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun <T> locked(block: () -> T): T = synchronized(PROCESS_LOCK) {
        check(root.isDirectory || root.mkdirs()) { "Diagnostic storage unavailable" }
        FileChannel.open(File(root, ".connection-events.lock").toPath(), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            channel.lock().use { block() }
        }
    }

    private class OversizedLog : IOException("Diagnostic file exceeds retention limit")

    companion object {
        const val MAX_RECORDS = 1000
        const val MAX_BYTES = 256 * 1024
        private const val MAX_LINE_CHARS = 768
        private const val EVENTS = "connection-events.log"
        private val PROCESS_LOCK = Any()
    }
}

internal fun connectionLogLine(wallMillis: Long, uptimeNanos: Long, pid: Int,
    event: String, owner: String?, code: String?, errorName: String?): String {
    fun field(value: String?): String = when {
        value.isNullOrEmpty() -> "-"
        value.length <= 96 && value.matches(Regex("[a-zA-Z0-9_.:-]+")) -> value
        else -> "redacted"
    }
    return "time_ms=$wallMillis uptime_ms=${uptimeNanos / 1_000_000} pid=$pid " +
        "event=${field(event)} owner=${field(owner)} code=${field(code)} error=${field(errorName)}"
}
