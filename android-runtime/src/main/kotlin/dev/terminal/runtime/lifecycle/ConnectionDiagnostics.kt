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
    private val writer = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(128),
        { task -> Thread(task, "connection-log").apply { isDaemon = true } },
        java.util.concurrent.RejectedExecutionHandler { _, _ -> dropped.incrementAndGet() })
        .apply { allowCoreThreadTimeOut(true) }

    fun initialize(files: File) { store = ConnectionLogStore(files, SystemClock::elapsedRealtimeNanos) }

    fun record(event: String, owner: String? = null, code: String? = null, error: Throwable? = null) {
        val target = store ?: return
        val createdAt = SystemClock.elapsedRealtimeNanos()
        val line = connectionLogLine(System.currentTimeMillis(), createdAt, android.os.Process.myPid(),
            event, owner, code, error?.javaClass?.simpleName)
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
}
