package dev.terminal.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

internal data class CrashReport(val id: String, val createdAt: Long, val summary: String, val text: String)

/** Immutable, individually committed reports also preserve crashes from the runtime process. */
internal class CrashReportStore(private val directory: File, private val retainedReports: Int = MAX_REPORTS) {
    init { require(retainedReports > 0) }

    fun record(text: String, summary: String, createdAt: Long = System.currentTimeMillis()): CrashReport =
        write(CrashReport(UUID.randomUUID().toString(), createdAt, summary.lineSequence().first().take(200), boundedText(text)))

    fun importLegacy(text: String, createdAt: Long = legacyTimestamp(text) ?: System.currentTimeMillis()): CrashReport {
        val id = legacyId(text)
        return read(id) ?: write(CrashReport(id, createdAt,
            text.lineSequence().firstOrNull { line -> line.isNotBlank() &&
                listOf("시간:", "스레드:", "프로세스:", "Time:", "Thread:", "Process:").none(line::startsWith) }
                .orEmpty().take(200), boundedText(text)))
    }

    fun reports(): List<CrashReport> {
        if (!directory.exists()) return emptyList()
        val files = directory.listFiles() ?: throw IOException("Cannot read crash report history")
        return files.filter { it.name.endsWith(".json") }
            .mapNotNull { read(it.name.removeSuffix(".json")) }
            .sortedWith(compareByDescending<CrashReport> { it.createdAt }.thenByDescending { it.id })
    }

    fun read(id: String): CrashReport? {
        if (!REPORT_ID.matches(id)) return null
        val file = File(directory, "$id.json")
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return null
        return runCatching {
            val json = JSONObject(file.readText())
            require(json.getString("id") == id)
            CrashReport(id, json.getLong("createdAt"), json.getString("summary"), json.getString("text"))
        }.getOrNull()
    }

    fun delete(id: String) {
        require(REPORT_ID.matches(id)) { "Invalid crash report ID" }
        val file = File(directory, "$id.json")
        if (!file.delete() && file.exists()) throw IOException("Cannot delete crash report")
    }

    fun importPending(context: Context): CrashReport? {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val text = preferences.getString(KEY_REPORT, null) ?: return null
        return preferences.getString(KEY_REPORT_ID, null)?.let(::read) ?: importLegacy(text)
    }

    fun acknowledge(context: Context, id: String) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val pendingId = preferences.getString(KEY_REPORT_ID, null)
            ?: preferences.getString(KEY_REPORT, null)?.let(::legacyId)
        if (pendingId == id) preferences.edit().remove(KEY_REPORT).remove(KEY_REPORT_ID).commit()
    }

    private fun write(report: CrashReport): CrashReport {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw IOException("Cannot create crash report history")
        val destination = File(directory, "${report.id}.json")
        val temporary = File(directory, "${report.id}.${UUID.randomUUID()}.tmp")
        try {
            val encoded = JSONObject().put("id", report.id).put("createdAt", report.createdAt)
                .put("summary", report.summary).put("text", report.text).toString().toByteArray(Charsets.UTF_8)
            FileOutputStream(temporary).use { output -> output.write(encoded); output.fd.sync() }
            if (!temporary.renameTo(destination)) throw IOException("Cannot commit crash report")
        } finally { temporary.delete() }
        reports().drop(retainedReports).forEach { File(directory, "${it.id}.json").delete() }
        return report
    }

    companion object {
        const val MAX_REPORTS = 20
        const val PREFERENCES = "crash-report"
        const val KEY_REPORT = "report"
        const val KEY_REPORT_ID = "report-id"
        private const val MAX_TEXT_CHARS = 65_536
        private const val TRUNCATED_SUFFIX = "\n[Report truncated]"
        private const val MAX_FILE_BYTES = 1_048_576L
        private val REPORT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        fun from(context: Context) = CrashReportStore(File(context.filesDir, "crash-reports"))
        private fun legacyId(text: String) = UUID.nameUUIDFromBytes(text.toByteArray(Charsets.UTF_8)).toString()
        private fun legacyTimestamp(text: String): Long? = runCatching {
            val line = text.lineSequence().first()
            if (!line.startsWith("시간:") && !line.startsWith("Time:")) return@runCatching null
            java.text.SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", java.util.Locale.US)
                .apply { isLenient = false }.parse(line.substringAfter(':').trim())?.time
        }.getOrNull()
        private fun boundedText(text: String): String {
            if (text.length <= MAX_TEXT_CHARS) return text
            val prefix = text.take(MAX_TEXT_CHARS - TRUNCATED_SUFFIX.length)
            return (if (prefix.last().isHighSurrogate()) prefix.dropLast(1) else prefix) + TRUNCATED_SUFFIX
        }
    }
}
