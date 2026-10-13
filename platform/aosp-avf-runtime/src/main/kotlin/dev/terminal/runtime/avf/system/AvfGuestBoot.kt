package dev.terminal.runtime.avf.system

import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** Custom Linux guests do not send Microdroid's payload-ready notification. */
internal class AvfGuestBoot {
    private val failure = CompletableDeferred<Nothing>()

    fun fail(error: Throwable) { failure.completeExceptionally(error) }

    suspend fun <T : Closeable> awaitSessiond(
        timeoutMillis: Long,
        retryMillis: Long,
        connect: () -> T,
    ): T {
        var lastError: Exception? = null
        try {
            return withTimeout(timeoutMillis) {
                var connection: T? = null
                while (connection == null) {
                    if (failure.isCompleted) failure.await()
                    connection = try {
                        connect()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        lastError = error
                        null
                    }
                    if (connection == null) delay(retryMillis)
                }
                try {
                    if (failure.isCompleted) failure.await()
                    currentCoroutineContext().ensureActive()
                    connection
                } catch (error: Throwable) {
                    runCatching { connection.close() }.exceptionOrNull()?.let(error::addSuppressed)
                    throw error
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            // Preserve cancellation of the owning operation instead of turning it into a boot error.
            currentCoroutineContext().ensureActive()
            throw IllegalStateException(
                "AVF Linux session daemon did not accept vsock port 40500 within ${timeoutMillis / 1_000} seconds",
                lastError ?: timeout,
            )
        }
    }
}
