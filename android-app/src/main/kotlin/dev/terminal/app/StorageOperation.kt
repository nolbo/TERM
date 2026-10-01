package dev.terminal.app

import kotlinx.coroutines.CancellationException

/** Composition disposal or effect restart cancels observation, rather than failing storage. */
internal suspend fun <T> runStorageOperation(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}
