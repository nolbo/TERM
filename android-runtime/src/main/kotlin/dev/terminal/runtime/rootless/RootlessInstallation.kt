package dev.terminal.runtime.rootless

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive

/** Cancels only this owner's preparation, retaining the lease until cleanup finishes. */
internal class RootlessInstallation {
    @Volatile private var job: Job? = null
    val active: Boolean get() = job != null
    val cancellable: Boolean get() = job?.isActive == true

    suspend fun <T> run(block: suspend () -> T): T = coroutineScope {
        val current = coroutineContext[Job]!!
        synchronized(this@RootlessInstallation) {
            check(job == null) { "Installation already running" }
            job = current
        }
        try {
            block()
        } finally {
            synchronized(this@RootlessInstallation) { job = null }
        }
    }

    @Synchronized fun cancel(): Boolean {
        val current = job ?: return false
        if (!current.isActive) return false
        current.cancel(RootlessInstallationCancelledException())
        return true
    }

    fun ensureActive() { job?.ensureActive() }

    companion object {
        const val CANCELLED_MESSAGE = "설치가 취소되었습니다. 다시 설치하면 중단된 작업을 복구합니다."
    }
}

internal class RootlessInstallationCancelledException : CancellationException(RootlessInstallation.CANCELLED_MESSAGE)
