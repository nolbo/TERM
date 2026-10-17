package dev.terminal.runtime.image

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import androidx.work.WorkInfo
import androidx.work.WorkManager

enum class AvfImageInstallPhase { PREPARING, MANIFEST, DOWNLOAD, VERIFY, INSTALL, BOOT, APT_REFRESH, COMPLETE }
enum class AvfImageInstallState { WAITING, RUNNING, RETRYING, SUCCEEDED, FAILED, CANCELLED }

data class AvfImageInstallSnapshot(
    val workId: String,
    val storageId: String,
    val state: AvfImageInstallState,
    val phase: AvfImageInstallPhase,
    val artifactIndex: Int = 0,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val startedAtElapsedMs: Long? = null,
    val updatedAtElapsedMs: Long? = null,
    val error: String? = null,
    val requestedAtEpochMs: Long = 0L,
) {
    val active get() = state in setOf(AvfImageInstallState.WAITING, AvfImageInstallState.RUNNING, AvfImageInstallState.RETRYING)
    // This measures the current compressed download, never the entire installation.
    val fraction: Float? get() = if (phase == AvfImageInstallPhase.DOWNLOAD &&
        downloadedBytes != null && totalBytes != null && totalBytes > 0 && downloadedBytes in 0..totalBytes)
        (downloadedBytes.toDouble() / totalBytes).toFloat() else null
}

/** UI observes persisted WorkManager state; observing or closing a view never starts or cancels work. */
class AvfImageInstallMonitor(context: Context) {
    private val manager = WorkManager.getInstance(context.applicationContext)

    fun observe(owner: LifecycleOwner, onChanged: (List<AvfImageInstallSnapshot>) -> Unit) {
        manager.getWorkInfosByTagLiveData(DebianImageDownloadWorker::class.java.name).observe(owner) { work ->
            onChanged(snapshots(work))
        }
    }

    companion object {
        internal const val STORAGE_TAG = "avf-image-storage:"
        internal const val REQUESTED_TAG = "avf-image-requested:"
        internal const val KEY_STORAGE = "storage_id"
        internal const val KEY_PHASE = "phase"
        internal const val KEY_ARTIFACT = "artifact_index"
        internal const val KEY_BYTES = "downloaded_bytes"
        internal const val KEY_TOTAL = "total_bytes"
        internal const val KEY_STARTED = "started_at_elapsed"
        internal const val KEY_UPDATED = "updated_at_elapsed"

        internal fun snapshots(work: List<WorkInfo>) = work.map(::snapshot)
            .sortedWith(compareByDescending<AvfImageInstallSnapshot> { it.active }
                .thenByDescending { it.requestedAtEpochMs }).distinctBy { it.storageId }

        internal fun snapshot(work: WorkInfo): AvfImageInstallSnapshot {
            val finished = work.state.isFinished
            val data = if (finished) work.outputData else work.progress
            val state = when (work.state) {
                WorkInfo.State.RUNNING -> AvfImageInstallState.RUNNING
                WorkInfo.State.SUCCEEDED -> AvfImageInstallState.SUCCEEDED
                WorkInfo.State.FAILED -> AvfImageInstallState.FAILED
                WorkInfo.State.CANCELLED -> AvfImageInstallState.CANCELLED
                else -> if (work.runAttemptCount > 0) AvfImageInstallState.RETRYING else AvfImageInstallState.WAITING
            }
            val phase = if (state == AvfImageInstallState.SUCCEEDED) AvfImageInstallPhase.COMPLETE
                else runCatching { AvfImageInstallPhase.valueOf(data.getString(KEY_PHASE).orEmpty()) }
                    .getOrDefault(AvfImageInstallPhase.PREPARING)
            fun number(key: String) = data.getLong(key, -1L).takeIf { it >= 0 }
            return AvfImageInstallSnapshot(work.id.toString(),
                work.tags.firstOrNull { it.startsWith(STORAGE_TAG) }?.removePrefix(STORAGE_TAG)
                    ?: data.getString(KEY_STORAGE) ?: "legacy",
                state, phase, data.getInt(KEY_ARTIFACT, 0).coerceIn(0, 5),
                number(KEY_BYTES), number(KEY_TOTAL), number(KEY_STARTED), number(KEY_UPDATED),
                data.getString(DebianImageDownloadWorker.KEY_ERROR),
                work.tags.firstOrNull { it.startsWith(REQUESTED_TAG) }?.removePrefix(REQUESTED_TAG)
                    ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L)
        }
    }
}
