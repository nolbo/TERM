package dev.terminal.runtime.vsock

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.io.IOException

/** Bounds uncancellable platform connection calls and closes descriptors returned too late. */
object GuestConnectionOpener {
    private val workers = ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS, SynchronousQueue<Runnable>(),
        { task -> Thread(null, task, "guest-connect", 256 * 1024).apply { isDaemon = true } })

    fun <T : AutoCloseable> open(timeoutMillis: Long, connect: () -> T): T {
        require(timeoutMillis > 0)
        val result = CompletableFuture<T>()
        workers.execute {
            try {
                val connection = connect()
                if (!result.complete(connection)) runCatching { connection.close() }
            } catch (error: Throwable) { result.completeExceptionally(error) }
        }
        var accepted = false
        try { return result.get(timeoutMillis, TimeUnit.MILLISECONDS).also { accepted = true } }
        catch (error: ExecutionException) {
            val cause = error.cause ?: error
            throw if (cause is Exception) cause else IOException("Guest platform connection failed", cause)
        }
        catch (error: InterruptedException) { Thread.currentThread().interrupt(); throw error }
        finally {
            if (!accepted && !result.cancel(false)) {
                // The native call may win immediately after get() times out.
                runCatching { result.getNow(null)?.close() }
            }
        }
    }
}
