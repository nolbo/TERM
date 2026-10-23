package dev.terminal.runtime.lifecycle

import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Bounded metadata only: never terminal bytes, host paths, exception messages or share tokens. */
object ConnectionDiagnostics {
    @Volatile private var directory: File? = null
    private val writer = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(128),
        { task -> Thread(task, "connection-log").apply { isDaemon = true } }, ThreadPoolExecutor.DiscardPolicy())
        .apply { allowCoreThreadTimeOut(true) }

    fun initialize(files: File) { directory = files }

    fun record(event: String, owner: String? = null, code: String? = null, error: Throwable? = null) {
        fun field(value: String?) = value?.take(96)?.replace(Regex("[^a-zA-Z0-9_.:-]"), "_") ?: "-"
        val line = "time_ms=${System.currentTimeMillis()} uptime_ms=${SystemClock.elapsedRealtime()} " +
            "event=${field(event)} owner=${field(owner)} code=${field(code)} error=${field(error?.javaClass?.simpleName)}"
        Log.i("TermConnection", line)
        val root = directory ?: return
        writer.execute {
            runCatching {
                val file = File(root, "connection-events.log")
                val previous = if (file.isFile && file.length() <= 64 * 1024) file.readLines().takeLast(127) else emptyList()
                val temporary = File(root, "connection-events.log.tmp")
                temporary.writeText((previous + line).joinToString("\n", postfix = "\n"))
                java.nio.file.Files.move(temporary.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            }
        }
    }
}
