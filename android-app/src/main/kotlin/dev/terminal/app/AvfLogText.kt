package dev.terminal.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Render legacy stored metadata and copy exactly the same chronological text shown on screen. */
internal fun avfLogText(entries: List<String>, query: String = "", zone: ZoneId = ZoneId.systemDefault()): String {
    val format = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS XXX", Locale.ROOT).withZone(zone)
    return entries.map { raw ->
        val timestamp = raw.substringBefore(' ').removePrefix("time_ms=")
        val time = if (raw.startsWith("time_ms=")) timestamp.toLongOrNull()?.let {
            runCatching { format.format(Instant.ofEpochMilli(it)) }.getOrNull()
        } else null
        val text = if (time == null) "[unknown] $raw" else "[$time] ${raw.substringAfter(' ', "")}"
        raw to text
    }.filter { (raw, text) -> raw.contains(query, ignoreCase = true) || text.contains(query, ignoreCase = true) }
        .joinToString("\n", postfix = "") { it.second }.let { if (it.isEmpty()) it else "$it\n" }
}
