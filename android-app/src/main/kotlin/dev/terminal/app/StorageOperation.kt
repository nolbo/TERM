package dev.terminal.app

import kotlinx.coroutines.CancellationException
import dev.terminal.runtime.session.StorageRegistry

internal data class StorageSizeState(val usage: StorageRegistry.Usage? = null, val failed: Boolean = false) {
    fun updated(result: Result<StorageRegistry.Usage>): StorageSizeState = result.fold(
        onSuccess = { StorageSizeState(it) },
        onFailure = { StorageSizeState(failed = true) },
    )
}

/** Composition disposal or effect restart cancels observation, rather than failing storage. */
internal suspend fun <T> runStorageOperation(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}
