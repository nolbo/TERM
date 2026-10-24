package dev.terminal.runtime.lifecycle

import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Bounded metadata only: never terminal bytes, host paths, exception messages or share tokens. */
object ConnectionDiagnostics {
    @Volatile private var store: ConnectionLogStore? = null
    private val dropped = java.util.concurrent.atomic.AtomicLong()
    private val bursts = DiagnosticBursts()
    private val writer = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(128),
        { task -> Thread(task, "connection-log").apply { isDaemon = true } },
        java.util.concurrent.RejectedExecutionHandler { _, _ -> dropped.incrementAndGet() })
        .apply { allowCoreThreadTimeOut(true) }

    fun initialize(files: File) { store = ConnectionLogStore(files, SystemClock::elapsedRealtimeNanos) }

    fun record(event: String, owner: String? = null, code: String? = null, error: Throwable? = null,
        details: Map<String, String> = emptyMap()) {
        val target = store ?: return
        val createdAt = SystemClock.elapsedRealtimeNanos()
        val line = connectionLogLine(System.currentTimeMillis(), createdAt, android.os.Process.myPid(),
            event, owner, code, error?.javaClass?.simpleName, connectionErrorDetails(error) + details)
        writer.execute {
            runCatching {
                val lost = dropped.getAndSet(0)
                if (lost > 0) {
                    val notice = connectionLogLine(System.currentTimeMillis(), createdAt, android.os.Process.myPid(),
                        "logging.dropped", null, lost.toString(), null)
                    if (target.append(notice, createdAt)) Log.i("TermConnection", notice)
                }
                if (target.append(line, createdAt)) Log.i("TermConnection", line)
            }
        }
    }

    /** Keep repeated input failures from immediately rotating away the original link failure. */
    fun recordLimited(event: String, owner: String?, code: String?, error: Throwable? = null,
        details: Map<String, String> = emptyMap()) {
        if (store == null) return
        val suppressed = bursts.admit("$event:$owner:$code", System.nanoTime()) ?: return
        record(event, owner, code, error, details + ("suppressed" to suppressed.toString()))
    }
}

internal class DiagnosticBursts {
    private data class Burst(val time: Long, var suppressed: Int = 0)
    private val entries = linkedMapOf<String, Burst>()
    @Synchronized fun admit(key: String, now: Long): Int? {
        val previous = entries[key]
        if (previous != null && now - previous.time < 1_000_000_000) {
            previous.suppressed++
            return null
        }
        if (entries.size >= 128 && key !in entries) entries.remove(entries.keys.first())
        entries[key] = Burst(now)
        return previous?.suppressed ?: 0
    }
}

/** Type, source location and errno explain failures without collecting arbitrary exception text. */
internal fun connectionErrorDetails(error: Throwable?): Map<String, String> = buildMap {
    var cause = error
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    val types = mutableListOf<String>()
    while (cause != null && types.size < 4 && seen.add(cause)) {
        types += cause.javaClass.simpleName.replace('$', '.')
        if (cause is android.system.ErrnoException) put("errno", cause.errno.toString())
        if (!containsKey("error_at")) {
            val frame = cause.stackTrace.firstOrNull { it.className.startsWith("dev.terminal.") }
                ?: cause.stackTrace.firstOrNull()
            frame?.let { put("error_at", "${it.className}.${it.methodName}:${it.lineNumber}"
                .replace('$', '.').replace('<', '_').replace('>', '_').takeLast(160)) }
        }
        cause = cause.cause
    }
    if (types.isNotEmpty()) put("causes", types.joinToString(":"))
}
